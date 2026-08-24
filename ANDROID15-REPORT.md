# Screen share on Android 15

The one thing `MEDIA-REPORT.md` could not check. Everything below was observed on
`emulator-5554` — **Android 15, API 35, arm64-v8a**, 1080×2220, two emulated
cameras — driven over adb, with the app at `targetSdk = 35`. Where something was
not observed it says so.

## Verdict

| Claim | Proven |
|---|---|
| Launches and joins a room on Android 15 | Yes — `35`, `36`, 2 of 2 relays up |
| Screen share works end to end | Yes — consent, service, projection, live frames |
| **No `SecurityException`** | Yes — zero across every run in this report |
| Foreground service carries the `mediaProjection` type **at runtime** | Yes — `types=0x00000020` read back from `dumpsys` |
| Consent → service → projection ordering is right | Yes — logged, in that order, 55 ms apart |
| Camera capture works on API 35 | Yes — `CameraStatistics` and live frames in the tile |
| Camera and screen share together, as one participant | Yes — `40`, `54`: one card, two panes, one name |
| Clean teardown, service and projection released | Yes — four counters at zero, cast indicator gone |
| Android 14+ partial ("single app") share | Works, and **exposed a real defect**, now fixed |

The ordering the whole exercise existed to test was already correct. Exercising
it on API 35 nonetheless found a genuine Android 14+ defect next door to it,
described in [What was broken](#what-was-broken).

## 1. The ordering question, settled

`ScreenShareService.start()` → wait on `ScreenShareService.running` → then
`startScreenShare(permission)`. On API 35 the platform agrees:

```
19:26:26.458 ActivityManager: Background started FGS: Allowed [dev.forgesworn.kithmoot … targetSdkVersion:35]
19:26:26.507 MediaProjectionManagerService: Start the token instance …MediaProjection@4f019b
19:26:26.513 DisplayDeviceRepository: Display device added: "WebRTC_ScreenCapture" 540 x 1110 … owner dev.forgesworn.kithmoot
19:26:26.542 org.webrtc.Logging: SurfaceTextureHelper: Setting listener to org.webrtc.ScreenCapturerAndroid@b989b8d
```

Foreground service first, projection 49 ms later, virtual display 6 ms after
that. **No `SecurityException` anywhere** — `logcat | grep -ic SecurityException`
returned `0` after every cycle in this report, including the ones that failed for
other reasons.

**The runtime type, which API 33 could not show.** `MEDIA-REPORT.md` had to fall
back on the manifest because API 33's `dumpsys` does not print
`foregroundServiceType`. API 35 does:

```
isForeground=true foregroundId=4601 types=0x00000020
    foregroundNoti=Notification(channel=kithmoot.screenshare … FOREGROUND_SERVICE)
startForegroundCount=1
```

`0x20` is `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`. That gap is closed.

Alongside it: `dumpsys media_projection` → `(dev.forgesworn.kithmoot, uid=10207):
TYPE_SCREEN_CAPTURE`, and a `WebRTC_ScreenCapture` virtual display at 540×1110,
which is the device's 1080×2220 halved as `LocalMedia.screenSize()` asks.

Frames are real: the tile renders the screen it is on, so the shared pane is a
recursive copy of the room seven levels deep (`38`). Each level is a captured
frame.

## 2. Android 14+ changes the consent dialog

The dialog is no longer a yes/no. It offers **"A single app"** or **"Entire
screen"**, and **"A single app" is the default** (`37`). Tapping Start on the
default opens an app picker (`43`). Most users will meet this path, not the
full-screen one, so both were tested.

Both work. Both produce a `mediaProjection` foreground service and a live
projection. Two things are worth knowing about the partial path:

- While the chosen app is not on screen, the Screen pane is **black** — the
  projection mirrors that app's task, and there is nothing to mirror. This is the
  platform's behaviour for partial capture, not the app's.
- Choosing **KithMoot itself** in the picker returns no projection at all. The
  app treats it as a decline: no service started, no crash, back to the room.
  Tried twice, on precise item bounds, with the same result.

## 3. What was broken

**Starting a single-app share while the camera was on killed the camera, and the
interface went on claiming it was live.** Reproduced twice, deterministically.

The picker launches the chosen app, which backgrounds KithMoot. The activity
result is only delivered when KithMoot resumes, so the foreground service starts
at that moment — and the camera dies within 300 ms:

```
19:39:42.548 CameraStatistics: Camera fps: 3.          ← camera healthy
19:39:43.323 ActivityManager: Background started FGS: Allowed [dev.forgesworn.kithmoot …]
19:39:43.578 E Camera2Session: Error: Camera device could not be opened due to a device policy.
             CameraCapturer: Stop capture done
```

`topResumedActivity` was `com.android.settings/.Settings` throughout — the app
was in the background. From Android 14, a backgrounded process keeps the camera
only while a foreground service *claims the `camera` type*. The service claimed
`mediaProjection` and nothing else, so the platform revoked the camera with
`ERROR_CAMERA_DISABLED`, reported as a device policy failure.

`45` is the result: Camera button lit, Camera pane black, camera gone.

This is not an emulator curiosity. It is the ordinary shape of the feature — you
share your screen *precisely* so you can leave the app and show something — and
on any Android 14+ device it would take the camera with it.

Two controls separate the cause from the noise:

- Camera on, then **entire-screen** share: camera survives (11–12 fps straight
  through the FGS start, `46`). The full-screen path never backgrounds the app.
- Camera on, app backgrounded by Settings, **no** FGS: camera survives. Plain
  backgrounding was not the trigger; the `mediaProjection`-only FGS was.

### The fix

Two commits' worth of change in one, across four files.

**1. The service claims what it is actually holding.** `ScreenShareService` now
starts foreground as `mediaProjection | camera | microphone`, and the manifest
declares those types and the `FOREGROUND_SERVICE_CAMERA` /
`FOREGROUND_SERVICE_MICROPHONE` permissions.

The mask is built at runtime from the permissions actually granted, not
hardcoded: naming a type the app has no runtime permission for is itself a
`SecurityException` on Android 14+, and a screen share with no camera is
perfectly ordinary. Verified both ways —

| Permissions | `types=` |
|---|---|
| Camera + microphone granted | `0x000000E0` (`0x20｜0x40｜0x80`) |
| Both revoked | `0x00000020`, share still starts, no exception (`53`) |

**2. The interface stops lying about a camera it has lost.** `LocalMedia` now
passes a `CameraVideoCapturer.CameraEventsHandler` instead of `null`; on
`onCameraError` or `onCameraDisconnected` it calls back to `RoomViewModel`, which
releases the capturer and says so. Only those two events are acted on — a freeze
is a complaint about frame rate, and a close arrives on an ordinary stop too. The
callback is handed to `act { }` rather than run inline, because it arrives on the
capturer's own thread and releasing the capturer from there would wait on that
same thread.

This second half is belt-and-braces: with the type fix in place the camera is not
taken away any more, so the path is not reachable on this emulator and **was not
exercised**. It is a reasoning-level change, verified only in that it does not
regress the camera paths, which were re-run in full afterwards.

**After the fix**, the identical single-app sequence keeps the camera:

```
19:43:29.452 ActivityManager: Background started FGS: Allowed …
19:43:31.634 CameraStatistics: Camera fps: 12.
19:43:39.648 CameraStatistics: Camera fps: 12.
```

`47` — camera pane live where `45` had it black, app backgrounded behind
Settings, `types=0x000000E0`, no camera error.

## 4. Camera, and both at once

Camera capture works on API 35: `Camera2Session: Camera device successfully
started`, then `CameraStatistics` counting 2–12 fps for the rest of the session.
`CameraStatistics` is not printed unless frames actually reach the source.

`40` and `54`: microphone, camera and screen share all live on one device — one
card headed **You**, a speaking border, two panes labelled **Screen** and
**Camera**, chips "Mic on this device" and "Sharing". One participant, not three.

**Honest limit:** the emulator's camera is a synthetic animated scene — the green
house in the screenshots — not a real one. Frames flow and the pipeline works;
nothing here says anything about exposure, orientation or front/back selection on
real hardware.

## 5. Teardown

**Stopping the share** (`42`): the Sharing chip and Screen pane go, camera and
mic untouched, and all four counters reach zero —

```
service records: 0   projections: 0   virtual displays: 0   notifications: 0
```

The status-bar cast indicator disappears with them.

**Leaving the room mid-share** (`50`): same four at zero, plus
`dumpsys media.camera` → `Device 0 is closed, no client instance` /
`Device 1 is closed, no client instance`. Back to the start screen, status bar
clean.

**Declining consent** (`48`): "Screen sharing needs Android's permission. Nothing
was shared." No service is started at all.

One incidental confirmation: destroying the activity while a room is live runs
`onCleared` → `closeSession()`, which closed the camera and released the
projection and service. No orphaned capture.

## 6. Android 15 specifics

- **Notification permission.** With `POST_NOTIFICATIONS` revoked, tapping Share
  shows the app's own rationale first (`51`), then the system prompt. Refusing
  gives "Without the notification, Android will stop the share." and **starts
  nothing** — no consent dialog, no service, no projection (`52`). That is a
  deliberate product choice rather than a platform requirement; whether a
  `mediaProjection` service would in fact have started without the permission was
  not tested, because the app declines to try.
- **Privacy indicators.** Android 15's green camera chip appears in the status
  bar while the camera runs, alongside the cast icon during a share (`41`).
- **Foreground service restrictions.** The service start was logged as
  `uidState: TOP … code:PROC_STATE_TOP` even on the partial-share path — the
  system app picker runs inside the requesting app's task, so the UID is still
  TOP when the result lands. That is what makes claiming the `camera` type legal
  there; a genuine background start of a `camera`-typed service on Android 15
  would not be.
- One benign log line, `ForegroundServiceTypeLoggerModule: Foreground service
  start for UID: 10207 does not have any types`, is emitted before the types are
  applied. `dumpsys` shows the types set correctly a moment later.

## Tests

`./gradlew :protocol:test :app:testDebugUnitTest --rerun-tasks` —
**132 tests, 0 failures, 0 errors, 0 skipped.** Unchanged in count; none of them
cover `ScreenShareService` or `LocalMedia`, which need the native stack and a
device.

## Still unproven

- **A real device.** Everything here is one emulator. The camera is synthetic,
  the encoder is software, and the frame rates (2–12 fps) are the emulator, not
  the capture path.
- **The `onCameraLost` path.** Not reachable once the type fix is in, so the new
  handler's behaviour was never observed firing. See above.
- **Remote receipt.** One device in the room throughout, so nothing here proves a
  peer *receives* these tracks. That was covered by the earlier two-emulator work.
- **Screen-share quality on hardware.** Nothing here says what a hardware encoder
  does with a 15 fps screencast source.
- **A camera lost to another app.** Not tested; the eviction path a second app
  would trigger is the same `onCameraDisconnected` callback, but that is
  reasoning, not evidence.

## Screenshots

All in `docs/screenshots/`.

| File | What it shows |
|---|---|
| `35-api35-launch.png` | Start screen on Android 15 |
| `36-api35-room-joined.png` | Room open, 2 of 2 relays up |
| `37-api35-consent-partial-share.png` | The Android 14+ dialog, defaulting to "A single app" |
| `38-api35-screen-share-live.png` | Entire-screen share, recursive live frames |
| `39-api35-camera-and-screen-one-participant.png` | Screen and camera, one card |
| `40-api35-mic-camera-screen-one-participant.png` | Mic, camera and screen, one person |
| `41-api35-foreground-notification.png` | "Sharing your screen", plus the privacy chip |
| `42-api35-share-stopped-camera-mic-live.png` | Share stopped; camera and mic untouched |
| `43-api35-single-app-picker.png` | The single-app picker |
| `44-api35-partial-share-settings-foreground.png` | Settings in the foreground, share running behind it |
| `45-api35-camera-died-button-still-lit.png` | **The defect**: camera dead, button still lit |
| `46-api35-camera-then-entire-screen-share.png` | Control: entire-screen share leaves the camera alone |
| `47-api35-post-fix-camera-survives-partial-share.png` | **After the fix**: camera live through a partial share |
| `48-api35-consent-declined.png` | Consent refused, nothing started |
| `49-api35-all-three-live-post-fix.png` | All three tracks after the fix |
| `50-api35-after-leave-teardown.png` | Left mid-share; everything released |
| `51-api35-notification-rationale.png` | The app's own reason, before the system prompt |
| `52-api35-notifications-refused.png` | Notifications refused; no share attempted |
| `53-api35-share-only-no-camera-permission.png` | Share with camera and mic revoked; `types=0x20` |
| `54-api35-final-smoke-all-three.png` | Final smoke test on the shipped build |
