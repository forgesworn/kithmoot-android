#!/usr/bin/env bash
set -euo pipefail

# Preserve the test exit status and independently prove shutdown. The runner's
# adb kill acknowledgement alone can leave its QEMU child alive until timeout.
finish() {
  status=$?
  trap - EXIT
  if [[ "${GITHUB_ACTIONS:-}" == true && -n "${KITHMOOT_RECORDING_AVD:-}" ]]; then
    if ! python3 scripts/stop-recording-ci-emulator.py; then
      [[ "$status" -ne 0 ]] || status=1
    fi
  fi
  exit "$status"
}
trap finish EXIT

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
