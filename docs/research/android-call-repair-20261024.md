# Android call repair, 24 October 2026

Draft investigation and repair in PR #324. This is not a release acceptance
record. Reported problems include severe loudspeaker echo, asymmetric audio
and video, a dropped call around a VPN change, and very quiet received audio.

## Observed and changed

Read-only inspection of the available Pixel found KithMoot owning
`MODE_IN_COMMUNICATION`, with the communication output on the earpiece.
Earpiece voice-call volume was already at its maximum. This supports a
routing problem; it does not establish the cause of every quiet-audio report.

`CallAudioRouting` now watches communication-device and audio-mode changes,
in addition to hardware arrivals and removals. It reapplies its existing
headset/speaker choice while active and retains the Telecom hand-off.
The device regression deliberately selects the earpiece, waits for an actual
platform fallback event, then requires speaker recovery. It also exercises
communication-mode reactivation and verifies that leaving releases the mode.
The emulator recovery workflow runs this test in both matrix variants.

The released profile-1 negotiation previously returned from `restartIce`
without a native restart because it has no profile-2 control channel. It now
uses native `restartIce` and the existing renegotiation callback. A bounded
health ladder waits six seconds after transport failure/disconnection,
restarts ICE, then allows eight seconds before rebuilding. A replacement
retains the three-rebuilds-per-minute budget, followed by a rest period.
Cold negotiation and healthy transport do not trigger this repair.
This ladder does not detect connected transports with missing media.

Software echo cancellation and suppression were already requested in the
published application. Their configuration has not been changed without
device evidence. An opt-in diagnostic test can report actual native audio
processing state, route, connection states and video frame counts. It is
restricted to a separate `.callprobe` application in a secondary Android
user. It exports no sound, pictures, room links, identities or credentials.
Collector completion alone is not acceptance.

## Evidence and remaining gates

Local targeted JVM validation passed 36 tests: 19 negotiation, 13 generation
and four legacy recovery tests. Separate probe app and instrumentation APKs
built successfully. Hosted validation must cover all 16 executed jobs from
the final commit and its own packaged artifacts before publication.

No repair APK has been installed on the Pixel by this task. During read-only
inspection its installed version changed from 0.6.108/code 131 to an
unpublished 0.6.109/code 132; Android reported `com.android.shell` as initiator.
The source of that installation is unknown. This does not establish that
KithMoot's updater caused a call failure.

The owner subsequently instructed us to keep the current call running.
Physical installation, profile switching, routing changes and microphone or
camera tests are held until the phone is available again. No speaker volume
has been changed. Physical GrapheneOS acceptance still requires sustained
two-way speech and video, loudspeaker echo checks, and recovery across network
changes. Neither the experimental realtime SDK release nor emulator success
proves these Android behaviours.
