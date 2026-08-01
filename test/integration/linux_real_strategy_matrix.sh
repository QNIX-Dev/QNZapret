#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
nfqws=/usr/lib/qnzapret/runtime/bin/nfqws2
queue=200
smoke_user=${SUDO_USER:-}
worker_pid=
runtime_dir=

if [[ ${EUID} -ne 0 ]]; then
  echo "SKIP: real strategy matrix requires root." >&2
  exit 77
fi
if [[ -z $smoke_user || $smoke_user == root ]] || \
    ! getent passwd "$smoke_user" >/dev/null; then
  echo "Run this script through sudo from the desktop user session." >&2
  exit 1
fi
if [[ ! -x $nfqws ]]; then
  echo "Install the QNZapret package before running the matrix." >&2
  exit 1
fi
if nft list table inet qnzapret >/dev/null 2>&1; then
  echo "Stop QNZapret in the GUI before running the strategy matrix." >&2
  exit 1
fi

contract_test=
for candidate in \
  "$repo_dir/build/linux/x64/release/qnzapret-runtime-tests" \
  "$repo_dir/build/linux/x64/debug/qnzapret-runtime-tests"; do
  if [[ -x $candidate &&
        $candidate -nt "$repo_dir/linux/runtime/runtime_contract.cc" &&
        $candidate -nt "$repo_dir/linux/runtime/runtime_contract_test.cc" &&
        ( -z $contract_test || $candidate -nt $contract_test ) ]]; then
    contract_test=$candidate
  fi
done
if [[ ! -x $contract_test ]]; then
  echo "Build fresh Linux native runtime tests before running the matrix." >&2
  exit 1
fi

runtime_dir=$(mktemp -d /tmp/qnzapret-real-matrix-XXXXXX)

cleanup_candidate() {
  if [[ -n ${worker_pid:-} ]]; then
    kill -TERM "$worker_pid" >/dev/null 2>&1 || true
    wait "$worker_pid" >/dev/null 2>&1 || true
    worker_pid=
  fi
  nft delete table inet qnzapret >/dev/null 2>&1 || true
}

cleanup() {
  local status=${1:-0}
  cleanup_candidate
  rm -rf -- "$runtime_dir"
  exit "$status"
}
trap 'cleanup $?' EXIT

counter_packets() {
  nft -j list table inet qnzapret | python3 -c '
import json, sys
data = json.load(sys.stdin)
def counters(value):
    if isinstance(value, dict):
        return value.get("counter", {}).get("packets", 0) + sum(
            counters(child) for child in value.values()
        )
    if isinstance(value, list):
        return sum(counters(child) for child in value)
    return 0
print(counters(data))
'
}

check_url() {
  local url=$1
  local output
  if output=$(runuser -u "$smoke_user" -- \
      curl -4 -I -L --silent --show-error --output /dev/null \
      --max-time 8 --connect-timeout 5 \
      --write-out 'code=%{http_code} remote=%{remote_ip} tls=%{time_appconnect} total=%{time_total}' \
      "$url" 2>&1); then
    printf '  PASS %-32s %s\n' "$url" "$output"
    return 0
  fi
  printf '  FAIL %-32s %s\n' "$url" "$output"
  return 1
}

check_quic() {
  local output
  if output=$(runuser -u "$smoke_user" -- \
      curl -4 --http3-only -I -L --silent --show-error --output /dev/null \
      --max-time 10 --connect-timeout 6 \
      --write-out 'code=%{http_code} remote=%{remote_ip} connect=%{time_connect} total=%{time_total}' \
      https://www.youtube.com/ 2>&1); then
    printf '  PASS %-32s %s\n' 'YouTube HTTP/3' "$output"
    return 0
  fi
  printf '  FAIL %-32s %s\n' 'YouTube HTTP/3' "$output"
  return 1
}

check_googlevideo_flow() {
  local output
  if output=$(runuser -u "$smoke_user" -- \
      curl -4 -k --connect-to ::speedtest.selectel.ru \
      --silent --show-error --output /dev/null --max-time 15 \
      --write-out 'code=%{http_code} remote=%{remote_ip} bytes=%{size_download} speed=%{speed_download} total=%{time_total}' \
      https://test.googlevideo.com/10MB 2>&1); then
    printf '  PASS %-32s %s\n' 'googlevideo 10MB flow' "$output"
    return 0
  fi
  printf '  FAIL %-32s %s\n' 'googlevideo 10MB flow' "$output"
  return 1
}

"$contract_test"
mapfile -t common_arguments < <("$contract_test" --print-profile)
required_arguments=(
  '--user=qnzapret-runtime'
  '--bind-fix4'
  '--bind-fix6'
  '--lua-desync=fake:blob=tls_google:tcp_ts=-100:repeats=2'
  '--lua-desync=send:ipfrag:ipfrag_pos_udp=8:ipfrag_disorder'
)
for required_argument in "${required_arguments[@]}"; do
  if [[ ! " ${common_arguments[*]} " =~ " ${required_argument} " ]]; then
    echo "Compiled profile is missing: $required_argument" >&2
    exit 1
  fi
done
common_arguments+=(--debug=1)

candidates=(
  'production-compiler|'
)

winner=
for candidate in "${candidates[@]}"; do
  name=${candidate%%|*}
  action_text=${candidate#*|}
  read -r -a action_arguments <<<"$action_text"
  log_path="$runtime_dir/$name.log"
  echo "=== $name ==="

  "$nfqws" "${common_arguments[@]}" "${action_arguments[@]}" \
    --dry-run >/dev/null
  "$nfqws" "${common_arguments[@]}" "${action_arguments[@]}" \
    >"$log_path" 2>&1 &
  worker_pid=$!

  for _ in $(seq 1 80); do
    if awk -v queue="$queue" '$1 == queue { found = 1 } END { exit !found }' \
        /proc/net/netfilter/nfnetlink_queue; then
      break
    fi
    if ! kill -0 "$worker_pid" >/dev/null 2>&1; then
      echo "nfqws2 exited before queue registration." >&2
      sed -n '1,180p' "$log_path" >&2
      exit 1
    fi
    sleep 0.05
  done
  awk -v queue="$queue" '$1 == queue { found = 1 } END { exit !found }' \
    /proc/net/netfilter/nfnetlink_queue
  "$contract_test" --print-nft | nft -f -

  packets_before=$(counter_packets)
  candidate_ok=true
  check_url https://www.google.com/ || candidate_ok=false
  check_url https://www.youtube.com/ || candidate_ok=false
  check_url https://i.ytimg.com/ || candidate_ok=false
  check_googlevideo_flow || candidate_ok=false
  check_quic || candidate_ok=false
  packets_after=$(counter_packets)
  printf '  nft counters: %s -> %s\n' "$packets_before" "$packets_after"
  grep -E \
    'hostname|hostlist|TLS|tls_client_hello|split pos|resolved split|autottl|ipfrag|profile' \
    "$log_path" | tail -n 24 | sed 's/^/  nfqws: /' || true

  cleanup_candidate
  if [[ $candidate_ok == true ]]; then
    winner=$name
    break
  fi
done

if [[ -z $winner ]]; then
  echo "No TLS strategy candidate passed the real IPv4 smoke." >&2
  exit 1
fi
echo "REAL_STRATEGY_WINNER=$winner"
