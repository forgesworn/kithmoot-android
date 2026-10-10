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
pull_proof() {
  local copier=(python3 scripts/pull-recovery-proof.py --adb "$adb_bin"
    --serial "$ANDROID_SERIAL" --source "$1" --destination "$reports" --reports "$reports")
  if [[ -n "${ANDROID_ADB_SERVER_PORT:-}" ]]; then
    copier+=(--server-port "$ANDROID_ADB_SERVER_PORT")
  fi
  "${copier[@]}"
}
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
  local commands=()
  shift 2
  local runner=(python3 scripts/run-recovery-instrumentation.py --adb "$adb_bin"
    --serial "$ANDROID_SERIAL" --reports "$reports" --report "$report")
  if [[ -n "${ANDROID_ADB_SERVER_PORT:-}" ]]; then
    runner+=(--server-port "$ANDROID_ADB_SERVER_PORT")
  fi
  # Capture every pipeline status before errexit can discard failure evidence.
  # An instrumentation summary can say OK even if adb itself exits nonzero.
  if "${runner[@]}" -- "$@" | tee "$reports/$report.txt"; then
    commands=("${PIPESTATUS[@]}")
  else
    commands=("${PIPESTATUS[@]}")
  fi
  # am instrument may exit zero after an assertion failure or process crash.
  if [[ "${commands[*]}" != '0 0' ]] || ! grep -Eq "^OK \($count tests?\)$" "$reports/$report.txt"; then
    echo "Instrumentation did not pass: $report (runner=${commands[0]}, tee=${commands[1]})" >&2
    # A disconnected emulator must not hide the original command failure.
    adb_device logcat -d -t 20000 > "$reports/$report-logcat.txt" || echo 'Could not capture emulator logcat' >&2
    adb_device exec-out screencap -p > "$reports/$report-screen.png" || echo 'Could not capture emulator screen' >&2
    exit 1
  fi
}

run_tests storage-and-ui 9 -e class \
  dev.forgesworn.kithmoot.storage.EncryptedRoomStorageTest,dev.forgesworn.kithmoot.storage.DisplayNameAndroidTest,dev.forgesworn.kithmoot.storage.RoomRecoveryUiTest
run_tests nearby-room-ui 3 -e class dev.forgesworn.kithmoot.ui.NearbyRoomUiTest
run_tests room-sharing-ui 2 -e class dev.forgesworn.kithmoot.ui.RoomSharingUiTest
run_tests room-sharing-entry 2 -e class dev.forgesworn.kithmoot.ui.RoomSharingEntryTest
run_tests nearby-room-entry 1 -e class dev.forgesworn.kithmoot.ui.NearbyRoomEntryTest
run_tests fresh-nearby-entry 6 -e class dev.forgesworn.kithmoot.ui.FreshNearbyEntryTest
run_tests native-host-entry 11 -e class dev.forgesworn.kithmoot.ui.NativeHostEntryTest
run_tests native-member-commands 5 -e class dev.forgesworn.kithmoot.epoch.NativeMemberCommandAndroidTest
run_tests native-retirement-store-refusal 6 -e class dev.forgesworn.kithmoot.ui.NativeRetirementStoreRefusalTest
run_tests composer-repeat-taps 1 -e class dev.forgesworn.kithmoot.ui.ComposerRepeatTapUiTest
run_tests home-screen 25 -e class dev.forgesworn.kithmoot.ui.HomeScreenUiTest
run_tests restart-prepare 1 -e class dev.forgesworn.kithmoot.storage.RoomRestartTest#a_prepare
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests restart-reopen 1 -e class dev.forgesworn.kithmoot.storage.RoomRestartTest#b_reopen -e requireRestart true

run_tests group-prepare 1 -e class dev.forgesworn.kithmoot.storage.PersistentGroupUiTest#a_create_and_join_web_group
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests group-reopen 1 -e class dev.forgesworn.kithmoot.storage.PersistentGroupUiTest#b_reopen_without_relay_or_creator -e requireRestart true
run_tests group-refusals 1 -e class dev.forgesworn.kithmoot.storage.PersistentGroupUiTest#c_refused_publication_and_retired_web_link_stay_outside_room

run_tests room-workspace 1 -e class dev.forgesworn.kithmoot.ui.RoomWorkspaceUiTest
pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/room-workspace.png"
run_tests room-countdown-journey 2 -e class dev.forgesworn.kithmoot.storage.RoomCountdownJourneyUiTest
pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/self-destruct-room-journey.png"
run_tests room-destruct-journey 1 -e class dev.forgesworn.kithmoot.storage.RoomDestructJourneyUiTest#a_expiry_deletes_the_open_room_and_shows_the_burst
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests room-destruct-restarted 1 -e class dev.forgesworn.kithmoot.storage.RoomDestructJourneyUiTest#b_the_deleted_room_stays_gone_after_a_process_restart -e requireRestart true
for picture in final-minute burst restarted; do
  pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/self-destruct-journey-$picture.png"
done
run_tests chat-and-screen-share 2 -e class dev.forgesworn.kithmoot.ui.ChatAndShareUiTest
for picture in chat viewer pip; do
  pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/chat-share-$picture.png"
done
run_tests artwork-picker 5 -e class dev.forgesworn.kithmoot.ui.ArtworkPickerUiTest
pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/artwork-review"

run_tests box-discovery-consent 1 -e class dev.forgesworn.kithmoot.ui.BoxDiscoveryUiTest
pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/box-discovery.png"
run_tests cadence-ui 1 -e class dev.forgesworn.kithmoot.ui.CadenceUiTest
run_tests room-epoch-ui 1 -e class dev.forgesworn.kithmoot.ui.RoomEpochUiTest
run_tests shared-work-ui 2 -e class dev.forgesworn.kithmoot.ui.SharedWorkUiTest
run_tests workspace-inbox-ui 5 -e class dev.forgesworn.kithmoot.ui.WorkspaceInboxUiTest
run_tests workspace-inbox-relay 1 -e class dev.forgesworn.kithmoot.ui.WorkspaceInboxRelayUiTest
run_tests shared-work-relay 1 -e class dev.forgesworn.kithmoot.ui.RoomWorkRelayTest
run_tests shared-work-entry 1 -e class dev.forgesworn.kithmoot.storage.PersistentGroupUiTest#d_shared_work_survives_initial_epoch_and_real_room_entry
pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/shared-work-review.png"
pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/shared-work-entry.png"

run_tests site-address-choose 1 -e class dev.forgesworn.kithmoot.storage.SiteAddressUiTest#a_choose_site
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests site-address-reopen 1 -e class dev.forgesworn.kithmoot.storage.SiteAddressUiTest#b_reopen_and_share_without_the_workshop_site -e requireRestart true

pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/site-address.png"

run_tests shared-projects-ui 2 -e class dev.forgesworn.kithmoot.projects.SharedProjectsUiTest
for picture in three-shared-projects project-room-admission restored-project-membership project-room-assignment; do
  pull_proof "/sdcard/Android/data/dev.forgesworn.kithmoot/files/ui-proof/$picture.png"
done
run_tests project-restart-prepare 1 -e class dev.forgesworn.kithmoot.projects.SharedProjectsRestartTest#a_prepare_pending
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests project-restart-recover 1 -e class dev.forgesworn.kithmoot.projects.SharedProjectsRestartTest#b_recover_exact_pending -e requireRestart true

run_tests chat-notifications 2 -e class dev.forgesworn.kithmoot.notifications.ChatNotificationsTest

# The MLS vault under the restore-witness coordinator (P3-03b-2): the real
# engine, Keystore-backed and copied software-key profiles, an in-process
# Ed25519 witness, and the order in which superseded seal keys are deleted.
run_tests mls-vault-storage 4 -e class dev.forgesworn.kithmoot.storage.MlsVaultStorageTest
run_tests coordinated-vault 20 -e class \
  dev.forgesworn.kithmoot.storage.CoordinatedVaultEngineTest,dev.forgesworn.kithmoot.storage.RollbackKeyOrderTest
# P3-03b-3a: a real engine session under the coordinator, created with the
# vault's own leaf binding signature, each step witnessed before release.
run_tests session-host 2 -e class dev.forgesworn.kithmoot.storage.SessionHostEngineTest
# P3-03b-3a: the driver loop with the real engine, an Update commit through a
# box signing real Ed25519 slot receipts, read back and applied.
run_tests session-driver 1 -e class dev.forgesworn.kithmoot.storage.SessionDriverEngineTest
# W01-W04 and a crash before the stale key's deletion: each persona is killed
# at its own point, then the app is force-stopped and recovers in a new process.
run_tests coordinated-kill 1 -e class dev.forgesworn.kithmoot.storage.CoordinatedVaultRestartTest#a_kill
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests coordinated-recover 1 -e class dev.forgesworn.kithmoot.storage.CoordinatedVaultRestartTest#b_recover -e requireRestart true
# P3-03b-3a: a session step killed after its snapshot write, its stage, the
# witness's commit and its promotion, recovered in a new process.
run_tests session-kill 1 -e class dev.forgesworn.kithmoot.storage.SessionRestartTest#a_kill
adb_device shell am force-stop dev.forgesworn.kithmoot
run_tests session-recover 1 -e class dev.forgesworn.kithmoot.storage.SessionRestartTest#b_recover -e requireRestart true
