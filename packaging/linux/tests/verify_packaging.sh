#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
runtime_unit="$repo_dir/packaging/linux/systemd/qnzapret-runtime.service"
telegram_unit="$repo_dir/packaging/linux/systemd/qnzapret-telegram.service"
sidecar_source="$repo_dir/linux/runtime/telegram_sidecar.cc"
bundle_script="$repo_dir/packaging/linux/build_bundle_debian12.sh"
state_test_binary=$(mktemp "${TMPDIR:-/tmp}/qnzapret-state-test.XXXXXX")
trap 'rm -f -- "$state_test_binary"' EXIT

assert_line() {
  local expected=$1
  local path=$2
  if ! grep -Fqx -- "$expected" "$path"; then
    echo "Missing expected line in $path: $expected" >&2
    exit 1
  fi
}

assert_absent() {
  local unexpected=$1
  local path=$2
  if grep -Fq -- "$unexpected" "$path"; then
    echo "Unexpected text in $path: $unexpected" >&2
    exit 1
  fi
}

assert_line \
  "CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_RAW CAP_SETUID CAP_SETGID CAP_KILL" \
  "$runtime_unit"
assert_line \
  "AmbientCapabilities=CAP_NET_ADMIN CAP_NET_RAW CAP_SETUID CAP_SETGID CAP_KILL" \
  "$runtime_unit"
assert_line "MemoryDenyWriteExecute=no" "$runtime_unit"

assert_line "StateDirectory=qnzapret" "$telegram_unit"
assert_line "StateDirectoryMode=0700" "$telegram_unit"
assert_absent "ReadWritePaths=" "$telegram_unit"
assert_absent "ListenStream=" "$telegram_unit"
grep -Fq 'TelegramStateDirectory()' "$sidecar_source"
grep -Fq 'chmod(g_state.secret_path.c_str(), 0600)' "$sidecar_source"
grep -Fq '"--host=127.0.0.1"' "$sidecar_source"
g++ -std=c++17 -Wall -Wextra -Werror \
  "$repo_dir/linux/runtime/telegram_state_directory_test.cc" \
  -I"$repo_dir/linux/runtime" $(pkg-config --cflags --libs glib-2.0) \
  -o "$state_test_binary"
"$state_test_binary"

assert_line '  -v "$repo_dir:/source:ro" \' "$bundle_script"
assert_absent 'repo_dir:/workspace' "$bundle_script"
assert_absent 'export HOME=' "$bundle_script"
grep -Fq -- '--exclude=.dart_tool' "$bundle_script"

grep -Fq 'version=0.0.6' "$repo_dir/packaging/linux/build_packages.sh"
grep -Fq '"qnzapret-${version}-*.rpm"' \
  "$repo_dir/packaging/linux/build_packages.sh"
grep -Eq '^Version:[[:space:]]+0\.0\.6$' \
  "$repo_dir/packaging/linux/qnzapret.spec"
grep -Fq '<release version="0.0.6" date="2026-08-01"/>' \
  "$repo_dir/packaging/linux/dev.qnzapret.metainfo.xml"
grep -Fq '%systemd_user_post qnzapret-telegram.service' \
  "$repo_dir/packaging/linux/qnzapret.spec"
grep -Fq '%systemd_user_preun qnzapret-telegram.service' \
  "$repo_dir/packaging/linux/qnzapret.spec"
grep -Fq '%systemd_user_postun_with_restart qnzapret-telegram.service' \
  "$repo_dir/packaging/linux/qnzapret.spec"
grep -Fq 'systemctl --user --machine="${user}@" daemon-reload' \
  "$repo_dir/packaging/linux/scripts/postinst"
assert_absent '"org.freedesktop.Application", "Open"' \
  "$repo_dir/linux/runner/linux_proxy_runtime_plugin.cc"
grep -Fq 'SOCK_STREAM | SOCK_CLOEXEC' \
  "$repo_dir/linux/runtime/telegram_sidecar.cc"
grep -Fq 'ProtectHome=read-only' \
  "$repo_dir/packaging/linux/systemd/qnzapret-telegram.service"

grep -Fq '/usr/libexec/qnzapret-runtime --cleanup' \
  "$repo_dir/packaging/linux/scripts/prerm"
for lifecycle_path in \
  "$repo_dir/packaging/linux/scripts/postinst" \
  "$repo_dir/packaging/linux/scripts/prerm" \
  "$repo_dir/packaging/linux/scripts/postrm" \
  "$repo_dir/packaging/linux/qnzapret.spec"; do
  assert_absent "flush ruleset" "$lifecycle_path"
  assert_absent "/etc/hosts" "$lifecycle_path"
  assert_absent "resolv.conf" "$lifecycle_path"
  assert_absent "NetworkManager" "$lifecycle_path"
done

echo "Linux packaging checks passed."
