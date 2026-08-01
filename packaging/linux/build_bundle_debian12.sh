#!/usr/bin/env bash
set -euo pipefail

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
flutter_dir=${QNZAPRET_FLUTTER_DIR:-"$HOME/.dev/flutter"}
bundle_parent="$repo_dir/build/linux/x64/release"
mkdir -p "$bundle_parent"
staging_dir=$(mktemp -d "$repo_dir/build/linux/.debian12-bundle.XXXXXX")
cleanup() {
  rm -rf -- "$staging_dir"
}
trap cleanup EXIT

if ! command -v podman >/dev/null 2>&1; then
  echo "Podman is required for the reproducible Debian 12 bundle build." >&2
  exit 1
fi
if [[ ! -x "$flutter_dir/bin/flutter" ]]; then
  echo "Flutter SDK not found at $flutter_dir." >&2
  exit 1
fi

podman run --rm --security-opt label=disable \
  --tmpfs /work:rw,exec,size=8g \
  -v "$repo_dir:/source:ro" \
  -v "$staging_dir:/output" \
  -v "$flutter_dir:/opt/flutter" \
  docker.io/library/debian:12-slim sh -lc '
    set -eu
    apt-get update >/dev/null
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      ca-certificates clang cmake git libgtk-3-dev liblzma-dev lld llvm \
      ninja-build pkg-config >/dev/null
    export PATH=/opt/flutter/bin:$PATH
    mkdir -p /work/qnzapret
    tar -C /source --exclude=.dart_tool --exclude=.git --exclude=build \
      -cf - . | tar -C /work/qnzapret -xf -
    cd /work/qnzapret
    flutter config --no-analytics >/dev/null
    flutter build linux --release
    cp -a build/linux/x64/release/bundle /output/bundle
  '

bundle_target="$bundle_parent/bundle"
previous_bundle="$staging_dir/previous-bundle"
if [[ -e "$bundle_target" ]]; then
  mv -- "$bundle_target" "$previous_bundle"
fi
if ! mv -- "$staging_dir/bundle" "$bundle_target"; then
  if [[ -e "$previous_bundle" ]]; then
    mv -- "$previous_bundle" "$bundle_target"
  fi
  exit 1
fi

echo "Debian 12-compatible Linux bundle: $bundle_target"
