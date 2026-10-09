# Live persistent admission codecs

The protocol module implements the opt-in `persistent-live-v1` descriptor,
request and answer profile from fold-kit. Its source specification is
[the shared wire profile](https://github.com/forgesworn/fold-kit/blob/476edf3/docs/live-persistent-admission.md). The verbatim synthetic
fixture is `protocol/src/test/resources/live-persistent-vectors.json`.

This is a codec prerequisite for fresh offline entry. No app route invokes it
yet. Saved-room nearby reopening continues to use its existing path.

The challenge uses a fresh one-use key and a distinct bearer-derived request
key. Only the original persistent link's root may answer. The response binds
the original request, expected room and expiry, and carries the unchanged
signed kind 1463 invitation. There is no delegation. `epochHint` remains
separate from the invitation's epoch-zero secret. Readers bound inputs and
refuse duplicate tags, duplicate JSON fields, noncanonical numbers, wrong
authority/room/request/key, stale responses and malformed nested invitations.
`parseLivePersistentEvent` is the bounded raw JSON reader; use it before a
generic event parser can discard extra fields or coerce strings to numbers.

Before exposing fresh Nearby joining:

- Recover the root's durable active/retired/closed/pending invitation state and
  enforce one logical authority writer. A legacy keeper file alone is not
  sufficient. Serialise response handoff with retirement, closure and rekey.
- Own challenge cancellation, monotonic deadline, retries, replay consumption,
  key deletion and global resource budgets. A stateless codec does not do this.
- Require fresh authenticated epoch admission even when the hint is zero.
  The existing settle-delay fast path cannot prove this. Unknown and removed
  participants keep their current policy; obtaining epoch zero never calls
  `letIn` using an unproved identity claim.
- Carry the separate discovery descriptor alongside the original invitation,
  select Nearby before opening any transport, and preserve permissions and
  zero-fallback behaviour on all failures. Signed relay hints do not choose a
  different route. No implicit control forwarding is enabled.
- Measure actual new RoomSession admission, cold keeper recovery, UI/network
  isolation and physical BLE. The unit/vector tests do not establish these.

The request/answer reuse ephemeral kinds 20466/20467 with a distinct encrypted
profile. Existing persistent and legacy delegation vectors remain unchanged.
No radio, public relay, MeshCore data type or production room is involved.

Local validation: all 390 protocol tests pass, including 24 shared fixtures
and four additional boundary tests. Request/answer encoders reproduce the
TypeScript ciphertext, event ID and signature with the fixture nonces and
BIP-340 auxiliary randomness. Fixture source: fold-kit `476edf3`; SHA-256
`3797de22214968fc91b4e052d22f496b7ab982c8a0ee9307784de596a54f3a9a`.
App build/lint and hosted checks are recorded by the pull request, separately
from this local JVM proof.
