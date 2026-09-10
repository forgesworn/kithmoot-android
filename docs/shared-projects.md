# Shared project protocol

`Projects` reads and writes the signed, encrypted directory used by the web
client: owner snapshots, recipient withdrawals and personal follow records.
Account signing and decryption use supplied callbacks. Each recipient receives
a fresh NIP-44 gift wrap. The owner key and project identifier identify a
project; names do not.

`ProjectDirectoryState` is an immutable projection for one account. Persist the
candidate returned by `accept` before displaying it. It retains the highest
observed revision, hides a definition when that revision has multiple signed
heads, and restores withdrawals across restarts. An old follow cannot join a
re-added membership with a new epoch. Metadata renames preserve the authority
digest; membership, archive and authority revision changes do not.

This is a protocol foundation. Android screens still use local room labels.
Account-scoped encrypted persistence, relay subscriptions, a durable outbox,
project screens and cross-device room-opening acceptance are not connected
yet. No new native APK or physical-device claim follows from these tests.

## Boundaries

- Directory membership grants no room access, history, context retrieval, tool
  permission or execution authority. Opening an invitation must still complete
  normal admission and check the resulting room identifier against the selected
  project entry.
- The account adapter must serialize writers, verify its encrypted cache,
  persist before publication, retain exact retry envelopes and stop on storage
  failure. `restore` rejects invalid state rather than starting an apparently
  empty account. This module provides no persistence.
- The projection retains at most 128 project references, including orphan
  follows, and eight heads per owner or personal-follow revision. Bodies are
  limited to 32 KiB, wrappers to 100,000 characters, membership to 64 entries
  and room lists to 32 entries.
- Highest observed revision does not prove that a relay delivered the newest
  owner update. Runtime permissions and bounded leases remain separate.
- Links must be HTTPS persistent invitations. Android pairing fields `k`/`x`,
  legacy traffic key `s`, and pairing field `c` are rejected whenever present,
  including malformed or null values. The native relay/policy envelope must
  also parse successfully.
- Signing validates before invoking the account and checks its exact answer.
  Unwrap verifies the outer signature and recipient before requesting
  decryption. Signer failures and cancellation return to the caller; malformed
  decrypted events yield no record.

## Interoperability

`protocol/src/test/resources/shared-project-web.json` is the unchanged fixture
from reviewed web head `1f81809af8ac5d8711f7dc58266051794429dba9`, merged in
`d7f078c5906188da11bcf22e48926bad98e531a9`. Its SHA-256 is
`f28bca06cf7f2f64938c3a770c10d0e7d12ff90ffba2a6b3383afca9b242ed95`.
Its repeated-byte keys are public synthetic test data.

`ProjectsTest` checks all 14 signed cases, authority digests, recipients, the
web-produced ciphertext and deliberate-join cases. It also tests signer
substitution, hostile wire types, hidden pairing credentials, conflicting
revisions, bounded orphan follows, withdrawal and restart behaviour. It emits
`protocol/build/interop/shared-project-android.json` for the independent web
reader to verify native signatures and decrypt the native snapshot and follow.

Run with Java 21:

```sh
./gradlew :protocol:test :app:testDebugUnitTest
```

Then, using a reviewed web checkout built with `npm run build:lib`:

```sh
node scripts/verify-shared-project-web.mjs /path/to/kithmoot
```
