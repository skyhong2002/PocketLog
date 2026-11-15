#!/usr/bin/env bash
# Install only the isolated app, on an explicitly selected device. No UI or grants.
set -euo pipefail
if [[ $# != 1 || -z $1 || $1 == -* ]]; then
  echo 'Usage: ANDROID_HOME=/path/to/sdk scripts/install-sandbox.sh DEVICE_SERIAL' >&2
  exit 2
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
apk="$root/android/app/build/outputs/apk/sandbox/app-sandbox.apk"
badging=$("$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk")
if [[ ${badging%%$'\n'*} != "package: name='tw.skyhong.pocketlog.sandbox' "* ]]; then
  echo 'Refusing APK without the isolated sandbox application ID.' >&2
  exit 1
fi
exec "$ANDROID_HOME/platform-tools/adb" -s "$1" install -r "$apk"
