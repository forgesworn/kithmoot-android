# The vault's tombstone list reaches the engine adapter

2026-11-05. Follows Vennel PR #348 (tombstoned credentials refused at the
invitation and admin-invitation boundary) and PR #349 (a member applies every
commit unchanged, then advises removal of any leaf whose credential its own
vault has tombstoned). The engine keeps no revocation state of its own: the
vault's verified kind-5 tombstone list is handed to it at every adapter call.

- `MlsVault.revokedCredentials` reads the persona's stored ids (64-hex) in
  vault-record order.
- `EngineSessions` binds them into `VmlsPlatform.newWithRevocations` at
  construction. The list is a snapshot: `VmlsRuntime.engine` rebuilds the
  persona's engine when the vault's set changes, closing only decrypted
  session copies (they reopen from their witnessed snapshots, exactly as a
  credential renewal does), so a fresh tombstone feeds the engine's removal
  advice on the next round. A failed vault read fails the operation — a list
  that cannot be read never silently becomes an empty one.
- Every `inspectInvitation`/`inspectAdminInvitation` site reads the list
  afresh, so an invitation signed by a tombstoned credential is refused at
  inspect time wherever it is pasted. Introduction completes and capability
  openings take the platform first: its current list gates completion
  alongside the list captured at prepare (Vennel PR #348's union rule).
- Commit acceptance never sees the list (Vennel PR #349's rule): a member
  whose list differs from another's applies the same bytes and differs only
  in its local removal advice. Nothing here mints a fence, witness artefact
  or wire record from the list.
- Engine pin: prerelease `vmls-ffi-android-053dc47` (Vennel `053dc47e`),
  replacing `5b3eb5f`. Archive, manifest and text-manifest digests are
  pinned in `scripts/prepare-vmls-ffi.py`; the asset is republished byte for
  byte from Vennel's `VMLS Android bundle` CI run for `053dc47`, by hand as
  with prior bundles.

Regression: `InvitationTombstoneTest` issues a real invitation under a
coordinated vault and the in-process witness (no box), tombstones the
inviter's credential in the vault, and asserts the same bytes are refused
`CredentialRevoked` at inspect with the vault's list — the empty-list
control still passes, and a platform freshly bound to the vault's list
refuses the capability prepare the same way.
