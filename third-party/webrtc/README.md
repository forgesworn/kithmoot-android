# Android WebRTC SDK

The app uses the WebRTC source revision and Android build configuration from
the `150.7871.04` SDK release. The pinned archive carries the app’s two Android
architectures. The archive fingerprint and upstream revisions are recorded in
`android.json`.

The receive engine can reject creation of a default video stream when the
same SSRC already belongs to a negotiated receive stream. The local patch
returns after that refusal, preserving the existing video sink. For an
unsignalled stream which can be registered, normal packet discovery continues.

The dependency release includes the build recipe and upstream notices and
patent grant. Run `python3 scripts/prepare-webrtc-android.py` before the
Android build. Pass the pinned archive’s local path to prepare it offline.

The recording emulator suite checks that the native sink continues delivering
frames after a browser reoffer, and that native video starts when the remote
offer omits SSRC attributes.
