## Unreleased

- Send any file, not only images: **Add a file** (+) beside the message box, then **Add photo or file**, up to 256 MiB as the web and desktop clients allow. The file is encrypted on the phone a megabyte at a time and only the sealed envelope is uploaded, with the same storage consent and the same limits on anonymous and Tor-only rooms as before. **Stop upload** cancels one in progress.
- Show a received file that is not a picture as a compact item with its name, download size and type. Tapping it offers **Save**, to a place you choose, or **Open with** another app. Nothing downloads until you choose, the file is checked against the message before it is opened, and its name is cleaned so it cannot disguise its extension. Android app packages can only be saved. Pictures still open in the image viewer.
- Read a shared file's details exactly as the web client does, now checked against every published `chatAttachment` vector and a file the web client wrote.

## 0.6.93

- A calmer chat screen, in step with the web and desktop apps: one palette and type scale on the logo colours, messages grouped into runs by sender, reactions under the bubble instead of over it, an outlined "Agent" label beside agents' names, and a one-line composer (Add a file, the field with Emoji inside it, Send) whose controls share one centre line at every text size.
- Stronger outlines and muted text when Android's high contrast text setting is on.
- The room's back arrow is labelled "Back to chats". It always kept the room saved; Leave room is in Room details.
- Open an invitation to a room sent from the web client. It no longer fails as a private conversation.

## 0.6.92

- Use WebRTC's own echo canceller and noise suppressor instead of the phone's. On many phones the built-in canceller let a loudspeaker call echo back to listeners as heavy reverb.
- Roll back 0.6.90's quieter automatic replies while calls that lost sound and screen shares are investigated. Repeated and stale call signals, epoch recovery and keeper admission are answered at once again.

## 0.6.91

- Add an original full-picture masked avatar, remembered before capture starts, and prepare multi-face mask compositing with stale-worker publication fencing. Automatic face masking remains unavailable pending our own qualified model.
- Remove MediaPipe camera effects and Google background weights. A chosen backdrop replaces the whole picture until the owned foreground model is ready.

## 0.6.90

- Automatic replies no longer give away when the phone is in use. A repeated call signal is answered after a short random wait, at most once every two seconds per device, and stale ones the same way.
- A room's key desk answers after a short random wait, and refuses a removed member once an hour rather than every time. In a room closing with more than 16 people, some late arrivals see the room stop answering rather than a "closed" notice.

## 0.6.89

- Keep a relay's read and write choices across a restart when no account is signed in. Read-only and write-only relays no longer come back as both, and an unticked relay no longer disappears.

## 0.6.88

- Open a tapped KithMoot join link straight in the app rather than the browser, on kithmoot.forgesworn.dev and kithmoot.app.
- Hold a call tile's picture through a missed heartbeat instead of flashing black.
- A red record button and one compact recording row.

## 0.6.87

- Move a relay up or down in Settings. Order decides which relays a room link carries when it can name only some.
- Say when a relay accepts a stored message and then does not return it; Settings counts that relay as needing attention.
- Keep a refused relay read on show while another read on the same relay is answered.
- Describe a refused write by its reason, such as rate limiting or authentication, rather than the relay's own words.
- Keep reading older keeper requests from a relay that serves short pages.

## 0.6.86

- Find a Signet contact and start a private conversation without sharing a room first.
- Discover encrypted private invitations when returning later, including while the sender is offline.
- Reopen the same conversation for chat, calls and collaboration; explicitly open or dismiss invitations.
- Retain failed invitations for retry and check current account and blocks before delivery.
- Keep existing joined-room background calls and messages; first-contact invitations are read while the account is open.

# Changelog

Release notes for the KithMoot Android app. Zapstore reads the section for the
version it publishes (`release_notes` in `zapstore.yaml`).

## [0.6.76] - 2026-10-09

- Call recording warnings name the recorder and say whether they selected
  audio, gallery video, speaker video or a screen share with camera. People
  joining later see the same signed notice. Older or incomplete notices retain
  the broader audio/video warning.
- Recording details belong to the room, recorder and exact recording notice;
  unrelated or altered details cannot change the warning. Your active call
  device advertises support so a web recorder can include its camera safely.

Recording and exporting a clip from the Android app are not available yet.
Recordings saved or shared by another person survive a temporary room ending.

## [0.6.65] - 2026-10-08

For people using the optional VMLS rooms preview on their own Bothy box:

- Report another of your own devices as lost or compromised. This phone
  starts its removal where the room can change and asks the keeper to
  revoke its box access immediately.
- Keepers can review, defer or decline requests. An approved request revokes
  that device's grants at the keeper's boxes without the usual removal grace,
  including after the requesting phone has left the room.
- Sent means a relay accepted the request, not that the keeper has acted.
  Publishing the request does not authenticate as the member to the relay.

The VMLS preview remains off by default. Requests need a verified keeper
identity and the keeper's DM relay list. Delivery and keeper action depend
on the relay and the keeper opening the app with their signer.

## [0.6.64] - 2026-10-08

For people trying the VMLS rooms preview on their own Bothy box (off until
turned on in Settings, Connections); nothing else changes.

- When the box has had its hourly share of changes to members or keys from
  this phone, the room says "waiting on the box" and that the change goes out
  by itself within the hour, rather than showing "Sending" with no reason.
- A removal the room's engine refuses for good is shown as stopped, with
  "Try the Remove again"; refusals that clear by themselves wait instead.
- Removing a device as compromised, or closing a room, without your signer
  now says to sign in again, rather than doing nothing.

## [0.6.63] - 2026-10-08

- Rooms can self-destruct. A new room with an end date offers
  "Self-destruct" or "Keep it read-only", and shows a green, amber and red
  countdown. When a self-destructing room ends, this phone leaves it, asks
  relays to delete its own messages there, and wipes what it kept of the
  room, even while the app is closed if background ringing or delivery is
  on (otherwise at the next start). A room joined from a web link now takes
  its name from the link.
- Settings is now a list of pages: your account, notifications and calls,
  display, privacy, connections and updates, side by side on a tablet or
  a wide screen.
- For people who run their own Bothy box: VMLS rooms and the restore
  witness, as a preview, off until turned on in Settings, Connections.
  They need a box that offers VMLS, which no Bothy release does yet; while
  the preview is off, none of it shows and nothing contacts a box.
- Settings show what is really happening: with notifications blocked, the
  notification and background options read off and say why, and signing
  out asks first.
- Security: the connection library is updated (rustls 0.23.45).

## [0.6.62] - 2026-10-06

- On a bad connection you can keep writing. Messages that cannot send yet
  wait at the end of the chat as your own, saying whether they are pending,
  sending, turned down or not yet confirmed, and go by themselves, oldest
  first, once a relay answers. Before, a second message was refused until
  the first had gone.
- A message that has not left your phone can be edited or deleted, and
  nobody ever sees the first version. One that may already have arrived can
  only be taken off the list, and says so.
- Rooms saved to your account reach your other devices even when a relay
  has lost them: the phone puts back what the relays dropped.

## [0.6.61] - 2026-10-06

- The app is about a quarter of the size to download: 38 MB instead of 142 MB.
  It no longer carries a second copy of its native code for Intel processors,
  which no phone that can install it uses, and its code is compressed inside
  the download.

## [0.6.60] - 2026-10-06

- When a room's key changes on its regular schedule, nothing is announced in
  the chat. A key change that removes someone is still announced.
- Notifications and call rings keep arriving for rooms you have not opened
  after a room's key changes: the phone follows the change in the
  background, using the copy sealed to it.
- A phone that joins a room, or comes back to one after a while, reads the
  room's last month, up to 16 key changes back, where it used to read 4.

## [0.6.59] - 2026-10-06

- A call no longer starts by itself. After a call ended, a phone could start
  a new one on its own - just after Leave, or while its relays were catching
  up - and everyone in the room was rung "… is calling" with nobody calling.
  Now only pressing Join starts a call; otherwise the phone rejoins the call
  it was on.
- A new call in a room you were on a call in less than a minute ago does not
  ring. It still shows in the room. This keeps phones quiet if someone on an
  older version starts a call by accident.

## [0.6.58] - 2026-10-05

- The rooms list is easier to scan when you have a lot of rooms. Rooms are
  grouped into Pinned, Recent and Older, with ended rooms at the end;
  Older and Ended fold away behind a count until you open them. With eight
  rooms or fewer it stays one list.
- Pin a room from its ⋯ menu to keep it at the top. Pins stay on this phone,
  and forgetting a room removes its pin.
- Each room has a coloured initial, and rows are shorter with no divider lines.
- Search, Projects and Open invite link are at the top of the list. Opening
  an invite link and signing in are in the ⋮ menu, rather than under your
  last room.

## [0.6.57] - 2026-10-05

- Forgetting a room now erases its keys from this phone: the current room key,
  the past keys it kept to bring other people's devices up to date, and the
  list of who the room knows. Reset saved rooms erases them for every room.
  Keys left behind by rooms you forgot on earlier versions are erased the
  first time this version opens.
- Stopping a Bothy's quiet cadence for a room no longer fails for up to an
  hour when the phone's clock is slightly behind the Bothy's.

## [0.6.56] - 2026-10-04

- Uses less battery in the background. Rooms that use the same relays now
  share one connection while the app is closed, so the phone keeps far fewer
  connections open and wakes its radio less often.
- A room left open behind other apps for 5 minutes is handed to the background
  service, the same as closing it. Others see you leave the room, but calls
  still ring and messages still arrive as notifications. Coming back to
  KithMoot reopens the room. A call, screen share, recording or unsent message
  keeps the room open.

## [0.6.55] - 2026-10-04

- Run a call as a meeting from your phone. In a room you made, Run as a meeting
  in the call's More menu turns meeting mode on and off and lists everybody,
  raised hands first, so you can make people speakers or stop them. Only
  speakers can talk or show video, on every phone, computer and browser.

## [0.6.54] - 2026-10-04

- KithMoot can now update itself if you installed it from the downloads page.
  It checks for a new version every few hours while open, and installs one only
  if it matches a release manifest signed with a key built into the app, at the
  exact size and fingerprint that manifest gives, and signed by the same key as
  the app you already have. Updates wait until you are off a call. If you
  installed KithMoot from Zapstore, it tells you an update is ready and opens
  Zapstore to install it. Settings has a Check for updates button and a switch
  for automatic checks.

## [0.6.53] - 2026-10-04

- Meeting mode, as on the web and desktop. When the person who made a room runs
  its call as a meeting, only the speakers they choose can talk or show video.
  Everybody else's microphone, camera and screen share stay off, with Raise
  your hand to ask to speak, and your phone does not play or show anybody who
  is not a speaker, whatever their app sends.
- When a call is being recorded, a red notice says so in the room, and
  KithMoot asks before you join the call. You can stay in the room and read
  the chat without joining.
- Links in chat messages can be tapped.

## [0.6.52] - 2026-10-03

- Once a room has removed somebody, its key goes only to people it knows, so a
  removed person stays out.

## [0.6.51] - 2026-10-03

- When you start a call on another of your devices, your phone shows a quiet
  notice with Join from this phone, instead of staying silent.
