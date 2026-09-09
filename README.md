# kithmoot-android

A native Kotlin implementation of the KithMoot protocol: an open workspace
with agents over Nostr, encrypted end to end, where one person can be present
on several devices at once and still appear to the room as a single
participant, and where calls are a feature of the room rather than the
product.

The TypeScript reference client is at
[`forgesworn/kithmoot`](https://github.com/forgesworn/kithmoot), live at
[kithmoot.forgesworn.dev](https://kithmoot.forgesworn.dev/), which is also
where the APK is published.

This repository is the **second, independent implementation**. The first is the
TypeScript reference client. That is the entire reason this exists: a protocol
implemented once, by one person, in one language, is a product. Implemented
twice, from a published wire contract, it is infrastructure. This repository is
the proof, and it is only worth something to the extent that it was written
against the published vectors rather than against the other implementation's
source.

## Status

**It joins rooms.** The protocol layer is checked against the published interop
vectors; on top of it sit a relay pool, the room state machine, a WebRTC mesh
and an Android interface. Two emulators have been in the same room as one
person on two devices, over a relay, with chat, microphone handover and screen
capture working. The same build has since been run on physical hardware, a
Pixel 10 Pro XL, where the screen share, the camera and the foreground-service
lifecycle all behave as they do on the emulator. Both sets of captures are in
`docs/screenshots/`.

What is *not* here:

- No room descriptor, agent ownership, attachments or approvals. Those
  vectors are carried in the published set and counted by the coverage
  guard, but nothing on this side implements them yet.
- **The message layer is read, not yet written.** Replies and threads,
  edits, retractions, mentions on the wire, direct-message invitations and
  read positions are decoded and resolved exactly as the reference does,
  checked against every `chatThread`, `chatEdit`, `chatRetract`,
  `chatMention`, `chatInvite` and `readPosition` vector, and the chat pane
  shows edits, retractions and threads. The composer still sends plain
  messages: no reply, edit or retract control, no DM started from here, and
  read positions are not published. `members` on a room policy is enforced.
- **Cannot follow a room epoch, but says so.** When somebody is removed the
  room moves to a key this client was not given, and everything would
  otherwise simply stop - no roster, no chat, no error, which reads as an
  application that is broken rather than a room that has moved on. It now
  watches for the authority's rekey, needs no key to do it, and tells the
  person what happened and to ask for a fresh link. Following the epoch
  properly is still to do; the rest of the `roomEpoch` vectors are counted
  and not run.
- No peer assist. An assist offer on somebody's roster entry is read and
  dropped, which `RosterEventVectorsTest` declares rather than hides.
- No forwarder support and no end-to-end encrypted media.
- No TURN server configured. The only ICE server is a public STUN, so two
  devices behind symmetric NATs will not find each other.
- Accessibility acceptance with TalkBack and physical-device testing of the
  new saved-room flows remain to do. Emulator tests cover the entry, recovery
  and destructive-confirmation controls.

### Saved rooms and identities

New rooms default to **Group: come back any time**, matching the web app. A v3
group link can admit someone while the creator and every member are offline.
The app fetches a signed, encrypted invitation from relay storage and checks
for retirement before entering. Creation and link replacement wait for a relay
to acknowledge storage before exposing the new link. Uncheck the group option
for a temporary meeting using the existing v2 live handshake.

Group membership is saved without a twelve-hour limit. Members receive no
inviter signing key or admission delegation. Creator keys remain in the encrypted
local vault, and returning from an old temporary link cannot overwrite saved
group authority. Device pairing retains its own credential expiry.

Stored admission depends on relay availability and retention. A group link and
its encrypted envelope provide durable access to epoch 0, including retained
history. Replacing the link asks cooperative clients to refuse new admission;
it cannot revoke copies of the key. Managed member removal, later-epoch recovery
and mobile push are separate features. See the published
[persistent group contract](https://github.com/forgesworn/kithmoot/blob/171de0a0e697add5d7ca0793b6f3980f4242b50c/docs/persistent-groups.md).

The home screen lists rooms saved on this device, with local names, search,
rename and a confirmed Forget action. Reopening preserves the participant and
device keys. Creators can return alone, and temporary meetings can serve their
saved invitation again; recovery does not depend on another member being online.
Audio, camera and screen sharing remain off until requested.

Room secrets, identity keys and invitation-host capabilities are encrypted
together using AES-256-GCM and an Android Keystore wrapping key, written
atomically under `noBackupFilesDir`. App backups are disabled. The signing keys
are decrypted into app memory while used; only the wrapping key is
non-exportable through Android Keystore. This is local recovery, not an export
or cross-device backup. Clearing app data or uninstalling loses the saved access.

Main-device credentials renew with the same keys. Paired devices keep only
their device key and the original bounded credential, never the participant
private key; an expired pairing needs a new pairing link. Expired admission
delegations are not renewed by recovery. Known retired invitations stay
retired, and known room-key changes block reopening with the old secret.
Invitation rotation saves the replacement and a signed retirement together
before publishing; pending retirement events are replayed on return.

Unreadable or corrupted data blocks room entry and stays intact until an
explicit deletion. Forget removes local access and identity for that room; it
does not delete other members or relay messages. Room names are local labels,
not shared room descriptors.

## What it implements

| Piece | What it does |
|---|---|
| Agents, and who may hear you | A member that says it is an agent is marked as one, and a control in the room decides whether this device's camera and microphone are sent to it at all - refused means the tracks are never handed to that connection |
| Room derivation | HKDF-SHA256 from the 32-byte room secret to a public `roomId` and a secret `roomKey`, under two separate info strings |
| Join URL | V3 group links carry a bearer and pinned inviter in the URL **fragment**, with the traffic secret in a signed, encrypted kind-1463 relay event. V2 temporary meetings keep the live handshake and bounded delegation. Legacy v1 links remain readable |
| Device credentials | Kind 20460, signed by the participant key, naming one device, one room, and a NIP-40 `expiration` |
| Roster events | Kind 20461, NIP-44 encrypted to the room key, with the device credential verified on the way in |
| Signal wrapping | Kind 21059 ephemeral gift wrap carrying SDP and ICE, NIP-44 encrypted to the recipient under a throwaway key |
| Durable chat | Kind 1460, matching the TypeScript wire format and fixed interop event; room-key encrypted, credential/proof checked, 2,000-character and 30-per-minute sender bounds, 30-day query horizon and 500-message in-memory cap |
| The message layer | Replies and threads, edits, retractions, mentions, DM invitations and read positions read and resolved as the reference does (`session/Messages.kt`, `Dm.kt`, `ReadPosition.kt`); a two-member `members` policy enforced at the gate |
| Kindred access | The `kin > kith > ken > open` tier ladder, proof issuing and verification, and the room gate |
| TURN credentials | coturn's REST convention: `<expiry>:<name>` with an HMAC-SHA1 password |
| Dead-drop keys | `nostr-deaddrop`'s derivation, written from its README: a pair's ikm as forgesworn-link's rendezvous material, a room's under its own case byte, one key per epoch, sender and counter (`protocol/DeadDrop.kt`). The module derives; the app does not ride quiet rooms yet |
| Contact cards | The contact card reader, written from the draft: steps 1 to 5 in order, the Link address card inside verified by a strict, cofactorless Ed25519 written out over BigInteger, refresh under the pinned node id (`protocol/ContactCard.kt`, `LinkCard.kt`, `crypto/Ed25519Strict.kt`). The module reads; the app has no card screen yet |

Two behaviours in there are load-bearing and easy to get quietly wrong:

- **`ken` never satisfies a `kith` gate.** Ken is one-way recognition: you
  pinned somebody's key; they never vouched for you. Kith is a mutual, verified
  bond. Treating them as interchangeable would silently open a gated room.
- **Roster and signal decoding return `null`, never throw.** Both run inside a
  relay subscription callback, where a single malformed event from a single
  hostile publisher would otherwise unwind the subscription and take the whole
  room down with it.

## Running the interop vectors

```sh
./gradlew :protocol:test
```

Requires a JDK 21 and a network connection on first run, to fetch dependencies.

`protocol/src/test/resources/kithmoot-vectors.json` is a verbatim copy of the
published vectors, never an edited one. There are **190 vectors across 26
groups**. The suite runs each vector in the groups this implementation covers
as its own named test case, so a failure names the vector, and adds three
guards that fail the build if a vector goes missing or a group loses its
negative cases. The six message-layer groups are run from the `:app` module,
where the chat codec lives, by `MessageLayerVectorsTest`
(`./gradlew :app:testDebugUnitTest`), reading the same file.

The groups this client does not implement - `channelDerivation`,
`roomEpoch` beyond the peek, `agentOwnership`, `chatAttachment`,
`approvalControl`, `roomDescriptor` and `verificationWords` - are counted by
`VectorCoverageTest` without being run, so the day one of them lands the
guard already knows how many cases it owes.

`deaddrop-vectors.json` and `contact-card-vectors.json` are verbatim copies
of `nostr-deaddrop`'s and `nostr-contact-card`'s known-answer files: nine
derivations, twenty-seven cards with the step each fails at, six refresh
cases including a small-order node id and a nonce point carrying torsion.
`DeadDropVectorsTest` and `ContactCardVectorsTest` run every one;
`CardFuzzTest` mutates the passing card and address card fifteen hundred
ways each and expects a verdict, never an exception. Where this module and
those files disagree, the disagreement is the finding.

`persistent-group-web.json` is a separate synthetic fixture produced by the
TypeScript implementation at `171de0a`. Native tests decode its welcome and
retirement, then reproduce its encrypted content and event id using the same
nonce. The original 95-vector file remains unchanged.

One guard is worth its own paragraph, because it caught something. A roster
vector used to be checked by parsing the expected entry through the same
model as the decoded one, which meant any field this client did not model was
dropped from BOTH sides and the vector passed without the behaviour existing.
That is what happened to display names for months: the `display-name` and
`display-name-hostile` vectors were green while nothing here read a name at
all. The test now re-encodes what it decoded and compares the JSON, and holds
a declared list of the fields this client knowingly drops - one, `assist` -
so the list can only shrink by somebody doing the work, never grow by
somebody not noticing.

The negative vectors are the ones that matter. An implementation that accepts
every well-formed structure passes all the positive vectors; only the negatives
catch an implementation that accepts *everything*, including a credential for
another room, a roster entry signed by a device it does not name, a gift wrap
opened by the wrong person, or a `ken` proof at a `kith` door.

## Layout

```
protocol/src/main/kotlin/dev/forgesworn/kithmoot/
├── crypto/     Hex, digests, BIP-340 signing, NIP-44 v2
└── protocol/   Events, rooms, credentials, roster, signalling, access, TURN

app/src/main/kotlin/dev/forgesworn/kithmoot/
├── relay/      Relay pool, sockets, filters, de-duplication
├── session/    Room session, presence, roles, chat, identity, pairing links
├── media/      WebRTC engine, negotiation, local capture
├── service/    The foreground service a screen share runs under
├── storage/    Encrypted saved rooms, identities and invitation capabilities
└── ui/         Compose: theme, start screen, room, tiles, chat, controls
```

`:protocol` has no Android dependencies by design. It runs on a plain JVM, which
keeps the vector suite fast and leaves the protocol reusable outside the app.

### Running it

Use JDK 21 and an Android SDK with platform 35 and build tools 34.0.0. Set
`ANDROID_HOME` to the SDK directory, or put `sdk.dir=/path/to/sdk` in the
gitignored `local.properties` file.

Check the protocol vectors, app unit tests, Android lint and both build variants:

```sh
./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
```

The [CI workflow](.github/workflows/ci.yml) runs those checks on pull requests
and pushes to `main`, and retains reports for seven days. A separate API 35
emulator job installs the debug app and tests actual Android Keystore storage,
corruption and missing-key handling, saved-room controls and identity continuity
across a forced process restart. Release signing and physical-device acceptance
remain separate requirements. The [release guide](docs/android-release.md)
covers signing with a selected key, APK verification and the physical acceptance
record.

To run the recovery checks on a **disposable emulator** (they replace KithMoot's
saved room data on that emulator):

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5554 bash scripts/check-recovery-emulator.sh
```

The script requires an explicit emulator serial and refuses physical devices.
It checks instrumentation summaries because Android can return a successful
shell exit code after a test-process crash. Restart preparation and reopening
run in separate processes, with participant/device identifiers compared and
creator controls retained. Recovery uses an unavailable loopback relay to verify
that saved rooms work offline. These checks do not prove live relay admission.

To install the debug build on a connected development device or emulator:

```sh
./gradlew :app:installDebug
```

A room opened in the application uses the relays named on the start screen; a
room joined from a link uses the relays the link names. Debug builds allow
cleartext to `localhost` and `10.0.2.2` so a relay on the development machine
can be used from an emulator; release builds refuse cleartext outright.

### On one person, several devices

This is the whole product, so it is worth saying where it lives. `session/`
folds the roster into people rather than machines, and `ui/room/Tiles.kt` turns
that into one tile group per **participant**. A person at a laptop with their
phone beside them is one card with one name, two video panes and one
microphone. Which of your own devices the room is actually hearing is decided
by `RoleArbiter`, on two rules: most recent claim wins, ties to the lowest
pubkey. There is no coordinator and no handover message, and the device that
loses the claim releases the microphone rather than sitting on a hot mic
nobody can hear.

### On the cryptography

NIP-44 v2 is implemented in this repository rather than taken from a Nostr SDK.
That is not preference. The roster is encrypted with the **raw 32-byte room key
used directly as the conversation key**, and every Nostr SDK exposes only
`encrypt(secretKey, publicKey)`. There is no way to hand one a symmetric key, so
the room channel cannot be expressed through them at all.

The primitives are not ours: ChaCha20, HMAC-SHA256, HKDF and the hashes come
from BouncyCastle, and all secp256k1 work (BIP-340 signing, verification and
the ECDH point multiplication) goes to libsecp256k1 through `secp256k1-kmp`.
What is written here is the NIP-44 construction that arranges them: the key
schedule, the padding scheme, the payload framing, and a constant-time MAC
comparison. No curve arithmetic, no block cipher and no compression function is
implemented in this repository.

Two details are worth recording, because they are the usual places a
re-implementation goes wrong. NIP-44 uses the **raw ChaCha20 stream cipher, not
the AEAD**, with the block counter starting at zero. And libsecp's `ecdh` is
*not* usable here, because it hashes the compressed shared point where NIP-44
wants the bare x coordinate.

## Licence

MIT. See `LICENSE`.

Chat shows timestamps above messages, searches loaded messages and people, and offers an emoji picker and encrypted quick reactions. Public kind-0 names and pictures are off by default and can be enabled for the current visit from Chat. Names remain paired with shortened keys; profiles are self-reported. Image requests use HTTPS, bounded downloads and a memory cache cleared when profile lookup is disabled or the room is closed.

Tap **Expand screen share** on a shared-screen pane for a full-window viewer. Pinch or use +/− to zoom, drag to pan, and use **Fit to screen** to reset. **Pop out** opens Android picture-in-picture on supported devices; Android supplies its movement and resizing controls. Closing the viewer keeps the call track alive. These controls have emulator coverage using generated video; physical-device acceptance remains a separate release check.

The emulator script also checks chat/search/emoji/reactions and a live synthetic screen in the fullscreen and picture-in-picture viewers. It saves synthetic UI captures with the recovery reports. No camera, microphone or desktop capture is used for this viewer check.

Persistent-group emulator checks use a local WebSocket relay and the web fixture.
They cover group creation, acknowledged link replacement, retirement of the old
link, admission with no member online, a forced process restart, and rejected
publication. Snapshot queries wait for EOSE from every relay connected when the
query begins; a missing EOSE or dropped connection fails the query. Relays that
were unavailable at that point are not proof of a complete global history.


## M2 protocol compatibility (0.4.1)

New rooms use v3 durable invitations. Existing v1/v2 links and saved rooms retain
their readers and recovery paths. Signalling writers retain seal-less 20462/21059
with additive inner profile tags and outer expiry; receivers also verify sealed
rumours and bound decrypt attempts before sender attribution. Reserved scoped
pass/policy decoders do not enable service enforcement. The independent Kotlin
reader consumes the same 190 vectors as the reference implementation.

See the [protocol draft](https://github.com/forgesworn/kithmoot/blob/main/docs/protocol.md)
and [compatibility ledger](https://github.com/forgesworn/kithmoot/blob/main/docs/protocol/m2-compatibility.md).
The API-35 emulator passed installed recovery/restart, v3 creation/rotation,
retired/refused invitation, chat and screen-sharing journeys. This does not
replace physical-device acceptance or add the unsupported features listed above.
