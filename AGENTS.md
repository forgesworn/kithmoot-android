# kithmoot-android

A native Kotlin implementation of the KithMoot protocol: an open, serverless
workspace with rooms, chat and calls over Nostr. This is the second,
independent implementation, checked against the published interop vectors
rather than against the TypeScript reference client
([`forgesworn/kithmoot`](https://github.com/forgesworn/kithmoot)).

## Build & Test

Use JDK 21 and an Android SDK with platform 35 and build tools 35.0.0. Set
`ANDROID_HOME`, or put `sdk.dir=/path/to/sdk` in the gitignored
`local.properties`.

Every `:app` task needs the three pinned native bundles prepared first. The
build refuses to start without them. `:protocol:test` needs none of them.

| Command | Purpose |
|---------|---------|
| `scripts/fetch-link-bridge.sh build/link-ffi-android.zip && python3 scripts/prepare-link-bridge.py build/link-ffi-android.zip` | Fetch and verify the reviewed ForgeSworn Link bundle (required before any `:app` task) |
| `scripts/fetch-vmls-ffi.sh build/vmls-ffi-android.zip && python3 scripts/prepare-vmls-ffi.py build/vmls-ffi-android.zip` | Fetch and verify the reviewed VMLS engine bundle (required before any `:app` task) |
| `python3 scripts/prepare-mesh-radio.py` | Fetch and verify the pinned native BLE engine (required before any `:app` task) |
| `./gradlew :protocol:test` | Protocol interop vectors (pure JVM, no Android) |
| `./gradlew :app:testDebugUnitTest` | App unit tests, including the message-layer vectors |
| `./gradlew :app:lintDebug :app:lintRelease` | Android lint |
| `./gradlew :app:assembleDebug :app:assembleRelease` | Build both variants |
| `./gradlew :app:installDebug` | Install the debug build on a connected device or emulator |

After the three preparation steps above,
`./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease`
is what CI runs on pull requests and pushes to `main` and the qualification
branches `feat/native-keeper-journal`, `feat/native-device-registry` and
`feat/native-rekey-preflight`, `feat/native-host-controls` and
`feat/native-rekey-interruption`, `feat/native-host-status`, `feat/native-member-controls` and `feat/native-retirement-store`, `feat/native-invitation-controls`, `feat/native-pending-recovery`, `feat/native-retirement-restart`, `feat/native-retirement-refusal`
and `feat/native-invitation-replacement`, `feat/native-invitation-generation-storage`, `feat/native-invitation-replacement-restart`, `feat/native-invitation-sharing-read`, `feat/native-replacement-restart-index-digest`, `feat/native-replacement-pending-store-refusal`, `feat/native-pending-store-admission-integration`
and `feat/native-link-route-read`, `feat/native-keeper-rekey-echo`, `feat/native-replacement-capacity`, `feat/native-pending-proof-report`, `feat/native-receiver-key-observation`, `feat/native-receiver-main-integration`, `feat/native-guest-preview-gate`, `feat/native-receiver-observer-variants`, `feat/native-replacement-archive`, `feat/native-replacement-envelope`, `feat/native-missing-index`, `feat/native-receiver-caller-bound`, `feat/native-atomic-file-overlap`, `feat/native-atomic-file-overlap-reporting`, `feat/native-replacement-spend-records`, `feat/native-active-spend-records`, `feat/native-hybrid-main-integration`, `feat/native-hybrid-logo-editor`, `feat/native-hybrid-project-logo`, `feat/native-atomic-file-serialization`, `feat/native-hybrid-current-integration`
(`.github/workflows/ci.yml`). The pending-store branches add five independently required cold-refusal shards.
The feature branch triggers retain the original gates without requiring PR creation. The current integrated workflow
requires all 16 executed jobs: verify, separate APK packaging, two recovery
matrix values, five
pending shards, two signing lineage checks and five recording matrix jobs.
Packaging requires verify and every APK consumer requires packaging; all
artifacts come from the same run and commit.

## Structure

```
protocol/       pure JVM module: crypto (BIP-340, NIP-44 v2), protocol
                 (events, rooms, credentials, roster, signalling, access, TURN)
                 and vmls (the VMLS/1 leaf-binding reader the MLS vault checks)
app/             the Android application
  account/       Nostr account: signer apps (NIP-55), bunkers (NIP-46), Signet, npub
  relay/         relay pool, sockets, filters, de-duplication
  session/       room session, presence, roles, chat, identity, pairing links
  media/         WebRTC engine, negotiation, local capture
  service/       foreground service a screen share runs under
  storage/       encrypted saved rooms, identities and invitation capabilities
  ui/            Compose: theme, start screen, room, tiles, chat, controls
  cadence/ discovery/ epoch/ mls/ notifications/ telecom/ update/
                 Bothy cadence, box discovery, rekey epochs, the MLS vault,
                 notifications, Android telecom calls, update checks
g5-signer/       NIP-55 fixture signer app (deterministic keys, never a real
                 identity), the signer package G5ProductJourneyTest drives
mesh-radio/      native BLE engine: the pinned upstream sources are fetched by
                 scripts/prepare-mesh-radio.py and compiled here
sdk-signet-contacts/
                 the pinned Signet contacts SDK source, built as a JVM library
docs/            release, interop and feature notes
scripts/         signing, lineage and vector-generation scripts
```

## Conventions

- `protocol/src/test/resources/kithmoot-vectors.json` is a verbatim copy of
  the published vectors: never edit it by hand.
- `:protocol` has no Android dependencies by design; keep it a plain JVM
  module so the vector suite stays fast and reusable outside the app.
- Roster and vector-decoding tests re-encode what they decode and compare
  JSON, rather than comparing parsed models, so an unmodelled field cannot
  silently drop from both sides and still pass.

## Key Files

| File | Purpose |
|------|---------|
| `protocol/src/main/kotlin/dev/forgesworn/kithmoot/protocol/` | Events, rooms, credentials, roster, signalling |
| `app/src/main/kotlin/dev/forgesworn/kithmoot/session/RoleArbiter.kt` | Decides which of a person's own devices holds the microphone |
| `app/src/main/kotlin/dev/forgesworn/kithmoot/ui/room/Tiles.kt` | Folds the roster into one tile group per participant |
| `docs/android-release.md` | Signing, lineage and acceptance evidence for a release |

## Common Pitfalls

- Recovery checks in `scripts/check-recovery-emulator.sh` replace saved room
  data on the target emulator; require an explicit `ANDROID_SERIAL` and
  refuse physical devices.
- Debug builds allow cleartext to `localhost` and `10.0.2.2` so an emulator
  can reach a relay on the development machine; release builds refuse
  cleartext outright.
- Release builds are R8-minified with resource shrinking; debug builds are
  not, so a release-only crash needs a release build to reproduce. Keep
  `app/build/outputs/mapping/release/mapping.txt` from the exact build to
  read its stack traces, and keep the JNA/uniffi and WebRTC rules in
  `app/proguard-rules.pro` intact: native code resolves those names at
  runtime.
- Groups the protocol does not yet implement (`channelDerivation`,
  `roomEpoch` beyond the peek, `agentOwnership`, `chatAttachment`,
  `approvalControl`, `roomDescriptor`, `verificationWords`) are counted by
  `VectorCoverageTest` without being run; do not mark them passing.
