# kithmoot-android

A native Kotlin implementation of the KithMoot protocol — the conference-room
protocol over Nostr where one person can be present on several devices at once
and still appear to the room as a single participant.

This repository is the **second, independent implementation**. The first is the
TypeScript reference client. That is the entire reason this exists: a protocol
implemented once, by one person, in one language, is a product. Implemented
twice, from a published wire contract, it is infrastructure. This repository is
the proof, and it is only worth something to the extent that it was written
against the published vectors rather than against the other implementation's
source.

## Status

**Protocol layer only.** What is here is the codec and the cryptography, checked
against the published interop vectors. What is *not* here, and is not started:

- No WebRTC media. No camera, no microphone, no screen share.
- No user interface, and no Android app module at all — the only module is
  `:protocol`, which is plain Kotlin with no Android dependencies.
- No relay client. Nothing in this repository opens a socket. Publishing,
  subscribing, re-announcing and the room state machine all sit above this
  layer and are not written yet.
- No room descriptor, no forwarder support, no end-to-end encrypted media.

So this does not yet join a room. It builds and reads every message a room is
made of, and it agrees with the reference implementation about the bytes.

## What it implements

| Piece | What it does |
|---|---|
| Room derivation | HKDF-SHA256 from the 32-byte room secret to a public `roomId` and a secret `roomKey`, under two separate info strings |
| Join URL | Encodes and decodes the capability link. The secret rides in the URL **fragment**, never the path or query, so it is never sent to a server |
| Device credentials | Kind 20460, signed by the participant key, naming one device, one room, and a NIP-40 `expiration` |
| Roster events | Kind 20461, NIP-44 encrypted to the room key, with the device credential verified on the way in |
| Signal wrapping | Kind 21059 ephemeral gift wrap carrying SDP and ICE, NIP-44 encrypted to the recipient under a throwaway key |
| Kindred access | The `kin > kith > ken > open` tier ladder, proof issuing and verification, and the room gate |
| TURN credentials | coturn's REST convention: `<expiry>:<name>` with an HMAC-SHA1 password |

Two behaviours in there are load-bearing and easy to get quietly wrong:

- **`ken` never satisfies a `kith` gate.** Ken is one-way recognition — you
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
published vectors, never an edited one. The suite runs each of the **34 vectors
across 8 groups** as its own named test case, so a failure names the vector, and
adds three guards that fail the build if a vector goes missing or a group loses
its negative cases.

All 34 pass, positive and negative, including byte-exact reproduction of every
recorded signature and ciphertext.

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
```

`:protocol` has no Android dependencies by design. It runs on a plain JVM, which
keeps the vector suite fast and leaves the protocol reusable outside the app.

### On the cryptography

NIP-44 v2 is implemented in this repository rather than taken from a Nostr SDK.
That is not preference. The roster is encrypted with the **raw 32-byte room key
used directly as the conversation key**, and every Nostr SDK exposes only
`encrypt(secretKey, publicKey)` — there is no way to hand one a symmetric key, so
the room channel cannot be expressed through them at all.

The primitives are not ours: ChaCha20, HMAC-SHA256, HKDF and the hashes come
from BouncyCastle, and all secp256k1 work — BIP-340 signing, verification and
the ECDH point multiplication — goes to libsecp256k1 through
`secp256k1-kmp`. What is written here is the NIP-44 construction that arranges
them. `REPORT.md` sets out exactly where that line falls.

## Licence

MIT. See `LICENSE`.
