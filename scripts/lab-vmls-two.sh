#!/usr/bin/env bash
# P3-03b-3's run on two emulators and one `bothyd` (not CI): a keeper on one
# disposable emulator, a guest on another, bothy-node's claimed VMLS fixture
# (`g5_fixture` with `G5_VMLS=1`) between them, and the invitation over a
# real Nostr relay on this machine, reached from each emulator through
# `adb reverse`. Each step of VmlsTwoDeviceLabTest is one `am instrument`,
# a new process: create, ask and join over the relay, a message each way, a
# restart with a message in the outbox, the guest offline then catching up,
# the keeper's credential and the guest's grant renewed, a second room the
# guest joins and leaves and the keeper closes, a Remove
# that loses its epoch to the guest's Update and is offered again, the
# removed device's grant revoked, the keeper's close of the first room, and a
# third room whose guest device is taken as compromised: its grant revoked at
# once, before the Remove (M05), the keeper's sends held until the Remove.
# With THIRD_SERIAL it runs a separate three-device lab instead, on a fresh
# box: a third emulator holds the guest's person on a second device; the
# keeper removes that device alone while the guest's phone, offline across it,
# sends under the old epoch (M06, M07); then a fifth room removes the whole
# person, one removal naming both devices (M06). Separate, because the box
# takes at most 8 new commits an hour from one grant
# (`SLOT_DEPOSITS_PER_HOUR`), and the two-device rooms use the keeper's.
#
# Usage: KEEPER_SERIAL=emulator-PORT GUEST_SERIAL=emulator-PORT [THIRD_SERIAL=emulator-PORT] \
#        BOTHY_NODE=path/to/bothy-node LAB_NOSTR_PORT=PORT [LAB_RELAY=wss://…] scripts/lab-vmls-two.sh
# LAB_NOSTR_PORT is a Nostr relay already listening on 127.0.0.1 (for example
# `trott relay -p PORT` from trott-devtools). Build the debug app and
# instrumentation APKs first.
set -euo pipefail

: "${BOTHY_NODE:?Set BOTHY_NODE to a bothy-node checkout whose g5_fixture has G5_VMLS}"
: "${LAB_NOSTR_PORT:?Set LAB_NOSTR_PORT to a Nostr relay listening on 127.0.0.1}"
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
keeper="${KEEPER_SERIAL:-}"
guest="${GUEST_SERIAL:-}"
third="${THIRD_SERIAL:-}"
devices=("$keeper" "$guest")
[[ -n "$third" ]] && devices+=("$third")
for serial in "${devices[@]}"; do
  case "$serial" in
    emulator-[0-9]*) ;;
    *) echo 'Set KEEPER_SERIAL and GUEST_SERIAL to disposable emulator serials (emulator-PORT).' >&2; exit 2 ;;
  esac
  if [[ "$("$adb_bin" -s "$serial" shell getprop ro.kernel.qemu </dev/null | tr -d '\r')" != 1 ]]; then
    echo "Refusing to run on $serial: not an emulator." >&2
    exit 2
  fi
done
[[ "$keeper" != "$guest" ]] || { echo 'The keeper and the guest need two emulators.' >&2; exit 2; }
[[ -z "$third" || ( "$third" != "$keeper" && "$third" != "$guest" ) ]] || { echo 'The third device needs its own emulator.' >&2; exit 2; }
nc -z 127.0.0.1 "$LAB_NOSTR_PORT" || { echo "No relay on 127.0.0.1:$LAB_NOSTR_PORT." >&2; exit 2; }
relay_url="${LAB_RELAY:-wss://link1.forgesworn.dev/link}"
nostr="ws://127.0.0.1:$LAB_NOSTR_PORT"
package=dev.forgesworn.kithmoot

cd "$(dirname "$0")/.."
reports="$PWD/app/build/reports/vmls-two-lab"
rm -rf "$reports"
mkdir -p "$reports"
fixture_pid=""
port=""
adb_on() { local serial="$1"; shift; "$adb_bin" -s "$serial" "$@" </dev/null; }
online() { adb_on "$1" shell svc wifi enable >/dev/null 2>&1 || true; adb_on "$1" shell svc data enable >/dev/null 2>&1 || true; }
cleanup() {
  trap - EXIT INT TERM
  online "$guest"
  for serial in "${devices[@]}"; do
    [[ -n "$port" ]] && adb_on "$serial" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
    adb_on "$serial" reverse --remove "tcp:$LAB_NOSTR_PORT" >/dev/null 2>&1 || true
  done
  [[ -n "$port" ]] && curl -fsS -X POST "http://127.0.0.1:$port/stop" >/dev/null 2>&1 || true
  if [[ -n "$fixture_pid" ]] && kill -0 "$fixture_pid" 2>/dev/null; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

failed() {
  adb_on "$keeper" logcat -d -t 20000 > "$reports/keeper-logcat.txt" || true
  adb_on "$guest" logcat -d -t 20000 > "$reports/guest-logcat.txt" || true
  [[ -n "$third" ]] && { adb_on "$third" logcat -d -t 20000 > "$reports/third-logcat.txt" || true; }
  echo "The two-device lab did not pass: $1. Reports: $reports" >&2
  exit 1
}

# One step on one device, its report numbered by [count]. Values are quoted for the device's shell: links carry `#`, lists `|`.
count=0
step() {
  local serial="$1" role="$2" name="$3"; shift 3
  local extra=()
  local shown=()
  while [[ $# -gt 0 ]]; do
    extra+=(-e "$1" "'$2'")
    # The link's fragment is the invitation's secret: not printed.
    if [[ "$1" == link ]]; then shown+=(-e link '<link>'); else shown+=(-e "$1" "'$2'"); fi
    shift 2
  done
  local out
  out="$reports/$(printf "%02d" "$count")-$role-$name.txt"
  echo "==> $role: $name ${shown[*]:-}"
  adb_on "$serial" shell am instrument -w -e fixture_control "http://127.0.0.1:$port" -e persona alice \
    -e role "$role" -e step "$name" -e relay "$nostr" ${extra[@]+"${extra[@]}"} \
    -e class dev.forgesworn.kithmoot.storage.VmlsTwoDeviceLabTest \
    "$package.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' > "$out"
  grep -Eq '^OK \(1 test\)$' "$out"
}
# Two steps at once, the first started [lead] seconds ahead.
both() {
  local lead="$1"; shift
  local first=() second=()
  while [[ "$1" != -- ]]; do first+=("$1"); shift; done
  shift
  second=("$@")
  count=$((count + 1))
  step "${first[@]}" & local a=$!
  sleep "$lead"
  step "${second[@]}" & local b=$!
  local ok=0
  wait "$a" || ok=1
  wait "$b" || ok=1
  return $ok
}
# One step alone; [both] numbers its two steps alike.
one() { count=$((count + 1)); step "$@"; }
kept() { adb_on "$1" shell run-as "$package" cat "no_backup/vmls-two-$2/$3" | tr -d '\r'; }

echo "==> Link relay $relay_url, Nostr relay $nostr"
echo "==> the claimed VMLS fixture"
: > "$reports/fixture.log"
fixture_bin="$(cd "$BOTHY_NODE" && cargo test -p bothy-runtime --test g5_fixture --no-run 2>&1 \
  | sed -n 's/.*Executable tests\/g5_fixture\.rs (\(.*\))$/\1/p' | tail -1)"
[[ -n "$fixture_bin" ]] || { echo 'The fixture did not build.' >&2; exit 1; }
(cd "$BOTHY_NODE/crates/bothy-runtime" && G5_LINK_RELAY="$relay_url" G5_OWNER_PERSONA=alice G5_VMLS=1 \
  exec "$BOTHY_NODE/$fixture_bin" --ignored --nocapture) >> "$reports/fixture.log" 2>&1 &
fixture_pid=$!
for _ in $(seq 1 900); do
  port="$(grep -Eo 'G5_FIXTURE_READY http://127\.0\.0\.1:[0-9]+' "$reports/fixture.log" | head -1 | sed 's/.*://')" || true
  [[ -n "$port" ]] && break
  kill -0 "$fixture_pid" 2>/dev/null || { echo 'The fixture exited before it was ready.' >&2; exit 1; }
  sleep 1
done
[[ -n "$port" ]] || { echo 'Timed out waiting for the fixture.' >&2; exit 1; }
curl -fsS "http://127.0.0.1:$port/ready" | tee "$reports/ready.json" | grep -q '"vmls":true'
echo

for serial in "${devices[@]}"; do
  online "$serial"
  adb_on "$serial" reverse "tcp:$port" "tcp:$port"
  adb_on "$serial" reverse "tcp:$LAB_NOSTR_PORT" "tcp:$LAB_NOSTR_PORT"
  adb_on "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
  adb_on "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  # Every run starts from empty app data: the Link vault holds 32 routes, and each run pairs afresh.
  adb_on "$serial" shell pm clear "$package" >/dev/null
  adb_on "$serial" logcat -c
done

if [[ -z "$third" ]]; then
# The room, its link over the relay, and the guest let in.
one "$keeper" keeper create room 1 || failed 'create'
link="$(kept "$keeper" keeper link1)"
both 20 "$keeper" keeper admit room 1 -- "$guest" guest join room 1 link "$link" || failed 'ask and join'

# A message each way.
both 2 "$keeper" keeper talk room 1 say hello-from-the-keeper expect hello-from-the-guest \
  -- "$guest" guest talk room 1 say hello-from-the-guest expect hello-from-the-keeper || failed 'a message each way'

# A restart with a message in the outbox: delivered once.
one "$keeper" keeper queue room 1 say sent-before-a-restart || failed 'queue'
adb_on "$keeper" shell am force-stop "$package"
both 2 "$keeper" keeper talk room 1 rounds 4 -- "$guest" guest talk room 1 expect sent-before-a-restart || failed 'restart'

# The guest offline while the keeper talks, then catching up.
away='while-you-were-away-1|while-you-were-away-2|while-you-were-away-3'
one "$keeper" keeper talk room 1 say "$away" || failed 'talk to an absent guest'
adb_on "$guest" shell svc wifi disable
adb_on "$guest" shell svc data disable
sleep 3
if adb_on "$guest" shell ping -c 1 -W 3 1.1.1.1 >/dev/null 2>&1; then failed 'the guest still reaches the internet'; fi
one "$guest" guest offline room 1 || failed 'offline'
online "$guest"
for _ in $(seq 1 30); do adb_on "$guest" shell ping -c 1 -W 3 1.1.1.1 >/dev/null 2>&1 && break; sleep 2; done
one "$guest" guest talk room 1 expect "$away" || failed 'catching up'

# Renewal (P3-03b-3d): the keeper's credential under the same key and the guest's grant, then a message each way.
one "$keeper" keeper renew room 1 || failed 'renew'
both 2 "$keeper" keeper talk room 1 say after-the-renewal expect from-the-guest-after \
  -- "$guest" guest talk room 1 say from-the-guest-after expect after-the-renewal || failed 'talk after the renewal'

# A second room: the guest joins and leaves; the keeper closes it, keeping the guest's grant for the first room.
one "$keeper" keeper create room 2 || failed 'create the second room'
link="$(kept "$keeper" keeper link2)"
both 20 "$keeper" keeper admit room 2 -- "$guest" guest join room 2 link "$link" || failed 'join the second room'
one "$guest" guest leave room 2 || failed 'leave'
one "$keeper" keeper close room 2 grant active || failed 'close the second room'

# Concurrent commits: the keeper's Remove made for an epoch the guest's Update takes first.
one "$keeper" keeper prepare-remove room 1 || failed 'prepare the Remove'
one "$guest" guest update room 1 || failed 'the Update'
one "$keeper" keeper settle room 1 || failed 'the Remove offered again'
one "$guest" guest removed room 1 || failed 'removed'
# The removed device's grant, revoked once the guest has seen its removal (D1 R2), before any close.
one "$keeper" keeper revoke room 1 || failed 'revoke the removed device'

# The keeper closes the first room: the guest's grant, in no other room now, is revoked.
one "$keeper" keeper close room 1 grant revoked || failed 'close the first room'

# P3-05b part 3: a third room, whose guest device the keeper takes as compromised. Its grant is revoked at once,
# before the Remove (M05), the keeper's sends are held, the device's later message is not taken, and the Remove follows.
one "$keeper" keeper create room 3 || failed 'create the third room'
link="$(kept "$keeper" keeper link3)"
both 20 "$keeper" keeper admit room 3 -- "$guest" guest join room 3 link "$link" || failed 'join the third room'
both 2 "$keeper" keeper talk room 3 say before-the-compromise expect from-the-guest-third \
  -- "$guest" guest talk room 3 say from-the-guest-third expect before-the-compromise || failed 'talk in the third room'
one "$keeper" keeper compromise room 3 || failed 'the compromised device revoked'
one "$guest" guest cut-off room 3 || failed 'the compromised device cut off'
one "$keeper" keeper contain room 3 || failed 'the Remove after the revocation'
one "$keeper" keeper close room 3 grant revoked || failed 'close the third room'
fi

if [[ -n "$third" ]]; then
  # M06 and M07: the person's phone and tablet both let in; the tablet removed alone while the phone is offline.
  one "$keeper" keeper create room 4 || failed 'create the fourth room'
  link="$(kept "$keeper" keeper link4)"
  both 20 "$keeper" keeper admit room 4 keep true -- "$guest" guest join room 4 link "$link" || failed 'the phone joins the fourth room'
  # The guest's person on the third emulator: its persona key copied across, never printed.
  "$adb_bin" -s "$guest" exec-out run-as "$package" cat no_backup/vmls-two-guest/persona \
    | "$adb_bin" -s "$third" shell "run-as $package sh -c 'mkdir -p no_backup/vmls-two-third && cat > no_backup/vmls-two-third/persona'"
  both 20 "$keeper" keeper admit room 4 device tablet-device -- "$third" third join room 4 link "$link" || failed 'the tablet joins the fourth room'
  # The phone first learns the tablet's Add: a message from the epoch before it, the tablet could never read.
  one "$guest" guest talk room 4 rounds 4 || failed 'the phone catches up'
  both 2 "$guest" guest talk room 4 say from-the-phone -- "$third" third talk room 4 expect from-the-phone || failed 'the phone to the tablet'
  one "$keeper" keeper talk room 4 expect from-the-phone || failed 'the phone to the keeper'
  one "$keeper" keeper remove-device room 4 || failed 'the tablet removed alone'
  one "$guest" guest stale room 4 || failed 'the phone sends under the old epoch'
  one "$keeper" keeper heard-stale room 4 || failed 'the keeper reads both epochs'
  one "$third" third removed-reader room 4 || failed 'the removed tablet'
  one "$keeper" keeper close room 4 grant revoked devices guest-device,tablet-device || failed 'close the fourth room'

  # M06: the whole person removed, one removal naming both devices.
  one "$keeper" keeper create room 5 || failed 'create the fifth room'
  link="$(kept "$keeper" keeper link5)"
  both 20 "$keeper" keeper admit room 5 keep true -- "$guest" guest join room 5 link "$link" || failed 'the phone joins the fifth room'
  both 20 "$keeper" keeper admit room 5 device tablet-device -- "$third" third join room 5 link "$link" || failed 'the tablet joins the fifth room'
  one "$keeper" keeper remove-person room 5 || failed 'the person removed'
  one "$guest" guest removed room 5 || failed 'the phone removed'
  one "$third" third removed room 5 || failed 'the tablet removed'
  one "$keeper" keeper close room 5 grant revoked devices guest-device,tablet-device || failed 'close the fifth room'
  adb_on "$third" logcat -d -s VmlsTwoDevice:I > "$reports/third-steps.txt" || true
fi

adb_on "$keeper" logcat -d -s VmlsTwoDevice:I > "$reports/keeper-steps.txt" || true
adb_on "$guest" logcat -d -s VmlsTwoDevice:I > "$reports/guest-steps.txt" || true
if [[ -n "$third" ]]; then echo "Three-device lab passed. Reports: $reports"; else echo "Two-device lab passed. Reports: $reports"; fi
