#!/usr/bin/env bash
# Fetch the immutable VMLS engine bundle (vmls-ffi) from its source-pinned
# public release (republished on this repository; vennel is private).
set -euo pipefail

if (( $# != 1 )); then
    echo "usage: scripts/fetch-vmls-ffi.sh OUTPUT_ARCHIVE" >&2
    exit 2
fi

output="$1"
mkdir -p "$(dirname "$output")"
temporary="$(mktemp "${output}.XXXXXX")"
trap 'rm -f "${temporary}"' EXIT
# A failed transfer leaves the previous archive untouched. The preparation
# script separately pins the archive digest, manifest source commit and every
# contained file, so this URL is a transport rather than a trust root.
curl --fail --location --silent --show-error \
    --proto '=https' --tlsv1.2 \
    'https://github.com/forgesworn/kithmoot-android/releases/download/vmls-ffi-android-3398d2e/vmls-ffi-android.zip' \
    --output "${temporary}"
mv "${temporary}" "$output"
trap - EXIT
