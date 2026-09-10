# Shared agent work on Android

The native implementation uses KithMoot’s signed assignment contract, rather
than a separate Android task model. The first protocol implementation lives in
`session/Assignments.kt`; `Chat.kt` now supports encrypted named channels and
checks each assignment’s inner participant signature against its room and
outer device credential.

`assignment-vectors.json` is generated from the TypeScript reference by
`scripts/generate-assignment-vectors.mjs`. Its recorded source digest identifies
the exact assignment implementation. Synthetic signed histories cover 29
projections, including question/answer, exact-result acceptance, cancellation,
stopped handoff, rejection, missing history, conflicting claims and forged
operations. Four payload, four catalogue, seven encrypted envelope and four
channel-derivation cases exercise the wire boundaries. Native signing is also
checked against a signer that changes the requested work.

The focused JVM run passed 23 tests across assignment, chat and message-vector
suites. This is protocol acceptance, not a working native work screen or a
physical-device release. Durable history and an exact pending-send journal,
confirmed relay delivery, catalogue discovery, decision controls and lifecycle
integration remain necessary. The existing app stops when a room moves to an
unsupported epoch; this change does not enable work across that boundary.
