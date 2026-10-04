#!/usr/bin/env bash
# The P3-03b-2 lab run (C8): the coordinated vault against a real `bothyd`
# restore witness, on a disposable emulator. Not CI. It starts a fresh
# `bothyd` in a temporary directory, on a WebPKI `wss://` Link relay (cards
# admit nothing else; ForgeSworn's own rehearsal relay by default), then:
#   1. `bothyd witness init`, and a witness-only pairing code from the daemon;
#   2. RestoreWitnessLabTest#a: the persona pairs its own writer, takes genesis,
#      and is held, since the box has not enrolled it;
#   3. the keeper's `bothyd witness enrol` line, exactly as the app shows it;
#   4. #b: active; the device enrolment and a signature, each witnessed;
#   5. a force-stop, then #c in a new process: the identical retry replays;
#      two cloned profiles, whose engines share one Link node id, write at
#      once and exactly one wins;
#   6. `bothyd witness retire` for the subject.
#
# Usage: ANDROID_SERIAL=emulator-PORT BOTHYD=… [LAB_RELAY=wss://…] scripts/lab-restore-witness.sh
# Build the debug app and instrumentation APKs first.
set -euo pipefail

case "${ANDROID_SERIAL:-}" in
  emulator-[0-9]*) ;;
  *) echo 'Set ANDROID_SERIAL to a disposable emulator serial (emulator-PORT).' >&2; exit 2 ;;
esac
: "${BOTHYD:?Set BOTHYD to a bothyd binary built from bothy-node main}"
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
adb_device() { "$adb_bin" -s "$ANDROID_SERIAL" "$@" </dev/null; }
if [[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'Refusing to run on a non-emulator device.' >&2
  exit 2
fi
relay_url="${LAB_RELAY:-wss://link1.forgesworn.dev/link}"

cd "$(dirname "$0")/.."
reports="$PWD/app/build/reports/restore-witness-lab"
mkdir -p "$reports"
work="$(mktemp -d "${TMPDIR:-/tmp}/witness-lab.XXXXXX")"
data="$work/data"
bothyd_pid=""
cleanup() {
  if [[ -n "$bothyd_pid" ]] && kill -0 "$bothyd_pid" 2>/dev/null; then kill "$bothyd_pid" 2>/dev/null || true; wait "$bothyd_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

# No loopback listener (another box on this machine may hold its port), and
# no public Nostr relays: the lab box publishes nothing.
# The witness flags come after the first start, which only makes the node's
# key. The control socket is relative, run from the work directory: a socket
# path has a short length limit.
witness=()
bothyd() {
  (cd "$work" && exec "$BOTHYD" --data-dir "$data" --link-relay "$relay_url" --role shelter --pool-bytes 1073741824 \
    --loopback-enabled false --nostr-relay ws://127.0.0.1:9 ${witness[@]+"${witness[@]}"} "$@")
}
start_bothyd() {
  # stdout carries the node's own claim code at first boot: never kept.
  # Started here, not through bothyd(), so the pid is the daemon's own.
  (cd "$work" && exec "$BOTHYD" --data-dir "$data" --link-relay "$relay_url" --role shelter --pool-bytes 1073741824 \
    --loopback-enabled false --nostr-relay ws://127.0.0.1:9 ${witness[@]+"${witness[@]}"} \
    --log-level "info,bothy_link=debug,bothy_runtime=debug") > /dev/null 2>> "$reports/bothyd.log" &
  bothyd_pid=$!
}
stop_bothyd() { kill "$bothyd_pid" 2>/dev/null || true; wait "$bothyd_pid" 2>/dev/null || true; bothyd_pid=""; }
wait_for() {
  local what="$1" tries=60
  shift
  until "$@" >/dev/null 2>&1; do
    tries=$((tries - 1))
    if (( tries == 0 )); then echo "Timed out waiting for $what" >&2; exit 1; fi
    sleep 1
  done
}
run_lab() {
  local report="$1"
  shift
  adb_device shell am instrument -w -e witnessLab true "$@" \
    dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$reports/$report.txt"
  if ! grep -Eq '^OK \(1 test\)$' "$reports/$report.txt"; then
    adb_device logcat -d -t 20000 > "$reports/$report-logcat.txt"
    echo "Lab step did not pass: $report" >&2
    exit 1
  fi
}
from_device() { adb_device shell cat "/sdcard/Android/data/dev.forgesworn.kithmoot/files/witness-lab/$1" | tr -d '\r'; }

echo "==> Link relay $relay_url"

echo "==> bothyd: first start makes the node's Link key, then witness init"
: > "$reports/bothyd.log"
start_bothyd
wait_for "the node's Link key" test -s "$data/identity/link.key"
sleep 2
stop_bothyd
mkdir -m 700 "$work/ctl"
witness=(--witness-dir "$work/witness" --witness-control-socket ctl/witness.sock)
bothyd witness init
start_bothyd
wait_for "the witness control socket" test -S "$work/ctl/witness.sock"

adb_device install -r app/build/outputs/apk/debug/app-debug.apk
adb_device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "==> a witness-only pairing code (kept off disk)"
code="$(bothyd witness pair | grep -Eo 'bothy:[^[:space:]]+' | head -1)"
[[ -n "$code" ]] || { echo 'No pairing code was printed.' >&2; exit 1; }
run_lab a-pair -e class dev.forgesworn.kithmoot.storage.RestoreWitnessLabTest#a_pair_and_begin -e pairingCode "$code"
code=""

line="$(from_device enrol-line.txt)"
subject="$(from_device subject.txt)"
echo "==> the keeper runs: $line"
[[ "$line" =~ ^bothyd\ witness\ enrol\ --subject\ [0-9a-f]{64}\ --installation\ [0-9a-f]{64}\ --writer\ [0-9a-f]{64}\ --initial-digest\ [0-9a-f]{64}$ ]] \
  || { echo 'The enrol line is not the expected shape.' >&2; exit 1; }
read -r -a enrol <<< "${line#bothyd }"
bothyd "${enrol[@]}" | tee "$reports/enrol.txt"

run_lab b-active -e class dev.forgesworn.kithmoot.storage.RestoreWitnessLabTest#b_active_enrol_and_sign
adb_device shell am force-stop dev.forgesworn.kithmoot
run_lab c-restart-clones -e class dev.forgesworn.kithmoot.storage.RestoreWitnessLabTest#c_restart_then_two_clones -e requireRestart true
from_device clones.txt | tee "$reports/clones.txt"

echo "==> the keeper retires the lab subject"
bothyd witness retire --subject "$subject" | tee "$reports/retire.txt"
echo "Lab run passed. Reports: $reports"
