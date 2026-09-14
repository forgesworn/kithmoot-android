# Android release signing and device acceptance

The public APK channel is currently a debug-signed preview. Version 0.6.0 is the
first production-lineage candidate and requires Android 13 or later. Publishing
it still requires the owner-held key, exact-preview upgrade proof and the
physical acceptance recorded below.

## 0.6.0 production-lineage candidate

Version code 23 raises the production floor to Android 13 and rotates from the
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
release lint and the unsigned release build, then signs
`kithmoot-0.6.0-production.apk` with only the production key and the lineage. It
requires v3 signing, Android 13 minimum, target SDK 35, version code greater
than 22, the exact package name and a non-debuggable manifest. It rejects v1,
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
  --apk app/build/outputs/apk/release/kithmoot-0.6.0-production.apk \
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
