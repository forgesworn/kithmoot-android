#!/usr/bin/env bash
# Explicit UI Upload -> Send against private test TLS and a loopback relay.
set -euo pipefail
umask 077
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${ANDROID_SERIAL:?Choose a disposable emulator explicitly}"
: "${KITHMOOT_RECORDING_SAMPLE:?Choose a qualified synthetic MP4}"
case "$ANDROID_SERIAL" in emulator-[0-9]*) ;; *) echo 'Disposable emulator required.' >&2; exit 2 ;; esac
[[ -f "$KITHMOOT_RECORDING_SAMPLE" ]] || { echo 'Synthetic MP4 is missing.' >&2; exit 2; }
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
  "$adb_bin" -s "$ANDROID_SERIAL" shell "run-as dev.forgesworn.kithmoot rm -rf no_backup/$fixture_name" >/dev/null 2>&1 || true
  rm -rf "$fixture_root"
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
cat "$KITHMOOT_RECORDING_SAMPLE" | "$adb_bin" -s "$ANDROID_SERIAL" shell -T "run-as dev.forgesworn.kithmoot sh -c 'cat > no_backup/$fixture_name/sample.mp4'"
"$adb_bin" -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e recordingNetworkFixture "$fixture_name" \
  -e class dev.forgesworn.kithmoot.media.recording.RecordingUploadSendUiTest \
  dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tee "$reports/instrumentation.txt"
rg -q '^OK \(2 tests\)' "$reports/instrumentation.txt"
! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|AssumptionViolated|INSTRUMENTATION_STATUS_CODE: -3' "$reports/instrumentation.txt"
