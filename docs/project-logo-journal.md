# Native project logo journal integration

The immutable `ProjectLogoDirectoryState` projection is implemented. A verified
owner-signed companion is accepted only against its current directory authority.
Lower revisions cannot replace newer artwork. Equal-revision forks withhold the
image until a higher revision resolves them, including explicit removal.
Metadata-only directory changes retain the logo; changed authority hides it
until a fresh companion arrives. Revision sequences are scoped to authority,
matching the browser. A fresh current head removes obsolete authority heads.

The projection retains at most 128 projects and eight heads per project.
Copies of original events protect verified state against input/output tag
mutation. Restore checks signatures, withholds stale/unknown authorities and
refuses an oversized cache. Seven additional tests bring the protocol suite
to 443 passing cases with no failures, errors or skips.

`SharedProjects` now integrates the companion projection into the account journal:

- Loads existing encrypted v1 journals and adds bounded companion heads,
  immutable encrypted outbox envelopes, request receipts and up to 128 waiting
  encrypted wrappers. Unknown fields and invalid signatures are rejected;
  existing bytes are preserved when loading or saving fails.
- Saves directory, companions and both outboxes/receipt sets in one candidate
  before exposing state or dispatching. Preserves the current combined outbox
  and receipt limits and the encrypted vault byte limit.
- Subscribes to both domains. Directory changes drain waiting wrappers against
  verified member contexts; accepted companions never create membership.
- Refreshes an unambiguous owner logo atomically when membership authority
  changes. Prunes stale envelopes and withholds conflicting/withdrawn artwork.
- Uses `publishConfirmedGuarded` with the captured publication generation and
  a cheap immutable snapshot of eligible pending envelopes. A guard must not
  take the account mutex or call the transport. Rechecks owner, authority,
  current head and recipient membership at dispatch and confirmation.
- Retains identical request/envelope bytes on retry. Account close and storage
  failure prevent delayed dispatch. Fourteen account regression cases cover
  persistence-before-send, legacy loading, restarted exact envelopes/receipts,
  companion-first arrival without membership, rename and removal/re-addition,
  atomic save failure, fork resolution and withheld offers, dispatch/confirmation guards, signer
  sign-out, corrupted signatures and disjoint project scope.

The additive fields belong to the device's encrypted cache, not the project
definition wire format. Older native versions with an exact-field cache reader
cannot open a cache after it has gained these fields; downgrading the installed
app is not a qualified recovery path. Existing legacy caches remain readable
by this version. The combined pending limit is 1,024, the combined request
receipt limit is 4,096, and the vault remains bounded to 32 MiB. Waiting artwork
does not create a project, join a room or follow an invitation. Archived,
withdrawn or conflicting projects expose no image.

Project cards render the verified inline artwork beside the accessible project
name. The owner can choose, preview/crop, replace or remove it using the same
bounded local-image editor as room logos. Members cannot open the owner editor.
A withheld fork can be resolved by an explicit removal without displaying an
unverified image. Cancelling the preview sends nothing.

An editor captures the account, directory and companion heads. Account changes,
membership/authority changes, withdrawal or another logo update invalidate it.
The journal checks cancellation both before and after an external signer waits,
before committing or sending anything. The accepted update refreshes the local
captured heads so successful removal can close the preview normally.

Emulator checks cover the real content-URI decoder and crop/share action,
owner/member controls, cancellation, stale selection and large text in both
themes. The installed account journey checks encrypted owner-to-member delivery,
cache restoration without relay history, stale-owner refusal and removal after
restart. These do not replace physical-phone acceptance.

Room headers and home rows now inherit the artwork of a sole joined, current
project. Opening a room through a project captures that explicit context, so a
room shared by several projects does not choose one arbitrarily. A room's own
logo takes precedence; removing the override restores project inheritance. The
room metadata and its editing authority remain separate from this display
context. Project room shortcuts use their selected project's context.

An active call captures its originating project when joining and keeps that
reference while the person reads other rooms or projects. Directory changes
refresh the artwork of that captured project. Withdrawal, archival or conflict
withholds it; another project cannot take over the existing call label. Leaving
clears the captured context. Capturing an absent context prevents later sign-in
or directory recovery from inventing a project for an already active call.

The [persistent project invitation preview](project-invitation-preview.md)
connects external and pasted links to a real review screen when the current
account already holds the exact invitation in a joined, unambiguous project.
Its label and inline artwork come from that verified directory; unknown links
show no guessed project identity. Review performs no invitation lookup, creates
no membership and grants no access. Only **Open room** starts entry, with the
reviewed account, project and authenticated room checked before saving.
Quiet, private and anonymous rooms,
anonymous browsing, ended rooms and another account's saved rooms withhold
project artwork. Names remain visible beside the logo and the admission screen
explains that a logo is not proof of access.

Source implementation, installed emulator checks, public APK availability and
physical-phone acceptance remain separate evidence. This source change
publishes no APK. Closed-room catch-up, cross-client recovery and physical
acceptance remain open; G15 is not complete.
