#!/usr/bin/env bash
# Fetch the immutable Link artifact from its source-pinned public release.
set -euo pipefail

if (( $# != 1 )); then
    echo "usage: scripts/fetch-link-bridge.sh OUTPUT_ARCHIVE" >&2
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
    'https://github.com/forgesworn/forgesworn-link/releases/download/android-ffi-f127d18/link-ffi-android.zip' \
    --output "${temporary}"
mv "${temporary}" "$output"
trap - EXIT
