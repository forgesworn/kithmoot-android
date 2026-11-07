# Android scoped VMLS interoperability

Status: **local candidate; release build and independent re-review pending**

## Source and artefact identity

- Android base: `b0d8a2f22290846afc8dac71edc63fad6eb2164d`.
- VMLS engine: Vennel `feea45fc4ca172b05ea671579df51e936c5804b6`, the merged VMLS 0.1.2 revision on `main`.
- Shared binding fixture: standalone `1c0adece2ec71ccfca0d98cafc7e3ddb0ebf33a2`, byte-identical to `protocol/src/test/resources/vmls/vmls-binding-v1.json` and containing exactly 43 cases, six valid framed cases and five unframed cases.
- Hosted bundle: Vennel post-merge Android run 42355481980. Archive SHA-256 `0de096b79e7ef3a7d98c97dac74ea0582d5b1856d8bdb8d646d911b82aceaa26`.
- `manifest.json`: `7ae7edf6658ba386cc4cd3ece43877b7e6ab970e69f2922de505f7b16385a946`.
- `manifest.txt`: `ce443a3d85d8baf01eb2dc390bb9bb9ec04aa16ac86512c2cd4d262d3ce6b3d0`.
- Rust 1.94.1, min SDK 26, arm64-v8a and x86_64. The generated Kotlin is byte-identical between the local and hosted builds; native binaries differ, so the hosted archive is the pinned hand-off.

## Implementation

- `EngineJoin` and `VmlsRuntime` pass the current `VmlsPlatform` through capability opening and pending-introduction completion, including retries and alternative inviters.
- Invitation inspection passes the vault's current revoked credential ids through a new read-only `MlsVault.revokedCredentialIds` accessor and its unit assertion.
- `admissible` takes the current platform explicitly; its instrumentation call sites pass the same engine platform.
- `LeafBindingTest` requires the exact published case set and names the two new duplicate-tag/missing-scope and expired/future-dated precedence cases.
- `scripts/prepare-admin-vmls-dev.py` selects the same merged source for the explicitly selected Admin2 development bundle, which remains excluded from release builds.
- Existing `EngineSessions` still uses the empty-revocation-list constructor, and no production caller writes `revokeCredential`. Both the inspection list and native capability revocation enforcement therefore lack production coverage.

## Verification completed locally

- Baseline and updated `./gradlew :protocol:test`: 684 tests passed with no failures, errors or skips, including all 38 framed independent Kotlin reader cases and the exact-case assertion. The five unframed cases are counted and covered by synthetic framing checks, not presented as 43 independent reader exchanges.
- Baseline and updated `./gradlew :app:testDebugUnitTest`: 3,387 tests passed with no failures or errors and one skip, against the old and new bundles; the final run includes the new revocation-accessor assertion.
- `python3 scripts/check-vmls-prepared.py build/vmls-ffi-android.zip`: all five exact/forge/refusal checks passed.
- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`: passed.
- `./gradlew -Pkithmoot.experimentalInvitationMls=true -Pkithmoot.adminInvitationBundle=true :app:assembleDebug :app:assembleDebugAndroidTest`: passed for the explicitly selected Admin2 development profile; no release task was allowed.
- Android 15 arm64 Google APIs emulator (`emulator-5554`, not GrapheneOS):
  - `SessionHostEngineTest`, `SessionDriverEngineTest`, `CoordinatedVaultEngineTest`: 19 tests passed.
  - `CoordinatedVaultRestartTest`: two tests passed.
  - `SessionRestartTest`: two tests passed.

## Remaining checks and limits

- `:app:lintDebug`, `:app:lintRelease` and `:app:assembleRelease` are pending.
- Publishing the `vmls-ffi-android-feea45f` prerelease is a required precondition for hosted CI and has not yet occurred.
- `admissible`, `addJoins` and `openCapability` have compile-time evidence only here; the live-box join lab was not run. Reading the vault before inspection also means a pending witness can surface `JoinRefusedException` before the native inspection error that previously led.
- No two-emulator/live-box lab, public relay, independent live mailbox/witness/allocation service or physical device was exercised.
- No independent second-person end-to-end exchange is claimed from the in-memory fixtures.
- No screen or user interaction changed. No deployment, production enablement, human security approval or physical-device acceptance is claimed.
