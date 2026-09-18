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
