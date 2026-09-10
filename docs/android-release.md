# Android release signing and device acceptance

The public APK channel is a debug-signed preview. Release signing support alone
does not promote it to a production release.

## 0.5.7 preview candidate

Version code 15 contains the conversation workspace and the fixes merged in
PR #18: quiet sends wake at their slot deadline, and a contact card's Link
transport hints no longer imply ownership of a Nostr message relay. Saved
contacts and explicit keeper-confirmed relay marks remain available.

The source changes pass native verification and the recovery emulator. A
preview build keeps the existing preview certificate so it can update without
discarding saved rooms. No physical device was connected during preparation;
the physical acceptance checklist below remains open for this candidate.

## Build with the selected release key

Use JDK 21 and the Android SDK described in the README. Keep the keystore outside
the checkout and back it up privately: future APK updates need the same signing
identity. Do not reuse the SDK debug keystore or another application's key.

Supply these environment variables from the host's private credential store:

| Variable | Value |
|---|---|
| `KITHMOOT_KEYSTORE` | Existing keystore path; an absolute path is preferred |
| `KITHMOOT_STORE_PASSWORD` | Keystore password |
| `KITHMOOT_KEY_ALIAS` | Signing key alias |
| `KITHMOOT_KEY_PASSWORD` | Key password |
| `KITHMOOT_CERT_SHA256` | Independently recorded signing certificate SHA-256 fingerprint |
| `ANDROID_HOME` | Android SDK path |

Passwords are read from the environment, never command-line arguments or tracked
files. Do not enable shell tracing, publish environment dumps or use Gradle debug
logging with signing credentials loaded. `ANDROID_BUILD_TOOLS` can select a
build-tools directory; the default is `$ANDROID_HOME/build-tools/34.0.0`.

```sh
bash scripts/build-signed-release.sh
```

The script runs protocol/app tests, release lint and the release APK build, then
checks the actual APK signature against the expected certificate, rejects the
Android debug certificate and a debuggable manifest, checks the application ID,
and prints the APK checksum. It does not publish or install anything.

With no signing variables, ordinary Gradle builds retain the unsigned release
variant for CI. A partially configured signing environment fails with the names
of the missing variables. There is no fallback to debug signing.

For a Play distribution, select the upload/app-signing arrangement separately;
a locally signed APK does not create a Play listing or enrol it in Play App
Signing. See Android's [signing guide](https://developer.android.com/studio/publish/app-signing)
and [APK signature verification](https://developer.android.com/tools/apksigner).

## Preserve existing installations

A production key differs from the published preview's debug certificate. Android
will reject it as an in-place update of that preview. Do not uninstall a user's
preview to bypass this: its saved room access is in app-private encrypted storage
and there is no export/recovery migration in this release. Test the new signing
identity on a spare device or unused Android user profile. Settle the preview
migration and version increment before publishing the first production APK.

## Physical acceptance for the exact candidate

Record the source commit, APK checksum, certificate fingerprint, device/model,
Android version and date alongside the results. Preserve existing app data.

- Create and reopen a saved group; force-stop and restart the app; verify the
  same identity, rooms and creator controls return with media initially off.
- Join a group while all other members are offline. Replace its invitation and
  confirm the old link is refused. Verify explicit errors when relay storage is
  unavailable and when the saved room has moved to a newer epoch.
- Exchange encrypted chat with the current web client, including reconnecting
  after switching Wi-Fi/mobile data. Verify the documented read-only message
  features and unsupported features remain accurately labelled.
- Exercise microphone, camera, screen sharing, expanded view and picture in
  picture with a second device. Confirm sharing stops when requested and Android
  foreground-service controls agree with the app.
- With TalkBack, complete entry, saved-room navigation, chat and destructive
  confirmations. Check focus order, spoken names, large text and keyboard state.

The emulator recovery script deliberately replaces saved data and refuses
physical serials. Do not relax that guard for device acceptance. Existing
screenshots and a successful emulator run do not close these checks for a new APK.
