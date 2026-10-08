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
Public relays authenticate on a NIP-42 challenge; mandatory authentication for
Link and circle routes is unchanged. No relay list means no fallback. Only an
`OK true` acknowledgement marks a request sent, and never revoked. Explicit
retries use fresh wraps; the keeper deduplicates by identity/device.

The keeper checks its own ledger's issuer, persona and device, and any current
roster identity. Sender-supplied scope cannot authorise or exclude boxes. Its
foreground inbox processes at most eight wraps per minute, deduplicating before
signer use. At most 1024 attempted wrap IDs are retained for nine days; at the
bound, processing waits for expiry. Requests and approvals are sealed in the
witnessed persona record. An approved request resumes after restart; a replay
cannot re-open a completed one. A keeper can revoke grants after leaving the
room. A stopped room uses the ledger-only path and makes no MLS-removal claim.

The sender's identity is authenticated, but which device sent the request is
not. Conflicting requests are shown with the affected devices. Relays still see
receiver, addresses, timing and volume. No acknowledgement from the keeper,
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
