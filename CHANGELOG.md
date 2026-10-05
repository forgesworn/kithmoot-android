# Changelog

Release notes for the KithMoot Android app. Zapstore reads the section for the
version it publishes (`release_notes` in `zapstore.yaml`).

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
