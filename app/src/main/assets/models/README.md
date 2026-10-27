# Bundled Selfie Segmenter

Google MediaPipe Selfie Segmenter (square, float16, model revision 1), restored
from the previously reviewed KithMoot asset. It is distributed with this APK;
the application never downloads it when effects are selected.

- Source: https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/1/selfie_segmenter.tflite
- File: `selfie_segmenter.tflite`, 249,537 bytes.
- SHA-256: `191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b`.
- Input: 256×256 RGB. Confidence categories: background first, person second
  when two masks are emitted; a single mask is foreground.
- Licence: Apache License 2.0; the full text is distributed beside this file
  as `LICENSE`. MediaPipe and its model assets are Copyright Google LLC /
  The MediaPipe Authors. No endorsement is implied.
- Runtime: `com.google.mediapipe:tasks-vision:0.10.35`, Apache-2.0.

[Model overview](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter)
and [Android API](https://developers.google.com/edge/mediapipe/solutions/vision/image_segmenter/android).

Stock MediaPipe reports usage through Google datatransport. KithMoot excludes
that dependency group and substitutes local no-op transports for both camera
and QR consumers. See `docs/effects-no-telemetry.md` in the source distribution.
The release guard pins the runtime pair and this exact model digest; minified
release tests exercise inference, bitmap ownership and the actual logger path.
