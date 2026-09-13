#!/usr/bin/env bash
# Link the published preview certificate to the owner-held production key.
set -euo pipefail
set +x
umask 077

cd "$(dirname "${BASH_SOURCE[0]}")/.."
for name in \
  KITHMOOT_PREVIEW_KEYSTORE KITHMOOT_PREVIEW_STORE_PASSWORD KITHMOOT_PREVIEW_KEY_ALIAS KITHMOOT_PREVIEW_KEY_PASSWORD KITHMOOT_PREVIEW_CERT_SHA256 \
  KITHMOOT_KEYSTORE KITHMOOT_STORE_PASSWORD KITHMOOT_KEY_ALIAS KITHMOOT_KEY_PASSWORD KITHMOOT_CERT_SHA256 \
  KITHMOOT_LINEAGE; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing $name; see docs/android-release.md" >&2
    exit 2
  fi
done

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
build_tools="${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
apksigner="$build_tools/apksigner"
[[ -x "$apksigner" ]] || { echo "Set ANDROID_HOME or ANDROID_BUILD_TOOLS; apksigner is missing" >&2; exit 2; }
command -v keytool >/dev/null || { echo "keytool is missing; use JDK 21" >&2; exit 2; }
command -v openssl >/dev/null || { echo "openssl is missing" >&2; exit 2; }
[[ -f "$KITHMOOT_PREVIEW_KEYSTORE" ]] || { echo "KITHMOOT_PREVIEW_KEYSTORE is not a file" >&2; exit 2; }
[[ -f "$KITHMOOT_KEYSTORE" ]] || { echo "KITHMOOT_KEYSTORE is not a file" >&2; exit 2; }
[[ ! -e "$KITHMOOT_LINEAGE" ]] || { echo "Refusing to overwrite KITHMOOT_LINEAGE" >&2; exit 2; }
mkdir -p "$(dirname "$KITHMOOT_LINEAGE")"

normalise_sha() { printf '%s' "$1" | tr -d ':' | tr '[:upper:]' '[:lower:]'; }
expected_preview="$(normalise_sha "$KITHMOOT_PREVIEW_CERT_SHA256")"
expected_production="$(normalise_sha "$KITHMOOT_CERT_SHA256")"
for expected in "$expected_preview" "$expected_production"; do
  [[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "Certificate fingerprints must be SHA-256 values" >&2; exit 2; }
done

certificate_sha() {
  local keystore="$1" alias="$2" password_name="$3"
  keytool -exportcert -keystore "$keystore" -alias "$alias" -storepass:env "$password_name" 2>/dev/null |
    openssl x509 -inform DER -noout -fingerprint -sha256 |
    sed 's/^.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]'
}
actual_preview="$(certificate_sha "$KITHMOOT_PREVIEW_KEYSTORE" "$KITHMOOT_PREVIEW_KEY_ALIAS" KITHMOOT_PREVIEW_STORE_PASSWORD)"
actual_production="$(certificate_sha "$KITHMOOT_KEYSTORE" "$KITHMOOT_KEY_ALIAS" KITHMOOT_STORE_PASSWORD)"
[[ "$actual_preview" == "$expected_preview" ]] || { echo "Preview keystore certificate does not match KITHMOOT_PREVIEW_CERT_SHA256" >&2; exit 1; }
[[ "$actual_production" == "$expected_production" ]] || { echo "Production keystore certificate does not match KITHMOOT_CERT_SHA256" >&2; exit 1; }
[[ "$actual_preview" != "$actual_production" ]] || { echo "Production and preview certificates must differ" >&2; exit 1; }

temporary="$(mktemp "${KITHMOOT_LINEAGE}.tmp.XXXXXX")"
trap 'rm -f "$temporary"' EXIT
"$apksigner" rotate \
  --out "$temporary" \
  --old-signer \
  --ks "$KITHMOOT_PREVIEW_KEYSTORE" \
  --ks-key-alias "$KITHMOOT_PREVIEW_KEY_ALIAS" \
  --ks-pass env:KITHMOOT_PREVIEW_STORE_PASSWORD \
  --key-pass env:KITHMOOT_PREVIEW_KEY_PASSWORD \
  --set-installed-data true \
  --set-shared-uid false \
  --set-permission true \
  --set-rollback false \
  --set-auth false \
  --new-signer \
  --ks "$KITHMOOT_KEYSTORE" \
  --ks-key-alias "$KITHMOOT_KEY_ALIAS" \
  --ks-pass env:KITHMOOT_STORE_PASSWORD \
  --key-pass env:KITHMOOT_KEY_PASSWORD
mv "$temporary" "$KITHMOOT_LINEAGE"
trap - EXIT

if command -v sha256sum >/dev/null 2>&1; then
  lineage_sha="$(sha256sum "$KITHMOOT_LINEAGE" | awk '{print $1}')"
else
  lineage_sha="$(shasum -a 256 "$KITHMOOT_LINEAGE" | awk '{print $1}')"
fi
printf 'Created signing lineage: %s\nLineage SHA-256: %s\n' "$KITHMOOT_LINEAGE" "$lineage_sha"
