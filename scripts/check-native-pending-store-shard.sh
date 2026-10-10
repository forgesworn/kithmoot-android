#!/usr/bin/env bash
set -euo pipefail

# Require verified exact artifacts and a disposable emulator BEFORE install.
cd "$(dirname "$0")/.."
if [[ ! "${ANDROID_SERIAL:-}" =~ ^emulator-[0-9]+$ ]]; then
  echo 'Set ANDROID_SERIAL to a disposable emulator.' >&2; exit 2
fi
case "${PENDING_WINDOW:-}" in
  committed-source-before-offer|charged-original-before-offer|offered-before-index|index-committed-before-source-acknowledgement|reference-installed-before-subscription-switch) ;;
  *) echo 'Select exactly one pending replacement window.' >&2; exit 2 ;;
esac
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
adb_args=(-s "$ANDROID_SERIAL")
if [[ -n "${ANDROID_ADB_SERVER_PORT:-}" ]]; then
  adb_args=(-P "$ANDROID_ADB_SERVER_PORT" "${adb_args[@]}")
fi
adb_device() { "$adb_bin" "${adb_args[@]}" "$@"; }
if [[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'Refusing pending-store shard acceptance on a physical device.' >&2
  exit 2
fi
python3 scripts/prepare-native-pending-inputs.py verify build/native-pending-store-apks --head "${GITHUB_SHA:?Set exact workflow head}"
adb_device install -r build/native-pending-store-apks/app-debug.apk
adb_device install -r build/native-pending-store-apks/app-debug-androidTest.apk
python3 scripts/check-native-pending-store-refusal-emulator.py "${PENDING_WINDOW:?Set one pending window}"
