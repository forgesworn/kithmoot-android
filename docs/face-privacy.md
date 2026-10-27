# Camera effects and privacy

Updated 26 October 2026. Real-camera GrapheneOS acceptance is recorded separately
from build, emulator and synthetic-frame evidence.

## Background replacement

The owner requested restoring Google's bundled on-device segmentation while
keeping tracking removed. MediaPipe's reviewed SDK and model cut the person
out over the chosen sea or dark fill; animated reef fish remain behind them.
The model is packaged in the APK and requires no Google Play service, account
or runtime download. The original datatransport exclusions and no-op transport
implementations remain mandatory. See [the integration and release gates](effects-no-telemetry.md)
and the distributed `assets/models/README.md` and Apache-2.0 licence.

A choice is remembered on this device and applied before camera capture starts.
The initial default is the dark background; explicit Off remains Off. Off is
the only raw-camera pass-through state. Missing/failed segmentation covers the
whole picture with the chosen backdrop and fish, or drops the frame if drawing
fails. Segmentation is an estimate and can still include background pixels at
edges, in low light or during fast movement.

## Face privacy

Avatar replaces every camera pixel without reading the camera texture or
running segmentation. Its synthetic picture uses the same processed VideoSource
as previews, peers and camera recordings. The sensor stays on locally; screen
sharing is a separate track. Avatar hides the sea/fish controls' effect while
selected. Voice and screen sharing can still identify a person.

Tracked face masking remains unavailable. No face-detection model was restored.
A previously saved mask choice remains covered if detections are missing or
invalid. The separate owned-vision experiments and unqualified weights are not
used as the production background provider by this change.

## Tracking and platform scope

Google datatransport reporting is excluded from both the restored camera SDK
and the existing bundled ML Kit QR scanner. Do not describe the app as having
no Google-origin code: Android, AndroidX, WebRTC and these bundled models all
include it. The acceptance target remains a physical GrapheneOS device without
sandboxed Google Play. AOSP/Google-APIs emulator tests do not replace that check.
