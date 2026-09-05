#!/usr/bin/env bash
set -euo pipefail

# These tests replace saved room data. Only run on a disposable emulator.
case "${ANDROID_SERIAL:-}" in
  emulator-[0-9]*) ;;
  *) echo 'Set ANDROID_SERIAL to a disposable emulator serial (emulator-PORT).' >&2; exit 2 ;;
esac
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
adb_args=(-s "$ANDROID_SERIAL")
if [[ -n "${ANDROID_ADB_SERVER_PORT:-}" ]]; then
  adb_args=(-P "$ANDROID_ADB_SERVER_PORT" "${adb_args[@]}")
fi
adb_device() { "$adb_bin" "${adb_args[@]}" "$@"; }
if [[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'Refusing to replace room data on a non-emulator device.' >&2
  exit 2
fi

cd "$(dirname "$0")/.."
reports=app/build/reports/recovery-emulator
mkdir -p "$reports"
adb_device install -r app/build/outputs/apk/debug/app-debug.apk
adb_device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Fresh API 35 images can leave Pixel Launcher in a first-boot ANR, whose
# system dialog covers the test activity. Restart only the emulator's known
# home process before acceptance starts. App crashes and ANRs still fail the
# normal visible-UI assertions; nothing dismisses dialogs during a test.
launcher="$(adb_device shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)"
case "$launcher" in
  com.google.android.apps.nexuslauncher/*|com.android.launcher3/*)
    adb_device logcat -d -t 4000 > "$reports/emulator-setup-logcat.txt"
    echo "Restarting emulator home before acceptance: ${launcher%%/*}"
    adb_device shell am force-stop "${launcher%%/*}"
    ;;
esac

run_tests() {
  local report="$1" count="$2"
  shift 2
  adb_device shell am instrument -w "$@" \
    dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$reports/$report.txt"
  # am instrument may exit zero after an assertion failure or process crash.
  if ! grep -Eq "^OK \($count tests?\)$" "$reports/$report.txt"; then
    adb_device logcat -d -t 20000 > "$reports/$report-logcat.txt"
    adb_device exec-out screencap -p > "$reports/$report-screen.png"
    echo "Instrumentation did not pass: $report" >&2
    exit 1
  fi
}

run_tests storage-and-ui 6 -e class \
  dev.forgesworn.kithmoot.storage.EncryptedRoomStorageTest,dev.forgesworn.kithmoot.storage.DisplayNameAndroidTest,dev.forgesworn.kithmoot.storage.RoomRecoveryUiTest
run_tests restart-prepare 1 -e class dev.forgesworn.kithmoot.storage.RoomRestartTest#a_prepare
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests restart-reopen 1 -e class dev.forgesworn.kithmoot.storage.RoomRestartTest#b_reopen -e requireRestart true

run_tests chat-and-screen-share 1 -e class dev.forgesworn.kithmoot.ui.ChatAndShareUiTest
for picture in chat viewer pip; do
  adb_device pull "/sdcard/Android/data/dev.forgesworn.kithmoot/files/chat-share-$picture.png" "$reports/"
done
