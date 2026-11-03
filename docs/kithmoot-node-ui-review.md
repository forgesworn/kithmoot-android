# Android KithMoot node UI review

Maintainer must review and approve UI work on Android Settings → Calls →
Your KithMoot node, the owner pairing hand-off, the room hosting choice,
guest ask/wait/admit screens, and Room details → Hosted on with removal and
close confirmations before merging or approval. ZAT pending.

Code revision: `d0b7a74e3404cdf8414e76c88f844375748ec30d`. The following documentation commit does not
change that code. Maintainer decision: pending; no approval is recorded.
The foundation PR #391 is merged. Port/lease core PR #393 is separate;
this UI candidate remains held for review.
Do not merge the UI branch on the strength of CI or this agent's checks.

## Build and evidence

The prepared debug APK is `app/build/outputs/apk/debug/app-debug.apk`,
127,267,791 bytes, SHA-256
`8e349b0b3bf2a6a6f7b08eda940e3ce05644639a38a3df81b70afe3eac4b9302`.
The test APK is `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`,
7,697,340 bytes, SHA-256
`4ac8d228c56bd1b1f39c43b3672f45ce1853e90d277b8cb41acdb314e8266b6e`.
These are development artefacts; no signed Android production release has
been replaced. Install both with `adb install -r APK` on a disposable test
profile. Camera and microphone permission are needed for the media check.

[Machine-readable qualification](evidence/kithmoot-node-ui-20261102/qualification.json)
records the source snapshot, build hashes and limitations. The exact source commit stayed unchanged through the final 15m8s matrix.
A 1,608-file source snapshot is hashed in the receipt:
684 protocol tests and 3,330 app tests passed; one optional actual-node test
was skipped because its private fixture was not configured. Debug and
release lint, debug/test APKs and the unsigned release APK build passed.
The unsigned release APK is 74,174,284 bytes, SHA-256
`2700a4a2de3c1f04b1cf3a37fb285ae84111b6ec3bf9c225885e3a94bdbe1e8b`.
All 17 hosted UI CI jobs passed on this revision (run 41572840392).

The current APK's actual-service native journey passed in 255.909s on the
no-Play AOSP API35 arm64 emulator. It exercised admission, fresh Root epoch,
two-way chat, device removal, remaining-member rekey, private custody wipe,
room purge and signed completion proof. Both devices renewed TURN; video
continued through ICE restart to 2,682 received frames; revocation stopped
native connections and tracks before closure completed. Four relay candidates
and no other candidate types were observed. This is native-to-native local
media, not forced TLS443 or a web/Android five-minute proof. Earlier-revision
results are retained separately in the receipt, not attributed to this APK.

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
one guest. The private Linux installer is implemented and the Archy candidate has a
separate held review pack; the
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
only test socket destinations and supplies a generated test CA. Native TURN also needs the two fixture
hostnames mapped to the host's assigned IPv4 in that disposable emulator;
without it the native resolver correctly refuses the reserved test names.
This mapping is deleted with the AVD and changes no production resolver. Never reuse
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
