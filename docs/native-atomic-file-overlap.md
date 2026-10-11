# Actual platform overlap fixture before a locking change

2026-10-11. This additive Android fixture follows mesh-kit-private precode
`1f7d568d569bc2e1725e8a7e8a6104d042a63703`,
`spec/native-atomic-file-overlap-design.md`.

`AtomicFileOverlapTest` uses genuine platform AtomicFile and Android Keystore.
One raw control stages a replacement, lets a separate platform reader inspect
the previous committed value, then checks whether that reader removes staging
and prevents the replacement commit. This is a controlled platform mechanism,
not attribution of an earlier pending-shard failure.

Three regression cases gate a genuine encrypted write after sync and before
platform finish, then start a separate same-path reader, writer or resetter.
They require the competitor to be observed BLOCKED at the storage method while
the writer owns the transaction; early platform entry or completion fails.
All gates are released and worker threads joined even on assertion failure.
The cases retain strict commit verification and real alias/file checks. A fifth
case requires an unrelated store to complete read and write before the gated
writer is released. No sleeps, probabilistic stress, retries, fake AtomicFile,
injected crypto or production callback is used.

The existing production implementation is byte-identical to parent `f139ea59`.
No locking fix has been implemented. Same-path regressions are expected to fail
until a genuine platform run establishes the mechanism and the corrective
implementation is separately measured. Local compilation cannot prove those
platform outcomes. Keep actual red captures before a production change; do not
turn expected failure into a passing qualification or waive these assertions.

All old 166 Android cases and 50 groups remain; the runner adds five cases in
one new group (171/51). Every pending-shard/process-death fixture, diagnostic
bound, original debug/release observer method and workflow job is preserved.
Any later corrective head requires its own full CI, actual reports, all original
process deaths and five complete pending diagnostics before merge.

No firmware, radio, MQTT/group, public relay, registration, purchase or contact
operation is included. Never flash M4 or open its T-Display. Remaining capacity,
security, sync, airtime and physical group requirements are unchanged.
