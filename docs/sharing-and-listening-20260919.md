# Android sharing and listening, 19 September 2026

Candidate: 0.6.8 (31), based on published 0.6.7 (`ddb9b84`). Work lives in an
isolated checkout; the existing rendezvous branch and its edits were preserved.

## Evidence

- Real TypeScript FSWNENC2 fixtures open in Kotlin, including a file spanning
  multiple authenticated records. Bad hash, key, tag and truncation are refused.
- Microphone-off buffers are overwritten with silence, including unsupported
  input configurations; audio sums saturate rather than wrap.
- Rapid paired listening handovers and screen-track publication are tested.
- Connected drawing segments leave before finger-up and retain their final point.
- A disposable Android 35 emulator passed the Compose chat/expanded-video test:
  live synthetic frames, fit, zoom, pan, drawing before finger-up, picture in
  picture and close.
- Android MediaProjection captured a 440 Hz tone from a separate synthetic app
  UID into the WebRTC audio-input callback while the microphone control stayed
  off. Stopping screen sharing stopped capture and removed local tracks.
- The image UI fetched a synthetic web-encrypted PNG over HTTPS, verified and
  decrypted it, showed the expected two colour panels, fitted and zoomed it,
  and closed the viewer when the message was retracted.

The emulator used a suffixed debug package (`dev.forgesworn.kithmoot.sharingtest`)
so no installed account was touched. Pixel 10 Pro XL was initially visible on
M4 with production 0.6.6 (29), then disconnected; the owner confirmed it is in
use for a call. No phone installation or physical capture acceptance occurred.

## Remaining release gates

Use the existing production certificate and lineage, verify the exact APK,
then perform an in-place Pixel update only once the call is finished. Check
speech plus captured playback with a remote listener, microphone mute while
playback continues, repeated start/stop, system revocation, listening-device
handover with web/Mac, full-size images and sustained cross-client drawing.
CI, an unsigned build, emulator capture, signing and physical acceptance are
separate claims. The public download remains 0.6.7 until publication is verified.
