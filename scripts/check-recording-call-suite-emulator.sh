#!/usr/bin/env bash
set -euo pipefail

# android-emulator-runner executes each script line in a separate shell.
# Keep the matrix dispatch in one script so conditionals remain intact.
case "${KITHMOOT_RECORDING_SUITE:?Set KITHMOOT_RECORDING_SUITE}" in
  capture)
    bash scripts/check-recording-emulator.sh full
    ;;
  codecs)
    bash scripts/check-recording-emulator.sh codecs
    ;;
  session)
    python3 scripts/check-recording-emulator-ready.py \
      --adb "${ANDROID_HOME:?}/platform-tools/adb" \
      --serial "${ANDROID_SERIAL:?}" \
      --reports "${KITHMOOT_RECORDING_REPORTS:?}"
    bash scripts/check-recording-session-interop-emulator.sh
    ;;
  *)
    echo 'Unknown recording suite; expected capture, codecs or session.' >&2
    exit 2
    ;;
esac
