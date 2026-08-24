# Camera capture and MediaProjection screen share

What was tested, on what, and what the evidence actually was. Everything below
was observed on `emulator-5554` (API 33, arm64-v8a, emulated cameras enabled)
driven over adb. Where something could not be observed it says so.

## Verdict

| Claim | Proven |
|---|---|
| Camera capture delivers frames | Yes — logcat frame counters and live video in the tile |
| Camera track is published to the roster | Yes — the tile renders from roster tracks, not from the local handle |
| MediaProjection consent dialog appears | Yes — screenshotted |
| Screen capture runs under a `mediaProjection` foreground service | Yes — service, notification and virtual display all observed |
| Stopping the share tears everything down | Yes — four ways of stopping it, all clean |
| Camera and screen share together, as one participant | Yes — one card, two panes, one name |
| No crashes, no leaked capturers, no double-started service | Yes, on this emulator, across the cycles listed below |
| Android 14/15 ordering enforcement | **No** — no API 34+ system image is installed. Code-verified only |

Two real defects were found in the teardown path and fixed. Neither was
reachable in a single-device room, which is why nothing crashed on the way to
finding them.

## 1. Camera capture

Started a room, tapped Camera. `CAMERA` was already granted, so the rationale
dialog was correctly skipped.

The capturer opened and delivered frames:

```
Camera2Session: Using capture format: 1280x720@[7.0:30.0]
Camera2Session: Opening camera 1
Camera2Session: Camera device successfully started.
CameraCapturer: Create session done. Switch state: IDLE
CameraStatistics: Camera fps: 11.
CameraStatistics: Camera fps: 12.
```

`CameraStatistics` is the WebRTC capturer counting frames it has actually
handed to the source — it is not printed unless frames arrive. The rate sat
between 2 and 12 fps for the rest of the session; the emulator is slow, not the
capture path.

`23-camera-live.png` shows the tile carrying live video where
`22-room-before-camera.png` showed the "Camera off" placeholder, and the Flip
control appearing.

**The tile is roster-driven, which is the useful part.** `ParticipantTileView`
renders `tile.videos`, and `buildTiles` builds that from
`Participant.liveTracks` — the roster the room published and read back. A local
`VideoTrack` that had never been announced would leave the placeholder in place.
Video in the tile therefore also proves the camera track was published.

**Honest limit:** the emulator's camera is a synthetic animated scene (the green
blocks in the screenshots), not a real one. Frames flow and the pipeline works;
nothing here says anything about exposure, orientation, or front/back selection
on real hardware.

## 2. MediaProjection screen share

Tapping Share brought up the system consent dialog — `24-mediaprojection-consent.png`,
"Start recording or casting with KithMoot?". Accepting it produced, in order:

```
ActivityTaskManager: START u0 {cmp=com.android.systemui/.media.MediaProjectionPermissionActivity}
DisplayDeviceRepository: Display device added: DisplayDeviceInfo{"WebRTC_ScreenCapture" …
    540 x 1110 … owner dev.forgesworn.kithmoot (uid 10180) … type VIRTUAL}
SurfaceTextureHelper: Setting listener to org.webrtc.ScreenCapturerAndroid@62e37b4
```

540×1110 is the device's 1080×2220 halved, which is what `LocalMedia.screenSize()`
asks for.

State while sharing:

```
dumpsys activity services  → isForeground=true foregroundId=4601
                             foregroundNoti=Notification(channel=kithmoot.screenshare …)
dumpsys media_projection   → (dev.forgesworn.kithmoot, uid=10180): TYPE_SCREEN_CAPTURE
dumpsys display            → WebRTC_ScreenCapture virtual display present
dumpsys notification       → id=4601, channel kithmoot.screenshare, flags 0x62 (ongoing)
```

`27-screen-share-foreground-notification.png` is the shade showing "Sharing your
screen / Everyone in the room can see this devi…".

Frames are genuinely being captured: the tile renders the capture of the screen
it is on, so the shared pane contains a recursive copy of the room, several
levels deep. Each level is a real captured frame — see `25-camera-and-screen-share.png`.

**Foreground service type.** API 33's `dumpsys` does not print
`foregroundServiceType`, so the *runtime* type could not be read back on this
emulator. What is established: the manifest declares
`android:foregroundServiceType="mediaProjection"` and the
`FOREGROUND_SERVICE_MEDIA_PROJECTION` permission; `ScreenShareService` passes
`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` to `ServiceCompat.startForeground`;
and the service was observed foreground with the projection live.

**Backgrounding.** With the share running, Home was pressed. The process dropped
to `fg-service-act` and the projection stayed up for the 14 s it was watched —
which is exactly the job the foreground service exists to do. Returning to the
app resumed rendering (`28-share-survived-backgrounding.png`).

### Stopping

Four ways, all clean — after each one, `fgs:0 proj:0 vdisp:0 notif:0`:

1. **Tapping Share again.** `26-share-stopped-camera-still-live.png` — the Sharing
   chip and the Screen pane go, the camera pane stays.
2. **Declining consent.** `29-share-declined.png` — "Screen sharing needs
   Android's permission. Nothing was shared." No service was started at all.
3. **The system stopping it.** The Screen Cast quick-settings tile (which listed
   KithMoot as the caster) was tapped. The projection was revoked and the app
   noticed: `MediaProjection.Callback.onStop` → `onScreenShareStopped` →
   `stopScreenShare()`. `33-system-stopped-share.png` shows the app back to the
   placeholder with Share unlit, and the foreground service gone. Logcat carries
   one benign `MediaProjectionManagerService: Attempted to stop inactive
   MediaProjection` — the app tidying up a projection the system had already
   killed.
4. **Leaving the room mid-share.** `32-after-leave-teardown.png` — projection,
   service, notification, virtual display and both camera devices all released.

## 3. Both at once, as one participant

`31-mic-camera-screen-one-participant.png`: microphone, camera and screen share
all live on one device. One card, headed **You**, with a speaking border, two
panes labelled **Screen** and **Camera**, and the chips "Mic on this device" and
"Sharing". Not two participants — one, with two video panes. That is the
product's whole claim, collapsed onto a single device.

## 4. Cycling, crashes and leaks

- Camera toggled on/off 8 times. `dumpsys media.camera` reported
  "Device 1 is closed, no client instance" after every off; no client was ever
  left holding the camera.
- Screen share taken through 3 full consent→share→stop cycles, plus 3 more
  inside full room cycles. Every cycle: consent dialog appeared, service went
  foreground, projection and virtual display appeared, and all four counters
  went to zero on stop. `startForegroundCount=1` each time — the service is not
  double-started.
- 5 complete room cycles (start room → mic → camera → share → leave). No
  `FATAL EXCEPTION`, no `ANR in`, no `SecurityException`, and the process
  survived all of them.
- Memory: an apparent +1 thread and +1.7 MB native per room cycle turned out to
  be OkHttp's idle-timeout threads and un-trimmed heap. After a 90 s settle the
  process was at 30 threads and 18.3 MB native — below where it started. No
  accumulating leak.

## What was fixed

Both defects are in `app/src/main/kotlin/dev/forgesworn/kithmoot/media/WebRtcEngine.kt`.

**1. Peer connections outlived the factory that made them.** `stop()` closed the
links inside `scope.launch { mutex.withLock { … } }`. Every caller
(`RoomViewModel.closeSession`) then called `dispose()` synchronously and
cancelled that same scope a few lines later — so the coroutine was cancelled
before it ever ran, `PeerConnectionFactory.dispose()` was called with live
`PeerConnection`s, and the connections (with their ICE transports and sockets)
were leaked. `links` is now guarded by a plain monitor instead of a coroutine
`Mutex`, so `closeLinks()` runs synchronously and finishes before the factory
goes. `reconcile` and `linkFor` lose their `suspend` — everything they did was
already synchronous.

**2. The audio device module was never released.** `JavaAudioDeviceModule` is not
disposed by `PeerConnectionFactory.dispose()`; it owns an `AudioRecord`, an
`AudioTrack` and their threads, and the caller has to hand it back. It is now
held as a field and `release()`d in `dispose()`, after the factory.

Neither defect is reachable in a room with no remote device — `links` is empty
and the leak is nil — which is why nothing here crashed before the fix and why
the fix cannot be exercised on one emulator either. It is a reasoning-level fix,
verified only to the extent that it does not regress the single-device paths:
rebuilt, reinstalled, and the whole sequence above re-run (`34-post-fix-all-live.png`),
plus two further full room cycles, all clean.

## Tests

`./gradlew :protocol:test :app:testDebugUnitTest` — **132 tests, 0 failures,
0 skipped.** Unchanged; `WebRtcEngine` has no unit tests (it needs the native
stack), so the fix is not covered by them.

## Still unproven

- **Android 14/15 ordering.** On API 34+ the `mediaProjection` foreground service
  must be running before the projection is created, and the failure is a
  `SecurityException`. `targetSdk` is 35, so this matters. Only `android-33`
  system images are installed, so it could not be run. Reading the code, the
  order is right: consent intent → result → `startForegroundService` → wait on
  `ScreenShareService.running` (5 s timeout) → `startScreenShare(permission)`.
  It wants an API 34 or 35 emulator, or a real device, before anyone calls it
  proven.
- **A real camera.** Synthetic emulator scene only.
- **Background camera.** With the camera on and no share running, Home was
  pressed; this emulator kept delivering frames throughout. Android 12+ can
  revoke camera access from a backgrounded app with no foreground service, and a
  physical device may well behave differently — in which case the Camera button
  would stay lit with nothing behind it. Untested.
- **Screen-share quality on real hardware.** Nothing here says anything about
  what a hardware encoder does with a 15 fps screencast source.
- **Remote receipt.** Only one device was in the room, so nothing proves a peer
  actually *receives* these tracks. That path was covered by the earlier
  two-emulator work, not by this.

## Screenshots

All in `docs/screenshots/`.

| File | What it shows |
|---|---|
| `22-room-before-camera.png` | Room open, camera off, placeholder tile |
| `23-camera-live.png` | Camera on, live frames in the tile |
| `24-mediaprojection-consent.png` | The system consent dialog |
| `25-camera-and-screen-share.png` | Screen and camera panes in one card |
| `26-share-stopped-camera-still-live.png` | Share stopped by the app, camera untouched |
| `27-screen-share-foreground-notification.png` | The foreground service notification |
| `28-share-survived-backgrounding.png` | Both tracks alive after Home and back |
| `29-share-declined.png` | Consent refused, nothing started |
| `30-camera-after-backgrounding.png` | Camera rendering again after a round trip |
| `31-mic-camera-screen-one-participant.png` | Mic, camera and screen, one person |
| `32-after-leave-teardown.png` | Left mid-share; everything released |
| `33-system-stopped-share.png` | The system revoked the projection; the app noticed |
| `34-post-fix-all-live.png` | Same sequence after the teardown fix |
