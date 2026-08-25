# KithMoot on real hardware

The first run on a physical device. Everything below was observed on a **Google
Pixel 10 Pro XL, Android 17, API 37, arm64-v8a**, serial `59041FDCQ005D3`, over
adb, with the debug build at `versionName=0.1.0`, `targetSdk=35`. Permissions
were **not** pre-granted, so the consent flow is the real one. Where something
was not observed, or was observed on a device somebody else was using at the
time, it says so.

## Verdict

| Claim | Proven |
|---|---|
| Launches and runs on API 37 without crashing | Yes — but the platform raises a 16 KB page-size warning, below |
| Real permission dialogs, camera / microphone / notifications | Yes — `58`–`59`, `63`–`64`, `66`–`67` |
| Room opens and relays connect over a real network | Yes — `57`, 2 of 2 relays up |
| **A real camera sensor**, front and back, and Flip | Yes — `60`, `61`, `62`; 24–30 fps |
| Screen share, **entire screen**, on API 37 | Yes — `70`; consent, service, projection, live frames |
| Screen share, **single app**, on API 37 | Yes — `77`–`79`; and the camera survives it |
| **No `SecurityException`** | Yes — `grep -ci` returned `0` after every cycle |
| Foreground service type at runtime | Yes — `types=0x000000E0` |
| Camera and screen together, one participant | Yes — `70`, `79` |
| Clean teardown | Yes — `80`; all four counters zero, cast indicator gone |
| Backgrounding **with a share** keeps camera and projection | Yes — `72`, `78`; 24 fps unbroken |
| Backgrounding **without a share** — what really happens | **The platform revokes the camera**, and the interface notices — `74` |
| No thermal throttling over a multi-minute share | Yes, with a caveat — see [Thermal](#8-thermal-and-battery) |
| **A hardware encoder** | **No** — with one device in the room, WebRTC never creates one |

One real defect was found that no emulator could have shown, and it is an
upstream one that cannot be fixed from this repository. Two previously
unexercised code paths were exercised for the first time, and both behaved.

## 1. The 16 KB page-size warning — a real forward-compatibility defect

On first launch the platform put up its own dialog before the app's first frame
(`55`):

> This app isn't 16 KB-compatible. ELF alignment check failed.
> The following libraries are not 16 KB-aligned:
> • `lib/arm64-v8a/libandroidx.graphics.path.so` : Unknown error
> • `lib/arm64-v8a/libjingle_peerconnection_so.so` : Unknown error
> • `lib/arm64-v8a/libsecp256k1-jni.so` : LOAD segment not aligned

**The platform's list is wider than the actual problem.** Parsing the program
headers of the three libraries out of the shipped APK:

| Library | `PT_LOAD` `p_align` | 16 KB-safe |
|---|---|---|
| `libandroidx.graphics.path.so` | `0x4000` | Yes |
| `libjingle_peerconnection_so.so` | `0x4000` | Yes |
| `libsecp256k1-jni.so` | `0x1000` | **No** |

Only libsecp256k1 is genuinely misaligned; for the other two the checker
reported "Unknown error" rather than a misalignment, and their segments really
are 16 KB-aligned. The APK's own packaging is fine as well — every `lib/` entry
is `Stored` and lands on a 16 KB-aligned file offset, which AGP 8.7.3 does for
us.

**Why it matters.** This device boots 4 KB pages
(`ro.boot.hardware.cpu.pagesize=4096`), so everything runs today. But its
hardware advertises `ro.product.cpu.pagesize.max=16384`. On a device booted with
16 KB pages, `libsecp256k1-jni.so` would fail to load — and that library does
every signature in the protocol, so the failure would not be cosmetic. It would
be total.

**Why it is not fixed here.** The library comes from
`fr.acinq.secp256k1:secp256k1-kmp-jni-android`, and this is not a version
problem: **0.17.3, the newest release, is still 4 KB-aligned**, on all four ABIs,
as is 0.16.0. Nor can the shipped binary be patched in place — its segments are
not congruent modulo 16 KB:

```
PT_LOAD  offset 0x0        vaddr 0x0        align 0x1000   congruent@16K: True
PT_LOAD  offset 0x146560   vaddr 0x147560   align 0x1000   congruent@16K: False
PT_LOAD  offset 0x146a40   vaddr 0x148a40   align 0x1000   congruent@16K: False
```

Raising `p_align` alone would produce a library the loader cannot map. The only
correct fix is to relink the native code with `-Wl,-z,max-page-size=16384`,
which means upstream, or vendoring a self-built JNI library. Rewriting the ELF
headers of the component that holds the signing keys is not a thing to do
quietly in a build script, so it was left alone and is reported instead.

**Recommended:** raise it with `acinq/secp256k1-kmp`, and treat 16 KB support as
a release blocker rather than a launch-time warning.

## 2. The real permission flow

All three follow the same shape — the app's own reason first, then Android's
dialog. Nothing here was pre-granted.

| Permission | App's reason | System dialog |
|---|---|---|
| Camera | `58` — "So the room can see you." | `59` — "Allow KithMoot to take pictures and record video?" |
| Microphone | `63` — "Only one of your devices has a live microphone at a time." | `64` — "Allow KithMoot to record audio?" |
| Notifications | `66` — "Without one the capture is killed within seconds." | `67` — "Allow KithMoot to send you notifications?" |

Camera and microphone were granted as **"While using the app"**, which is the
default a real user meets and which is what makes §7 interesting.

## 3. A real camera

Not a synthetic scene this time:

```
Camera2Session: Available preview sizes: [3264x2448, 3264x1836, 2560x1920, … ]
Camera2Session: Using capture format: 1280x720@[15.0:30.0]
Camera2Session: Using video stabilization.
Camera2Session: Using continuous video auto-focus.
CameraStatistics: Camera fps: 26.
```

24–30 fps sustained, against 2–12 fps on both emulators. `60` shows the tile
carrying a plastered wall, a copper pipe and a window — real optics, real
exposure, real focus.

**Front and back both work, and Flip round-trips.** The default is front:

- `60` — front camera (device 1), 24 fps.
- `61` — after Flip, device 0 at 28–30 fps. `dumpsys` shows
  `DISCONNECT device 1` then `CONNECT device 0`, with device 1 left closed and
  holding no client. The image is a flat grey field because the phone was lying
  face-down on a desk; it is a real frame, but a dull one.
- `62` — flipped back to front, wall and window again.

The platform names camera 1 `Facing: Front` and camera 0 `Facing: Back`
(`device@1.1/internal/1` and `/internal/0`), so the app's `frontFacing = true`
default is honoured on this hardware.

## 4. Screen share on API 37

**Android 17 changes the consent dialog again.** It is no longer the API 35
radio list; it is a dropdown — "Share one app" / "Share entire screen" — with
Cancel and Next (`68`, `69`). **"Share one app" is still the default**, so the
partial path remains the one most users will meet. Both were tested.

**Ordering, which is what the exercise exists to check:**

```
09:22:35.063 ActivityManager: Background started FGS: Allowed [dev.forgesworn.kithmoot … uidState: TOP]
09:22:35.217 MediaProjectionManagerService: Start the token instance …MediaProjection@c5d54fc
09:22:35.240 DisplayDeviceRepository: Display device added: "WebRTC_ScreenCapture" 540 x 1202 … owner dev.forgesworn.kithmoot
09:22:35.279 SurfaceTextureHelper: Setting listener to org.webrtc.ScreenCapturerAndroid@6e912b4
```

Foreground service first, projection 154 ms later, virtual display 23 ms after
that. **`logcat | grep -ci SecurityException` returned `0`** after every cycle in
this report. 540×1202 is the device's 1080×2404 halved, as `screenSize()` asks.

**Runtime state while sharing:**

```
isForeground=true foregroundId=4601 types=0x000000E0
    foregroundNoti=Notification(channel=kithmoot.screenshare … FOREGROUND_SERVICE)
startForegroundCount=1
dumpsys media_projection → (dev.forgesworn.kithmoot, uid=10213): TYPE_SCREEN_CAPTURE
```

`0xE0` is `mediaProjection | camera | microphone` — the Android 15 fix holds on
API 37. The service is not double-started.

Frames are real: `70` shows the shared pane containing a recursive copy of the
room three levels deep, each level a captured frame, beside the camera pane.
`71` is the shade — "Sharing your screen / Everyone in the room can see this
device's screen." — with Android's green camera indicator alongside it.

## 5. The single-app path keeps the camera

This is the path that exposed a real bug on API 35, where starting a partial
share while the camera was on killed the camera and left the button lit. On real
API 37 hardware, with the fix in place, it does not:

```
09:34:29.401 CameraStatistics: Camera fps: 24.
09:34:31.051 ActivityManager: Background started FGS: Allowed [dev.forgesworn.kithmoot … uidState: TOP]
09:34:31.104 DisplayDeviceRepository: Display device added: "WebRTC_ScreenCapture" …
09:34:33.403 CameraStatistics: Camera fps: 24.
09:34:35.404 CameraStatistics: Camera fps: 24.
```

No gap, no `ERROR_CAMERA_DISABLED`, no device-policy error. Calculator was the
chosen app and was `topResumedActivity` throughout, so KithMoot was genuinely
backgrounded (`78`, with the cast chip running). `79` shows the camera pane
still live on return.

The **Screen pane is black** in `79`. That is the platform's behaviour for
partial capture while the chosen app is off-screen, not a fault — same as on
API 35.

## 6. Camera and screen together, as one participant

`70`: one card headed **You**, a speaking border, two panes labelled **Screen**
and **Camera**, and the chips "Mic on this device" and "Sharing". Microphone,
camera and screen live on one device, as one person — not three participants.
`79` is the same thing on the partial-share path.

## 7. Backgrounding — and the answer to the open question

**With a share running, everything survives.** Home pressed with camera, mic and
an entire-screen share live; after 25 s the process was `fg-service-act`, the
projection and virtual display were still up, `types=0x000000E0`, and the camera
was still delivering 24 fps. `72` is the home screen with the cast chip at 02:12.

**Without a share, the platform takes the camera away.** This is what
`MEDIA-REPORT.md` flagged as untested and what the emulator got wrong — the
emulator kept delivering frames; real hardware does not. Camera on, no
foreground service, Home pressed, and about five seconds later:

```
09:31:56.301 CameraService: Camera 1: Access for "dev.forgesworn.kithmoot" has been restricted,
             isUidTrusted 0, isUidActive 1 isUidVisible 0, isCameraPrivacyEnabled 0 procState 15
09:31:56.309 E Camera2Session: Error: Camera device could not be opened due to a device policy.
```

**And the interface does not lie about it.** `74`: the Camera button is unlit,
the Flip control is gone, and the tile reads "Camera off". This is the
`onCameraLost` handler added during the Android 15 work, which that report had
to describe as "a reasoning-level change… not reachable… **was not exercised**".
It is reachable on real hardware, and it works.

**One incidental finding.** Backgrounding drops the relay sockets. On return the
header honestly reads "No relay reachable — nobody can see you yet" in red with
a red dot (`74`), and it recovers to "2 of 2 relays up" on its own within about
20 s (`75`). Correct behaviour, and worth knowing that the red state is
transient rather than a failure.

## 8. Thermal and battery

Over a 150 s window with camera, microphone and an entire-screen share all live:

| | Start | +150 s |
|---|---|---|
| Battery temperature | 32.7 °C | 33.6 °C |
| Thermal status | `0` (none) | `0` (none) |
| Battery level | 28 % | 30 % (charging) |
| Camera | 24 fps | 24 fps |

No throttling at any point, and the device was not warm to any degree the
sensors considered notable. Across the session the app held a screencast for
roughly eight minutes in total with no thermal event and no errors.

**Two caveats, and they matter.**

1. **No encoder was running.** See §9. This measures capture, virtual display
   and local rendering — not the encode path that a real call would add. Treat
   it as a floor, not as the thermal cost of a call.
2. **The device was in use during the window.** At 09:35:20–09:35:25, inside the
   soak, the user opened KithMoot's own share sheet and sent a 151-character
   `text/plain` over Bluetooth. My interference check only looked for launcher
   taps carrying icon bounds, so it missed an app-initiated chooser. The numbers
   above are therefore from a device that was being handled, which if anything
   loads it more, but the run was not the controlled one it was meant to be.

## 9. What a single device cannot prove

**The hardware encoder question is still open, and this is why.** With one
participant in the room there is no peer connection, so WebRTC never
instantiates a video encoder at all. Checked directly:

```
logcat | grep -Ei 'HardwareVideoEncoder|MediaCodecVideoEncoder|c2\.|OMX\.'  → nothing
logcat | grep -Ei 'PeerConnection|IceConnection|addTrack'                   → nothing
dumpsys media.metrics | grep -i kithmoot                                    → nothing
```

Capture is proven on real silicon; **encoding is not touched**. Nothing in this
report says what a hardware encoder does with a 15 fps screencast source, and
nothing in it can.

An API 35 emulator was booted and the APK installed on it to act as a second
participant, which would have engaged the encoder. It could not be completed:
joining needs the room's join link, the link lives in the fragment and is only
reachable through the clipboard, `cmd clipboard get` does not exist on this
build, and by then the phone was in active use by its owner — so driving its UI
further to extract the link was the wrong thing to do. The emulator was shut
down.

Also still unproven, for the same reason: **remote receipt on real hardware**,
and **screen-share quality through a real encoder**.

## Note on the device

This is a personal phone and it was in use during part of the session. One
teardown that first looked like a defect turned out to be a person tapping the
launcher icon at 09:27:39 (`START … cat=[LAUNCHER] … bnds=[799,1173][1059,1466]
from uid 10155 (com.android.launcher3)`) and then closing the room — the
platform logs that as `Content Recording: Stopped MediaProjection due to
foreground service change`, `stopReason=7`, which is the ordinary consequence of
`ScreenShareService.stop()`, not a fault. `73` is the state after it. Two of my
own later taps landed in an unrelated app the owner had opened; they hit empty
screen area and did nothing. Nothing was installed, uninstalled, reset or
reconfigured except our own app, which was left force-stopped so that nothing of
ours held the camera, microphone or a projection afterwards.

Two screenshots were redacted before being committed, because these are real
frames from a real phone: `79` has the owner's face pixelated in the camera pane,
and `77` has the recents previews pixelated where they showed personal accounts.
The unredacted originals were kept out of the repository.

## Tests

`./gradlew :protocol:test :app:testDebugUnitTest --rerun-tasks` —
**157 tests, 0 failures, 0 errors, 0 skipped**, counted from the JUnit XML rather
than read off the console. Unchanged; none of them cover `ScreenShareService`,
`LocalMedia` or `WebRtcEngine`, which need the native stack and a device.

## Screenshots

All in `docs/screenshots/`, prefixed `real-` to separate them from emulator runs.

| File | What it shows |
|---|---|
| `55-real-launch-api37.png` | The 16 KB page-size dialog, before the first frame |
| `56-real-start-screen.png` | Start screen on Android 17 |
| `57-real-room-joined.png` | Room open, 2 of 2 relays up on a real network |
| `58-real-camera-rationale.png` | The app's reason for the camera |
| `59-real-camera-system-dialog.png` | Android's real camera dialog |
| `60-real-camera-live-front.png` | **A real sensor** — front camera in the tile |
| `61-real-camera-flipped-to-back.png` | After Flip: back camera, device 0 |
| `62-real-camera-flipped-back-to-front.png` | Flipped back; the round trip is clean |
| `63-real-mic-rationale.png` | The app's reason for the microphone |
| `64-real-mic-system-dialog.png` | Android's real microphone dialog |
| `65-real-mic-and-camera-live.png` | Mic and camera live, speaking border |
| `66-real-notification-rationale.png` | The app's reason for notifications |
| `67-real-notification-system-dialog.png` | Android's real notification dialog |
| `68-real-mediaprojection-consent.png` | API 37's new dropdown consent dialog |
| `69-real-consent-dropdown.png` | "Share one app" / "Share entire screen" |
| `70-real-entire-screen-share-live.png` | Entire-screen share; recursive live frames, one participant |
| `71-real-foreground-notification.png` | "Sharing your screen", plus the camera indicator |
| `72-real-share-running-while-backgrounded.png` | Home screen, share still running |
| `73-real-state-after-unexpected-stop.png` | After the owner closed the room |
| `74-real-camera-revoked-in-background.png` | **Camera revoked by the platform; the interface says so** |
| `75-real-relay-after-backgrounding.png` | Relays reconnected on their own |
| `76-real-consent-single-app-default.png` | Consent defaulting to a single app |
| `77-real-single-app-picker.png` | The picker (previews redacted) |
| `78-real-partial-share-calculator-foreground.png` | Calculator in front, share running behind |
| `79-real-camera-survives-partial-share.png` | **Camera alive through a partial share** (face redacted) |
| `80-real-share-stopped-camera-mic-live.png` | Share stopped; camera and mic untouched, all counters zero |
