# Native replacement capacity measurements

Precode is mesh-kit-private `spec/native-replacement-capacity-measurement.md`,
committed as `a23810d` before this fixture. This successor preserves the Link
route enumeration repair and the keeper rekey recovery repair from `ac393377`,
including main `2f09b118`.

The journal unit test creates ordinary signed epoch requests and durable
signature/verification/lane reservations. It installs the actual saved source
reference while the fresh authority is suspended, then selects it. RETIRED
fixtures archive their exact notice through normal offer and receiver completion.
Every starting source is closed/reopened by the strict reader and checked against
the actual receiver and saved index before capacity is measured. No fabricated
record is supplied to bypass another precondition.

The matrix measures 60 cases: Internet/Nearby/Mixed, ACTIVE/RETIRED, fresh counts
13–16, and ages 0/59/60/61/3719/3720/3721 seconds. All 47 journal tests pass in a
19.660-second fresh production/test compilation and direct JUnit run. The two
complete sources compile against 7,910 read-only dependency files, whose hashes
remain unchanged. This is local JVM evidence; full Gradle, lint, APK, signing and
process-death qualification belong to the successor's own nine hosted jobs.

39 cases accept and 21 refuse. ACTIVE costs two fresh signatures, accepting 14
and refusing 15. RETIRED costs one, accepting 15 and refusing 16, with the exact
archived notice/attempt record retained. The signing window is inclusive at 60
seconds; at 61 seconds fresh credit returns. Nearby's 13,328 bytes of grant debt
(13,748 with the archived notice) persists through 3720 seconds and expires at
3721. Internet/null-lane spending expires after 60 seconds. Expired spending does
not erase the old signed request/answer cache or create a participant receipt.

Accepted cases enter bearer/welcome factories once and retirement once only for
ACTIVE, with one source write and zero index writes or offers. Refusal leaves
source/index bytes and epoch unchanged with zero writes/offers. Factory counters
measure entry to those three factories, not auxiliary nonce/signing randomness.
Schema 5→6 preserves every cached request, answer and counter and adds only the
required generation-zero field for epoch-request caches. A second strict reader
also accepts the resulting pending/refused record without writes. Numeric rows
are emitted only after closing the readers/owner and wiping fixture buffers.

The 256-spend boundary, archive/generation boundary and independent pending/
completed 2 MiB file limits remain unmeasured. The actual event size equality
checks cover these valid small policies, not the 16 KiB threshold. Full
replacement acceptance, physical BLE/RF, MESHSPASTIC/MQTT and UK–Portugal delivery
remain separate gates. No radio, firmware, MQTT/group or public relay is operated.
