# Stage 4 — Android protocol layer: interop report

**Date:** 24 August 2026
**Scope:** protocol layer only. No WebRTC, no UI, no relay client.
**Result:** all **34 published vectors pass**, positive and negative, across all
8 groups (`roomDerivation` 4, `joinUrl` 7, `deviceCredential` 4, `rosterEvent`
3, `signalWrap` 4, `kindredProof` 3, `accessEvaluation` 5, `turnCredential` 4),
plus 3 coverage guards. 37 tests, 0 failures.

No vector had to be weakened, skipped or edited, and no disagreement was found
between the two implementations. Everything the vectors record byte-for-byte —
every event id, every BIP-340 signature, every NIP-44 ciphertext — is reproduced
byte-for-byte from the recorded aux-rand and nonces.

## Was NIP-44 v2 implemented by hand?

**Yes, and it was unavoidable.** The roster is encrypted with the **raw 32-byte
room key used directly as the conversation key**, with no ECDH keypair in the
picture. Every Nostr SDK surface — `org.rust-nostr:nostr-sdk` included — exposes
only `nip44Encrypt(secretKey, publicKey, …)` and offers no way to pass a
symmetric key, so the room channel cannot be expressed through any of them.
This is the same wall `relayswarm-kit` hit in Swift, for the same reason.

The line between library and hand-written code:

| From a library | Written here |
|---|---|
| ChaCha20 (`ChaCha7539Engine`, BouncyCastle) | The NIP-44 construction that arranges them |
| HMAC-SHA256, SHA-256, SHA-1 (BouncyCastle) | Key schedule: HKDF-extract for the ECDH conversation key, HKDF-expand to 76 bytes split into ChaCha key / 12-byte nonce / HMAC key |
| HKDF-SHA256 (`HKDFBytesGenerator`, BouncyCastle) | The padding scheme (length prefix, power-of-two chunking, 32-byte floor) |
| BIP-340 sign and verify (libsecp256k1 via `secp256k1-kmp`) | Version byte, payload framing, base64 |
| ECDH point multiplication (`pubKeyTweakMul`, libsecp256k1) | Constant-time MAC comparison via `MessageDigest.isEqual` |

No curve arithmetic, no block cipher and no compression function is implemented
in this repository. Two details are worth recording because they are the usual
places a re-implementation goes wrong: NIP-44 uses the **raw ChaCha20 stream
cipher, not the AEAD**, with the block counter starting at zero; and libsecp's
`ecdh` is *not* usable here, because it hashes the compressed shared point,
where NIP-44 wants the bare x coordinate.

`rosterEvent` and `signalWrap` both reproduce byte-exactly, which is
unambiguous confirmation that the NIP-44 implementation is correct in both its
symmetric and its ECDH mode.

## Where the TypeScript source had to be consulted

**Nowhere. No file under `kithmoot/src/` was opened at any point.**

That is not a claim that the vectors and spec were sufficient. They were not.
Several things the wire format depends on are written down in neither, and each
one had to be *recovered experimentally from the recorded bytes* — by decrypting
a recorded ciphertext to see what shape came out, or by testing candidate
constructions against a recorded signature until one verified. That is reading
the vectors, not reading the implementation, and it produced a byte-compatible
result. But it means an implementer without the ability to run those experiments
would have been stuck, so **every item below is a hole in the spec**, and they
matter more than the clean run does.

### 1. The HKDF parameters for room derivation

The spec and the brief name the two info strings and nothing else. Salt, input
keying material and output length are all unstated. Recovered by testing salt
candidates against `roomDerivation/typical-secret-1`.

> **Should say:** `roomId = hex(HKDF-SHA256(ikm = secret, salt = "", info =
> "kithmoot/v1/room-id", L = 32))`, and the same with `kithmoot/v1/room-key` for
> the room key. Note that an empty salt is not the same statement as a 32-byte
> zero salt in every HKDF API, even though SHA-256 makes them equivalent here.

### 2. What a kindred proof actually signs

Nothing anywhere states the signed message. The vectors record the resulting
signature and no more. Recovered by brute-forcing candidate canonical strings
against `kindredProof/ken` until one verified under BIP-340.

> **It is:** `sha256("kithmoot/v1/kindred:<tier>:<participant>:<expiresAt>")`,
> signed BIP-340 by the issuer key.

Two consequences worth a decision, not just documentation:

- **The issuer is not in the signed message.** It is bound only by which key
  verifies. That is sound, but it should be a stated choice.
- **The proof does not name a room.** A proof issued once is replayable at every
  room that trusts that issuer, until it expires. That may well be the intent —
  it is a statement about a person, not about a door — but it is exactly the
  kind of thing a spec has to say out loud.

### 3. The roster plaintext, including its field order

The vectors record ciphertext only. The JSON inside is not described anywhere.
Recovered by decrypting `rosterEvent/valid` with its recorded room key.

> **It is:** `{participant, device, credential{kind, created_at, tags, content,
> pubkey, id, sig}, tracks[{trackId, role}], claims, updatedAt}` — and the field
> order is **load-bearing**. In TypeScript it falls out of `JSON.stringify`
> preserving insertion order and nobody has to think about it. In any other
> language it has to be deliberately reproduced or the ciphertext will not
> match. The `claims` map's ordering is likewise unpinned; only a single-key
> example appears in the vectors.

> **Should say:** either pin the field order explicitly, or state plainly that
> byte-exact ciphertext reproduction is not part of the contract and only
> decryptability is.

### 4. The gift-wrap plaintext

Same situation, recovered the same way. The wrapped payload is the inner event
as compact JSON in the order `kind, created_at, tags, content, pubkey, id, sig`
— not NIP-01's `[0, …]` canonical array, and not alphabetical.

### 5. The gift wrap's `created_at`

The vectors show the outer wrap carrying **exactly the same timestamp as the
inner event**. NIP-59 conventionally randomises the wrap's timestamp precisely
so the relay cannot read the inner one. Implemented as the vectors pin it, but
this looks like a deliberate simplification that gives up part of what gift
wrapping is for, and it should be recorded as a decision rather than left to be
inferred.

### 6. The room key is used *directly* as the NIP-44 conversation key

Stated in the dispatch brief and confirmed by the vectors, but absent from the
spec. It is a real deviation from NIP-44, which derives the conversation key by
HKDF-extract over an ECDH shared point, and any implementer will hit it.

### 7. Rejection reason strings, beyond the seven that are pinned

The vectors pin `wrong room`, `expired`, `bad signature`, `open room`, `kindred
proof accepted`, `tier too low`, `untrusted issuer`. Every other refusal path
has no pinned string, so these were invented here: `wrong kind`, `no device
named`, `no expiry`, `no kindred proof`, `proof names another participant`,
`proof expired`, `bad proof signature`. If reasons are part of the contract —
and pinning seven of them implies they are — the rest need pinning too.

### 8. The expiry boundary

No vector exercises `expiresAt == now`. Implemented as **expired** (`expiresAt
<= now`), following NIP-40's reading, for credentials and kindred proofs alike.

### 9. Check precedence in `evaluateAccess`

Both the `untrusted issuer` and `tier too low` vectors are satisfied by either
ordering of those two checks, so the precedence is unpinned. It only shows if a
client displays the reason, but two implementations will differ.

### 10. Miscellaneous unpinned edges

- Whether an absent `r` in a join URL fragment is an error or an empty relay
  list. Implemented as an error.
- Whether `unwrapSignal` must check the inner event's `p` tag against the
  recipient. Implemented as a check, failing closed.
- `SignalBody` beyond `sdp` and `candidate` — the `answer` case never appears,
  and neither does any additional field, so the serialisation order for
  anything else is undefined.
- `rosterEvent/wrong-signing-device` supplies no `now` for decoding, unlike its
  sibling vectors. The test falls back to the entry's `updatedAt`; the outcome
  is null either way, so nothing hinges on it.

## The one thing missing from the vectors

**No vector presents a forged kindred proof from an admitted issuer.**

Every proof in the `accessEvaluation` group is correctly signed. The
`untrusted issuer` negative is rejected on the allow-list before the signature
is ever reached, and `tier too low` is rejected on the ladder. So an
implementation that **never verifies a kindred proof's signature at all** passes
all 34 vectors — it would admit anyone who could fabricate a proof naming a
trusted issuer, which is a two-line forgery.

This implementation verifies the signature (last, after the cheaper checks, so a
forged proof cannot burn a curve operation for free). But the gap should be
closed in the vector file, not just here: a proof with a valid-looking signature
from an admitted issuer at a sufficient tier, expected to be refused. It is the
highest-value vector the set does not yet have.

## Smaller notes on the vectors themselves

- Several vectors reuse **one recorded value for two cryptographic roles**:
  `rosterEvent/valid` uses the same hex for `nonceHex` and `auxRandHex`, and
  both `signalWrap` positives use the same hex for `outerAuxRandHex` and
  `nip44NonceHex`. Harmless in a frozen fixture — neither needs to be
  unpredictable here — but it is a poor pattern to copy into live code, and the
  vectors README does not warn against it.
- `joinUrl/decode-malformed-fragment` uses `!!!`, which fails on the base64
  alphabet before any parsing happens. It is a weaker test than it looks: a
  fragment made only of valid base64url characters that does not decode to
  valid JSON would exercise the path that actually matters.
- The vectors README's invariant — verify first, byte-exact reproduction second
  — is the right framing and made this straightforward to work against.

## Toolchain note

`fr.acinq.secp256k1:secp256k1-kmp` is pinned at **0.15.0**, not the current
0.24.0. Every release from 0.17.0 onward is compiled with Kotlin 2.1+ metadata,
which the Kotlin 2.0.21 compiler the house standard fixes cannot read — 0.24.0
fails with an internal compiler error rather than a clear message. Only 0.15.0
and 0.16.0 remain on Kotlin 1.9 metadata. When the workspace moves off Kotlin
2.0.21, this pin can move with it.

## Verification

```sh
./gradlew :protocol:test --rerun-tasks
# 37 tests, 0 failures (34 vectors + 3 coverage guards)
```

The suite was mutation-checked rather than merely run. Making `ken` clear a
`kith` gate fails `accessEvaluation/kith-room-rejects-ken-proof`, and dropping
the roster's device-binding check fails `rosterEvent/wrong-signing-device` —
so the negative vectors are doing real work, not passing vacuously.
