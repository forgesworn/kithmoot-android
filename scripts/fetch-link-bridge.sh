#!/usr/bin/env bash
# Fetch the immutable Link artifact. The token is read only and must be scoped
# to this one private repository's Actions artifacts.
set -euo pipefail

if (( $# != 1 )); then
    echo "usage: scripts/fetch-link-bridge.sh OUTPUT_ARCHIVE" >&2
    exit 2
fi

: "${FORGESWORN_LINK_ARTIFACT_TOKEN:?set a read-only token for forgesworn/forgesworn-link Actions artifacts}"
output="$1"
mkdir -p "$(dirname "$output")"
temporary="$(mktemp "${output}.XXXXXX")"
trap 'rm -f "${temporary}"' EXIT
# GH_TOKEN stays in the child environment rather than appearing in a command
# argument or URL. A failed transfer leaves the previous archive untouched.
GH_TOKEN="${FORGESWORN_LINK_ARTIFACT_TOKEN}" gh api \
    repos/forgesworn/forgesworn-link/actions/artifacts/10286259939/zip > "${temporary}"
mv "${temporary}" "$output"
trap - EXIT
