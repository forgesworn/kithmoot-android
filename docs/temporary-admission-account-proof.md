# Temporary admission account proofs

The native codec mirrors fold-kit 0.11.0 at
`7ca679fe9e6a3e35c15e366ff9ae879593f8383a`. Optional account claims stay separate
from `verifiedParticipant`; only a fresh signature bound to the invitation,
request device and exact outer timestamp proves the account. Invalid nested
proofs remain ordinary bearer requests. Legacy requests retain their wire form.

`protocol/src/test/resources/invitation-account-vectors.json` is copied verbatim
from the merged fold-kit corpus, SHA-256
`1baf98449831910bc05d21769367cae7e475a75ceedbafc019b446b4cfc5f5a6`.
Tests decode and re-encode the exact signed/encrypted known answers, including
legacy requests, forged signatures and substituted devices.

Temporary guest entry selects only the current matching live account signer.
Anonymous room entry supplies no account or account proof. One 90-second deadline
includes signing and relay response; immutable retries do not create new requests.
A response/retirement subscription is active before asking an external signer.
Cancel, retirement and account replacement prevent a late signature or grant
completing entry. The temporary key is wiped while an admitted delegate retains
its own separate copy.

Temporary hosts now show a bounded queue of individual requests instead of
silently granting every bearer. Cards distinguish guest names, unverified account
claims and signed account proofs, and retain separate device/event identities for
duplicate names. Requests expire after 90 seconds. Let in waits for guarded relay
confirmation; an unconfirmed grant offers an immutable retry. Rotation, retirement,
epoch changes, room departure and cancelled dispatch prevent stale grants.
Only a confirmed relay acceptance clears the card; it does not claim the guest joined.
The queue scrolls within the room and keeps 48 dp actions reachable at large text sizes.

Dismiss is deliberately local. The existing protocol has no decline event, so it
cannot notify a guest of a refusal. Explicit declines require a compatible protocol
extension in fold-kit first, then matching native support.

This foundation does not complete G17. The guest preview/state/retry journey,
explicit decline protocol, temporary-room creation controls, cross-client emulator
journeys, physical external-signers and unfamiliar host/guest acceptance remain
to be completed.
No signed APK or public deployment is claimed here.
