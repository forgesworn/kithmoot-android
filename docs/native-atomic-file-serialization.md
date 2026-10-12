# Serialize same-path encrypted storage transactions

The unchanged five-case platform fixture at ancestor `8d8d4885` executes on
API 35 ARM64: the raw platform and unrelated-path controls pass; same-path
read, write and reset enter while another instance has a staged transaction,
causing actual writer errors. The complete red capture is hash-bound in
mesh-kit-private `lab/hybrid-room/native-atomic-overlap-platform-red.json`,
published before this change in `6bc2cd1`. Two earlier pre-fixture startup
failures remain retained. The design precedes the reproducer and correction.

`EncryptedRoomStorage` now strongly owns one monitor per canonical vault path.
A synchronized registry holds weak values and prunes cleared entries on lookup.
Separate instances of one path share that monitor; unrelated paths have their
own. Each entire read, write and reset runs under it, including key operations,
authentication, staging/finish/failure cleanup, strict commit verification and
deletion verification. No existing assertion, fixture or deadline changes.

This is process-local exclusion. It does not provide cross-process locks or
multi-file repository transactions, and the controlled reproducer does not
attribute the earlier hosted failures. Missing-key/index refusal stays intact.

The successor branch has its own nine-job workflow trigger. Require the
unchanged five-case genuine platform green run, all existing ordinary cases and
process-death controls, complete debug/release reports and all nine exact-head
CI jobs before qualification. New upstream main `763a1c52` is still a separate
integration and requalification requirement before merge. No hardware, MQTT,
group/public messaging, firmware or lab-key operation is included.


## Current-main integration candidate, 2026-10-12

A separate integration branch combines corrective `eb26d296` with main
`db9eb36c`. Guest admission freshness checks surround the qualified openForRoom
path; receiver barrier behaviour, replacement cases, and both logo/replacement
driver profiles are retained. The five-case AtomicFile fixture and corrective
EncryptedRoomStorage source remain byte-identical. The merged guest suites
run once at their complete current counts, 10 UI and 6 entry cases. A static
full-class count audit reports no mismatch; this is not Android execution.

The combined source is still being compiled and qualified. Three local fake
recovery-driver checks hit their existing 30-second deadlines; those failures
are retained, not waived. Earlier own-head nine-job green evidence does not
qualify this combined head. Its own workflow retains all nine gates, both
upstream and native ordinary suites, process-death boundaries and pending
refusal shards. No merge or current-source timing/physical qualification is
claimed by publishing this candidate.
