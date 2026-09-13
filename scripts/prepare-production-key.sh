#!/usr/bin/env bash
# Create the owner-held KithMoot signing identity outside every checkout.
set -euo pipefail
set +x
umask 077

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

usage() {
  cat >&2 <<'EOF'
Usage: bash scripts/prepare-production-key.sh /absolute/path/kithmoot-production.p12

This is an interactive owner ceremony. It refuses an existing file, a path
inside the checkout and non-interactive input. keytool prompts for the password;
the script never accepts it as an argument or environment variable.
EOF
  exit 2
}

[[ $# -eq 1 ]] || usage
[[ -t 0 && -t 1 ]] || {
  echo "Production key creation requires an interactive terminal" >&2
  exit 2
}
command -v keytool >/dev/null || { echo "keytool is missing; use JDK 21" >&2; exit 2; }
command -v openssl >/dev/null || { echo "openssl is missing" >&2; exit 2; }

requested="$1"
[[ "$requested" == /* ]] || { echo "The production keystore path must be absolute" >&2; exit 2; }
[[ "$requested" == *.p12 ]] || { echo "The production keystore must end in .p12" >&2; exit 2; }
parent="$(dirname "$requested")"
[[ -d "$parent" ]] || { echo "The parent directory must already exist" >&2; exit 2; }
parent="$(cd "$parent" && pwd -P)"
output="$parent/$(basename "$requested")"
case "$output" in
  "$repo_root"|"$repo_root"/*)
    echo "Refusing to create a production key inside the checkout" >&2
    exit 2
    ;;
esac
[[ ! -e "$output" ]] || { echo "Refusing to overwrite the production keystore" >&2; exit 2; }
[[ ! -L "$output" ]] || { echo "Refusing a symlink output" >&2; exit 2; }

alias_name="kithmoot-production"
cat <<EOF
Production signing identity
  output: $output
  alias:  $alias_name
  key:    RSA 4096 / SHA256withRSA
  life:   10,950 days

keytool will now ask for a new password twice. Store it in the chosen password
manager. Afterwards, make two offline byte-for-byte backups before creating the
signing lineage.
EOF
printf 'Type CREATE to continue: '
IFS= read -r confirmation
[[ "$confirmation" == "CREATE" ]] || { echo "Cancelled" >&2; exit 2; }

keytool -genkeypair \
  -keystore "$output" \
  -storetype PKCS12 \
  -alias "$alias_name" \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10950 \
  -dname "CN=KithMoot Production, OU=ForgeSworn, O=ForgeSworn, C=GB"
chmod 600 "$output"

echo "Re-enter the new keystore password so its public certificate can be recorded."
certificate_sha="$(
  keytool -exportcert -keystore "$output" -alias "$alias_name" 2>/dev/tty |
    openssl x509 -inform DER -noout -fingerprint -sha256 |
    sed 's/^.*=//' | tr -d ':' | tr '[:upper:]' '[:lower:]'
)"
[[ "$certificate_sha" =~ ^[0-9a-f]{64}$ ]] || {
  echo "The keystore exists, but its certificate fingerprint could not be read" >&2
  exit 1
}

if command -v sha256sum >/dev/null 2>&1; then
  file_sha="$(sha256sum "$output" | awk '{print $1}')"
else
  file_sha="$(shasum -a 256 "$output" | awk '{print $1}')"
fi

cat <<EOF
Created owner-held production keystore: $output
Alias: $alias_name
Certificate SHA-256: $certificate_sha
Keystore file SHA-256: $file_sha

Do not continue to the lineage until two offline backup files independently
produce the same keystore SHA-256. Record the certificate fingerprint outside
the checkout; it is the future KITHMOOT_CERT_SHA256 value.
EOF
