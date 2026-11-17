# Audio activity layout stability

The chat audio meter reserves two text lines for every state. On narrow phones,
`Incoming audio: Waiting` wraps where `Incoming audio: Sound` fits on one line;
allowing that height to change resizes the neighbouring native video surface.
Idle traces are white, and sound uses the theme accent. The dark plot backing
preserves contrast in light and dark themes. Written states and reduced-motion
level bars remain available.

In the full call and visible call gallery, each person's name and microphone
trace share a fixed-width footer inside their video tile. The self-preview is
slightly larger. The separate audio panel and floating speaker line no longer
compete with the bottom controls. Audio samples update only the strip, without
rebuilding the video surface. Quiet is distinct from muted; only actual mute
state draws the crossed-out microphone. Shared-screen tiles keep their own
labels and do not duplicate the person's microphone trace.

On a narrow phone with enlarged text, controls wrap into additional rows
with their words kept on one line. The landscape rail also grows with text
size. Buttons retain their full accessible names.

Remote traces use microphone receiver PCM for the claimed mic device, after
hold and meeting policy. They never use the loudest room audio. A receiver
whose microphone role is unknown, including a combined screen-playback track,
does not supply a voice waveform. This avoids labelling shared music as speech.
The local tile uses raw mic peaks before app playback is mixed in. The chat
panel and hidden-gallery dock retain their aggregate incoming-audio display.

With the mic enabled, the engine requests capture from the existing WebRTC
audio device module even before a peer connects. Otherwise a solo call can
remain at Waiting indefinitely. This preview request exists only while there
are no native peer connections. It is released before opening a peer and
resumes after the last connection, including retiring connections, is disposed.
Keeping a client request alongside a native sender exposes the pinned SDK's
native-stop buffer race ([upstream report](https://github.com/livekit/client-sdk-android/issues/1056)).
Mute, mic off and leave release preview capture; there is one recorder per engine.
No permission, protocol, storage, telemetry or service dependency is added.
Peak snapshots remain bounded and ephemeral.

Review the chat, full call and call dock on a narrow phone, with enlarged text
as well. Alternate waiting, silence, speech, microphone mute and off; the
video bounds must stay constant. Check light/dark themes and reduced motion.
`AudioActivityUiTest` covers chat and solo camera-off calls;
`CallTileAudioUiTest` checks alternating speakers, actual mute, fixed label and
meter bounds, and a 320 dp phone with enlarged text. `CallScreenUiTest` covers
landscape controls and rotation. `SoloMicrophoneActivityTest` exercises real
native capture while alone, mute/unmute, peer join/departure, mic off and leave
on a disposable emulator. Physical GrapheneOS and a
simultaneous Android/desktop/web call remain separate acceptance checks.

Related browser fixes and regression cases are in the KithMoot repository's
`docs/call-video-stability.md`, branch `fix/call-video-stability-20261116`.

## Loudspeaker selection

During an active call, the loudspeaker button follows Android's selected
communication output, rather than treating the saved preference as a successful
switch. AudioManager and Telecom apply changes asynchronously; until Android
reports the speaker, the button does not claim it is on. Pressing again retries
the request even if the saved choice has not changed.

A connected headset remains the default when a call starts. An explicit
Loudspeaker press selects the built-in speaker ahead of that headset. Turning
it off returns to the headset or earpiece, with speaker fallback on devices
without either. Telecom retains audio mode and focus ownership when handling
the call; the app continues to request its route through the existing hand-off.

`CallAudioRoutingTest` covers headset defaults and explicit speaker selection.
`CallAudioRoutingDeviceTest` checks real AudioManager switching, persistence,
mode recovery and an asynchronous Telecom stand-in that deliberately leaves the
route unchanged. Physical Pixel output, Bluetooth/USB switching and Telecom
acceptance still require a real device call.

## Verification on 16 November 2026

- `:app:testDebugUnitTest`: 3,571 passed, one skipped, no failures.
- Main Kotlin and Java compilation passed.
- `:app:compileDebugAndroidTestKotlin`: passed, including the new narrow-phone
  bounds regression. Instrumentation was compiled, not run: no device was connected.
- Combined Gradle check completed successfully. No APK was published or installed.

Logs and JUnit results are retained with the browser evidence at
`~/.codex/artifacts/kithmoot-call-video-20261116T120714Z`.
