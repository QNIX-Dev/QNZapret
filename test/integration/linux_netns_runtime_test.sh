#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
nfqws=${QNZAPRET_NFQWS:-"$repo_dir/runtime/assets/qnzapret/bin/nfqws2"}
asset_root=${QNZAPRET_ASSET_ROOT:-"$repo_dir/runtime/assets/qnzapret"}
runtime_binary=${QNZAPRET_RUNTIME_BINARY:-}
queue=200

if [[ ${EUID} -ne 0 ]]; then
  echo "SKIP: Linux network namespace integration test requires root." >&2
  exit 77
fi
if ! getent passwd qnzapret-runtime >/dev/null; then
  echo "SKIP: install the package so qnzapret-runtime user exists." >&2
  exit 77
fi
for command in curl ip nft nsenter openssl python3 unshare; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "SKIP: required integration command is missing: $command" >&2
    exit 77
  fi
done

if [[ -z $runtime_binary ]]; then
  for candidate in \
    "$repo_dir/build/linux/x64/release/bundle/libexec/qnzapret-runtime" \
    "$repo_dir/build/linux/x64/debug/bundle/libexec/qnzapret-runtime"; do
    if [[ -x $candidate ]]; then
      runtime_binary=$candidate
      break
    fi
  done
fi
if [[ ! -x ${runtime_binary:-} || ! -x $nfqws ]]; then
  echo "SKIP: build the Linux runtime and ensure bundled nfqws2 exists." >&2
  exit 77
fi

if [[ ${QNZAPRET_INSIDE_NETNS:-0} != 1 ]]; then
  runtime_dir=$(mktemp -d /tmp/qnzapret-netns-XXXXXX)
  staged_asset_root="$runtime_dir/assets"
  cp -a -- "$asset_root" "$staged_asset_root"
  chmod 0755 "$runtime_dir"
  chmod -R a+rX "$staged_asset_root"
  staged_nfqws="$staged_asset_root/bin/nfqws2"
  if unshare --net --mount-proc env \
    QNZAPRET_INSIDE_NETNS=1 \
    QNZAPRET_NFQWS="$staged_nfqws" \
    QNZAPRET_ASSET_ROOT="$staged_asset_root" \
    QNZAPRET_RUNTIME_BINARY="$runtime_binary" \
    QNZAPRET_RUNTIME_DIRECTORY="$runtime_dir" \
    "$0"; then
    status=0
  else
    status=$?
  fi
  rm -rf -- "$runtime_dir"
  exit "$status"
fi

runtime_dir=${QNZAPRET_RUNTIME_DIRECTORY:?}
server_net_pid=
daemon_pid=
http_pid=
tls_pid=

cleanup() {
  local status=${1:-0}
  if [[ -n ${daemon_pid:-} ]]; then
    kill "$daemon_pid" >/dev/null 2>&1 || true
    wait "$daemon_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n ${http_pid:-} ]]; then
    kill "$http_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n ${tls_pid:-} ]]; then
    kill "$tls_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n ${server_net_pid:-} ]]; then
    kill "$server_net_pid" >/dev/null 2>&1 || true
    wait "$server_net_pid" >/dev/null 2>&1 || true
  fi
  nft delete table inet qnzapret >/dev/null 2>&1 || true
  nft delete table inet qnzapret_foreign_test >/dev/null 2>&1 || true
  if (( status != 0 )) && [[ -f $runtime_dir/runtime.log ]]; then
    echo "--- qnzapret integration runtime log ---" >&2
    sed -n '1,260p' "$runtime_dir/runtime.log" >&2
  fi
  rm -rf -- "$runtime_dir"
}
trap 'cleanup $?' EXIT

ip link set lo up
unshare --net -- sh -c 'exec sleep 3600' &
server_net_pid=$!
for _ in $(seq 1 30); do
  [[ -e /proc/$server_net_pid/ns/net ]] && break
  sleep 0.05
done
test -e "/proc/$server_net_pid/ns/net"
ip link add qnz-client type veth peer name qnz-server
ip link set qnz-server netns "$server_net_pid"
ip address add 10.203.0.1/24 dev qnz-client
ip link set qnz-client up
nsenter -t "$server_net_pid" -n ip link set lo up
nsenter -t "$server_net_pid" -n ip address add 10.203.0.2/24 dev qnz-server
nsenter -t "$server_net_pid" -n ip link set qnz-server up

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj /CN=www.youtube.com \
  -keyout "$runtime_dir/tls.key" -out "$runtime_dir/tls.crt" \
  >/dev/null 2>&1
nsenter -t "$server_net_pid" -n python3 -m http.server 80 \
  --bind 10.203.0.2 >"$runtime_dir/http.log" 2>&1 &
http_pid=$!
nsenter -t "$server_net_pid" -n openssl s_server -quiet -www \
  -accept 10.203.0.2:443 -cert "$runtime_dir/tls.crt" \
  -key "$runtime_dir/tls.key" >"$runtime_dir/tls.log" 2>&1 &
tls_pid=$!

nft add table inet qnzapret_foreign_test
QNZAPRET_INTEGRATION_TEST=1 \
QNZAPRET_NFQWS="$nfqws" \
QNZAPRET_ASSET_ROOT="$asset_root" \
QNZAPRET_RUNTIME_DIRECTORY="$runtime_dir" \
  "$runtime_binary" --integration-test \
  >"$runtime_dir/runtime.log" 2>&1 &
daemon_pid=$!

for _ in $(seq 1 100); do
  if nft list table inet qnzapret >/dev/null 2>&1 && \
      grep -q QNZAPRET_INTEGRATION_READY "$runtime_dir/runtime.log"; then
    break
  fi
  sleep 0.05
done
nft list table inet qnzapret >/dev/null
grep -q QNZAPRET_INTEGRATION_READY "$runtime_dir/runtime.log"
awk -v queue="$queue" '$1 == queue { found = 1 } END { exit !found }' \
  /proc/net/netfilter/nfnetlink_queue

counter_packets() {
  nft -j list table inet qnzapret | python3 -c '
import json, sys
data = json.load(sys.stdin)
def counters(value):
    if isinstance(value, dict):
        total = value.get("counter", {}).get("packets", 0)
        return total + sum(counters(child) for child in value.values())
    if isinstance(value, list):
        return sum(counters(child) for child in value)
    return 0
print(counters(data))
'
}

packets_before=$(counter_packets)
curl --fail --silent --show-error --max-time 5 \
  -H 'Host: www.youtube.com' http://10.203.0.2/ >/dev/null
curl --fail --silent --show-error --max-time 5 --insecure \
  --resolve www.youtube.com:443:10.203.0.2 \
  https://www.youtube.com/ >/dev/null
packets_after=$(counter_packets)
if (( packets_after <= packets_before )); then
  echo "NFQUEUE nft counters did not increase for HTTP/TLS traffic." >&2
  exit 1
fi

if ! grep -Eiq \
  'youtube|tls_client_hello|http_req|multisplit|hostlist' \
  "$runtime_dir/runtime.log"; then
  echo "nfqws2 diagnostics contain no proof of an L7 HTTP/TLS decision." >&2
  sed -n '1,240p' "$runtime_dir/runtime.log" >&2
  exit 1
fi

nfqws_pid=$(sed -n 's/.*QNZAPRET_INTEGRATION_READY child=\([0-9][0-9]*\).*/\1/p' \
  "$runtime_dir/runtime.log" | tail -1)
test -n "$nfqws_pid"
kill -KILL "$nfqws_pid"
wait "$daemon_pid"
daemon_pid=

if nft list table inet qnzapret >/dev/null 2>&1; then
  echo "QNZapret table survived daemon fail-open crash cleanup." >&2
  exit 1
fi
nft list table inet qnzapret_foreign_test >/dev/null

echo "Linux NFQUEUE netns integration passed: counters $packets_before -> $packets_after; L7 observed; crash cleanup preserved foreign table."
