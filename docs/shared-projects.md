# Shared projects

The **Projects** tab shows the signed-in account's shared directory. Owners can
create and edit projects, invite people and agents by npub, share selected
persistent room invitations and archive a project. Invited members deliberately
join or leave; a re-added member must join again. The owner identity accompanies
each name, and people details expand only when needed.

Opening a shared room completes normal admission and verifies the room ID. A
saved room under another identity is refused with an explanation; the app never
silently replaces it. Leaving a room returns to the selected home tab.

Projects require a signer with NIP-44 support. **Sync** refreshes the account
directory. **Retry project updates** sends retained, exact encrypted envelopes
after a failed or uncertain publication. Merely restoring the account does not
replay those sends. Valid relay settings survive process restarts.

## Protocol and persistence

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

`SharedProjects` connects this projection to account signing, recipient-filtered
relay history and live updates. `ProjectVault` stores signed records, the exact
encrypted outbox and request receipts using Android Keystore AES-GCM and atomic
files under `noBackupFilesDir`. One writer owns each account vault. Restored
records are verified before they can become visible; invalid or unwritable
storage stops editing and preserves existing data.

Publication follows durable storage. Acknowledgements remove only the exact
envelopes they confirm, including when another edit happens during sending.
Sign-out clears visible state and closes the directory before closing its signer;
the encrypted cache remains available when that account returns.

## Boundaries

- Directory membership grants no room access, history, context retrieval, tool
  permission or execution authority. Opening an invitation must still complete
  normal admission and check the resulting room identifier against the selected
  project entry.
- The account adapter serializes writers, verifies its encrypted cache,
  persists before publication, retains exact retry envelopes and stops on storage
  failure. `restore` rejects invalid state rather than starting an apparently
  empty account. The cache is bounded to 32 MiB, 1,024 pending envelopes and
  4,096 request receipts.
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
- Conflicting owner heads hide room shortcuts. Native conflict-resolution UI
  is still absent; the owner must review and resolve them in the web client.
- This directory does not provide a cross-project decision inbox or an Oathrun
  setup wizard. Physical-phone continuity, live-agent pairing and background
  push remain separate acceptance requirements.

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

## Installed-app acceptance

The recovery-emulator script includes a three-project journey with overlapping
and disjoint synthetic people/agents, owner edits, explicit member joins, normal
room admission, mismatched-room refusal before publication, withdrawal restored
without relay history, and deliberate rejoining after a new membership epoch.
Two separate instrumentation processes test account, relay preference and
encrypted outbox recovery across a forced stop, followed by an explicit resend
of the same envelope IDs. These use an isolated emulator and loopback relays.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
ANDROID_SERIAL=emulator-PORT ANDROID_HOME=/path/to/sdk bash scripts/check-recovery-emulator.sh
```

The script replaces synthetic saved room data and must only run on a disposable
emulator. Emulator and signed-fixture evidence does not establish physical-device
or live Android-to-Oathrun acceptance.
