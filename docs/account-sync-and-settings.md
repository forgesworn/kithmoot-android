# Account sync and settings

The avatar in the top-right app bar is the account entry point on Chats,
Projects and in a room. It opens profile editing, relay settings, account sync
and sign-out. Signing out from a room first asks to leave it and discard its
unsent draft; saved room access remains on the device.

## Conversation layout

Inside a room, the room title and account avatar share one header. The title
opens room details: people, invitations, additional devices, public-profile
preferences, relay counts, quiet scheduling and the Bothy history comparison.
These controls no longer occupy the top of every conversation. Search opens
from the header and returns to Chat when selected from Work or Call.

Messages use incoming/outgoing bubbles with names at the start of a sender's
group, compact times, date separators and the recorded delivery lane. Tap or
hold a message for its identity, delivery detail and reaction controls; only
existing reactions appear beneath it. The composer includes the emoji picker.
The compact encryption/relay label opens the full privacy explanation.

`RoomWorkspaceUiTest` checks that messages occupy over 60 percent of the room
screen with search closed, diagnostics remain accessible in room details and
drafts/search/scroll position survive opening call controls without starting
media. Checks use the Pixel's 1080x2404 display and density 390 on an emulator.

## Chats and rooms across devices

Android uses the same `kithmoot.rooms.v1` kind-30078 records as the browser.
Names and invitation links are NIP-44 encrypted to the account. Public tags
contain a random address, not a room ID. Records are merged per room using
whole-second timestamps and the lowest event ID for ties. Encrypted removals
survive stale relay replay. Exact signed pending publications are committed
to a separate account-scoped Android Keystore vault before sending; retry
after restart reuses the same event.

Signing in restores bookmarks from configured read relays. Account rooms not
already saved on the phone appear under **From your other devices** in Chats.
Opening one obtains room admission and verifies the admitted room ID before
creating a saved room or starting the session. The same account must remain
active throughout that operation. Pairing links are rejected; bookmarks
cannot enrol a device or navigate to an external website.

Newly opened account rooms are added to the account automatically. Existing
phone rooms can be explicitly added from Chats. Guest, anonymous, secondary,
retired and moved-on rooms are excluded from that import. The browser's
existing **Add rooms** flow is still needed for browser-only visitor history.

Bookmarks do not copy device credentials or a message archive. Persistent
group invitations use retained signed admission records; temporary meetings
may require another member online. Messages are loaded through the existing
room protocol, within its retention and access limits. Relay availability
and epoch changes can restrict recovery. A successful project sync is not a
receipt for room sync or complete message history.

## Public profile editing

The editor fetches the account's latest verified kind-0 metadata before
allowing publication. It retains unknown fields from other clients while
editing username, display name, about, picture/banner URLs, website, NIP-05
and Lightning address. Picture/banner/website inputs require HTTPS.

Publication rechecks the profile head to catch changes from another device,
checks that the signer returned the exact requested event, and waits for a
write-relay acknowledgement. An uncertain send can be retried with the same
signed event during this app session. Acknowledgement is not proof that every
other Nostr client has refreshed. The UI explicitly identifies these changes
as public; opening the editor never publishes.

## Relay choices and evidence

Relays have independent read/write checkboxes; clearing both disables one.
The list requires at least one read relay and one write relay. Choices are
stored for the account on this device. Saving reconnects room-bookmark and
project sync. Saved rooms retain their invitation URLs; matching relay
read/write choices apply when those rooms reopen. Active room connections
must be left before changing the settings. Signer/bunker transport is managed
separately from content relays.

The panel reports socket connection state, read refusals/authentication
requirements, end-of-history receipts, write acceptance/refusal and missing
write acknowledgements. **Connected** only describes the socket. A read
receipt and a write receipt are separate evidence. No unsolicited test event
is published merely to show a green indicator.

The optional **Publish public relay list** action publishes kind 10002 with
the [NIP-65](https://github.com/nostr-protocol/nips/blob/master/65.md) `r` tags
and read/write markers after an explicit publication confirmation. Ordinary
**Save relay choices** remains local. Disabled relays are not published.

## Checks

- `:app:testDebugUnitTest` includes encrypted bookmark replay, restart/retry,
  wrong-account isolation, failed storage, pairing-link rejection,
  public-profile field preservation and directional relay transport tests.
- `scripts/verify-room-bookmarks-web.mjs /path/to/kithmoot --prepare`
  regenerates the committed synthetic fixture using the actual browser
  writer. `RoomBookmarksTest` consumes it and emits an Android fixture;
  running the script without `--prepare` verifies that the actual browser
  reader accepts Android saves and removals.
- `AccountExperienceUiTest` uses disposable emulator storage, a synthetic
  external-signer-shaped account and a loopback relay. It exercises account
  restoration without the room/bookmark/epoch caches, room admission and
  retained message recovery, account-menu navigation, profile publication,
  relay status and sign-out from a room. It refuses physical devices.

The Pixel's production installation requires a production-signed update.
Debug/emulator checks alone do not prove recovery of a real account on that
Pixel, and uninstalling it is not an upgrade path.

### Composer and conversation actions follow-up

Conversation rows expose an explicit Open button. Their overflow menu contains rename, project, Bothy and removal actions as applicable. Removing the synced bookmark and removing local access use separate confirmation text.

RoomState copy updates use StateFlow.update: incoming chat/presence, notices and media updates must not overwrite a concurrent send completion with a stale chatSending value. Typing and emoji selection remain available while a send awaits a relay receipt; Send remains disabled until the current attempt completes. The confirmation wait is described in words beside the composer.

The account activity regression exercises repeated relay echoes followed by acknowledgements, a withheld acknowledgement through the existing 75-second timeout, preservation of the next typed draft and successful sending afterwards. It also covers opening from both list types and cancelling their separate removal confirmations.
