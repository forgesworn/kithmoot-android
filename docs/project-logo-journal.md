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

The owner editor, contextual inheritance, navigation/invitation/admission
branding and originating-call label follow this integration. The journal is
used by the app, but the owner editor and project-logo rendering remain to wire
in. This source change publishes no APK. G15 remains open.
