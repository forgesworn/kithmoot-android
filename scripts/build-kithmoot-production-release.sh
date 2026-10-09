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
To build without a terminal, set KITHMOOT_PASSWORD_FILE to an absolute path of
a file only you can read (mode 600) that holds the password.
EOF
  exit 2
}

[[ $# -eq 2 ]] || usage
password_file="${KITHMOOT_PASSWORD_FILE:-}"
if [[ -n "$password_file" ]]; then
  [[ "$password_file" == /* && -f "$password_file" && ! -L "$password_file" && -O "$password_file" ]] \
    || { echo "KITHMOOT_PASSWORD_FILE must be an absolute path to a regular file you own" >&2; exit 2; }
  [[ "$(stat -f %Lp "$password_file" 2>/dev/null || stat -c %a "$password_file")" == 600 ]] \
    || { echo "KITHMOOT_PASSWORD_FILE must have mode 600" >&2; exit 2; }
else
  [[ -t 0 && -t 1 ]] || { echo "This build requires an interactive terminal, or KITHMOOT_PASSWORD_FILE" >&2; exit 2; }
fi
repository="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository"
keystore="$1"; lineage="$2"
for path in "$keystore" "$lineage"; do
  [[ "$path" == /* ]] || { echo "Both paths must be absolute" >&2; exit 2; }
  [[ -f "$path" && ! -L "$path" ]] || { echo "Input is not a regular file: $path" >&2; exit 2; }
done

# This is a generated build input, never a repository source file.  The
# preparation script pins and verifies the artifact before atomically replacing
# the generated directory.
scripts/fetch-link-bridge.sh build/link-ffi-android.zip
python3 scripts/prepare-link-bridge.py build/link-ffi-android.zip

# The VMLS engine ships in the release too, so its reviewed bundle is fetched
# and verified the same way.
scripts/fetch-vmls-ffi.sh build/vmls-ffi-android.zip
python3 scripts/prepare-vmls-ffi.py build/vmls-ffi-android.zip
python3 scripts/prepare-mesh-radio.py

if [[ -n "$password_file" ]]; then
  IFS= read -r production_password < "$password_file" || [[ -n "$production_password" ]]
else
  printf 'Production keystore password: '
  IFS= read -r -s production_password
  printf '\n'
fi
# A pasted password often brings a trailing space or carriage return with it,
# which keytool then rejects.  Trim surrounding whitespace in the shell so the
# password never passes through another process.
production_password="${production_password#"${production_password%%[![:space:]]*}"}"
production_password="${production_password%"${production_password##*[![:space:]]}"}"
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
