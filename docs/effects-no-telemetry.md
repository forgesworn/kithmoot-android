# Bundled camera segmentation without usage reporting

The owner authorised restoring Google's on-device background segmentation on
26 October 2026. A person should remain visible over the chosen sea scene and
animated reef fish. GrapheneOS without Google Play remains the phone target.

## Exact integration

- `com.google.mediapipe:tasks-vision:0.10.35` and its matching `tasks-core`.
- Bundled square Selfie Segmenter: 249,537 bytes, SHA-256
  `191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b`.
- CPU inference on the camera worker, through `MediaPipeSegmenter`. No runtime
  model download, account, Play service or hosted inference path is used.
- The model and runtime are Apache-2.0 components. The model provenance and full
  licence are distributed in `assets/models/`; existing fish/scene artwork is unchanged.

[Google's Android guide](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter/android)
documents the asset-backed API. This does **not** mean the stock SDK is free of
usage reporting: its compiled `TasksStatsLoggerFactory` constructs
`TasksStatsProtoLogger`, whose `RemoteLoggingClient` sends performance and
usage events through Google datatransport.

## Removing the reporting path

Both MediaPipe and the existing bundled ML Kit QR scanner exclude the entire
`com.google.android.datatransport` dependency group. The app's same-name local
transport classes satisfy those libraries' binary API calls as no-ops.
`Event` retains no payload, `TransportRuntime.initialize` does nothing,
`newFactory` returns `NoOpTransportFactory`, and `NoOpTransport.send` does
nothing. They contain no sockets, persistence, jobs, backend or upload queue.
The pinned `RemoteLoggingClient` bytecode's transport field resolves to that
local implementation. Do not restore a real transport library to fix a link error.

`verifyNoDatatransportTelemetry` rejects the real transport group, its manifest
services/receiver, any deviation from the reviewed MediaPipe SDK pair, and a
changed/missing bundled model. The task is required by `check`. A version bump
must include a fresh logging/dependency review and matching release tests.
R8 keeps MediaPipe's JNI surface, the local transport API, WebRTC and JNI Zero.

`BundledSegmentationDeviceTest` exercises repeated real model inference on the
same borrowed bitmap and repeated timestamps, checks the model hash, inspects
the actual MediaPipe logger's no-op transport, and rejects real telemetry
backend classes. Both API33/35 release signing-lineage jobs run it against the
minified APK. StrictMode rejects Java network access on the inference thread;
this narrow check alone is not a whole-application network audit.

## Frame safety and fish

An enabled background never takes the raw-frame pass-through route. A missing,
failed or invalid mask produces the complete synthetic backdrop, including
fish when selected; a failed composite is dropped. The person is overlaid only
when segmentation succeeds. Avatar remains fully synthetic and takes precedence.
Camera stop/restart, facing and orientation changes invalidate older results and
reset model state. Fish rendering does not depend on model startup or mask success.
Reduced motion suppresses reef animation.

Segmentation can still misclassify hair, held objects or background pixels;
the Effects sheet explains that it is an estimate, not guaranteed concealment.
Do not turn a synthetic/emulator pass into a real-camera quality claim.

## Review on the physical phone

Build/install the matching minified release and its instrumentation APK without
uninstalling the application. On a physical GrapheneOS phone without Google
Play, run the bundled-segmentation, reef and existing privacy tests. Then open
Effects in a disposable call, choose each sea and the dark fill, move hands/head,
flip cameras, toggle Fish swimming past, background/resume, and check Off/Avatar.
The participant should remain visible, tank scenes should replace the room,
fish should move behind them, and disabling fish should retain the cut-out.
Retain local screenshots/timings and owner observations. Synthetic tests do not
establish real-person cut-out quality or sustained-call performance.
