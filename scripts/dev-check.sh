#!/usr/bin/env bash
# Bounded development checks; never starts the journal service or installs an APK.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"
case "${1:-help}" in
  android)
    : "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ (with javac)}"
    : "${ANDROID_HOME:?Set ANDROID_HOME to the SDK with platform 36/build-tools 35.0.0}"
    test -x "$JAVA_HOME/bin/javac"
    exec ./android/gradlew -p android --no-daemon --max-workers=2 \
      -Dorg.gradle.parallel=false testDebugUnitTest assembleDebug lintDebug \
      :app:assembleSandbox :app:lintSandbox
    ;;
  rust)
    # A rootless container also supplies the C toolchain missing from SteamOS.
    engine=podman
    cache=${XDG_CACHE_HOME:-$HOME/.cache}/pocketlog-dev
    mkdir -p "$cache/cargo" "$cache/target"
    "$engine" build --jobs=1 --memory=4g --cpu-period=100000 --cpu-quota=200000 \
      -t localhost/pocketlog-dev:rust-1.100.0-audit-0.23.0 -f dev/Containerfile .
    exec "$engine" run --rm --cpus=2 --memory=4g --pids-limit=512 \
      --userns=keep-id -e CARGO_HOME=/cargo -e CARGO_TARGET_DIR=/target \
      -e CARGO_BUILD_JOBS=2 -e RUSTUP_HOME=/usr/local/rustup \
      -v "$root/server/Cargo.toml:/workspace/server/Cargo.toml:ro" \
      -v "$root/server/Cargo.lock:/workspace/server/Cargo.lock:ro" \
      -v "$root/server/src:/workspace/server/src:ro" \
      -v "$root/server/tests:/workspace/server/tests:ro" \
      -v "$root/server/web:/workspace/server/web:ro" \
      -v "$cache/cargo:/cargo" -v "$cache/target:/target" \
      -w /workspace/server localhost/pocketlog-dev:rust-1.100.0-audit-0.23.0 \
      bash -euc 'cargo fmt --check; cargo clippy --locked --all-targets -- -D warnings; cargo test --locked; /usr/local/cargo/bin/cargo-audit audit --file Cargo.lock'
    ;;
  web)
    node --check server/web/app.js
    exec node scripts/test-web-timeline.cjs
    ;;
  *)
    echo 'Usage: scripts/dev-check.sh {android|rust|web}'
    echo 'See docs/DEVELOPMENT.md for toolchains, isolated Pixel install and Docker smoke.'
    exit 2
    ;;
esac
