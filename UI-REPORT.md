# KithMoot Android — the interface

What was here before: a 13-line `MainActivity` rendering `Text("KithMoot")` with
no theme, so the title drew dark grey on the platform's dark ground and the app
launched to what looked like a blank screen. `ui/` was an empty directory. The
112 tests all passed because none of them touched a screen.

What is here now is a working two-screen application, verified on two emulators
in the same room over a relay.

## What was built

**Theme.** `ui/theme/` — a Material 3 scheme defined explicitly for both light
and dark, and a type scale roughly 1.2x Material's defaults with 15sp as the
smallest size in the interface. `onSurfaceVariant`, which Material sets to a
mid-grey and which is where interfaces usually give up on contrast, is a light
slate in dark and a dark slate in light. Nothing is set in a light weight.
Material's disabled-button default (the surface colour at 38%) is overridden
where it appears, because it is grey on grey.

**Start screen.** `ui/start/StartScreen.kt` — "Start a room", a field for a
join link, and the relay list folded away behind a disclosure.

**Room screen.** `ui/room/` — a grid of tile groups, one per **participant**.
`Tiles.kt` is a pure fold from the session's participant list to the tile model
and carries the product claim: all of one person's tracks, from all of their
devices, land in one card with one name. Screens sort before cameras; only the
microphone the room can actually hear is reported; your own card says *which*
of your devices is holding the mic.

**Controls.** Microphone, camera, camera flip, screen share, chat and add-device
in the bottom bar. Leave sits in the header instead, away from the media
toggles — hanging up is not a toggle and should not be a thumb's width from one.

**Chat.** `ui/room/ChatPane.kt` in a modal sheet, wired to `Chat`. Lines are
attributed to the person, not the device, so a message typed on your phone
appears as *you* on your laptop.

**Add a device.** `ui/room/AddDeviceSheet.kt`, with the warning first, in the
error colour, above the link: **"This link is you. Only ever send it to your own
device."**

**Pairing links.** `session/PairingLink.kt` (new, with tests). A second device
cannot be enrolled the obvious way round — `PrimaryIdentity.enrol` needs the new
device's pubkey before that device has said anything, and the protocol has no
rendezvous. So the first device mints the whole identity: a fresh device key and
a credential for it, both carried in the fragment. The format is a strict
superset of a join URL, so a client that knows nothing about pairing still reads
it as a perfectly good join link and joins as a stranger rather than failing
shut.

**Permissions.** `ui/Permissions.kt` — each permission is asked for at the
moment the thing it is for is asked for, with a sentence of rationale shown
before the system dialog, never at launch. Screen share asks for notifications
first (on API 33+), then goes through `MediaProjection` consent, then starts
`service/ScreenShareService` — foreground, type `mediaProjection`, persistent
notification — and **waits for the service to actually be foreground** before
creating the projection, because `startForegroundService` returns before that
happens and starting the capture inside that window is the race that produces a
`SecurityException`.

## Two bugs found and fixed while testing on the device

1. **Cleartext relays were silently blocked.** `targetSdk 35` refuses cleartext
   HTTP, so a `ws://` relay never connected and the room said "No relay
   reachable" with no explanation anywhere. Debug builds now carry a network
   security config permitting cleartext to `localhost` and `10.0.2.2` only;
   release builds still refuse it, which is right — a `ws://` relay shows a
   passive observer which room ids a device is talking about.

2. **A hot microphone after losing the claim.** When another of your devices
   took the microphone, this one kept capturing: the roster correctly stopped
   anyone hearing it, but the button stayed lit and the user believed they were
   being heard. The device that loses the claim now releases the hardware. The
   claim is also published *before* the track is created, or the device would
   see one of its own others still holding the role and shut itself straight
   back off.

A third thing was fixed after seeing it: room-opening did key generation, two
signatures and a NIP-44 encryption **on the main thread**, including the first
load of libsecp256k1. All of it — opening, leaving, and every control — now runs
on `Dispatchers.Default`.

## Verification

| Check | Result |
|---|---|
| `./gradlew :protocol:test :app:testDebugUnitTest` | **132 pass, 0 fail** (112 before, 20 added) |
| `./gradlew :app:assembleDebug` | builds, 51.8 MB debug APK |
| `./gradlew :app:installDebug` | installs on `emulator-5554` |
| Launch, screenshot, read it | renders; no `FATAL` and no ANR in logcat |
| Deep link `am start -a VIEW -d …` | opens straight into the room |
| Two devices, one participant | **one tile, "2 devices"** |
| Chat across devices | sent from one, arrives on the other, attributed to "You" |
| Microphone handover | claim moves; both devices agree with no coordinator |
| Screen capture | live, under a foreground service, `isForeground=true` |
| Public relays | "Start a room" reaches `relay.damus.io` and `nos.lol`, 2 of 2 up |

The 20 added tests are `ui/room/TilesTest` (8 — the grouping, screen-before-
camera ordering, stale-microphone filtering, stable order), `session/PairingLinkTest`
(8 — round trip, join-URL compatibility, fragment-only, adoption, wrong-room
refusal, rubbish) and `ui/RelayInputTest` (4).

## Screenshots

All in `docs/screenshots/`, taken from `emulator-5554` and `emulator-5556`.

| File | What it shows |
|---|---|
| `01-start.png` | Start screen, light |
| `03-room.png` | Room, one relay up, alone |
| `04-add-device.png` | The pairing sheet and its warning |
| `05-two-devices-one-tile.png` | **Two devices, one tile group, "2 devices"** |
| `06-second-device.png` | The same room from the second device |
| `07-chat.png`, `08-chat-received.png` | Chat sent from one device, received on the other |
| `09-mic-rationale.png`, `10-mic-system-prompt.png`, `11-mic-live.png` | Rationale before the system prompt, then a live mic |
| `12-mic-on-other-device.png` | The other device labelling where the mic is |
| `13-`, `14-mic-handover-*.png` | The microphone moving between devices |
| `15-notifications-rationale.png`, `16-mediaprojection-consent.png` | The screen-share consent chain |
| `17-screen-share-live.png` | Screen capture running — the recursive tile is the real thing |
| `18-room-dark.png`, `19-start-dark.png` | Both screens in dark |
| `20-mic-released-on-handover.png` | The fix: the control goes dark when the claim is lost |
| `21-started-room-default-relays.png` | A room opened here, on the public relays |

## What does not work, honestly

- **No peer media between the two emulators.** Each emulator sits behind its own
  NAT with only a public STUN configured and no TURN, so the WebRTC mesh never
  connected between them. Presence, chat and roles all went over the relay and
  all worked. Local capture works — the screen share renders its own capture
  back into the tile, which is how `17-screen-share-live.png` was taken. Camera
  capture was not exercised: the emulator's camera is a synthetic pattern and
  proves nothing about a real one.
- **Identity is not persisted.** Rejoining a room makes you a new participant,
  and a paired device is paired only until the app is killed. `store/` is still
  an empty package. This is the next thing worth doing.
- **The emulator wedged twice while running two at once** — three unrelated
  system processes ANR'd within seven seconds each time, including one report
  against this app for "Application does not have a focused window". On a freshly
  booted single emulator there are no ANRs at all. The main-thread crypto that
  could have contributed to it has been moved off the main thread regardless.
- **No instrumented tests.** The tile grouping is covered by unit tests and by
  the two-emulator run; nothing automated drives the interface.
