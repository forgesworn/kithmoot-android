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
Six emulator UI cases include the full RoomScreen and retain an unsent chat draft
while same-name requests are individually admitted and declined; failed refusals offer a retry or local dismissal.

Explicit Decline now mirrors fold-kit 0.12.0 at
`dc51766b7e6a141d5ba8aa0e5421df86432a7412`. Its encrypted version-3 reply
uses kind 20467, binds the exact request/device and current responder authority,
and carries no room secret or new delegation. The shared refusal vectors are
copied verbatim, SHA-256
`8e6c63385c41a63166682e61d735aa33d62be1965481db4f1e5e4c9a062db550`.
Older clients ignore this reply and keep their existing bounded wait.

The host card reports Sending refusal until a relay acknowledges it. Failure
keeps Retry decline and Dismiss available; retry reuses the exact signed event.
An uncertain grant cannot turn into a refusal, or the reverse, because a reply
already received cannot be revoked. Dismiss remains local. A validated refusal
ends the guest wait with a distinct explanation, stops request retries and wipes
the temporary key. Forged, malformed, stale and unrelated refusals cannot end it.

The [refusal qualification receipt](evidence/temporary-admission-refusal-2026-10-10.json)
records final unit results, both lint/build variants and 12 rendered admission
and nearby recovery cases on an emulator. Installed app and test APKs were
pulled back and fully byte matched before instrumentation.

This foundation does not complete G17. The guest preview/state/retry journey,
temporary-room creation controls, complete browser/native admission journeys,
journeys, physical external-signers and unfamiliar host/guest acceptance remain
to be completed.
No signed APK or public deployment is claimed here.
