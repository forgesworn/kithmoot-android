# Native recording qualification

Android's recording warnings are already part of the room UI. This branch adds
native audio/video recording controls, capture and local export. The draft/parser
candidate passed 18 focused JVM tests and all 14 local recording emulator checks,
including gallery selection, signed notices, background pause, explicit resume,
discard and recipient playback of browser recording attachments through the real
MIME parser. The subsequent Add-to-chat candidate also passed those 18 JVM and
14 emulator checks, including the explicit Add/Discard/Remove journey. The later
Upload candidate passed 16 focused JVM tests and 15 emulator checks, plus
Keystore restoration after a forced app-process stop and real-node transfer.
Live browser/native video, private-node sharing UI, physical qualification and signed delivery remain open; this
implementation does not close G11. Historical results below are scoped to their
recorded source/APK hashes.

## Private recording drafts

Add retains an independently encrypted recording draft in its original room;
it preserves the local export for Save/Discard. The draft journal uses the
application's Android Keystore-backed encrypted storage, while only encrypted
file envelopes are kept in the no-backup draft directory. Reservations are
process-local: restart removes unfinished envelopes, and Forget rejects late
encryption completion. Expiry and explicit removal commit recovery-key removal
before unlinking ciphertext. Journal failures are reported rather than treated
as successful wipes. The store caps retained and unfinished drafts at four.

The [draft/parser receipt](evidence/native-recording-draft-parser-2026-10-10.json)
qualifies persistence, revocation, origin binding and wire parsing. It does not
qualify the later Add navigation UI, Upload or Send. Upload must remain an
explicit action to a chosen HTTPS origin, followed by a separate explicit Send.
The sender UI and durable remote deletion remain unfinished. In particular,
the current private node rejects the existing long-lived signed DELETE tokens;
a successful immediate transfer does not prove cleanup after Forget or restart.

The [Add journey receipt](evidence/native-recording-add-2026-10-10.json) records
the later source and APKs. Through the real activity controls it verifies Add
opens the original chat and retains its original room/call metadata, leaves the
storage server unselected and upload receipt empty, preserves the local export,
allows its controls to reopen, and keeps the encrypted draft after local Discard.
Explicit Remove then deletes that draft. All 14 emulator checks passed in
104.405 seconds after the build finished. This is synthetic emulator evidence;
it does not qualify a live call, system Save provider, physical device, Upload,
Send, or behaviour under concurrent build load.

### Storage authorisation and cleanup

`RecordingUploadJournal` creates a separate storage identity for each explicitly
chosen HTTPS origin. The private node can authorise its public key with the
existing `--allow-pubkey` policy. It does not receive a room/device key or a
file recovery key. Its application owner uses device-encrypted storage and
retains exact origin/hash cleanup records after Forget removes room references.
Each cleanup retry signs a fresh five-minute DELETE; upload authorisation is
also capped by the original room deadline. Cleanup is registered before a PUT,
waits for an active upload to finish, and recovers interrupted PUTs as possible
remote copies. Upload retry cannot overlap deletion of the same origin/hash.
Failed network requests remain journalled. The existing foreground/background
cleanup loops call this owner, separately from the legacy image cleanup ledger.

A synthetic real-node check verified upload with an explicitly allowlisted
storage identity, Forget without retaining the room reference, journal
restoration, expired DELETE refusal, fresh DELETE success and HEAD 404.
This fixture used a private test journal and injected clock; it does not prove
Android Keystore restart persistence, physical elapsed-time retention or the
sender UI. The subsequent Upload candidate below qualifies Keystore restart
separately. Local recording and Save require no node authorisation.

The [storage journal receipt](evidence/native-recording-upload-journal-2026-10-10.json)
binds these results to the final native class bundle, source and APKs: six JVM
tests, the synthetic real-node sequence (PUT 201, expired DELETE 401, fresh
DELETE 200, HEAD 404), and all 14 recording emulator checks in 36.292 seconds.
The test node and HTTPS proxy were stopped afterwards. Android Keystore
restart acceptance and the actual sender Upload/Send journey remain separate.

### Explicit Upload candidate

The recording draft offers Upload recording. Its server field is blank for a
new draft; Get storage key creates a public node identity without contacting
the server. The operator authorises that key on the chosen private node. A
separate checkbox permits Upload, which binds this draft to that HTTPS origin.
Cancel upload cancels only its request. Room closure and network withdrawal
also close it. Failures keep the independent encrypted draft and schedule
possible remote cleanup; a failed durable finalisation cannot become ready for
Send. The original local Save/Discard copy remains independent.

The [Upload receipt](evidence/native-recording-upload-ui-2026-10-10.json) records
16 focused JVM tests and all 15 local emulator checks passing in 41.589 seconds.
The real activity verifies node setup, public-key copying, separate consent
and independent Discard/Remove. A separate guarded driver force-stops the app
between preparation and verification: the new process restores the same
Keystore-wrapped storage identity, finds no forgotten room reference and signs
a fresh exact-file DELETE. This uses an injected clock and no HTTP.

The actual `RecordingUploadRequest` also passed against the real private node
using the compiled app classes and synthetic playable MP4. It retains the
verified receipt before becoming ready for Send; Forget/restart then rejects
expired deletion and confirms a fresh DELETE with HEAD 404. This caught a real
URL-suffix mismatch between transfer and draft validation. Both now accept the
same bounded hash-plus-extension form while rejecting a substituted hash.
The HTTPS fixture trust remains test-only, and its services were stopped.

These checks do not establish UI-driven HTTPS transfer, durable Send/outbox
ownership, live browser/native capture, physical endurance/interruption or
signed production delivery. Hosted run
38082646329 passed all gates on the preceding `a3d6124` cleanup checkpoint;
the latest Upload source still requires complete hosted qualification.

### Deferred Add navigation

Add may finish after a call becomes locked, answering or displayed in PiP.
The Compose owner now keeps that completion pending without navigating or
acknowledging it until chat is allowed. The same event then opens its original
chat exactly once. Repeated Add reuses the independently encrypted draft.

The [navigation receipt](evidence/native-recording-deferred-add-2026-10-10.json)
records all 15 local recording checks passing in 39.482 seconds. The real
Compose app was exercised with each restriction independently, including
re-entry after acknowledgment and the existing Upload/Discard/Remove journey.
A preliminary regression check against the preceding APK failed because it
consumed the pending Add while locked. These checks use Compose restriction
inputs; physical keyguard and OS PiP transitions remain separate acceptance.

The subsequent [admission integration receipt](evidence/native-recording-admission-integration-2026-10-10.json)
covers `3fd3b0fc`, which includes main's `44857b84` admission-authentication
changes: 417 protocol tests, 2,010 app unit tests, debug/instrumentation builds,
15 recording emulator checks (42.659 seconds) and six admission UI checks
(6.184 seconds) all passed. This local command did not run Android lint or
the release build. Hosted run 38086034518 was still in progress at the receipt
snapshot; complete hosted and production qualification remain open.

## Exact Send ownership candidate

`RoomSession.prepareChatForSend` signs without retaining or publishing. The
recording draft journal can keep that exact room/identity-bound message before
handoff. `sendPreparedChatDurable` and the outbox reuse it on retry, preserving
an existing UNKNOWN or MOVED delivery state. A short draft-owner guard covers
the outbox storage commit, so Forget cannot precede a late journal write.

Once prepared, Send cannot be replaced by another signed message or removed
through the ordinary discard path. Failed local cleanup keeps the exact
message for retry. Restart also retains its journal entry when the local
ciphertext file is missing after upload. Successful handoff cleanup removes
local draft keys without scheduling deletion of the uploaded copy.

The [Send-owner receipt](evidence/native-recording-prepared-send-2026-10-10.json)
records all 2,019 app unit tests, debug/instrumentation builds, 15 recording
emulator checks (39.194 seconds) and six admission UI checks (5.334 seconds)
passing. The room-session fixture confirms that retries use the same relay
event and leave one own chat message with the original recording descriptor;
it uses a synthetic transport, not an independent recipient network journey.

At that checkpoint the Send control and non-retaining confirmation path were
unwired. The subsequent UI candidate below supplies them; this earlier receipt
does not qualify that later source or production.

### Explicit HTTPS Upload and Send candidate

Uploaded drafts now offer Send recording, independently of the ordinary text
composer. An interrupted prepared message offers Retry Send and cannot be
uploaded again or removed through the ordinary discard action. Durable chat
hands the exact event to its original outbox. Non-retaining chat retries that
same event until confirmation without creating an ordinary outbox record.
Local draft cleanup preserves the independent Save/Discard export and does
not schedule remote deletion of a copy referenced by the message.

The [HTTPS Send receipt](evidence/native-recording-https-send-ui-2026-10-10.json)
records 2,022 app unit tests, debug/instrumentation builds and 23 emulator checks
passing: 15 recording checks (51.991 seconds), six admission UI checks
(4.755 seconds), and two UI-driven TLS Upload-to-Send/redirect checks
(9.154 seconds). The native owner journey also clicks Send through a real
loopback relay, preserves the local export, explicitly adds another independent
draft, then checks Discard and Remove.

The HTTPS journey begins with an independently qualified synthetic MP4. A
temporary certificate is trusted only by the test model's constructor client;
normal activities retain the production client's TLS trust. The loopback
Blossom-contract server verifies the separate storage identity's signature,
five-minute authorisation bound, encrypted envelope and exact hash. Upload
offers one ciphertext copy only after consent; a separate Send publishes one
event on the original chat channel. A redirect to a second TLS endpoint
receives no upload, is not retried, and cannot enable Send. The checkbox label
now toggles the whole accessible consent row.

This server is a contract fixture, not the Wildbloom daemon. Earlier direct
daemon tests remain separate evidence. An independent recipient fetch/play
journey, prepared-Send recovery across rekey/interruption, stop-notice recovery,
live call capture, background reliability, physical acceptance and final
hosted/signed production delivery remain open.

### Interrupted Send and secure room changes

The [Send recovery receipt](evidence/native-recording-send-restart-2026-10-10.json)
records 2,024 passing app unit tests and two HTTPS Upload/Send emulator checks
on rebuilt debug and instrumentation APKs. A session test interrupts an offer,
restores the signed message and UNKNOWN outbox record into new objects, then
confirms the identical ciphertext with one recording descriptor. A second
test reopens at a newer epoch: the old event becomes MOVED, remains retained
on retry and is never offered or replaced. Non-retaining Send refuses that
old epoch before publication.

The durable transport guard now explicitly rechecks the message's epoch.
The sender distinguishes a cleanly unsent MOVED message from an UNKNOWN
delivery that may have arrived, rather than describing both as waiting.
These JVM reconstructions do not qualify Android process death or the whole
recording owner journey through rekey; those acceptance gates remain open.

## Signed stop recovery candidate

The [stop-journal receipt](evidence/native-recording-stop-journal-2026-10-10.json)
records the full local CI command passing with 417 protocol and 2,033 app tests,
both lints and debug/release packaging. Final debug/instrumentation APKs passed
all 15 recording checks (38.356 seconds), both HTTPS Upload/Send checks
(9.112 seconds), and the forced restart's prepare, verify and cleanup stages.

Before publishing recording details or On, the native authority now retains
an already signed Off notice in the application's Keystore-wrapped,
backup-excluded `kithmoot.recording-stops.v1` journal. It holds only room/device
identifiers and signed Off metadata. Capture can disappear with the process
without losing the notice. A reopened owner exposes Retry stop notice even
without an On replay, and re-envelops the same Off signature using the current
room epoch and device credentials. It needs no authority secret for that retry.
Starting a new recording still requires the authority secret and a successful
journal write. Failed startup/publication keeps the stop pending; a verified
newer recording retires the old notice without publishing it. Forget fences
an old owner before a late write or guarded dispatch. The Record control stays
disabled while stop confirmation is pending.

A replay or local echo of the matching Off does not clear the journal: it may
precede a failed publication. Cleanup requires explicit transport confirmation
of the retained stop. Expired device credentials cannot publish or complete
that stop; its signed metadata remains available after credential renewal.

The dedicated emulator driver checks a different process ID, encrypted storage
at rest, restoration of the exact signature, rejection under the old epoch's
channel/key and acceptance under the new fixture epoch. Its transport is a
synthetic acknowledgement fixture, not a real peer. Actual saved-room reopening
and the retry banner through process death still need UI qualification.

Quiet rooms are an additional open gate: `QuietTransport` has no guarded
publication implementation. Its existing `publishConfirmed` means local
queue retention, not relay delivery. Recording On must not authorise capture
merely because it is queued for a later cadence slot. Implement delivery-aware
recording notices and prepared recording Send through the quiet transport;
preserve the cadence instead of bypassing it. This candidate is not qualified
for quiet-room recording or sharing.

## Member-rekey recovery release gate

Hosted run 38078615048 failed the member-removal case while the controller
replayed its own rekey. Recovery observed the old epoch before taking the
receiver barrier; an intervening receiver adoption could make its subsequent
hold incorrectly mark the next epoch pending. Recovery now supplies its target
to the barrier, which skips that hold if the receiver is already current.
Normal source transactions still hold publication. A deterministic JVM test
checks replay after adoption, a single durable commit and the next transaction's
publication refusal. The [recovery receipt](evidence/native-recording-recovery-race-2026-10-10.json)
records 13 focused JVM tests, all five member-command emulator checks, and all
14 recording checks passing on the patched APKs. This closes the reproduced
ordering defect locally; the exact patched head still needs hosted CI.

## Capture boundary

`CallAudioCapture` attaches sinks to explicitly supplied remote WebRTC audio
tracks and accepts a caller-supplied outgoing buffer after microphone mute and
app-share mixing. The pinned `io.github.webrtc-sdk:android:144.7559.12` binary
provides `AudioTrack.addSink` and `AudioTrackSink.onData`; a screen recording or
Android playback-capture workaround is unnecessary for these incoming tracks.

The adapter neither chooses a room nor publishes a notice. Its eventual owner
must publish the authority-signed recording notice before enabling capture,
and continuously select only the originating call's authorised inputs. Track
replacement and revocation drop queued samples. Native sink attachment/removal
does not hold the callback lock. Disk writes run on a separate worker outside
that lock. Inputs are bounded to 32 mono/stereo 48 kHz PCM sources, with at most
two seconds buffered per source. Unsupported formats, queue overruns and file
limits fail explicitly.

One monotonic recording clock anchors each source; consecutive PCM buffers use
their sample cadence so callback scheduling jitter cannot accumulate gaps.
The two-second window tolerates short worker scheduling delays while keeping
sample storage bounded to 6 MiB for 32 sources. Pause clears pending
inputs and omits paused time from the exported timeline. The current lossless
WAV writer is a capture qualification format, capped at 256 MiB including its
header. It is not the final AAC/H.264 video encoder, nor a physical A/V sync or
45-minute qualification.

`AacRecordingFile` is the compressed local audio output for the same mixer:
48 kHz mono AAC-LC at a requested 64 kbit/s in an M4A container. It writes to an
app-private file on the export worker, timestamps PCM by sample count, omits
paused time, reserves space for container tables and drains encoder EOS before
returning a finalised file. Empty/failed exports are removed and existing files
are never overwritten. Encoder stalls have bounded waits. The room owner supplies
the same authorised incoming audio tracks and gains as playback, including
meeting, hold and monitor policy. The engine feeds outgoing PCM after microphone
mute and app-share mixing. Source revocation updates the active capture. No
remote storage destination is selected automatically.

The final AAC access unit is held in a bounded 1024-sample buffer and zero-padded
before EOS, so an encoder cannot silently drop a partial final unit. An empty
MP4 EOS marker sets the track duration to the original PCM clock using the
[Android muxer contract](https://developer.android.com/reference/android/media/MediaMuxer#writeSampleData(int,%20java.nio.ByteBuffer,%20android.media.MediaCodec.BufferInfo)).
The decoder qualification still requires the complete two seconds of samples;
successful container metadata alone does not establish complete capture.

## Audio/video export candidate

`AvRecordingFile` drives a 1280 by 720, 15 fps H.264 surface encoder from the
mixed audio's 48 kHz sample count. Its composition callback receives the exact
export sample position. AAC and H.264 tracks stream to separate private files;
finalisation remuxes samples in timestamp order through a four-MiB buffer. Both
tracks receive the same original sample-clock duration. The combined source
limit remains 256 MiB, with reserved container space. Temporary storage can
approach twice the source limit during remuxing; this is bounded disk storage,
not a qualified phone storage or battery result. Existing destinations are
preserved, and failed, empty or discarded exports remove their private parts.

The backend compiled and both of its instrumentation checks passed on the
Android 35 host-GPU emulator. It is not wired into the recording controls yet.
The independent gallery/speaker/selected-screen compositor is a source
candidate under qualification. Its plan binds the original room and
call, gates each device by recording capability and meeting permission, and
keeps unavailable sources as named placeholders. It retains at most three
scaled frames per input, uses the audio capture clock, and rejects new frames
while paused or detached. The compositor qualification decodes synthetic
native red/green cameras to check capability exclusion, permission withdrawal
and a missing screen with its selected camera still visible. These checks
do not cover real call ownership or phone lifecycle. The focused JVM run
passed 22 tests: six layout-plan checks, six audio-capture checks (including
video-clock revocation during pause, failure and detachment), four local-store
checks and six recording-stop checks. The decoded gallery test passed:
capability exclusion, an authorised camera appearing and permission withdrawal
produced the expected pixels. The synthetic MP4 has 30 H.264 frames and 94 AAC
packets, with both track durations exactly two seconds. The
[scoped compositor receipt](evidence/native-recording-scene-2026-10-10.json)
records the exact source/APKs and saved movie.

Seven of the eight emulator tests completed successfully. The final
screen-and-camera test stalled in `glCreateShader_enc` waiting for an emulator
OpenGL RPC. Its stack was preserved and the process deliberately stopped;
running that test alone with the same installed APK also stalled at shader
creation. Neither run proves that test passed. The compositor now has separate
video and overlay drawers: the pinned SDK's `GlGenericDrawer` otherwise deletes
and recreates its cached shader whenever the shader type changes. That latest
change awaits a new build and decoded playback, and is not a confirmed fix for
the driver stall.

The live-call diagnostic sinks received 13 frames from each callback, with both
callbacks referring to the same native track; the bound sink also received 13
frames. This rules out choosing a different callback wrapper as the remedy.
The diagnostic sinks are still present in that receipt's fixture, so this
does not close the production-representative live-call gate.

The legacy SwiftShader-indirect renderer
reported unsupported GLES readback and produced an all-black MP4; the identical
APKs passed visible-frame playback on the host renderer. `AvRecordingTest` decodes the complete two-track file and compares three
synthetic sound/picture transitions within 80 milliseconds. It also checks
track duration, all 30 encoded video frames, empty/discarded/over-limit cleanup
and existing-file preservation. That synthetic encoder test cannot establish
live-call lip sync, physical video inputs or 45-minute acceptance.

The [10 October encoder receipt](evidence/native-recording-av-2026-10-10.json)
records the exact APK and encoder/test source hashes. Full playback decoded
96,256 audio samples and all 30 video frames from a 52,005-byte synthetic MP4.
The visible transitions preceded their audible counterparts by 6,667, 50,000
and 6,667 microseconds. Empty/discarded/over-limit cleanup and existing-file
preservation also passed. The full five-test gate still failed its browser
video test: native video decoded counters advanced, but the monitored sink
received no frames. These measurements prove this synthetic encoder pipeline
only; there were no real room, call, camera, screen or physical-device inputs.

## Wildbloom storage compatibility

`sealFile` and `openFileAttachment` stream the existing FSWNENC2 envelope in
one-MiB authenticated records. Source files are bounded to 256 MiB and envelopes
to 260 MiB. Canonical encrypted filename, MIME type, padding, salted HKDF key,
nonce and AAD match the shared Wildbloom format. No published vector changes.
The old in-memory image APIs retain their existing limits.

Readers authenticate all records, canonical metadata, size and the message's
complete ciphertext hash before returning a plaintext file. Partial work stays
in app-private temporary files and is deleted on failure. Destinations must not
exist; callers must supply app-private directories and own the files' lifetime.

`uploadFileMedia` streams immutable app-owned ciphertext to an explicitly chosen
HTTPS Blossom origin, using the existing scoped authorisation and exact
hash/size/origin receipt checks. Failed uploads retain the sealed local file.
`fetchFileAttachment` downloads into a bounded private file and authenticates
before returning plaintext. These APIs initiate no discovery, public NIP-94
publication, replication, RelaySwarm participation or automatic upload. The
room's storage consent and explicit Send remain separate owner responsibilities.

## Reproducible checks

Use JDK 21 and the project's Android SDK. Prepare the pinned mesh-radio sources
if this checkout's generated build inputs are absent:

```sh
python3 scripts/prepare-mesh-radio.py
KITHMOOT_RECORDING_INTEROP_DIR="$PWD/app/build/recording-interop" \
  ./gradlew :app:testDebugUnitTest \
    --tests '*FileAttachmentTest' --tests '*FileAttachmentUploadTest' \
    --tests '*PcmRecordingTest' --tests '*CallAudioCaptureTest'
node scripts/verify-recording-wildbloom.mjs ../wildbloom
```

The independent Wildbloom reader verifies a mixed 440/660 Hz synthetic WAV and
a 34 MiB synthetic binary envelope labelled `video/mp4`. That binary tests large
file interoperability only; it is not a playable video. The generated receipt
records exact reader/writer source hashes. Recovery keys in these generated
fixtures belong only to synthetic test material.

`BrowserCallInteropTest#nativeRecordingExportsBothSidesOfTheBrowserCall` runs on
a disposable emulator, refuses physical devices, supplies a synthetic 660 Hz
outgoing buffer and receives the actual browser's 440 Hz tone over WebRTC. It
checks both frequencies in the native WAV and authenticates its encrypted
round-trip. It does not use a real account or room. Prepare the browser test
assets with `node scripts/prepare-browser-call-interop.mjs` before building the
instrumentation APK. Source/unit, emulator and physical evidence remain separate.

`AacRecordingTest` sends two synthetic tones through the actual capture mixer,
pauses for 30 seconds on its injected clock, and decodes the complete M4A with
Android's media APIs. It checks both tones, compressed size and the two-second
audio duration, plus empty-export, discard and existing-file preservation.
Run both capture and compressed export after building the APKs:

```sh
ANDROID_SERIAL=emulator-PORT ANDROID_HOME=/path/to/sdk \
  bash scripts/check-recording-emulator.sh
```

The script requires an explicitly selected disposable emulator and checks the
instrumentation result itself; a zero `am instrument` exit is not a passed run.

The 2026-10-10 source qualification passed 401 protocol and 1,891 app JVM
tests. The actual Wildbloom reader passed all three generated envelopes,
including the filename canonicalisation case and the 34 MiB multi-record file.
`ffprobe` identified the synthetic WAV as 48 kHz mono `pcm_s16le`, one second.
These checks establish file/mixer interoperability, not live-call or video
acceptance. The browser-call instrumentation test must pass independently.

The subsequent native-controls candidate passed 93 selected recording,
negotiation and track JVM checks and built debug/instrumentation APKs. Both AAC
instrumentation checks passed on the disposable Android 35 emulator after final
access-unit padding: 94 packets decoded to 96,256 samples and the container
retained the original 2,000,000-microsecond duration. The 256 extra decoded
samples are final-unit padding. The independent browser-call gate remains open:
the initial fixture was stuck in ICE checking with zero RTP received. After
restarting the disposable emulator with working DNS and using the app's STUN/TURN
configuration, ICE connected and both sides received audio (704 native and
1,003 browser packets at the failed assertion). Both video frame counts remained
zero, so the fixture still failed before recording began. The next candidate
starts the synthetic native capturer explicitly and records video packet and
decoder counters. That run received 875 video packets and reported 327 decoded
frames, but delivered zero frames to the monitored native sink; the browser
video counter also remained zero. It still failed before recording began.
The six stop-notice JVM tests passed, including failed acknowledgement, stopped
refreshes, explicit retry and rejection of an old retry against a new recorder.

## Work remaining before recording is available

`RoomWork.startRecording` now provides authority-signed capture details followed
by the running notice, requiring a relay acknowledgement for each before it
returns. A mutex admits one local start; the version outranks both previous
notices and any orphan details from a failed start. Five-minute refreshes carry
both signed records. `stopRecording(id)` confirms a newer off notice only for
that id, so an old completion cannot stop a newer recording. Closing the owner
cancels refreshes. The owner detaches capture before stopping the notice and
reports a failed stop confirmation without implying capture is still running
locally. A failed stop cancels running-notice refreshes and retains the exact
recording id for an explicit Retry stop notice action. Retry is available even
after leaving the call while the original room remains open; a newer signed
notice clears the pending retry, and an old retry cannot stop a new recorder. The original call exposes an explicit Record audio confirmation and
Stop action; its dock also exposes Stop. Pause/Resume controls now retain the
exact capture owner and run outside the UI thread, omit paused time, and keep
the signed warning visible. Those latest controls await their build and owner
runtime qualification. Failed encoder finalisation now releases resources on
the export worker; longer video remuxing has a bounded two-minute completion
wait rather than the audio-only ten-second limit. Leave, room closure and epoch changes
detach capture synchronously and finalise on an application-owned worker.

Completed exports are retained in the application's no-backup private
directory until Save or Discard. Save uses Android's document picker and a
bounded streaming copy; failed copies retain the private export. Completed
exports survive process death, while unfinished containers are removed when
the application first recovers its store. One unresolved export blocks another
recording. These controls are a source candidate, not runtime or physical
acceptance evidence.

The latest store candidate persists audio/video format, MIME type, extension,
the original room/call/name and an optional self-destruct deadline in private
atomic metadata. Save selects the matching document type. Earlier local M4A
exports with no origin remain saveable, but no origin is inferred from the
currently open room. New captures use the exact session and call they began
on; the owner stops when that call changes.

Room destruction revokes retention under the same store lock as completion,
including when a video remux finishes afterwards. Unsaved completed exports
are removed, expired exports are excluded before recovery presents them, and
Save rechecks availability after the copy so destruction during a document
provider operation does not leave a late saved copy. Explicit copies saved
before destruction remain user-owned. Open-room and background self-destruct
paths both invoke this revocation, even when relay deletion is postponed.
Storage failures revoke in-process availability and leave cleanup retryable;
the open call also stops its local capture on a revocation error. The current
focused JVM run passed all 27 checks: nine store tests, six layout tests, six
audio-capture tests and six stop-notice tests. Both debug APKs built successfully.
The owner/runtime gates remain pending; the nine store tests are pure JVM
filesystem checks rather than physical document-provider acceptance.

The screen-and-camera decoder check reproduced its native `glCreateShader`
RPC stall after a fresh emulator renderer. Inspection of the pinned SDK's
I420 uploader showed that it uploads tightly packed planes without setting
pixel unpack alignment. The selected camera scales to 244 pixels, producing
122-byte chroma rows, while a new encoder EGL context defaults to four-byte
alignment. The compositor now explicitly selects one-byte unpack alignment.
The corrected build passed all eight emulator instrumentation checks, including
decoded pixels for the previously stalled screen/camera overlay, on the same
renderer without another reset. The gallery movie independently reports 30
1280-by-720 H.264 frames and 94 AAC packets, both tracks exactly two seconds.
The [27 JVM / eight emulator checks receipt](evidence/native-recording-retention-alignment-2026-10-10.json)
pins the source and immutable APK snapshots. Its live-call fixture still has
early diagnostic video sinks; that dependency remains unresolved and the
production-representative live-call gate is open.

The next app-owned candidate passed 32 focused JVM tests and built both debug
APKs. Its full 11-test emulator run passed nine checks and failed two:
the browser/native call still produced no observable video frames, even with
diagnostic sinks, and the real gallery-recording UI did not pause when its
Activity became hidden. The latter reached confirmed signed capture before
failing at the lifecycle boundary. The
[app-owner failure receipt](evidence/native-recording-video-owner-2026-10-10.json)
preserves those APKs and the failed gates. A subsequent source candidate moves
visibility notification into Activity callbacks, prevents hidden input refresh
and queued Resume, gives the recording its own active-speaker hold selection,
and probes the exact AAC/H.264 encoder formats before offering video. Those
changes are undergoing a separate build and have not yet closed the runtime
gates.

That lifecycle/speaker candidate subsequently passed 35 focused JVM tests,
including the three active-speaker hold/device/permission checks. Its corrected
test APK passed all nine native codec/compositor checks, including encrypting,
opening and decoding the generated gallery on Android. The app-owned UI test
was first blocked at setup by a System UI ANR dialog. After the dialog was
dismissed, the exact APK reached capture but reported audio after a Gallery
tap; the test had not confirmed the radio's selected state. The
[lifecycle/speaker receipt](evidence/native-recording-lifecycle-speaker-2026-10-10.json)
preserves both failures and explicitly excludes the live browser gate from
this local-capture run. The next source candidate makes each picker row one
radio control, reads the current selection at confirmation, checks the selected
radio before starting the app test, and keeps a raced pause flush outside the
lifecycle monitor. It is undergoing another build; app-owner acceptance remains
open.

An actual hash-verified gallery MP4 from the earlier emulator compositor gate
has now been encrypted with the compiled Android writer, opened independently
by Wildbloom's reader and fully decoded with host ffmpeg. Both tracks have a
two-second duration. The
[playable Wildbloom interop receipt](evidence/native-recording-playable-wildbloom-2026-10-10.json)
records the movie, ciphertext and compiled writer hashes without recovery keys.
This proves playable format interoperability for generated test media; private
HTTPS node upload and recipient app playback remain separate open gates.

- Qualify live call controls, meeting/hold/monitor/mute changes, interruption,
  expiry, access withdrawal and asynchronous finalisation cleanup end to end.
- Qualify the stop-notice retry UI and room's conservative unconfirmed warning
  during relay failure and reconnection.
- Integrating compressed audio with H.264 encoding, a common audio/video clock and independent gallery,
  speaker and selected-share compositing from privacy-processed call tracks.
- Per-device video capability filtering and named unavailable/unsupported inputs.
- Qualify Local Save/Discard and add explicit encrypted attachment staging, storage consent,
  Send, incoming video playback and retained-copy wording.
- Physical 45-minute export/playback, instantaneous lip-sync measurement,
  memory, heat, battery, storage, Bluetooth/network and lifecycle acceptance.

RelaySwarm remains an optional future ciphertext delivery adapter. Persistent
Wildbloom-compatible storage supplies availability when the recorder leaves;
choosing storage does not by itself establish replication or recovery acceptance.

## Latest local controls qualification

The [picker/owner receipt](evidence/native-recording-picker-owner-2026-10-10.json)
records 35 passing focused JVM tests and ten passing local-capture emulator
checks. The actual accessible Gallery row is selected before starting capture.
The app publishes a signed gallery notice, pauses on backgrounding, remains
paused on return and resumes only after an explicit action. The exported MP4
has AAC and H.264 tracks of equal duration and retains its original room
metadata; explicit Discard removes it. Browser/native interop is excluded from
these ten checks. No physical microphone/camera, system document-provider Save,
private-node UI or production-release claim follows from this receipt.

Subsequent source changes bind the document-picker callback to the originally
selected export name, preventing a callback from saving another room's replacement
recording. They also require a fresh `RecordingView.On` before installation and
throughout capture, stopping when a signed running notice becomes unconfirmed.
These changes are under a new JVM/build qualification and are not part of the
picker/owner receipt.

A loopback HTTPS fixture running the bundled private Wildbloom daemon exposed
an authorisation lifetime mismatch: `mediaAuthorisation` backdated creation by
one second, making a requested five-minute lifetime 301 seconds. The node's
strict 300-second limit correctly refuses it. Native encrypted upload and
verified download succeed with a 300-second event lifetime; owner deletion is
still under investigation. This fixture does not prove the app's Add/Upload/Send
or recipient Show/playback flows, which remain open.

The [Save/freshness receipt](evidence/native-recording-save-freshness-2026-10-10.json)
now records a successful full JVM run (401 protocol and 1,924 app tests), both
debug APK builds and ten passing local emulator checks for those ownership and
freshness changes. These checks exclude the live browser/native call test and
physical hardware. The authorisation fixes are subsequent source changes.

The private-node's encoding refusals have also been isolated: Android used
padded standard Base64, while current
[BUD-11](https://github.com/hzrd149/blossom/blob/master/buds/11.md) requires
URL-safe Base64 without padding. A fixture-only header correction allowed the
native streaming upload, hash-verified download, wrong-key rejection and owner
delete plus HEAD-404 check to pass. The client helper must reproduce that result
without the override before recording the network primitive as qualified.

The [private-node receipt](evidence/native-recording-private-node-2026-10-10.json)
now records the corrected Android source passing without any fixture header
rewrite against both the bundled daemon and a current-node build. The synthetic
MP4 is uploaded over loopback HTTPS, downloaded into a private file and matched
to its original plaintext hash. Missing authority and wrong-server authority
receive 401, a wrong recovery key exposes no plaintext, and owner DELETE succeeds
with subsequent HEAD 404. Full ffmpeg decoding of the downloaded movie succeeds.
The test-only TLS terminator uses the fixture certificate and default hostname
verification; Android production trust is unchanged. No real recording/key or
public node was used. The fixture's backend upload uses Expect: 100-continue to
forward early refusals cleanly; this is not an Android transport change.

The earlier deletion gap was distinct from this immediate owner-delete check:
the existing media ledger stores a long-lived signed delete event, whereas the
private node enforces a five-minute event lifetime. Durable cleanup after room
expiry or a later network recovery could not rely on that ledger. The separate
recording storage journal described above now renews deletion using storage-only
identities; the recording sender UI must use it before offering upload bytes.

The subsequent actual Gradle-built app classes passed the same private-node
transfer with no header rewrite. The wrong-key check requires
`AEADBadTagException`, and no destination is created. Eight focused media and
streaming-transfer JVM tests passed, and debug app/instrumentation APKs rebuilt
successfully. This qualification covers the network primitives and authorisation
corrections, rather than the still-missing app sharing/player journey.

## Recipient playback candidate

The recipient's recording attachment now offers Show. It opens a dedicated
viewer whose download uses private bounded files rather than the image viewer's
32 MiB memory buffer. The reader authenticates the complete encrypted envelope
and uses its authenticated MIME type before preparing a native player. Play is
explicit, backgrounding pauses playback and returning does not resume it. Close
cancels only this viewer's calls, rejects late decrypted results and removes its
private directory. A new process clears abandoned playback directories before
opening its first viewer. No external app receives a file or recovery key.

Three preliminary JVM checks passed for a 34 MiB synthetic recording, Close
during a response and an image disguised as a recording in the message hint.
The actual Gradle build, lint and recipient playback UI qualification are still
running. The new emulator suite includes the recipient viewer check: eleven
local checks, or twelve when including the unresolved live browser/native test.
The recipient widget fixture uses a synthetic encrypted response and native AAC
playback. It does not qualify real-room delivery, a real private-network node,
physical hardware or the still-missing sender Add/Upload/Send controls.

The merged current-main baseline passed 401 protocol and 1,960 app JVM tests,
then stopped at a remux lint error. The sync-frame flag is now explicitly mapped
from MediaExtractor's enum to MediaCodec's enum; both lint variants and app
variants are being rebuilt alongside this viewer candidate.

The first recipient-widget APK failed its Pause-control visibility check after
playback advanced to one second. Screenshots showed the controls clipped below
the dialog's visible area. The next candidate places playback controls above the
media area, bounds the title and handles system insets explicitly. Its debug app
and instrumentation APKs built successfully, and all eleven local emulator
checks passed. The [recipient-controls receipt](evidence/native-recording-recipient-controls-2026-10-10.json)
records explicit Show/fetch, explicit Play, native AAC playback progress,
background pause, return remaining paused and private plaintext removal on Close.
The earlier debug/release lint and variant results apply to the earlier layout;
the corrected layout still needs the final full CI run. No physical or real-room
recipient journey is claimed by this widget fixture.

Cross-platform recipient playback remains open: the reference PWA's
`app/src/call-recorder.ts` prefers WebM/Opus or Ogg/Opus and includes codec
parameters in MIME types. This candidate's whitelist covers native MP4/WAV only.
The reader/player must accept the reference recorder's authenticated formats,
including recordings with no finite duration metadata, before that gate closes.


The cross-format recipient candidate now normalises codec parameters for viewer
selection while retaining authenticated metadata. It accepts WebM/Opus,
VP8/Opus video and Ogg/Opus alongside native MP4/WAV. A missing declared media
duration no longer prevents preparation: elapsed time remains visible and
seeking is offered when the native player supplies a finite duration.

Four actual viewer instrumentation cases passed on the isolated Android 35
emulator, including independent Wildbloom-encrypted files produced by the real
PWA CallRecorder. Browser video additionally required the generated coloured
frames to appear in an Android screenshot. Show fetched once, Play was explicit,
background paused, return remained paused and Close removed private plaintext.
The [cross-format receipt](evidence/native-recording-cross-format-controls-2026-10-10.json)
retains the earlier Ogg assertion failure, concurrent-run interruptions and
fresh-emulator startup failure. These synthetic widget cases do not prove
real-room delivery, live browser/native recording, physical playback or release
readiness. The full fourteen-case suite and latest-source full CI remain running.

The fixture generator is `scripts/make-recording-playback-fixtures.mjs`. Its
assets are instrumentation-only silence/canvas colours with disposable
synthetic recovery keys. Ogg is a lossless remux of the browser's Opus stream,
not a Firefox recording. Independent ffmpeg decoding reported an Opus packet
warning in browser output; the actual Android player cases nevertheless passed.


The subsequent complete fourteen-case run on the separate emulator finished at
13/14: all four recipient playback cases passed, but RecordingOwnerUiTest timed
out waiting for background capture to be both paused and idle. Background
pause under this run is unqualified; its cause is under investigation using a
single-case rerun of the identical APK. This failure is retained in the receipt
and supersedes any inference that the complete latest suite is green. JVM gates
passed at 401 protocol and 1,963 app tests; latest-source lint/release qualification
is still running.


Full local CI for the recipient implementation completed successfully in
18m 58s: 401 protocol tests, 1,963 app tests, debug/release lint and debug/release
APK assembly. The [full-CI receipt](evidence/native-recording-recipient-full-ci-2026-10-10.json)
binds results to source and APK hashes; it proves neither hosted CI nor a signed
production shipment. The reproducible owner background-pause failure remains
open. A diagnostic-only owner instrumentation change was applied after this
build, preserving all production source and the original pause assertion.


The diagnostic-only owner APK passed the unchanged background pause/resume
and original export assertions in 26.509s after the concurrent build completed,
using the same production app APK. The [diagnostic receipt](evidence/native-recording-owner-diagnostic-2026-10-10.json)
records that pass. Resource pressure is a possible factor, not an established
root cause; the earlier full-run and isolated background-state failures remain
in the evidence. A complete run without a concurrent build is underway.


The full settled fourteen-case suite passed in 43.439s on the same isolated
emulator with no concurrent Gradle build. This includes the unchanged owner
pause/resume assertions and all four recipient playback cases. The full-CI and
settled-runtime receipts prove this checkpoint's local gates; earlier failures
under load remain recorded and require investigation. Sender Add/Upload/Send,
durable node cleanup, stop-notice recovery, live-call video, hosted CI, physical
endurance/interruption and signed production delivery remain open.
