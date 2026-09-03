# kithmoot-android

A native Kotlin implementation of the KithMoot protocol: the conference-room
protocol over Nostr where one person can be present on several devices at once
and still appear to the room as a single participant.

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

- No persisted identity. Leaving a room and rejoining it makes you a new
  participant, because nothing is written to disk yet. A second device stays
  paired only for as long as the application is running.
- No room descriptor, agent ownership, attachments or approvals. Those
  vectors are carried in the published set and counted by the coverage
  guard, but nothing on this side implements them yet.
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
- The interface has had no accessibility pass beyond contrast and type size,
  and there are no instrumented tests.

## What it implements

| Piece | What it does |
|---|---|
| Agents, and who may hear you | A member that says it is an agent is marked as one, and a control in the room decides whether this device's camera and microphone are sent to it at all - refused means the tracks are never handed to that connection |
| Room derivation | HKDF-SHA256 from the 32-byte room secret to a public `roomId` and a secret `roomKey`, under two separate info strings |
| Join URL | V2 links carry a bearer invitation plus a pinned per-link root key in the URL **fragment**, never the room traffic secret. Each admitted client receives a bounded root-authenticated delegation and keeps the link available if the creator leaves; a durable root-signed tombstone retires it. Legacy v1 links remain readable |
| Device credentials | Kind 20460, signed by the participant key, naming one device, one room, and a NIP-40 `expiration` |
| Roster events | Kind 20461, NIP-44 encrypted to the room key, with the device credential verified on the way in |
| Signal wrapping | Kind 21059 ephemeral gift wrap carrying SDP and ICE, NIP-44 encrypted to the recipient under a throwaway key |
| Durable chat | Kind 1460, matching the TypeScript wire format and fixed interop event; room-key encrypted, credential/proof checked, 2,000-character and 30-per-minute sender bounds, 30-day query horizon and 500-message in-memory cap |
| Kindred access | The `kin > kith > ken > open` tier ladder, proof issuing and verification, and the room gate |
| TURN credentials | coturn's REST convention: `<expiry>:<name>` with an HMAC-SHA1 password |

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
published vectors, never an edited one. There are **95 vectors across 14
groups**. The suite runs each vector in the 8 groups this implementation
covers as its own named test case, so a failure names the vector, and adds
three guards that fail the build if a vector goes missing or a group loses its
negative cases.

The 6 groups this client does not implement - `channelDerivation`,
`roomEpoch`, `agentOwnership`, `chatAttachment`, `approvalControl` and
`roomDescriptor` - are counted by `VectorCoverageTest` without being run, so
the day one of them lands the guard already knows how many cases it owes.

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
└── ui/         Compose: theme, start screen, room, tiles, chat, controls
```

`:protocol` has no Android dependencies by design. It runs on a plain JVM, which
keeps the vector suite fast and leaves the protocol reusable outside the app.

### Running it

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
