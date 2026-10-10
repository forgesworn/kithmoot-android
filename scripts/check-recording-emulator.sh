#!/usr/bin/env bash
# Synthetic native capture/export qualification; never runs on a physical phone.
set -euo pipefail
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
: "${ANDROID_SERIAL:?Choose a disposable emulator explicitly}"
case "$ANDROID_SERIAL" in
  emulator-[0-9]*) ;;
  *) echo 'Recording qualification requires a disposable emulator.' >&2; exit 2 ;;
esac
adb_bin="$ANDROID_HOME/platform-tools/adb"
classes='dev.forgesworn.kithmoot.media.recording.AacRecordingTest,dev.forgesworn.kithmoot.media.recording.AvRecordingTest,dev.forgesworn.kithmoot.media.recording.NativeVideoSinkTest,dev.forgesworn.kithmoot.media.recording.RecordingVideoSceneTest,dev.forgesworn.kithmoot.media.recording.RecordingOwnerUiTest,dev.forgesworn.kithmoot.media.recording.RecordingPlaybackUiTest,dev.forgesworn.kithmoot.media.recording.RecordingUploadJournalAndroidTest'
case "${1:-full}" in
  full) classes="dev.forgesworn.kithmoot.media.BrowserCallInteropTest#nativeRecordingExportsBothSidesOfTheBrowserCall,$classes"; expected=16 ;;
  local-capture) expected=15 ;;
  *) echo 'Choose full or local-capture; local-capture excludes live browser interop.' >&2; exit 2 ;;
esac
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" get-state 2>/dev/null)" == device ]] || {
  echo 'The selected disposable emulator is not online.' >&2; exit 2;
}
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo 'Refusing recording qualification on a physical device.' >&2; exit 2;
}
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || {
  echo 'Wait for the disposable emulator to complete its boot before running qualification.' >&2; exit 2;
}
cd "$(dirname "$0")/.."
reports="${KITHMOOT_RECORDING_REPORTS:-$PWD/app/build/reports/native-recording}"
mkdir -p "$reports"
# Feature worktrees can precede the version installed by another candidate.
# Android permits this downgrade for debuggable APKs; this script is restricted
# to a deliberately selected disposable emulator above.
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class "$classes" \
  dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/instrumentation.txt"
# am instrument can exit zero even when the test process fails.
rg -q "^OK \\($expected tests\\)" "$reports/instrumentation.txt"
! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$reports/instrumentation.txt"
