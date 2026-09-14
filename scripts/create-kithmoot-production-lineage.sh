#!/usr/bin/env bash
# One interactive, local-only ceremony for the KithMoot preview-to-production lineage.
set -euo pipefail
set +x
umask 077

usage() {
  cat >&2 <<'EOF'
Usage: bash scripts/create-kithmoot-production-lineage.sh PREVIEW_KEYSTORE PRODUCTION_KEYSTORE LINEAGE

All three paths must be absolute. This command prompts on the terminal for the
two keystore passwords. It never writes a password, places one on the command
line, or copies either key.
EOF
  exit 2
}

[[ $# -eq 3 ]] || usage
[[ -t 0 && -t 1 ]] || { echo "This ceremony requires an interactive terminal" >&2; exit 2; }
preview="$1"; production="$2"; lineage="$3"
for path in "$preview" "$production" "$lineage"; do
  [[ "$path" == /* ]] || { echo "All paths must be absolute" >&2; exit 2; }
done
[[ -f "$preview" && ! -L "$preview" ]] || { echo "Preview keystore is not a regular file" >&2; exit 2; }
[[ -f "$production" && ! -L "$production" ]] || { echo "Production keystore is not a regular file" >&2; exit 2; }
[[ ! -e "$lineage" ]] || { echo "Refusing to overwrite the lineage" >&2; exit 2; }

printf 'Preview keystore password: '
IFS= read -r -s preview_password
printf '\nProduction keystore password: '
IFS= read -r -s production_password
printf '\n'

export KITHMOOT_PREVIEW_KEYSTORE="$preview"
export KITHMOOT_PREVIEW_STORE_PASSWORD="$preview_password"
export KITHMOOT_PREVIEW_KEY_ALIAS=androiddebugkey
export KITHMOOT_PREVIEW_KEY_PASSWORD="$preview_password"
export KITHMOOT_PREVIEW_CERT_SHA256=5a04a77faf9d747728b8d74175225aa1920add75371a9d566d801fe55e390874
export KITHMOOT_KEYSTORE="$production"
export KITHMOOT_STORE_PASSWORD="$production_password"
export KITHMOOT_KEY_ALIAS=kithmoot-production
export KITHMOOT_KEY_PASSWORD="$production_password"
production_certificate_sha="$(keytool -exportcert -keystore "$production" -alias kithmoot-production -storepass:env KITHMOOT_STORE_PASSWORD 2>/dev/null | openssl x509 -inform DER -noout -fingerprint -sha256 | sed 's/^.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]')"
export KITHMOOT_CERT_SHA256="$production_certificate_sha"
export KITHMOOT_LINEAGE="$lineage"

[[ "$KITHMOOT_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "Could not read the production certificate" >&2; exit 1; }
trap 'unset preview_password production_password KITHMOOT_PREVIEW_STORE_PASSWORD KITHMOOT_PREVIEW_KEY_PASSWORD KITHMOOT_STORE_PASSWORD KITHMOOT_KEY_PASSWORD' EXIT
bash scripts/create-signing-lineage.sh
