# Android release signing and device acceptance

The public website currently offers production-signed 0.6.7 (30), Android 13 or later. Signing, publication and physical acceptance are recorded separately.

## 0.6.11 the secure-update crash and the camera ladder

Version code 34 fixes a process crash and puts a ceiling on what the camera
costs in a group call. No wire change: web and desktop peers need nothing.

The crash: a local track change (a camera or microphone toggle, a share
starting or stopping, or the engine's first read of them) while the room was
between epochs, which is every link rotation, every removal and every rejoin,
called the fail-closed `announce()` and the `IllegalStateException` ("Room
publication is blocked during a secure update") ended the process. CI's
`recovery-emulator` lane hit it on `main` after 0.6.10 in
`PersistentGroupUiTest.a_create_and_join_web_group`. `RoomSession.setTracks`
is now best-effort like a heartbeat: the set is kept while the gate is shut
and goes out in the successor epoch's first announcement, which
`RoomEpochTransitionTest` pins both ways. The engine's jobs also now run
under a supervisor with a handler, so a media job that throws is a
`KithMootMedia` log line and not the end of the app.

The heat: every remote device on a call has its own peer connection and its
own encoder, and each encoded the full 1280 by 720 at 30 with no bitrate
ceiling. `VideoLadder` fits the one camera source to the number of devices
it goes to (one: 720p at 30; two or three: 540p at 24; four or more: 360p at
15) with one downscale at the source, and caps each camera sender's bitrate
(1.2 Mbps, 800 kbps, 500 kbps). Screen shares are untouched. The rung moves as
links open and close and as the audience rule changes; a sender added later is
capped as it is added, on both the add-a-track path and the profile-2 slots.
`VideoLadderTest` pins the steps. Not measured on a handset yet: the check is
a ten-minute three-way video call on the Pixel with `dumpsys thermalservice`
sampled every two minutes, and the far end's received size in
`chrome://webrtc-internals`.

## 0.6.10 the freeze on calls

Published on 22 September 2026 from `f4a1efa`: owner-signed on the M4 with the production key and lineage (v3 only), APK SHA-256 `725266089a7b32b87870e724e41afc24591c3dfe981adf80688f694aa50c3ad8`, certificate `135bcabf…`, lineage `0ccf5ece…`. Passed the web repository's publication verifier, installed in place over 0.6.9 on the owner's Pixel 10 Pro XL (version 33 reported, first-install time preserved, launched in about a second, no crash), offered as GitHub pre-release `v0.6.10`, and on the website once kithmoot's release PR merges. A live multi-person call on this build is the check that remains.

Version code 33 fixes the "KithMoot isn't responding" that 0.6.9 produced three
times in two days on the owner's Pixel 10 Pro XL, always during or around a
call. All three system traces show the same deadlock: a roster change ran the
engine's reconcile on the main thread, which closed the departed device's peer
connection while holding the engine lock; that close waits for libwebrtc's
signalling thread, and the signalling thread was delivering an ICE state
callback that took the same lock. Now nothing that runs on a WebRTC callback
takes the engine lock, a link is closed only after the lock is released, and
the engine's collectors run off the main thread. `LinkTableTest` pins the two
rules. It also carries the epoch request admission proof (PR #70), which the
web keeper deployed on 22 September now requires: without this build an
Android member who misses a removal in a keeper room cannot catch up.

Not changed here, and worth knowing while judging a hot phone: the camera is
captured at 1280 by 720 at 30 frames a second and every remote peer gets its own
encoder with no bitrate, resolution or framerate cap.

## 0.6.8 sharing and listening candidate

Version code 31 adds app playback capture during screen sharing, preserves an
existing listening device on automatic joins, makes rapid explicit handovers
win within the same clock second, and sends drawing segments while the finger
is down. Expanded screen views display both local and remote marks. Drawing
has a separate 480-per-20-second sender allowance; the ordinary negotiation
allowance remains 120. Existing web/desktop releases with the old combined
allowance can still stop receiving sustained strokes until updated.

Native WebRTC has one input stream here. While sharing sound, that stream is
advertised as screen-audio and mixes the microphone only when its independent
control is on and unmuted. Android playback capture excludes KithMoot's UID to
avoid sending room audio back to listeners. Apps can prohibit capture; protected
content and voice-communication playback are not bypassed. Android's own
MediaProjection consent and the foreground notification remain required.

Incoming encrypted PNG, JPEG, WebP and GIF attachments can be opened, fitted,
viewed at actual size, zoomed and passed explicitly to another image app.
Downloads happen only on a tap, use HTTPS without redirects, verify the complete
ciphertext hash, and authenticate all envelope records before displaying data.
The viewer allows 32 MiB encrypted files and 16 megapixels. SVG, larger files,
attachment uploads and arbitrary floating overlays on other Android apps are
not part of this update. Open in temporarily writes only the selected decrypted
image into a narrowly scoped FileProvider cache; stale exports are removed on
the next export. Closing or retracting the message closes the viewer.

Local unit, lint and build checks and emulator checks are recorded in
[the sharing evidence](sharing-and-listening-20260919.md). Production signing
still uses the owner's existing key and lineage on M4. This candidate is not
yet published or installed over the Pixel's 0.6.6; the owner is using that phone
for a call. Do not interrupt it for acceptance testing.

## 0.6.7 rejoin, call membership, rotation and no telemetry

Published on 18 September 2026 from `7d153e9`: owner-signed with the production key and lineage (v3 only), APK SHA-256 `a1f4c64b1c296d4eb66bbfdbbfc18206b0d5cc7815e1e92592d64597f4521a5a`, certificate `135bcabf…`. On the website and as GitHub pre-release `v0.6.7`. The signed build was installed on an arm64 emulator and started cleanly with no linkage errors; it has not yet run on a handset.
What it carries: entering a room is never silently dropped after a Leave, and
media starts when the room's epoch becomes active instead of on a one-shot
30 second retry; Android publishes and reads the roster's call membership, so
"on the call" means the same thing on every client and a desktop offers "Join
call" against a phone that is on one; the app turns with the phone all four
ways, honouring the system rotation lock; Google's datatransport uploader is
excluded from the app, with inert stand-ins under the same class names and a
`verifyNoDatatransportTelemetry` check wired into `check`, because MediaPipe
0.10.35 and the ML Kit barcode scanner both reported usage through it; a link
error in the segmenter fails the frame rather than the app. New `KithMootJoin`
log lines cover the join path.

Unproven until it runs on a handset: the segmenter with the stand-ins (the
arm64 emulator dies of SIGILL inside MediaPipe on main and on this build
alike, so it can prove nothing either way); QR sign-in with ML Kit's uploader
removed; the four rotations by eye. First thing to do after installing: turn
background replace on once, and scan one QR code.

## 0.6.6 one-way audio release

Published on 18 September 2026 from `639bc71`: owner-signed with the production key and lineage (v3 only), APK SHA-256 `3c894eb69f106b7987726e3b7e04980057cecf0055759d6995bcfa5ffd918fcd`, passed the web repository's publication verifier, installed in place over 0.6.5 on one Pixel 10 Pro XL, offered on the website and as GitHub pre-release `v0.6.6`.

Version code 29 fixes the cause of "I can see them but not hear them" in the native negotiator. Two ends of a profile-1 pair could complete different negotiations: one side answered before its microphone reached that connection, the offer was repeated, and the second, true answer was either thrown at the stack while it was already settled (libwebrtc refused it and the tile read "Video connection failed" for the rest of the call, though nothing was torn down) or, on the far side, dropped as a duplicate. Descriptions are now compared by what they propose, not by their bytes: candidates, the `o=` version, the media port, the `c=` line and `a=rtcp:` are ignored; directions, ICE credentials, codecs and fingerprints are not. An answer of a known shape is dropped quietly; one of an unknown shape is a disagreement and is repaired with one ordinary renegotiation, bounded per connection. A repeated offer is answered from store when local media has not changed. Profile-1 offers are now re-sent until answered, because a lost offer used to wedge the pair for good, and carry a sequence number so an answer to an abandoned offer is not applied to the current one; a far end that does not echo it is judged exactly as before. Profile 2 is untouched and still switched off. Requires the same owner-held signing key; the physical checks listed under 0.6.5 remain open, with a real call between this build, desktop 0.1.7 and the web client first among them.

## 0.6.5 call reliability release

Published on 18 September 2026 from `b759947`: signed by the owner with the production key and the preview-to-production lineage (APK Signature Scheme v3 only), APK SHA-256 `1a4f0c7226ee5fac299f6b492eaae039892edf401df208cdd4e7d65de58320cc`, checked by the web repository's publication verifier, installed in place over 0.6.4 on one Pixel 10 Pro XL, and offered on the website and as GitHub pre-release `v0.6.5`. The signer writes its APK under `umask 077`; the first upload was therefore unreadable by the web server and answered 403 for about two minutes, and the web repository's deploy script now sets the mode itself. The physical checks listed at the end of this section are still open.

Version code 28 brings Android level with the web client's call work of 17 and 18 September 2026. Remote tiles are keyed by role rather than by track id, so a far end that toggles its camera or microphone no longer loses its tile. The room's third default relay is added. The microphone button now mutes by disabling the track and keeps the microphone open, as the web client does, so unmuting is instant and the person's tile says "muted" to everybody else; the microphone is released on leaving the call. Tiles show "muted" and "silenced for you" as two distinct states. "Backdrop" replaces what is behind you with one of four sea scenes, with fish swimming past; there is no blur. The background pipeline was proven on an arm64 emulator only, and adds about 28 MiB to the APK, nearly all of it the MediaPipe runtime.

The profile-2 call scheme (reliable signalling, fixed media slots, generations and pair health) is compiled in and switched off: `CALL_PROFILE_2_ENABLED` in `session/CallProfile.kt` is false, the roster entry carries no `callProfile`, and the wire is unchanged for every pair.

Open before publication, none of it provable without a handset: the three microphone button states and their TalkBack labels, the two tile badges, the front-camera mirror of a backdrop against the real mirrored preview, frame cost and battery of a backdrop on real hardware, and a call between this build, the desktop 0.1.5 preview and the web client.

## 0.6.4 account rooms and notifications

The release adds account room bookmarks, editable public profiles and selectable read/write relays, joined-room notifications with a Zen bell, and private-room call controls. Message alerts require an active room connection; closed-app push and alerts from other rooms are not implemented. Preview text is opt-in and notification sounds are suppressed during calls.

## 0.6.3 rendezvous-provisioning candidate

Version code 26 is the first candidate above the already-installed 0.6.2
production-lineage artifact. It carries the explicit, Bunker-only rendezvous
provision ceremony and its dedicated Keystore-backed recipient vault. It must
be signed with the same owner-held production key and preview-to-production
lineage, then accepted in place on a physical Android 13-or-later device before
any public publication. A successful build is not approval to install or
publish it.

The reviewed signer script obtains its output filename from the unsigned APK's
validated version metadata (`kithmoot-0.6.6-production.apk` for this release) and
refuses a version code at or below 25. This prevents a future candidate from
silently reusing the pre-rendezvous update slot.

## 0.6.2 production-lineage candidate (historical)

Version code 25 raises the production floor to Android 13 and rotates from the
published preview certificate with APK Signature Scheme v3. The lineage trusts
installed data and signature permissions from the preview certificate, while
refusing shared-UID inheritance, rollback and authenticator privilege. Gradle
always emits an unsigned release; the release script alone applies the
owner-selected key and lineage with v1 and v2 signing disabled.

The app keeps the preview's encrypted vaults and AndroidKeyStore entries in
place. If it finds a preview local-key account, it shows the retained npub and
requires the same public identity through a NIP-55 signer app or NIP-46 bunker.
A different account is refused before the stored account is changed. Nothing is
exported and no room, device, invitation, Link credential, consent, cadence or
epoch state is made portable.

CI creates disposable old and new keys on Android 13 and 15. It updates the
same package through the reviewed lineage, proves the UID and production vault
contents survive, and proves the old signer cannot replace the rotated app.
Those disposable checks do not substitute for the exact published preview APK,
the owner-held production key or a physical phone.

## 0.5.14 successor-room recovery candidate

Version code 22 completes authority-pinned room epoch recovery. A retained
device durably follows a signed successor, requests the current epoch after a
missed update, and moves roster, chat, channels, work, descriptor, media
signalling and quiet traffic together. A removed or closed device receives no
successor secret and cannot publish. If a paired Bothy owns the old quiet
cadence, Android blocks publication until Bothy durably retires old-generation
real sends; the exact pending transition resumes after process death.

The four-repository Vennel composition covers the generated JNI call over an
ordinary paired Link route, old-message failure, retained cover, Android and
Bothy restart, and lower-generation refusal. Owner-key signing, exact-preview
upgrade evidence and the physical checks below remain release gates.

New account sign-in accepts only NIP-55 signer apps and NIP-46 signers. The
production UI has no raw `nsec` or hex-key entry, and a release build refuses a
legacy local-key account. Debug previews retain read access so an existing
install is not destroyed before the preview-data decision and migration path
are settled.

## 0.5.13 quiet cadence integration candidate

Version code 21 lets a persistent quiet room connected to Bothy schedule a
bounded cadence hand-off of up to twelve hours for this device. An exact lease
request and any unconfirmed queued inner event survive a lost reply, delegated
counters remain unavailable to the phone, and stop preserves cover through the
original end epoch. The room shows
the acknowledged schedule and Bothy's queue outcome counts. See
[the quiet cadence guide](quiet-cadence.md)
for the ownership model, test evidence and remaining live and physical-device
gates.

## 0.5.12 paired Bothy preview candidate

Version code 20 lets a signed-in participant move a private conversation onto
their paired Bothy. The app authenticates the sheltered relay with the selected
signer, asks for the minimum room-scoped grant, saves a pre-signed revocation
before publishing the grant, and changes routes only after Bothy confirms the
write. A guest proves its exact persona, room and device through a confirmed
roster write before cutover. Disconnect and explicit revocation retire the
Bothy route before the local Link route is withdrawn, including recovery after
an interrupted first attempt.

The private conversation itself is created from a participant in an ordinary
room. Its invitation is sealed to that account and requires an explicit Open
action. The reviewed ForgeSworn Link Android bundle is pinned by source commit,
archive checksum and per-file manifest in `docs/link-android-bridge.md`.

This remains a debug-signed preview using the existing preview certificate.
The composed two-emulator journey covers distinct NIP-55 personas, public-relay
introduction, paired Bothy delivery, retained chat, restart recovery and
two-phase route retirement. Physical-phone continuity, background push,
production signing and a recovery/export path for existing preview installs
remain open release gates.

## 0.5.11 shared-project preview candidate

Version code 19 connects the signed-in account's shared project directory to the
native Projects tab. Create or edit a project, choose people and agents, share
room invitations and deliberately join invitations from others. Projects and
uncertain updates survive process restart in an encrypted device vault; Retry
sends the same retained ciphertext. Opening a project room still checks normal
admission and refuses a room saved under a different identity.

This preview retains the existing signing certificate. The
[shared-project guide](shared-projects.md) describes the protocol, installed-app
checks and remaining limits. Native conflict resolution, a cross-project
decision inbox, live Oathrun pairing and physical-phone acceptance remain open.

## 0.5.8 shared-work preview

Version code 16 adds a native Work tab beside Chat and Call. It uses the same
signed assignments as the web client: create work from an advertised agent
action, answer a question, review the exact result, request changes and manage a
stopped handoff. Decisions appear before routine progress. An encrypted device
journal retains verified history and uncertain sends for explicit exact retry.

The preview retains the existing preview certificate. Native unit, lint, emulator
screen, socket/vault and actual room-entry tests cover the implementation;
[shared-work evidence](shared-agent-work.md) describes their boundaries.
Quiet-room work, paired-device signing, the first live Oathrun connection and
physical-phone acceptance remain open. Those limits do not change the existing
chat and call support. This is a debug-signed preview, not a production release.

## Create the owner-held signing lineage

Use JDK 21 and Android build tools 35.0.0. The owner must create and back up a
new KithMoot production key outside the checkout before this step. Do not reuse
an SDK debug key or another application's key, and do not let an unattended
build invent the lasting production identity.

The reviewed interactive helper fixes the alias, algorithm, lifetime and
certificate subject while leaving the path and password with the owner. It
refuses non-interactive input, an existing file and any path inside the
checkout:

```sh
bash scripts/prepare-production-key.sh /absolute/private/path/kithmoot-production.p12
```

`keytool` asks for the password directly. Make two offline byte-for-byte
backups, compare both backup checksums with the printed keystore checksum and
record the public certificate fingerprint independently before continuing.

Supply these values from the host's private credential store:

| Variable | Value |
|---|---|
| `KITHMOOT_PREVIEW_KEYSTORE` | Retained keystore that signed the published preview |
| `KITHMOOT_PREVIEW_STORE_PASSWORD` | Preview keystore password |
| `KITHMOOT_PREVIEW_KEY_ALIAS` | Preview signing-key alias |
| `KITHMOOT_PREVIEW_KEY_PASSWORD` | Preview key password |
| `KITHMOOT_PREVIEW_CERT_SHA256` | Independently recorded published-preview certificate fingerprint |
| `KITHMOOT_KEYSTORE` | Owner-held production keystore path |
| `KITHMOOT_STORE_PASSWORD` | Production keystore password |
| `KITHMOOT_KEY_ALIAS` | Production signing-key alias |
| `KITHMOOT_KEY_PASSWORD` | Production key password |
| `KITHMOOT_CERT_SHA256` | Independently recorded production certificate fingerprint |
| `KITHMOOT_LINEAGE` | New output path outside the checkout, ending in `.lineage` |
| `ANDROID_HOME` | Android SDK path |

For the recovered KithMoot preview signer, use the one-command local ceremony:

```sh
bash scripts/create-kithmoot-production-lineage.sh \
  /absolute/private/path/kithmoot-preview-debug.keystore \
  /absolute/private/path/kithmoot-production.p12 \
  /absolute/private/path/kithmoot-preview-to-production.lineage
```

It prompts for the preview and production passwords directly in the terminal,
keeps them off the command line and writes only the lineage output. It pins the
published 0.5.12 preview certificate and the fixed `androiddebugkey` and
`kithmoot-production` aliases. For a different reviewed preview signer, supply
the variables below and run the lower-level command instead:

```sh
bash scripts/create-signing-lineage.sh
```

The script verifies both certificates before creating anything, refuses to
overwrite an existing lineage, assigns the five reviewed capabilities, and
prints the lineage SHA-256. Back up the lineage with the production key and
record its checksum independently. Passwords reach `keytool` and `apksigner`
through named environment variables; the scripts disable shell tracing.

## Build with the production key and lineage

For the recovered KithMoot preview signer, use the local interactive builder:

```sh
bash scripts/build-kithmoot-production-release.sh \
  /absolute/private/path/kithmoot-production.p12 \
  /absolute/private/path/kithmoot-preview-to-production.lineage
```

It downloads and verifies the pinned Link bridge, then prompts for the
production password without placing it in shell history, derives and checks
the public certificate and lineage hashes, then invokes the reviewed builder
below. For a different reviewed production signer, keep the
production variables above, add the independently recorded
`KITHMOOT_LINEAGE_SHA256`, and run the lower-level command instead:

```sh
bash scripts/build-signed-release.sh
```

The script first verifies the lineage checksum. It runs protocol/app tests,
release lint and the unsigned release build, then signs the versioned production
APK with only the production key and the lineage. It requires v3 signing,
Android 13 minimum, target SDK 35, version code greater than 25, the exact
package name and a non-debuggable manifest. It rejects v1,
v2, the Android debug certificate and any certificate mismatch, then prints
the APK checksum. It does not publish or install anything.

Ordinary Gradle builds always retain the unsigned release variant for CI. There
is no Gradle signing fallback and no production credential in repository CI.
For Play distribution, choose the upload/app-signing arrangement separately;
a locally signed APK does not create a listing or enrol it in Play App Signing.
See Android's [signing guide](https://developer.android.com/studio/publish/app-signing)
and [APK signature verification](https://developer.android.com/tools/apksigner).

## Preserve existing installations

The production certificate differs from the published preview certificate, but
the reviewed v3 lineage allows an in-place update on Android 13 and later while
preserving the package UID, app-private files and AndroidKeyStore entries. Never
uninstall the preview as a migration step. Before publication, install the
exact public preview on an isolated Android 13-or-later device or emulator,
create representative data, update it with the exact candidate, and verify the
retained account and every production vault. Also prove the old preview-signed
APK is refused after rotation. Record both APK hashes, both certificate hashes,
the lineage hash, UID, Android version and results.

## Physical acceptance for the exact candidate

Record the source commit, APK checksum, certificate fingerprint, device/model,
Android version and date alongside the results. Preserve existing app data.

Capture the exact public facts before and after the manual in-place update. The
collector is read-only: it validates the supplied APK, refuses emulators and
Android versions below 13, pulls only the installed APK and records a hash of
the device serial. It never reads app-private storage, logcat, screenshots or
signer data, and it refuses to overwrite an evidence file.

```sh
python3 scripts/capture-physical-release-state.py \
  --channel preview \
  --apk /path/to/exact-public-kithmoot-0.5.12-preview.apk \
  --out /private/evidence/physical-before.json

# Deliberately install the verified production APK with `adb install -r`, then
# complete the manual checks below without uninstalling or clearing KithMoot.

python3 scripts/capture-physical-release-state.py \
  --channel production \
  --apk app/build/outputs/apk/release/kithmoot-0.6.3-production.apk \
  --lineage /absolute/private/path/preview-to-production.lineage \
  --production-cert-sha256 "$KITHMOOT_CERT_SHA256" \
  --out /private/evidence/physical-after.json
```

The two records must name the same device-serial hash and package UID. The
before record must match the pinned public preview; the after record must match
the exact production certificate and embedded two-signer lineage. The records
establish installation identity and continuity only; record the manual vault,
background, battery, accessibility and second-device results separately.

- Create and reopen a saved group; force-stop and restart the app; verify the
  same identity, rooms and creator controls return with media initially off.
- Join a group while all other members are offline. Replace its invitation and
  confirm the old link is refused. Verify explicit errors when relay storage is
  unavailable and when the saved room has moved to a newer epoch.
- Exchange encrypted chat with the current web client, including reconnecting
  after switching Wi-Fi/mobile data. Verify the documented read-only message
  features and unsupported features remain accurately labelled.
- Schedule a quiet cadence through the reviewed Bothy deployment, rotate the
  room while the app is backgrounded, and verify the old queued message is
  marked **Conversation rekeyed**, the successor room resumes only after the
  Bothy receipt, and both states survive force-stop and restart.
- Exercise microphone, camera, screen sharing, expanded view and picture in
  picture with a second device. Confirm sharing stops when requested and Android
  foreground-service controls agree with the app.
- With TalkBack, complete entry, saved-room navigation, chat and destructive
  confirmations. Check focus order, spoken names, large text and keyboard state.

The emulator recovery script deliberately replaces saved data and refuses
physical serials. Do not relax that guard for device acceptance. Existing
screenshots and a successful emulator run do not close these checks for a new APK.
