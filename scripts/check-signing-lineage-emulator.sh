#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

# This replaces app-private data and signing identities. Only use a disposable emulator.
case "${ANDROID_SERIAL:-}" in
  emulator-[0-9]*) ;;
  *) echo "Set ANDROID_SERIAL to a disposable emulator serial (emulator-PORT)." >&2; exit 2 ;;
esac
adb_bin="${ANDROID_HOME:?Set ANDROID_HOME}/platform-tools/adb"
adb_args=(-s "$ANDROID_SERIAL")
if [[ -n "${ANDROID_ADB_SERVER_PORT:-}" ]]; then
  adb_args=(-P "$ANDROID_ADB_SERVER_PORT" "${adb_args[@]}")
fi
adb_device() { "$adb_bin" "${adb_args[@]}" "$@"; }
[[ "$(adb_device shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || {
  echo "Refusing to replace data on a non-emulator device." >&2
  exit 2
}

cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
build_tools="${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
apksigner="$build_tools/apksigner"
[[ -x "$apksigner" ]] || { echo "apksigner is missing" >&2; exit 2; }
unsigned=app/build/outputs/apk/release/app-release-unsigned.apk
test_unsigned=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$unsigned" && -s "$test_unsigned" ]] || { echo "Build the unsigned release and debug instrumentation APK first" >&2; exit 2; }

api="$(adb_device shell getprop ro.build.version.sdk | tr -d '\r')"
reports="app/build/reports/signing-lineage-api-$api"
mkdir -p "$reports"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/kithmoot-lineage.XXXXXX")"
cleanup() {
  adb_device uninstall dev.forgesworn.kithmoot.test >/dev/null 2>&1 || true
  adb_device uninstall dev.forgesworn.kithmoot >/dev/null 2>&1 || true
  rm -rf "$scratch"
}
trap cleanup EXIT

export KITHMOOT_PREVIEW_STORE_PASSWORD="disposable-preview-password"
export KITHMOOT_PREVIEW_KEY_PASSWORD="$KITHMOOT_PREVIEW_STORE_PASSWORD"
export KITHMOOT_STORE_PASSWORD="disposable-production-password"
export KITHMOOT_KEY_PASSWORD="$KITHMOOT_STORE_PASSWORD"
export KITHMOOT_PREVIEW_KEYSTORE="$scratch/preview.jks"
export KITHMOOT_PREVIEW_KEY_ALIAS=preview
export KITHMOOT_KEYSTORE="$scratch/production.jks"
export KITHMOOT_KEY_ALIAS=production
export KITHMOOT_LINEAGE="$scratch/preview-to-production.lineage"

keytool -genkeypair -keystore "$KITHMOOT_PREVIEW_KEYSTORE" -storepass:env KITHMOOT_PREVIEW_STORE_PASSWORD \
  -keypass:env KITHMOOT_PREVIEW_KEY_PASSWORD -alias "$KITHMOOT_PREVIEW_KEY_ALIAS" -keyalg RSA -keysize 2048 \
  -validity 3650 -dname "CN=Disposable KithMoot Preview" -noprompt >/dev/null 2>&1
keytool -genkeypair -keystore "$KITHMOOT_KEYSTORE" -storepass:env KITHMOOT_STORE_PASSWORD \
  -keypass:env KITHMOOT_KEY_PASSWORD -alias "$KITHMOOT_KEY_ALIAS" -keyalg RSA -keysize 2048 \
  -validity 3650 -dname "CN=Disposable KithMoot Production" -noprompt >/dev/null 2>&1
certificate_sha() {
  local keystore="$1" alias="$2" password_name="$3"
  keytool -exportcert -keystore "$keystore" -alias "$alias" -storepass:env "$password_name" 2>/dev/null |
    openssl x509 -inform DER -noout -fingerprint -sha256 |
    sed 's/^.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]'
}
KITHMOOT_PREVIEW_CERT_SHA256="$(certificate_sha "$KITHMOOT_PREVIEW_KEYSTORE" "$KITHMOOT_PREVIEW_KEY_ALIAS" KITHMOOT_PREVIEW_STORE_PASSWORD)"
KITHMOOT_CERT_SHA256="$(certificate_sha "$KITHMOOT_KEYSTORE" "$KITHMOOT_KEY_ALIAS" KITHMOOT_STORE_PASSWORD)"
export KITHMOOT_PREVIEW_CERT_SHA256 KITHMOOT_CERT_SHA256
bash scripts/create-signing-lineage.sh > "$reports/lineage.txt"

old_apk="$scratch/old.apk"
new_apk="$scratch/new.apk"
old_test="$scratch/old-test.apk"
new_test="$scratch/new-test.apk"
cp "$unsigned" "$old_apk"
cp "$unsigned" "$new_apk"
cp "$test_unsigned" "$old_test"
cp "$test_unsigned" "$new_test"
sign_apk() {
  local apk="$1" keystore="$2" alias="$3" store_name="$4" key_name="$5"
  shift 5
  "$apksigner" sign --ks "$keystore" --ks-key-alias "$alias" --ks-pass "env:$store_name" --key-pass "env:$key_name" \
    --min-sdk-version 33 --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true \
    --v4-signing-enabled false "$@" "$apk"
}
sign_apk "$old_apk" "$KITHMOOT_PREVIEW_KEYSTORE" "$KITHMOOT_PREVIEW_KEY_ALIAS" KITHMOOT_PREVIEW_STORE_PASSWORD KITHMOOT_PREVIEW_KEY_PASSWORD
sign_apk "$old_test" "$KITHMOOT_PREVIEW_KEYSTORE" "$KITHMOOT_PREVIEW_KEY_ALIAS" KITHMOOT_PREVIEW_STORE_PASSWORD KITHMOOT_PREVIEW_KEY_PASSWORD
sign_apk "$new_apk" "$KITHMOOT_KEYSTORE" "$KITHMOOT_KEY_ALIAS" KITHMOOT_STORE_PASSWORD KITHMOOT_KEY_PASSWORD \
  --lineage "$KITHMOOT_LINEAGE" --rotation-min-sdk-version 33
sign_apk "$new_test" "$KITHMOOT_KEYSTORE" "$KITHMOOT_KEY_ALIAS" KITHMOOT_STORE_PASSWORD KITHMOOT_KEY_PASSWORD

run_test() {
  local class="$1" report="$2" count="$3"
  adb_device shell am instrument -w -e class "$class" \
    dev.forgesworn.kithmoot.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$reports/$report.txt"
  grep -Eq "^OK \\($count tests?\\)$" "$reports/$report.txt" || {
    adb_device logcat -d -t 20000 > "$reports/$report-logcat.txt"
    echo "Instrumentation did not pass: $report" >&2
    exit 1
  }
}
package_uid() {
  adb_device shell cmd package list packages -U dev.forgesworn.kithmoot | tr -d '\r' |
    sed -n 's/^package:dev\.forgesworn\.kithmoot uid://p'
}

adb_device install "$old_apk"
adb_device install "$old_test"
old_uid="$(package_uid)"
[[ -n "$old_uid" ]] || { echo "Could not read the preview UID" >&2; exit 1; }
run_test dev.forgesworn.kithmoot.storage.SigningLineageSeedTest seed 1
adb_device uninstall dev.forgesworn.kithmoot.test >/dev/null

adb_device install -r "$new_apk"
new_uid="$(package_uid)"
[[ "$new_uid" == "$old_uid" ]] || { echo "Package UID changed across signing rotation" >&2; exit 1; }
adb_device install "$new_test"
run_test dev.forgesworn.kithmoot.storage.SigningLineageVerifyTest verify 2
adb_device uninstall dev.forgesworn.kithmoot.test >/dev/null

set +e
rollback="$(adb_device install -r "$old_apk" 2>&1)"
rollback_status=$?
set -e
printf '%s\n' "$rollback" > "$reports/rollback-refusal.txt"
[[ $rollback_status -ne 0 ]] || { echo "The old signer was unexpectedly allowed to replace the rotated app" >&2; exit 1; }
printf '%s\n' "$rollback" | grep -Eq 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match' || {
  echo "Old-signer reinstall failed for an unexpected reason" >&2
  exit 1
}
printf 'Signing lineage passed on API %s; UID %s survived and old-signer rollback was refused.\n' "$api" "$old_uid" | tee "$reports/summary.txt"
