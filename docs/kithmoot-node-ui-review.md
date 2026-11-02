# Android KithMoot node UI review

Maintainer must review and approve UI work on Android Settings → Calls →
Your KithMoot node, the owner pairing hand-off, the room hosting choice,
guest ask/wait/admit screens, and Room details → Hosted on with removal and
close confirmations before merging or approval. ZAT pending.

Code revision: `128e1be2bcfc7677626a08dcb061a94bb4146f09`. The following documentation commit does not
change that code. Maintainer decision: pending; no approval is recorded.
This is a draft candidate stacked on the separate node foundation PR #391.
Do not merge the UI branch on the strength of CI or this agent's checks.

## Build and evidence

The prepared debug APK is `app/build/outputs/apk/debug/app-debug.apk`,
127,267,791 bytes, SHA-256
`e46c9896f87201b282fb48fca6f93aff657ea2734849014d54f33ba6edaa266e`.
The test APK is `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
7,697,349 bytes, SHA-256
`8c5eb058661c11f984ba5e5fa8ac25e3e50777f94ae8a0728190fcff4253cae3`.
These are development artefacts; no signed Android production release has
been replaced. Install both with `adb install -r APK` on a disposable test
profile. Camera and microphone permission are needed for the media check.

[Machine-readable qualification](evidence/kithmoot-node-ui-20261102/qualification.json)
records the source snapshot, build hashes and limitations. The 1,613-file
source snapshot was unchanged throughout the final 10m47s matrix:
682 protocol tests and 3,325 app tests passed; one optional actual-node test
was skipped because its private fixture was not configured. Debug and
release lint, and both APK builds passed.

The actual-service Kotlin test separately passed admission, fresh Root epoch,
two-way chat, device removal, remaining-member rekey, private custody wipe,
room purge and signed completion proof. The native AOSP API 35 arm64 run,
without Google Play services, passed in 16.556s. Its extended run passed in
256.585s: both devices renewed TURN, video continued through ICE restart to
2,464 received frames, and revocation stopped the native connections and
tracks before closure completed. Four relay candidates and no other
candidate types were observed.

The five screenshots below render public synthetic component states, not a
complete activity journey against a public node. They were inspected after
the capture tests passed; the tests check rendered pixels and cancellation
and confirmation behaviour. They do not establish maintainer acceptance.

- [Node settings](evidence/kithmoot-node-ui-20261102/android-node-settings.png)
- [Local unpair confirmation](evidence/kithmoot-node-ui-20261102/android-node-unpair-confirmation.png)
- [Hosted room and admission](evidence/kithmoot-node-ui-20261102/android-node-hosted-room.png)
- [Removal confirmation](evidence/kithmoot-node-ui-20261102/android-node-removal-confirmation.png)
- [Closure confirmation](evidence/kithmoot-node-ui-20261102/android-node-close-confirmation.png)

## Functional review setup and expected results

Use a private candidate node with the supervisor, Spry and Shelter room
profile, valid certificates for the node and TURN hostname, and a fresh
local pairing link. Use two disposable app profiles: one keeper account and
one guest. The installer and Archy package are still being prepared; the
full public-node journey below remains an owner/maintainer review step.

1. Open the local owner pairing link. The app names the server and asks for
   confirmation before contacting it. Cancel sends nothing. Sign in and
   approve: Settings → Calls → Your KithMoot node shows the paired host,
   availability, version, room/device counts and storage use. Changing
   accounts must not reveal the previous account's private custody.
2. Start a room. With a paired node, choose hosting on that node. An ordinary
   room remains an explicit alternative. Node creation must finish durable
   owner enrolment and both grant acknowledgements before opening the room.
   An interrupted creation resumes its saved authority rather than creating
   a second room.
3. Open the version-6 room link in the guest profile. It offers Ask to join;
   the guest types no server, token or relay setting. Opening the link alone
   grants no room access. Tap Ask and observe the wait state. Cancelling
   stops the ask connection. The keeper sees an addressed request. Decline
   sends no admission answer; Admit grants access only after both backends
   acknowledge the keeper-signed grant.
4. Send chat both ways and start a camera call. Room details → Hosted on
   names the sole node and explains relay-only calls, files off and keys on
   devices. File and external storage paths must be unavailable. Loss of
   node access blocks traffic and calls. A temporary outage retains private
   recovery state and does not silently switch to a public relay or issuer.
5. Remove the guest. Cancel the confirmation first and check that nothing
   changes. Confirm removal: all that participant's devices lose access,
   native media stops, and the remaining members receive a new epoch. A
   previously removed participant cannot regain access through automatic
   known-participant admission.
6. Close the room. Cancel first. Confirm: the same saved operation survives
   interruption, the node revokes/purges, and local private room custody is
   wiped. The public operation reference remains in node settings. Check
   it until the node's signed completion receipt is shown. A lost reply
   retries the same operation rather than reporting premature completion.
7. Unpair locally. Cancel first, then confirm. Only this device's saved
   pairing is removed; it neither transfers node ownership nor silently
   revokes existing hosted rooms. Reconnect from a fresh local node link
   using the existing owner account.

For component layout and confirmation checks, run instrumentation class
`dev.forgesworn.kithmoot.node.NodeScreensTest`. For the opt-in actual-service
native check, run `dev.forgesworn.kithmoot.node.NodeNativeMediaTest` with
`nodeConfig` naming an owner-readable fixture file on a disposable emulator.
Use `nodeRenewal=true` for the real-time renewal run. The private fixture
keeps the canonical HTTPS audience and TLS hostname verification; it changes
only test socket destinations and supplies a generated test CA. Never reuse
its one-use pairing code or install its CA on a personal device.

## Remaining acceptance

The local native run does not prove public TURN TLS443, independent NATs,
web/Android cross-client five-minute media, four-URL allocation capacity or
physical GrapheneOS operation. Those network and hardware checks remain
separate from UI ZAT. No private Shelter source or binary was published.
The task-owned emulator and AVD were stopped and deleted, and its fixture
and private pairing file were removed. No task test server is left running.

Record the maintainer's decision and reviewed revision here. Refresh this
pack and obtain approval again after material UI changes.
