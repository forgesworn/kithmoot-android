#!/usr/bin/env bash
# Fetch the built-in call relay's Android library from its pinned spry-turn
# release (docs/design/2026-10-28-built-in-relay.md in kithmoot, stage A3).
set -euo pipefail

if (( $# != 1 )); then
    echo "usage: scripts/fetch-spry-relay.sh OUTPUT_ARCHIVE" >&2
    exit 2
fi

output="$1"
mkdir -p "$(dirname "$output")"
temporary="$(mktemp "${output}.XXXXXX")"
trap 'rm -f "${temporary}"' EXIT
# A failed transfer leaves the previous archive untouched. The preparation
# script pins the archive digest, the source commit and every contained file,
# so this URL is a transport rather than a trust root.
curl --fail --location --silent --show-error \
    --proto '=https' --tlsv1.2 \
    'https://github.com/forgesworn/spry-turn/releases/download/v0.4.1/spry-relay-v0.4.1-android.tar.gz' \
    --output "${temporary}"
mv "${temporary}" "$output"
trap - EXIT
