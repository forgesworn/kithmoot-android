# Recording capture notices

Android understands the signed `recording-capture` companion described in the
reference client's `docs/recording-capture-notices.md`. The existing recording
start/stop signature and wire format stay unchanged.

The companion binds room, recording id/version, layout, recording participant
and device to the room authority's signature. Details are displayed only for
the exact currently running notice. Details alone cannot start a recording
warning or keep a stopped recording live. Invalid signatures and stale versions
are ignored; missing details retain an audio/video warning.

The room banner, late-join consent and original call's dock describe the
captured media and recorder, with a short public key alongside a display name.
The latter remains a self-asserted label. Android advertises encrypted roster
`recordingProfile: 2`, allowing the recording compositor to distinguish
supported endpoints from older audio-notice clients.
This flag does not enable recording on Android or prove consent.

`protocol/src/test/resources/recording-capture.json` is a verbatim copy of the
reference client's additive fixture file. JVM tests independently verify all
four signed modes and re-encode their JSON exactly; existing published
`kithmoot-vectors.json` remains unchanged. Tests also cover malformed capability
values, legacy JSON, authority substitution, notice ordering, matching
id/version, stale replays, stopped recordings and the visible capture wording.

The native audio adapter and streaming Wildbloom-compatible file APIs are
tracked in [Native recording qualification](native-recording.md). Recording
audio/video controls and local Save/Discard are implemented on this branch.
The actual gallery controls, signed gallery notice, background pause, explicit
resume, original-room metadata and Discard passed the ten-test local emulator
qualification. System document-provider Save, physical capture, live browser/native
video and app sharing/playback flows remain open.

G11 still requires actual video/audio capture, synchronised 45-minute export,
bounded memory, interruptions and encrypted sharing on supported physical
clients. The native gallery and physical navigation acceptance under G14 also
remain open; a recording warning in the dock is not a video gallery.
