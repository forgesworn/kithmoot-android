#!/usr/bin/env bash
# Synthetic signed stop recovery through actual Keystore storage and process death.
set -euo pipefail
command -v grep >/dev/null || { echo 'grep is required to verify instrumentation results.' >&2; exit 2; }
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${ANDROID_SERIAL:?Choose a disposable emulator explicitly}"
case "$ANDROID_SERIAL" in emulator-[0-9]*) ;; *) echo 'Disposable emulator required.' >&2; exit 2 ;; esac
adb_bin="$ANDROID_HOME/platform-tools/adb"
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 2
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || exit 2
cd "$(dirname "$0")/.."
reports="${KITHMOOT_RECORDING_REPORTS:-$PWD/app/build/reports/native-recording-stop-restart}"
mkdir -p "$reports"
fixture="recording-stop-restart-$(openssl rand -hex 6)"
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_stage() {
  local stage="$1"
  "$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e recordingStopRestartFixture "$fixture" -e recordingStopRestartStage "$stage" \
    -e class dev.forgesworn.kithmoot.media.recording.RecordingStopRestartAndroidTest \
    dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/$stage.txt"
  grep -Eq '^OK \(1 tests?\)' "$reports/$stage.txt"
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|AssumptionViolated|INSTRUMENTATION_STATUS_CODE: -3' "$reports/$stage.txt"
}
trap 'run_stage cleanup' EXIT
run_stage prepare
"$adb_bin" -s "$ANDROID_SERIAL" shell am force-stop dev.forgesworn.kithmoot
run_stage verify
