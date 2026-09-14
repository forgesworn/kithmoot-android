#!/usr/bin/env bash
# Build an unsigned release, then apply the production key and preview lineage.
set -euo pipefail
set +x
umask 077

cd "$(dirname "${BASH_SOURCE[0]}")/.."
for name in KITHMOOT_KEYSTORE KITHMOOT_STORE_PASSWORD KITHMOOT_KEY_ALIAS KITHMOOT_KEY_PASSWORD \
  KITHMOOT_CERT_SHA256 KITHMOOT_LINEAGE KITHMOOT_LINEAGE_SHA256; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing $name; see docs/android-release.md" >&2
    exit 2
  fi
done
[[ -f "$KITHMOOT_KEYSTORE" ]] || { echo "KITHMOOT_KEYSTORE is not a file" >&2; exit 2; }
[[ -f "$KITHMOOT_LINEAGE" ]] || { echo "KITHMOOT_LINEAGE is not a file" >&2; exit 2; }

normalise_sha() { printf '%s' "$1" | tr -d ':' | tr '[:upper:]' '[:lower:]'; }
expected_cert="$(normalise_sha "$KITHMOOT_CERT_SHA256")"
expected_lineage="$(normalise_sha "$KITHMOOT_LINEAGE_SHA256")"
for expected in "$expected_cert" "$expected_lineage"; do
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "Certificate and lineage fingerprints must be SHA-256 values" >&2; exit 2; }
done
if command -v sha256sum >/dev/null 2>&1; then
  actual_lineage="$(sha256sum "$KITHMOOT_LINEAGE" | awk '{print $1}')"
else
  actual_lineage="$(shasum -a 256 "$KITHMOOT_LINEAGE" | awk '{print $1}')"
fi
[[ "$actual_lineage" == "$expected_lineage" ]] || { echo "Signing lineage does not match KITHMOOT_LINEAGE_SHA256" >&2; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
build_tools="${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
for tool in apksigner aapt; do
  [[ -x "$build_tools/$tool" ]] || { echo "Set ANDROID_HOME or ANDROID_BUILD_TOOLS; $tool is missing" >&2; exit 2; }
done

./gradlew :protocol:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon
unsigned=app/build/outputs/apk/release/app-release-unsigned.apk
[[ -s "$unsigned" ]] || { echo "Unsigned release APK was not produced" >&2; exit 1; }
output=app/build/outputs/apk/release/kithmoot-0.6.1-production.apk
rm -f "$output" "$output.idsig"
"$build_tools/apksigner" sign \
  --ks "$KITHMOOT_KEYSTORE" \
  --ks-key-alias "$KITHMOOT_KEY_ALIAS" \
  --ks-pass env:KITHMOOT_STORE_PASSWORD \
  --key-pass env:KITHMOOT_KEY_PASSWORD \
  --lineage "$KITHMOOT_LINEAGE" \
  --min-sdk-version 33 \
  --rotation-min-sdk-version 33 \
  --v1-signing-enabled false \
  --v2-signing-enabled false \
  --v3-signing-enabled true \
  --v4-signing-enabled false \
  --out "$output" \
  "$unsigned"

verification="$("$build_tools/apksigner" verify --verbose --print-certs "$output")"
actual_cert="$(printf '%s\n' "$verification" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1 | tr '[:upper:]' '[:lower:]')"
[[ "$actual_cert" == "$expected_cert" ]] || { echo "APK signing certificate does not match KITHMOOT_CERT_SHA256" >&2; exit 1; }
printf '%s\n' "$verification" | grep -Fq 'Verified using v1 scheme (JAR signing): false' || { echo "APK unexpectedly uses v1 signing" >&2; exit 1; }
printf '%s\n' "$verification" | grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): false' || { echo "APK unexpectedly uses v2 signing" >&2; exit 1; }
printf '%s\n' "$verification" | grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' || { echo "APK does not use v3 signing" >&2; exit 1; }
if printf '%s\n' "$verification" | grep -qi 'certificate DN:.*CN=Android Debug'; then
  echo "Refusing an APK signed with an Android debug certificate" >&2
  exit 1
fi

badging="$("$build_tools/aapt" dump badging "$output")"
printf '%s\n' "$badging" | grep -q "^package: name='dev.forgesworn.kithmoot'" || { echo "Unexpected application ID" >&2; exit 1; }
version_code="$(printf '%s\n' "$badging" | sed -n "s/^package:.*versionCode='\([0-9][0-9]*\)'.*/\1/p")"
[[ "$version_code" =~ ^[0-9]+$ && "$version_code" -gt 22 ]] || { echo "Production versionCode must be greater than preview version 22" >&2; exit 1; }
printf '%s\n' "$badging" | grep -q "^sdkVersion:'33'" || { echo "Production minSdk must be 33" >&2; exit 1; }
printf '%s\n' "$badging" | grep -q "^targetSdkVersion:'35'" || { echo "Production targetSdk must be 35" >&2; exit 1; }
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
  echo "Refusing a debuggable APK" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  apk_sha="$(sha256sum "$output" | awk '{print $1}')"
else
  apk_sha="$(shasum -a 256 "$output" | awk '{print $1}')"
fi
printf 'Verified production APK: %s\nCertificate SHA-256: %s\nLineage SHA-256: %s\nAPK SHA-256: %s\n' \
  "$output" "$actual_cert" "$actual_lineage" "$apk_sha"
