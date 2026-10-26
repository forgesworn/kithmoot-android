# Android private-group product candidate

This is an explicit, default-off candidate selection. It does not publish a native asset, build or sign an APK, install it, enroll accounts, pair nodes, or create groups. Qualification and owner approval remain separate.

Select with both properties:

```sh
-Pkithmoot.invitationProfile=admin2 -Pkithmoot.accountNetworkDefault=private-invitations
```

`kithmoot.invitationProfile` accepts only `disabled` (default) or `admin2`. The selected product uses the normal pinned native bundle; combining it with either `kithmoot.experimentalInvitationMls=true` or `kithmoot.adminInvitationBundle=true` refuses configuration. The local Admin2 development selector still refuses every release task. Both experimental BuildConfig fields remain false in the selected product, including release.

The APK selection does not enable the persisted user switch. In Settings → Connections, enable **Private groups and restore witness** explicitly, then open **Private groups** in Settings. Enroll the vault at the owner's restore witness and provision the account's rendezvous child through the existing account flow. The paired home uses the owner's Bothy and Link route; signer and account setup continue through the existing Bark/Cambium/Heartwood path. No public discovery, trust inference, relay fallback, or automatic account enrollment is introduced.

**Private invitations** is a manual authenticated private ceremony. Pairing does not grant storage rights. The group and each member must use the same paired home. An independently authorized node keeper handles minimal package registration and returns its exact node-signed receipt. A designated exact device leaf controls group invitations, Add/Remove and admin roles; node keeper authority is separate. Consent retains the exact profile, policy, intended identities, device, credential, installation, admin-set commitment and deadline. This is not an automatic invitation UX.

Trusted local restart uses the existing sealed snapshot, generation and witness checks and the Admin2-specific native opener. Lost or untrusted state requires a fresh invitation and rejoin; no migration or fallback is implemented. Historical Invitation1 snapshots are refused in the selected product. A default build refuses both invitation profiles at restore and at public invitation operation boundaries; historical FullVmls1 remains separate.

The normal preparer verifies the archive SHA-256 and source commit. At every Android build boundary, `--verify-prepared` pins both manifest bytes to that reviewed ZIP, checks the exact file set, and validates each native library and generated binding against those immutable manifest hashes. Rewriting a generated payload and its manifest together cannot satisfy this check.

Deferred qualification commands (run after concurrent Gradle qualification is finished):

```sh
python3 scripts/check-invitation-build-profile.py
python3 scripts/check-vmls-prepared.py /path/to/reviewed/vmls-ffi-android.zip
# Prepare all three pinned native inputs first, following AGENTS.md.
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
./gradlew -Pkithmoot.invitationProfile=admin2 -Pkithmoot.accountNetworkDefault=private-invitations :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
```

The build-property matrix executes the real Gradle configuration and checks its BuildConfig values and development release refusal without building an APK. Runtime tests exercise shared pairing cancellation, keeper request decoding/registration routing and legacy refusal. Restore tests use the same authorized snapshot dispatcher as the native opener. These checks do not establish physical-device acceptance, stack deployment, or independent review.
