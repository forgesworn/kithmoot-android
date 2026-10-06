# Changelog

Release notes for the KithMoot Android app. Zapstore reads the section for the
version it publishes (`release_notes` in `zapstore.yaml`).

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
