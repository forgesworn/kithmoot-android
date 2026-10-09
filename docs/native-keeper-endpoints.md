# Native keeper endpoint and local adoption prerequisites

The outgoing courier can use the exact selected Internet, Nearby or mixed
endpoints. Original signed rekey and retirement notices have a guarded control
path that stays available after the transport queue reset while ordinary chat
is blocked. It checks the selected owner's lifetime and current transport
generation at handoff. Stopping a relay pool invalidates the old generation.

A source transition can hold its actual RoomSession before signing and feed the
original notice through the normal authenticated receiver gate locally, without
waiting for a relay echo. A failed receiver write leaves the original source
transaction pending and ordinary chat blocked. Source completion still requires
the actual receiver vault and session to agree, plus recorded courier custody
and any required retirement handoff. Relay OK is relay acceptance; a Nearby
offer and local custody are not authenticated participant storage receipts.

The broader checks exposed an admission-expiry race in durable chat. The
transport now distinguishes a proven pre-offer rejection with
PublicationNotOfferedException. Only that result may restore the prior state.
Generic errors after dispatch preserve UNKNOWN, including across journal reopen
and later expiry. Mixed-lane handoff cannot use one lane's rejection as proof
that the other lane never offered. A temporary barrier alone does not mark a
message MOVED.

## Qualification

Direct cached Kotlin/Compose compiler and JUnit: 492 checks in 60 classes pass.
The changed production and test files are freshly compiled, including actual
RelayPool, RoomMeshTransport, HybridRoomTransport and RoomSession. Other app and
protocol classes use the existing fully qualified courier build. This is not a
fresh Gradle build, lint/APK qualification, real process kill or physical
BLE/radio acceptance. The portable runner and source-hashed lab evidence live
in mesh-kit-private/lab/hybrid-room/NATIVE-KEEPER-ENDPOINTS.md.

The first expanded run failed the existing admission-expiry retry test. After
the guard fix, that test and deterministic expiry/ambiguous-offer checks pass.

## Remaining integration

These methods remain internal prerequisites. Attach the exclusive journal-owned
foreground controller, request subscriptions, root transition issuer and UI only
with saved-room authority references and an explicit legacy migration policy.
Do not reconstruct a second signer from SavedRoom beside the journal. Then run
full final-head Gradle/hosted checks and actual encrypted journal process-death
acceptance before claiming native room hosting is complete.
