#!/usr/bin/env bash
# Build and verify a release APK with an explicitly selected signing certificate.
set -euo pipefail
set +x

cd "$(dirname "${BASH_SOURCE[0]}")/.."
for name in KITHMOOT_KEYSTORE KITHMOOT_STORE_PASSWORD KITHMOOT_KEY_ALIAS KITHMOOT_KEY_PASSWORD KITHMOOT_CERT_SHA256; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing $name; see docs/android-release.md" >&2
    exit 2
  fi
done
expected="$(printf '%s' "$KITHMOOT_CERT_SHA256" | tr -d ':' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'KITHMOOT_CERT_SHA256 must be the expected signing certificate SHA-256 fingerprint' >&2
  exit 2
fi
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
build_tools="${ANDROID_BUILD_TOOLS:-$sdk/build-tools/34.0.0}"
for tool in apksigner aapt; do
  [[ -x "$build_tools/$tool" ]] || { echo "Set ANDROID_HOME or ANDROID_BUILD_TOOLS; $tool is missing" >&2; exit 2; }
done

./gradlew :protocol:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease --no-daemon
apk=app/build/outputs/apk/release/app-release.apk
[[ -s "$apk" ]] || { echo 'Signed release APK was not produced' >&2; exit 1; }
certificate="$("$build_tools/apksigner" verify --verbose --print-certs "$apk")"
actual="$(printf '%s\n' "$certificate" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')"
[[ "$actual" == "$expected" ]] || { echo 'APK signing certificate does not match KITHMOOT_CERT_SHA256' >&2; exit 1; }
if printf '%s\n' "$certificate" | grep -qi 'certificate DN:.*CN=Android Debug'; then
  echo 'Refusing an APK signed with an Android debug certificate' >&2
  exit 1
fi
badging="$("$build_tools/aapt" dump badging "$apk")"
if printf '%s\n' "$badging" | grep -q '^application-debuggable'; then
  echo 'Refusing a debuggable APK' >&2
  exit 1
fi
printf '%s\n' "$badging" | grep -q "^package: name='dev.forgesworn.kithmoot'" || { echo 'Unexpected application ID' >&2; exit 1; }
printf 'Verified release APK: %s\nCertificate SHA-256: %s\n' "$apk" "$actual"
if command -v sha256sum >/dev/null 2>&1; then sha256sum "$apk"; else shasum -a 256 "$apk"; fi
