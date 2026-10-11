#!/usr/bin/env bash
# Synthetic storage identity/cleanup restart; never touches a physical device.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_SERIAL:?Choose a disposable emulator explicitly}"
case "$ANDROID_SERIAL" in emulator-[0-9]*) ;; *) echo 'Choose a disposable emulator.' >&2; exit 2 ;; esac
adb_bin="$ANDROID_HOME/platform-tools/adb"
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo 'Refusing storage restart qualification on a physical device.' >&2; exit 2;
}
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
  echo 'Wait for the selected emulator to finish booting.' >&2; exit 2;
}
cd "$(dirname "$0")/.."
reports="${KITHMOOT_RECORDING_REPORTS:-$PWD/app/build/reports/native-recording-upload-restart}"
mkdir -p "$reports"
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_stage() {
  local stage="$1"
  "$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e class dev.forgesworn.kithmoot.media.recording.RecordingUploadJournalAndroidTest \
    -e recordingUploadRestartStage "$stage" \
    dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/$stage.txt"
  rg -q '^OK \(1 tests?\)' "$reports/$stage.txt"
  ! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$reports/$stage.txt"
}
run_stage prepare
"$adb_bin" -s "$ANDROID_SERIAL" shell am force-stop dev.forgesworn.kithmoot
run_stage verify
