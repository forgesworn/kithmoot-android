#!/usr/bin/env bash
# Actual Android/browser sessions over a disposable loopback relay.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_SERIAL:?Choose a disposable emulator explicitly}"
case "$ANDROID_SERIAL" in
  emulator-[0-9]*) ;;
  *) echo 'Session qualification requires a disposable emulator.' >&2; exit 2 ;;
esac
adb_bin="$ANDROID_HOME/platform-tools/adb"
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo 'Refusing Session qualification on a physical device.' >&2; exit 2;
}
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
  echo 'Wait for the disposable emulator to complete its boot.' >&2; exit 2;
}
cd "$(dirname "$0")/.."
reports="${KITHMOOT_RECORDING_REPORTS:-$PWD/app/build/reports/native-recording-session}"
mkdir -p "$reports"
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Verify installed bytecode outside the app's bounded process-start window.
"$adb_bin" -s "$ANDROID_SERIAL" shell cmd package compile -m verify -f dev.forgesworn.kithmoot
"$adb_bin" -s "$ANDROID_SERIAL" shell cmd package compile -m verify -f dev.forgesworn.kithmoot.test
"$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class dev.forgesworn.kithmoot.media.recording.LiveRecordingBrowserInteropTest \
  dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/instrumentation.txt"
rg -q '^OK \(1 test\)' "$reports/instrumentation.txt"
! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$reports/instrumentation.txt"
! rg -q '^INSTRUMENTATION_STATUS_CODE: (-3|-4)$' "$reports/instrumentation.txt"
