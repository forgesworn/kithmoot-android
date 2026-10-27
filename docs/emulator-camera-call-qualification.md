# Camera lifecycle and call endurance on emulators

Use a disposable emulator and matching app/instrumentation APKs. These checks
provide repeatable capture, model, recovery and resource evidence. Physical
GrapheneOS acceptance, real-person cut-out quality, acoustic echo, battery,
thermal behaviour and real network handovers remain separate.

## Build and run

Prepare the three pinned native components as described in [AGENTS.md](../AGENTS.md).
Use JDK21 and the Android35 SDK, then bundle the browser implementation being
qualified and record its `app/build/browser-call-interop/source.json`:

```sh
node scripts/prepare-browser-call-interop.mjs ../kithmoot
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Start a disposable API35 ARM64 emulator with both cameras enabled. The camera
checks require compatible OpenGL support even though model inference uses CPU.
On the M1 host, use `-gpu swiftshader -camera-front emulated -camera-back emulated`.
Install both APKs, verify their bytecode, and unlock the emulator:

```sh
export ANDROID_SERIAL=emulator-5564
"$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" shell cmd package compile -m verify -f dev.forgesworn.kithmoot
"$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" shell cmd package compile -m verify -f dev.forgesworn.kithmoot.test
"$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_WAKEUP
"$ANDROID_HOME/platform-tools/adb" -s "$ANDROID_SERIAL" shell wm dismiss-keyguard
python3 scripts/check-camera-call-emulator.py camera --adb "$ANDROID_HOME/platform-tools/adb" --serial "$ANDROID_SERIAL" --reports build/qualification/camera
python3 scripts/check-camera-call-emulator.py call --adb "$ANDROID_HOME/platform-tools/adb" --serial "$ANDROID_SERIAL" --reports build/qualification/call --seconds 2700
```

The runner refuses physical devices, requires completed boot, rejects skipped
tests and process crashes, and checks the pulled completion report. It never
installs or clears the app. Use `--seconds 125` for a preliminary call run that
includes every recovery action; it does not qualify the 45-minute duration.
Stop and delete only the emulator/AVD created for the task after retaining its
reports. No physical phone is needed for this procedure.

## What is asserted

`CameraLifecycleProfileTest` keeps a foreground activity, compares raw capture
with sea/fish output, and runs three hide/resume, front/back switch and camera
stop/restart cycles. Hidden or stopped capture must publish zero frames. Every
resumed phase must deliver frames. It then profiles 30 warmed, detached640x360
portrait frames, separating inference from total composition. Those timings
exclude camera texture readback and are not handset performance predictions.

`BundledSegmentationDeviceTest` verifies the actual bundled model, known
foreground/background regions, input ownership, no-op logging and covered
output for invalid masks. Its startup-failure regression feeds60 frames through
one failed model factory: it must be called once, with every output still
covered. After a session reset, exactly one further attempt is permitted.

The endurance case uses native WebRTC and the actual bundled browser `Peer`
inside Chromium WebView. Generated video and440/660Hz tones cross real RTP in
both directions, using local test signalling. Every30-second window must make
audio-packet and decoded-video progress in both directions. The same connection
cycles through steady traffic, native video mute/resume, browser camera
replacement and ICE restart. The browser accumulates per-stats-object deltas
because ICE/SSRC changes can reset RTP counters. Each window records Java/native
heap usage and resolves the replacement camera to its live receiver.

This is media-engine endurance. It does not cover room admission/relay outage,
TURN/NAT traversal, actual microphone interruptions, background service behaviour,
screen sharing, mobile-network changes or audible continuity between real users.

## Emulator failures found during qualification

On an Apple M4 host, MediaPipe0.10.35 can crash at `rdsvl` in its XNNPACK CPU
backend because the guest advertises SME instructions it cannot execute. The
observed fault was offset`0x95d754` in `libmediapipe_tasks_jni.so`; disassembly
matched the [upstream report](https://github.com/google-ai-edge/mediapipe/issues/6293).
Keep that failure as evidence; use a compatible host to exercise segmentation.
Do not downgrade the reviewed SDK or count a skipped model test as a pass.

The M1 emulator's host graphics mode also rejected model graph construction
with `GL_INVALID_ENUM`. The previous per-frame retry loop attempted221 native
graph constructions before Android killed the app under memory pressure.
Caching the unavailable model bounds that failure path until a session reset
while retaining the synthetic background. Software graphics permits the real
model checks on this host.
