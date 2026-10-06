#!/usr/bin/env bash
# The P3-03b-3b-1 lab run: the keeper's own MLS device against a real Bothy
# with VMLS on, on a disposable emulator. Not CI. It starts bothy-node's
# claimed-box fixture (`g5_fixture` with `G5_VMLS=1`: claimed by the fixture
# master, events on, VMLS initialised with an in-process witness) on a WebPKI
# `wss://` Link relay, then runs VmlsBoxLabTest: an ordinary pairing, the
# keeper's VMLS grant to its MLS device over the sheltered relay,
# capabilities, a package registered and withdrawn, and a lone group's Update
# commit through the driver.
#
# Usage: ANDROID_SERIAL=emulator-PORT BOTHY_NODE=path/to/bothy-node [LAB_RELAY=wss://…] scripts/lab-vmls-box.sh
# Build the debug app and instrumentation APKs first.
set -euo pipefail

case "${ANDROID_SERIAL:-}" in
  emulator-[0-9]*) ;;
  *) echo 'Set ANDROID_SERIAL to a disposable emulator serial (emulator-PORT).' >&2; exit 2 ;;
esac
: "${BOTHY_NODE:?Set BOTHY_NODE to a bothy-node checkout whose g5_fixture has G5_VMLS}"
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
adb_device() { "$adb_bin" -s "$ANDROID_SERIAL" "$@" </dev/null; }
if [[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'Refusing to run on a non-emulator device.' >&2
  exit 2
fi
relay_url="${LAB_RELAY:-wss://link1.forgesworn.dev/link}"

cd "$(dirname "$0")/.."
reports="$PWD/app/build/reports/vmls-box-lab"
mkdir -p "$reports"
fixture_pid=""
port=""
cleanup() {
  trap - EXIT INT TERM
  if [[ -n "$port" ]]; then
    curl -fsS -X POST "http://127.0.0.1:$port/stop" >/dev/null 2>&1 || true
    adb_device reverse --remove "tcp:$port" >/dev/null 2>&1 || true
  fi
  if [[ -n "$fixture_pid" ]] && kill -0 "$fixture_pid" 2>/dev/null; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi
}
trap cleanup EXIT INT TERM

echo "==> Link relay $relay_url"
echo "==> the claimed VMLS fixture"
: > "$reports/fixture.log"
# Built first and run directly, so the pid is the fixture's own and cleanup
# stops it even when it never became ready.
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
adb_device reverse "tcp:$port" "tcp:$port"

adb_device install -r app/build/outputs/apk/debug/app-debug.apk
adb_device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb_device logcat -c
adb_device shell am instrument -w -e fixture_control "http://127.0.0.1:$port" -e persona alice \
  -e class dev.forgesworn.kithmoot.storage.VmlsBoxLabTest \
  dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$reports/lab.txt"
if ! grep -Eq '^OK \(1 test\)$' "$reports/lab.txt"; then
  adb_device logcat -d -t 20000 > "$reports/lab-logcat.txt"
  echo 'The lab did not pass.' >&2
  exit 1
fi
echo "Lab run passed. Reports: $reports"
