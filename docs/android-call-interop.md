# Android and PWA private-room calls

The Android call engine sent a raw SDP candidate string inside SignalBody.candidate and treated received candidates the same way. The browser Peer sends JSON-encoded RTCIceCandidateInit and parses that JSON on receipt. Matching roster entries and local camera preview therefore did not establish a working call; Android could not correctly exchange trickled network candidates with a browser.

Android now emits the browser representation, retains sdpMid and sdpMLineIndex, and accepts both browser JSON and the earlier Android raw form. The encrypted signal envelope and room membership checks are unchanged. A failed negotiation is contained to its peer, and native connection callbacks feed the call state rather than treating local camera capture as connection proof.

CallAudioRouting owns MODE_IN_COMMUNICATION, transient voice-call audio focus and communication-device selection while native call audio is active. It prefers attached communication headsets and otherwise the built-in speaker. It clears the selection, releases focus and releases its audio mode when finished. This follows the [Android AudioManager communication routing API](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)). Physical Bluetooth and wired-headset changes still need device testing.

The participant's monitor role remains separate from their microphone role. When another device owns playback, Android displays Listen here to claim monitor explicitly. Own-device audio stays muted to avoid feedback. The PWA already renders its participant's other cameras; compatible media transport lets them arrive.

Call tiles show signed kind-0 names and pictures, with signed room names/contact names and npub fallback, and a selectable full npub. Public profiles are enabled by default in ordinary rooms and can be disabled in room settings; that preference persists. Anonymous rooms do not fetch public profiles. Pending cameras use profile placeholders and Connecting video until a frame actually arrives. Native connection failures show Video connection failed. The local camera and another own-device camera have separate labels.

## Validation

Prepare the actual browser Peer used by native instrumentation (Node 24):

    node scripts/prepare-browser-call-interop.mjs /path/to/kithmoot

The generated bundle and source SHA are in app/build/browser-call-interop, an Android test-only assets directory. BrowserCallInteropTest runs that source inside Chromium WebView against real libwebrtc PeerLink/WebRtcPeerConnection. It checks both polite/impolite negotiation orderings, decoded video in both directions, audio RTP in both directions, positive decoded energy from a synthetic browser oscillator, communication mode/speaker selection and mode release. It refuses physical-device hardware. Synthetic video and emulator microphone input are used; no personal account or room is involved.

CallIdentityUiTest checks profile name, short/selectable full npub, the pending-video placeholder and explicit monitor handoff. ChatAndShareUiTest exercises actual native video rendering and picture-in-picture. PeerLink JVM tests cover browser JSON arriving before SDP, media-section preservation, invalid JSON and legacy raw candidates.

Emulator RTP/decoding and routing proof is not physical acceptance of the Pixel/iPhone/Alex call across the internet. Repeat that exact private-room call after installing the signed candidate. This change does not add an Android TURN credential service; the remote browser's TURN/direct-path behaviour remains relevant to networks which block direct UDP.

## 16 September follow-up: device video and TURN

Android update 2026-09-16: final unsigned SHA256 cb4af002d5fe15464ac7b6e140c6f5420e68ce4f96c31345d1d8f98b68f99d55

Listen here stays visible with Listening on this phone status. Adds hosted PWA
TURN defaults, binds remote video via negotiated media sections while retaining
callback-owned tracks, and logs connection packet/frame counts without SDP,
addresses, credentials or message contents. Existing room/account/UI fixes kept.

391 unit tests, release build and lint pass. Four final emulator tests pass:
current browser Peer versus native, both negotiation directions, camera restart,
forced TURN with decoded video and bidirectional audio RTP/positive tone energy,
and call identity/Listen UI. Tests use synthetic media only.

Physical Pixel call was reported working before this update. This build has not
been physically accepted and requires owner-local production signing on M4.
Web sibling-camera release 20260916T100810Z is separate and already live.

## All-way rotation

MainActivity is now `android:screenOrientation="fullUser"`, so the phone can
reach all four rotations (portrait, both landscapes, reverse portrait), and
the system's own rotation lock is honoured: nobody sees an unwanted flip who
has that switch off. The activity already keeps `configChanges` for
orientation and is not recreated on a turn, so Compose and the call engine
just see a new frame shape rather than a restart.

Camera frames come from libwebrtc's own Camera2 capturer, which reads the
display rotation to stamp each frame's rotation metadata; nothing in this app
overrides that. The background-replacement compositor
(`media/effects/FrameCompositor.kt`, `Compositing.kt`) turns every camera
frame upright before it draws the sea, the reef and the person, and always
emits rotation zero, so the segmentation mask, the backdrop artwork and the
composited output line up in all four orientations, both for the local
preview and for what the room receives. `CompositingTest` covers the
rotation/working-size arithmetic for 0, 90, 180 and 270 explicitly, including
that all four cost the same number of pixels. The front-camera mirror and the
self-view/remote `SurfaceViewRenderer` tiles rotate the same way, since both
read the frame's own rotation rather than assuming portrait.

QR scanning (`ui/qr/QrScanner.kt`) uses CameraX's own display-rotation
tracking for `ImageAnalysis`, so scanning keeps working turned any way.

Not proven on a physical handset in this change: the four rotations have not
been photographed on a real device, only exercised through the JVM-testable
geometry and read through the capture/render code paths.
