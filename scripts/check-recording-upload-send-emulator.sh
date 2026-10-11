#!/usr/bin/env bash
# Explicit UI Upload -> Send against private test TLS and a loopback relay.
set -euo pipefail
umask 077
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${ANDROID_SERIAL:?Choose a disposable emulator explicitly}"
restart_mode="${KITHMOOT_RECORDING_SEND_RESTART:-0}"
[[ "$restart_mode" == 0 || "$restart_mode" == 1 ]] || { echo 'Choose restart mode 0 or 1.' >&2; exit 2; }
if [[ "$restart_mode" == 0 ]]; then
  : "${KITHMOOT_RECORDING_SAMPLE:?Choose a qualified synthetic MP4}"
  [[ -f "$KITHMOOT_RECORDING_SAMPLE" ]] || { echo 'Synthetic MP4 is missing.' >&2; exit 2; }
fi
case "$ANDROID_SERIAL" in emulator-[0-9]*) ;; *) echo 'Disposable emulator required.' >&2; exit 2 ;; esac
adb_bin="$ANDROID_HOME/platform-tools/adb"
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || exit 2
[[ "$("$adb_bin" -s "$ANDROID_SERIAL" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] || exit 2
cd "$(dirname "$0")/.."
reports="${KITHMOOT_RECORDING_REPORTS:-$PWD/app/build/reports/native-recording-upload-send}"
mkdir -p "$reports"
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$ANDROID_SERIAL" install -r -d app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/kithmoot-ui-network.XXXXXX")"
fixture_name="recording-ui-network-$(openssl rand -hex 6)"
cleanup() {
  local status="$?"
  if [[ "$status" != 0 ]]; then
    # Preserve the original failure and the selected emulator's runtime state;
    # a system ANR must not be mistaken for a recording/transfer result.
    python3 - "$adb_bin" "$ANDROID_SERIAL" "$reports" <<'PY' || true
import json, subprocess, sys
from pathlib import Path
adb, serial, folder = sys.argv[1:]
reports = Path(folder)
results = {}
for name, args in [
    ("failure-logcat.txt", ["logcat", "-d", "-t", "6000"]),
    ("failure-screen.png", ["exec-out", "screencap", "-p"]),
    ("failure-last-anr.txt", ["shell", "dumpsys", "activity", "lastanr"]),
]:
    try:
        with (reports / name).open("wb") as output, (reports / (name + ".error.txt")).open("wb") as error:
            results[name] = subprocess.run([adb, "-s", serial, *args], stdout=output,
                                          stderr=error, timeout=10).returncode
    except Exception as error:
        results[name] = type(error).__name__
(reports / "failure-diagnostics.json").write_text(json.dumps(results, indent=2) + "\n")
PY
  fi
  if [[ "$restart_mode" == 1 ]]; then
    run_restart_stage cleanup || status=1
  fi
  "$adb_bin" -s "$ANDROID_SERIAL" shell "run-as dev.forgesworn.kithmoot rm -rf no_backup/$fixture_name" >/dev/null 2>&1 || true
  rm -rf "$fixture_root"
  return "$status"
}
run_restart_stage() {
  local stage="$1" statuses=()
  if "$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e recordingNetworkFixture "$fixture_name" -e recordingSendRecoveryStage "$stage" \
    -e class dev.forgesworn.kithmoot.media.recording.RecordingSendRecoveryUiTest \
    dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/$stage.txt"; then
    statuses=("${PIPESTATUS[@]}")
  else
    statuses=("${PIPESTATUS[@]}")
  fi
  "$adb_bin" -s "$ANDROID_SERIAL" logcat -d -t 6000 > "$reports/$stage-logcat.txt" 2>&1 || true
  [[ "${statuses[*]}" == '0 0' ]] || return 1
  grep -Eq '^OK \(1 tests?\)' "$reports/$stage.txt" || return 1
  ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|AssumptionViolated|INSTRUMENTATION_STATUS_CODE: -3' "$reports/$stage.txt"
}
trap cleanup EXIT
cat > "$fixture_root/openssl.cnf" <<'CONFIG'
[req]
distinguished_name = dn
x509_extensions = v3
prompt = no
[dn]
CN = 127.0.0.1
[v3]
subjectAltName = IP:127.0.0.1
basicConstraints = critical,CA:true
keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth
CONFIG
openssl req -new -x509 -newkey rsa:2048 -nodes -days 1 -config "$fixture_root/openssl.cnf" \
  -keyout "$fixture_root/key.pem" -out "$fixture_root/cert.pem" > "$reports/tls-generation.txt" 2>&1
openssl pkcs12 -export -inkey "$fixture_root/key.pem" -in "$fixture_root/cert.pem" \
  -name recording-ui-fixture -passout pass:synthetic-test-only \
  -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 -out "$fixture_root/fixture.p12"
"$adb_bin" -s "$ANDROID_SERIAL" shell "run-as dev.forgesworn.kithmoot mkdir -p no_backup/$fixture_name"
cat "$fixture_root/fixture.p12" | "$adb_bin" -s "$ANDROID_SERIAL" shell -T "run-as dev.forgesworn.kithmoot sh -c 'cat > no_backup/$fixture_name/fixture.p12'"
if [[ "$restart_mode" == 1 ]]; then
  run_restart_stage prepare
  "$adb_bin" -s "$ANDROID_SERIAL" shell am force-stop dev.forgesworn.kithmoot
  run_restart_stage verify
  exit 0
fi
cat "$KITHMOOT_RECORDING_SAMPLE" | "$adb_bin" -s "$ANDROID_SERIAL" shell -T "run-as dev.forgesworn.kithmoot sh -c 'cat > no_backup/$fixture_name/sample.mp4'"
"$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e recordingNetworkFixture "$fixture_name" \
  -e class dev.forgesworn.kithmoot.media.recording.RecordingUploadSendUiTest \
  dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/instrumentation.txt"
rg -q '^OK \(4 tests\)' "$reports/instrumentation.txt"
! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|AssumptionViolated|INSTRUMENTATION_STATUS_CODE: -3' "$reports/instrumentation.txt"
