#!/usr/bin/env bash
set -euo pipefail
# Separate whole suites across disposable emulator jobs; keep every deadline.
case "${1:-}" in ordinary|restarts) ;; *) echo 'Use ordinary or restarts.' >&2; exit 2 ;; esac
cd "$(dirname "$0")/.."
if [ "$1" = ordinary ]; then
  bash scripts/check-recovery-emulator.sh
else
  case "${ANDROID_SERIAL:-}" in
    emulator-[0-9]*) ;;
    *) echo 'Set ANDROID_SERIAL to a disposable emulator serial.' >&2; exit 2 ;;
  esac
  adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
  adb_args=(-s "$ANDROID_SERIAL")
  if [[ -n "${ANDROID_ADB_SERVER_PORT:-}" ]]; then
    adb_args=(-P "$ANDROID_ADB_SERVER_PORT" "${adb_args[@]}")
  fi
  adb_device() { "$adb_bin" "${adb_args[@]}" "$@"; }
  if [[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
    echo 'Refusing restart acceptance on a non-emulator device.' >&2; exit 2
  fi
  reports=app/build/reports/sharing-restart-emulator
  mkdir -p "$reports"
  adb_device install -r app/build/outputs/apk/debug/app-debug.apk
  adb_device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  launcher="$(adb_device shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)"
  case "$launcher" in
    com.google.android.apps.nexuslauncher/*|com.android.launcher3/*)
      adb_device logcat -d -t 4000 > "$reports/emulator-setup-logcat.txt"
      adb_device shell am force-stop "${launcher%%/*}"
      ;;
  esac
  python3 scripts/check-sharing-restart-emulator.py
  python3 scripts/check-room-logo-restart-emulator.py image
  python3 scripts/check-room-logo-restart-emulator.py removal
  python3 scripts/check-native-host-restart-emulator.py
  python3 scripts/check-native-rekey-restart-emulator.py before-handoff
  python3 scripts/check-native-rekey-restart-emulator.py after-handoff
  python3 scripts/check-native-retirement-restart-emulator.py pending-original
  python3 scripts/check-native-retirement-restart-emulator.py reserved-before-offer
  python3 scripts/check-native-retirement-restart-emulator.py offered-before-archive
  python3 scripts/check-native-retirement-restart-emulator.py archive-before-hint
  python3 scripts/check-native-replacement-restart-emulator.py committed-source-before-offer
  python3 scripts/check-native-replacement-restart-emulator.py charged-original-before-offer
  python3 scripts/check-native-replacement-restart-emulator.py offered-before-index
  python3 scripts/check-native-replacement-restart-emulator.py index-committed-before-source-acknowledgement
  python3 scripts/check-native-replacement-restart-emulator.py reference-installed-before-subscription-switch
fi
