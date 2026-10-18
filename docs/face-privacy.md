# Camera privacy and owned vision

Version 0.6.91 candidate, 18 October 2026. Physical acceptance remains open.

The original full-picture masked Avatar is independent of ML. Camera effects
remember the local choice before capture starts. Avatar avoids texture
readback, detection and segmentation; its synthetic picture reaches the same
processed VideoSource used by previews, peers and camera recordings.
The camera sensor stays on locally. Screen sharing is a separate track.

Tracked mask is unavailable until our own learned model is trained and
qualified. The compositor accepts every detected face and covers the entire
picture on missing/invalid detections. A saved mask preference therefore
remains covered even though the option is disabled. Choice/generation checks
under the publication lock reject frames from older worker jobs, including
changes away and back.

MediaPipe camera code, dependency and Google background weights are removed.
A chosen backdrop now replaces the whole picture, pending owned foreground
matting. Controls say this explicitly. Keep the existing ML Kit QR
telemetry exclusions, no-op transport stubs and release verification: those
remain relevant independently of camera models. This is not a claim that
Android, WebRTC or the rest of the application contain no Google-origin code.

The MIT training library lives in the sibling KithMoot repository:
[kithmoot/vision](../../kithmoot/vision/README.md). It defines an original
multiple-face/foreground neural model, offline training and evaluation,
licence/provenance manifests and bounded personal matte adaptation.
It includes an Android ARM64 cross-built Rust inference core, a shared C ABI
and WASM SIMD build, but no qualified weights, integrated JNI camera adapter
or Android local-training product flow.
Targets include people, hands and held objects. Empty-room and occupied
recordings are optional local adaptation inputs, not a substitute for broad
training or automatic ground-truth labels. The manual patch tracker and its
setup UI were removed from this draft.

The original geometric mask is generated from KithMoot's MIT artwork source
by scripts/build-face-mask.mjs in the sibling web repository. No stock,
film/comic or third-party mask asset is included.

Focused JVM tests cover geometry, preferences and publication races.
Instrumented synthetic-frame tests exercise real Canvas/vector/YUV paths;
they do not establish real-face inference. Final device tests, physical calls,
held objects, several people, dim light, source changes, remote recordings,
battery and thermal costs remain open. Do not describe this branch as a
finished automatic face masking or background matting release.
