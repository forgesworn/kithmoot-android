# Member requests to revoke a compromised device

P3-08, 8 October 2026. Part of the opt-in VMLS preview.

A guest can select another of their own devices observed in the room, even
from a retained roster after this phone is removed. While the room can change,
the app journals a compromised-device MLS Remove and holds this phone's sends
until it is witnessed. A separate identity-sealed request asks the keeper to
revoke box access immediately. The keeper must explicitly accept.

The room retains its keeper from the authenticated admission answer. Older
rooms without that identity cannot request revocation; there is no roster-based
guess. The member has no grant identifier: the membership journal uses a
domain-separated box/device reference with no keeper authority.

The experimental kind-21350 rumour is never published directly. NIP-59 seals
and gift wraps carry it to the keeper's latest verified kind-10050 relays.
Publishing supplies no identity AUTH; a relay requiring it can refuse the request.
Only keeper inbox reads authenticate on a NIP-42 challenge. Existing Link and
known-circle pools retain mandatory AUTH; this dedicated public-DM carrier has
no circle-discovery registry. No relay list means no fallback. Only an
`OK true` acknowledgement marks a request sent, and never revoked. Explicit
retries use fresh wraps; the keeper deduplicates by identity/device.

The keeper checks its own ledger's issuer, persona and device, and any current
roster identity. Sender-supplied scope cannot authorise or exclude boxes. Its
foreground inbox processes at most eight wraps per minute, deduplicating before
signer use. Outer signatures are verified before an attempt is recorded. A witnessed
`until` cursor per relay reads one 64-event page per minute, rotating relays and
ignoring future events. Stored queries verify before pool dedup, end on EOSE,
and time out after five seconds without moving an incomplete cursor. The
boundary second is read inclusively so a split page does not discard it.
At most 1024 attempted wrap IDs are retained for up to nine days, with FIFO
eviction at capacity. Processing and its one-minute limit survive restart. Requests and approvals are sealed in the
witnessed persona record. Expired outbox entries are pruned, and request byte budgets reserve room for
deduplication. A missing box route ends as unavailable; pruned completed grants
finish as no live grants in the ledger, without claiming new revocations.
An approved request resumes after restart; a replay
cannot re-open a completed one. A keeper can revoke grants after leaving the
room. A stopped room uses the ledger-only path and makes no MLS-removal claim.

The sender's identity is authenticated, but which device sent the request is
not. Conflicting requests are shown with the affected devices. Relays still see
receiver, addresses, timing and volume. The member does not identify itself by
relay AUTH. Keeper prompts offer Later across restart; a newer request can reopen
a declined one, with an hour between new prompts from the same identity.

Delivery is best effort: ordinary NIP-17 messages also consume the decryption
budget; floods, full storage, and more than 64 events at one timestamp can hide
requests until expiry. Relays may withhold events. Directory lists are cached
for fifteen minutes. Keeper authentication with an external signer may still
prompt; hardware acceptance remains open. No acknowledgement from the keeper,
instantaneous global revocation, fresh-device recovery or browser flow is added.

Checks: protocol and app tests, debug/release lint and both APK builds. Hostile
parser cases include random bytes, every truncation, duplicate authority tags,
unknown tags and deeply nested input. The three-emulator scenario extends
`scripts/lab-vmls-two.sh`: set `P308_ONLY=true` and provide all three disposable
emulator serials. A local Nostr relay carries real signed/encrypted events; a
real Bothy fixture and Link carry MLS and grants. Each step restarts the app
process. This is runtime integration, not physical-device or Compose consent
acceptance. The Vennel gate record retains attempt outcomes and artifact hashes.

Required before merge: the P3-08 Opus security review. Signing and publication
remain separate; the current 0.6.64 production APK does not contain this change.


## Explicit private keeper checks (25 October 2026)

In the opt-in preview's VMLS boxes screen, each saved box offers **Check
privately** and **Older privately**. A separate, one-check consent identifies
this account to that selected Bothy and permits opening keeper envelopes. It is
bound to the persona, box and saved route; leaving the screen, backgrounding,
signing out, changing the route or selecting Stop withdraws it. The MLS device
vault's leaf-binding/box-request approvals do not supply this permission.

Private checks separately bound Link connection (30 seconds), keeper AUTH
(10-second silent signer lookup plus 60-second intent approval and five seconds
for the node reply), and the stored page (five seconds after matching AUTH OK).
These are client ceilings: the node may refuse or expire its unauthenticated
session sooner. Withdrawal wakes a pending phase and prevents a late AUTH/REQ;
external signer replies do not extend a phase deadline.

Only the pinned saved Link `/events` connection is used. Its matching NIP-42
AUTH acceptance precedes the recipient REQ; pairing and a VMLS deposit grant
are not keeper authority. The private check performs no public directory read,
recipient query, public AUTH or publication and has no public fallback. Bothy
still sees the authenticated keeper, recipient filter, ciphertext, sizes and
timing. Link carriers can observe connection metadata. This is not anonymity.

Public keeper checks are now explicit, separately labelled actions; foreground
rounds retain outbox and approved-request processing but perform no unsolicited
public inbox lookups. Member request **sending remains public and separate**.
Private member-to-keeper sending needs a reviewed authorization design: the
VMLS device grant does not authorize an ephemeral-author gift wrap at ordinary
`/events`. No node authorization gate is weakened here.

Public and private checks share the witnessed one-page/minute and eight-wrap
allowance. Signatures precede deduplication; the exact p-only outer/seal/request
and keeper ledger/membership checks still gate intake. Private permission is
checked before and after both external decryption layers and before persistent
processing. A wrap attempt reserved before signer work remains spent on
cancellation, without creating a request prompt.

Normal checks query a fresh head and preserve older progress. Older checks use
their separately persisted inclusive cursor (private: persona/box/route;
public: relay). Signed matching unsupported shapes can move a boundary without
signer work; forged material cannot. Only an EOSE-complete processed page with
a strictly older signed boundary advances retained work. Empty, capped
same-second and expired boundaries hold; neither a short page nor absence
proves history exhausted. A held older boundary cannot hide later arrivals from
a fresh normal check. This finite check does not prove complete history.

Validation for this change uses synthetic JVM fixtures and debug Kotlin
compilation only. It does not establish physical signer/device behavior,
Compose acceptance or a mixed-client journey. Production MLS remains opt-in;
this read-path change does not activate or qualify the newly selected
invitation-based profile or arbitrary rollback detection.
