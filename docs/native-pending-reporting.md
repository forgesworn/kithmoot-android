# Pending cold recovery reporting

The five pending-store drivers require a recovery PID different from the killed
PID, the exact transition mode, one passing instrumented case and eleven checked
numeric rows. The previous fixture put PID/mode in the same status bundle as
`REPORT_KEY_STREAMRESULT`. Four 3bd instrumented cases passed but the pretty-mode
command emitted only the stream, leaving external process proof incomplete.

The report site now sends three bundles, after the original eleven assertions
and NonCancellable cleanup: a combined stream/probe control, metadata-only actual
PID/mode, then the original numeric stream. The driver requires exactly one
`NATIVE_PENDING_REPORT_CONTROL shape=combined` marker and no leaked probe fields.
It retains the original `am instrument -w`, killed/new-PID, mode, row, file/key,
zero-route, physical-device and cleanup checks. No production source changes.

Generated reporting tests first demonstrate five malformed controls being
accepted by the unchanged driver; the same tests reject all five after the
additive check. The pending profile's 26 driver tests pass, as do 23 APK-input
tests and the five-stage row validator (260 malformed captures rejected).
Generated output is parser evidence only. Complete driver-suite results and
actual workflow evidence are recorded in mesh-kit-private `lab/hybrid-room/`.

The new branch preserves capacity af580 and main 2f09. Its own nine-job workflow
must prove 150 ordinary checks in 46 groups, six retired-store rows, thirteen
positive and five pending SIGKILL/new-PID drivers, all 55 pending rows and these
five actual reporting controls before merge. The unexplained committed-source
receiver-key mutation, the capacity limits and physical/security gates remain
open; fixing report transport does not qualify those. No radio or MQTT settings
are changed.
