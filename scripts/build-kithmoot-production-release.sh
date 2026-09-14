#!/usr/bin/env bash
# Build and verify the production APK without placing a signing password in history.
set -euo pipefail
set +x
umask 077

usage() {
  cat >&2 <<'EOF'
Usage: bash scripts/build-kithmoot-production-release.sh PRODUCTION_KEYSTORE LINEAGE

Both paths must be absolute. This command prompts for the production keystore
password on the terminal and passes it only to the existing reviewed builder.
EOF
  exit 2
}

[[ $# -eq 2 ]] || usage
[[ -t 0 && -t 1 ]] || { echo "This build requires an interactive terminal" >&2; exit 2; }
keystore="$1"; lineage="$2"
for path in "$keystore" "$lineage"; do
  [[ "$path" == /* ]] || { echo "Both paths must be absolute" >&2; exit 2; }
  [[ -f "$path" && ! -L "$path" ]] || { echo "Input is not a regular file: $path" >&2; exit 2; }
done

printf 'Production keystore password: '
IFS= read -r -s production_password
printf '\n'
export KITHMOOT_KEYSTORE="$keystore"
export KITHMOOT_STORE_PASSWORD="$production_password"
export KITHMOOT_KEY_ALIAS=kithmoot-production
export KITHMOOT_KEY_PASSWORD="$production_password"
production_certificate_sha="$(keytool -exportcert -keystore "$keystore" -alias kithmoot-production -storepass:env KITHMOOT_STORE_PASSWORD 2>/dev/null | openssl x509 -inform DER -noout -fingerprint -sha256 | sed 's/^.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]')"
export KITHMOOT_CERT_SHA256="$production_certificate_sha"
export KITHMOOT_LINEAGE="$lineage"
lineage_sha="$(shasum -a 256 "$lineage" | cut -d' ' -f1)"
export KITHMOOT_LINEAGE_SHA256="$lineage_sha"
[[ "$KITHMOOT_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "Could not read the production certificate" >&2; exit 1; }
[[ "$KITHMOOT_LINEAGE_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "Could not hash the lineage" >&2; exit 1; }
trap 'unset production_password KITHMOOT_STORE_PASSWORD KITHMOOT_KEY_PASSWORD' EXIT
bash scripts/build-signed-release.sh
