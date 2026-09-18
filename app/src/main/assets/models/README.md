# selfie_segmenter.tflite

What it is, where it came from, and what it is allowed to be shipped under.

## The file

| | |
|---|---|
| File | `selfie_segmenter.tflite` |
| Size | 249,537 bytes (244 KiB) |
| SHA-256 | `191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b` |
| Input | 256x256 RGB |
| Output | two confidence masks at 256x256, background first and person second |

It is the MediaPipe **Selfie Segmenter** (general, square) model, published by
Google for the MediaPipe Image Segmenter task. This is byte-for-byte the same
file the web client serves from `models/selfie_segmenter.tflite`, so the two
clients cut a person out of a frame with the same model and disagree only where
their pipelines do.

## Licence

**Apache License 2.0**, the licence MediaPipe and its published model assets
are released under. A copy is at <https://www.apache.org/licenses/LICENSE-2.0>.
The runtime that loads it, `com.google.mediapipe:tasks-vision`, is Apache 2.0
as well - its own POM says so.

## Why it is bundled rather than fetched

Every MediaPipe example downloads the model from Google's CDN on first use.
Doing that here would mean that switching a background on - in an application
whose whole claim is that no operator can see you - tells a third party your IP
address and that you are about to join a call. 244 KiB in the APK buys that
back, and it also means the feature works on a phone with no route to Google at
all.

## If it is ever replaced

Keep the size and the digest above in step with the file, and check the output
shape first: the pipeline reads the **last** confidence mask as the person (see
`media/effects/PersonSegmenter.kt`). A model that emits one mask, or emits them
the other way round, cuts the room out and leaves the person behind.

## The model doesn't call home; the runtime it loaded under used to

The bundled `.tflite` is a local file with no network behaviour of its own.
But every released version of `com.google.mediapipe:tasks-core` (checked
0.10.0 through 0.10.35, and the older `0.1.0-alpha-*` and `1.0.0` releases;
there is no clean one) ships a `TasksStatsProtoLogger` that unconditionally
reports segmenter usage - session start/end, invocation counts, latency - to
Google. It does this through `com.google.android.datatransport`
(`transport-api` + `transport-backend-cct` + `transport-runtime`), tagged log
source `COREML_ON_DEVICE_SOLUTIONS`, via Google's CCT backend. Decompiled
`TasksStatsLoggerFactory.create()` and the call sites in `TaskRunner` across
several versions all call `TasksStatsProtoLogger.create(...)` directly; there
is no `BaseOptions` flag, system property, or older/newer release that
switches it to the dummy logger MediaPipe also ships
(`TasksStatsDummyLogger`) instead.

Since no upstream version is clean, `app/build.gradle.kts` excludes the
`com.google.android.datatransport` group from the `tasks-vision` dependency
and supplies tiny no-op replacements for the exact classes MediaPipe's
compiled `RemoteLoggingClient` needs to link and run - see
`app/src/main/kotlin/com/google/android/datatransport/**/*Stub.kt`. The
segmenter runs exactly as before; the logger it builds now sends its events
nowhere.

**This turned out not to be MediaPipe-only.** `play-services-mlkit-barcode-
scanning` (the QR sign-in/pairing decoder) independently depends on an older
`com.google.android.datatransport` (2.x) for its own usage-telemetry client
(decompiled `com.google.android.gms.internal.mlkit_vision_barcode.zzwt`
.. `zzwx`) and puts the same `TransportBackendDiscovery` /
`JobInfoSchedulerService` / `AlarmManagerSchedulerBroadcastReceiver`
components in the merged manifest. Android dexing puts every dependency's
classes in one flat namespace, so it is not possible to give MediaPipe no-op
datatransport classes while ML Kit keeps the real ones under the same fully
qualified names: `./gradlew :app:assembleRelease` failed with
`DexArchiveMergerException: Type com.google.android.datatransport.Event is
defined multiple times` until ML Kit's copy was excluded too. Its call
pattern is the same handful of entry points (`TransportRuntime.initialize`/
`getInstance`/`newFactory`, `TransportFactory.getTransport`,
`Transport.send`, plus `Event.ofTelemetry` and
`CCTDestination.getSupportedEncodings`, both absent from MediaPipe's own
usage), so the stub file covers both.

`app/build.gradle.kts` excludes `com.google.android.datatransport` from both
`libs.mediapipe.tasks.vision` and `libs.mlkit.barcode.scanning`.
`verifyNoDatatransportTelemetry` (wired into `./gradlew check`) fails the
build if either dependency edge reopens, or if the merged manifest ever
declares those components again. Confirmed clean: `./gradlew
:app:dependencies --configuration releaseRuntimeClasspath` shows no
`com.google.android.datatransport` or `com.google.firebase:firebase-encoders*`
entries (both were only ever pulled in transitively by the two excluded
edges), and the merged release manifest has none of the three forbidden
components. `./gradlew :app:assembleRelease` (unsigned) succeeds; the QR
sign-in path itself is unverified without a device.
