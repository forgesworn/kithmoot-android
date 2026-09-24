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

| Command | Purpose |
|---------|---------|
| `./gradlew :protocol:test` | Protocol interop vectors (pure JVM, no Android) |
| `./gradlew :app:testDebugUnitTest` | App unit tests, including the message-layer vectors |
| `./gradlew :app:lintDebug :app:lintRelease` | Android lint |
| `./gradlew :app:assembleDebug :app:assembleRelease` | Build both variants |
| `./gradlew :app:installDebug` | Install the debug build on a connected device or emulator |

`./gradlew :protocol:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease`
is what CI runs on pull requests and pushes to `main` (`.github/workflows/ci.yml`).

## Structure

```
protocol/       pure JVM module: crypto (BIP-340, NIP-44 v2) and protocol
                 (events, rooms, credentials, roster, signalling, access, TURN)
app/             the Android application
  account/       Nostr account: signer apps (NIP-55), bunkers (NIP-46), Signet, npub
  relay/         relay pool, sockets, filters, de-duplication
  session/       room session, presence, roles, chat, identity, pairing links
  media/         WebRTC engine, negotiation, local capture
  service/       foreground service a screen share runs under
  storage/       encrypted saved rooms, identities and invitation capabilities
  ui/            Compose: theme, start screen, room, tiles, chat, controls
g5-signer/       signer module
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
- Groups the protocol does not yet implement (`channelDerivation`,
  `roomEpoch` beyond the peek, `agentOwnership`, `chatAttachment`,
  `approvalControl`, `roomDescriptor`, `verificationWords`) are counted by
  `VectorCoverageTest` without being run; do not mark them passing.
