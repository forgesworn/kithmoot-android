# Keeper recovery after an authenticated echo

The source controller used to hold a transition after sampling the live epoch.
An authenticated echo could finish adopting that same pending original while
the hold waited for the receiver mutex. The late hold then put the already
adopted room into Updating for the following epoch, and the exact-original
receiver assertion correctly failed. The independent preparation failure in
abb97ecb CI has this stack; a controlled real-controller regression reproduces
the same `RoomSession.applyKeeperRekey:565` requirement.

Recovery now passes the retained original through the ordinary receiver barrier
without the redundant hold. Pre-signing transition holds and all authenticated
owner/key/cause/exact-original checks remain. The receiver handles adoption both
with no echo and with an echo already in progress, keeping traffic blocked
until the authenticated commit succeeds. There is no new signer, original,
receipt inference or budget reset.

The regression pauses the existing receiver gate under its actual epoch mutex,
starts the real keeper recovery, then releases the echo. It first fails on
unchanged production (36/37 controller cases pass), with receiver epoch 1 but
controller Failed at the original requirement. The same regression passes after
the correction on Internet, Nearby and Mixed in-process transports. All 37
controller cases pass, including existing no-echo, write-failure and guarded
replacement cases. Each controlled echo has exactly one receiver gate entry and
the normal two durable writes (pending transition then activation), unchanged
original cause, epoch 1 and retained charged spending. Nearby and Internet each
charge one 1,110-byte original on selected lanes. Fresh chat then sends.

Initial validation observed one additional test assertion failure after recovery
already reached Ready: the new write-count expectation had incorrectly assumed
one write. The actual EpochVault.follow protocol writes pending then active.
That expectation was corrected to require both writes; the final unchanged-code
red and corrected-code green runs use byte-identical test source. Preserve the
earlier failure capture rather than calling it a passing run.

Precode is mesh-kit-private `spec/native-keeper-rekey-echo-design.md`, committed
before any implementation. The isolated branch preserves Link repair 3bd5f7bf
and current main 2f09b118, including release metadata. Complete local source and
dependency hashes and further endpoint/readiness results are recorded in the
private lab. Local JVM checks do not qualify the Android build or physical RF.
The required own-head gate remains all nine hosted jobs, 150 ordinary checks/
46 groups, six retired-store rows, thirteen positive plus five pending actual
SIGKILL/new-PID drivers and all 55 pending rows. Never cancel/restart earlier
failed runs to replace evidence. No radio or public relay is operated on.
