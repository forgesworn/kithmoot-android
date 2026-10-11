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

Account integration remains to implement in `SharedProjects`:

- Load existing encrypted v1 journals and add bounded companion heads,
  immutable encrypted outbox envelopes, request receipts and up to 128 waiting
  encrypted wrappers. Reject unknown fields and invalid signatures; preserve
  existing bytes when migration or saving fails.
- Save directory, companions and both outboxes/receipt sets in one candidate
  before exposing state or dispatching. Preserve the current combined outbox
  and receipt limits and the encrypted vault byte limit.
- Subscribe to both domains. Directory changes drain waiting wrappers against
  verified member contexts; accepted companions never create membership.
- Refresh an unambiguous owner logo atomically when membership authority
  changes. Prune stale envelopes and withhold conflicting/withdrawn artwork.
- Use `publishConfirmedGuarded` with the captured publication generation and
  a cheap immutable snapshot of eligible pending envelopes. A guard must not
  take the account mutex or call the transport. Recheck owner, authority,
  current head and recipient membership at dispatch and confirmation.
- Retain identical request/envelope bytes on retry. Account close and storage
  failure must prevent delayed dispatch. Test withheld, denied, interrupted,
  restarted, conflicting and removed/re-added-member journeys.

The owner editor, contextual inheritance, navigation/invitation/admission
branding and originating-call label follow this integration. This projection
is not yet used by the app and publishes no APK. G15 remains open.
