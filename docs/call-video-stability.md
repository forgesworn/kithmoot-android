# Audio activity layout stability

The audio meter reserves two text lines for every state. On narrow phones,
`Incoming audio: Waiting` wraps where `Incoming audio: Sound` fits on one line;
allowing that height to change resizes the neighbouring native video surface.
Idle traces are white, and sound uses the theme accent. The dark plot backing
preserves contrast in light and dark themes. Written states and reduced-motion
level bars remain available.

This is a local rendering change. It adds no capture, transmission, storage,
telemetry or service dependency.

Review the chat, full call and call dock on a narrow phone, with enlarged text
as well. Alternate waiting, silence, speech, microphone mute and off; the
video bounds must stay constant. Check light/dark themes and reduced motion.
`AudioActivityUiTest.changing_audio_states_does_not_move_the_video_on_a_narrow_phone`
covers the height transition in a Compose fixture. Physical GrapheneOS and a
simultaneous Android/desktop/web call remain separate acceptance checks.

Related browser fixes and regression cases are in the KithMoot repository's
`docs/call-video-stability.md`, branch `fix/call-video-stability-20261116`.

## Verification on 16 November 2026

- `:app:testDebugUnitTest`: 3,571 passed, one skipped, no failures.
- Main Kotlin and Java compilation passed.
- `:app:compileDebugAndroidTestKotlin`: passed, including the new narrow-phone
  bounds regression. Instrumentation was compiled, not run: no device was connected.
- Combined Gradle check completed successfully. No APK was published or installed.

Logs and JUnit results are retained with the browser evidence at
`~/.codex/artifacts/kithmoot-call-video-20261116T120714Z`.
