# Public-relay account authentication

KithMoot Android Settings → Connections → Nostr relays gains the same explicit
account-identification choice as the Linux desktop client. This addresses an
already-allowlisted person whose room messages have different device signers.
The relay-side correction is deployed separately; a Nostr account login alone
does not grant permission to identify that account to every room relay.

## Behaviour and identity boundary

Save and enable a relay first, then choose **Use signed-in account**. The
confirmation names its exact URL and the current account npub. Cancel grants
nothing. Approval allows NIP-42 only for foreground rooms using that URL,
until sign-out, account-session replacement or app process exit. Nothing is
written to preferences, invitations or account events. Removing or disabling
the relay also removes permission. A late confirmation from an older account
session is refused, even if a new session uses the same npub.

Open a room again after approval and approve its signer request. A room with
this permission waits for successful authentication before sending its frames.
The existing consented ForgeSworn Link route still works. Anonymous rooms
never use the public account. A chat-only child can use its live owning
account's exact grants; it cannot transfer them to another account session.
Change or withdraw permission from home after leaving open rooms.

Account sync, public discovery and background listeners acquire no new
identity permission. The confirmation describes foreground room scope. This
does not claim reliable background delivery through an AUTH-only public relay.

## Review pack

- Screens: Android Settings → Connections → Nostr relays, its **Identify to
  this relay?** confirmation, and the read-only relay sheet inside a room.
- Candidate: branch `fix/public-relay-account-auth`; build and screenshots
  are recorded here after verification. No real recipient or message content
  is used in UI captures.
- Setup: sign in with a disposable fixture account; add a test relay that
  implements NIP-42 and permits that account, then save relay choices.
- Cancel: choose **Use signed-in account**, check exact URL/account, cancel.
  Reopening a room must not prompt the signer or send AUTH for this relay.
- Approve: choose it again and **Use this account**. **Stop identifying**
  appears. Reopen a room, approve signer AUTH, send a device-signed message
  and verify its exact signed event is returned by the test relay.
- Withdraw: leave rooms and choose **Stop identifying**. A later room must
  not send account AUTH. The relay may now refuse the connection.
- Boundaries: remove/disable the relay, sign out/in, switch accounts, restart
  the process, cancel a pending confirmation through an account change, and
  open an anonymous room. Permission must not survive or spread to another
  URL, account, device or invitation. The in-room action is disabled.
- Accessibility: inspect phone portrait at standard and enlarged text in
  light and dark themes; URL/npub and the consent buttons must remain readable.

Linux desktop already has **Use signed-in account** in the relay settings,
including the published v0.1.89 source. Its room-scoped permission is held for
the tab/session. The sender should choose that control on relay.trotters.cc,
approve the signer challenge, reconnect and retry a pending message.

## Verification

Permission tests cover exact URL/session binding, revocation, stale approvals,
disabled/removed relays and process restart. Existing RelayPool tests cover
challenge/ACK gating and refused or withdrawn authentication. UI tests cover
cancel, approve, withdraw, account-change dismissal and the disabled in-room
control. Build, emulator, CI and signed-release results are added after they
complete. Real Android/Linux peer delivery and physical GrapheneOS acceptance
remain pending until exercised on those devices.
