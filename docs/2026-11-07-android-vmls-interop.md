# Android scoped VMLS interoperability

Status: **PR #399 merged; all 17 baseline CI jobs passed; production-signed 0.6.137 installed on the physical Pixel with three native checks passed. Live-box joins and owner acceptance remain open.**

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
- Baseline and updated `./gradlew :app:testDebugUnitTest`: 3,386 tests passed, one skipped and no failures or errors, against the old and new bundles; the final run includes the new revocation-accessor assertion.
- Four framed leaf-mismatch/signature cases assert unsigned decode success only, not full leaf or signature verification.
- `python3 scripts/check-vmls-prepared.py build/vmls-ffi-android.zip`: all five exact/forge/refusal checks passed, including the public `vmls-ffi-android-feea45f` fetch path.
- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`: passed.
- `./gradlew -Pkithmoot.experimentalInvitationMls=true -Pkithmoot.adminInvitationBundle=true :app:assembleDebug :app:assembleDebugAndroidTest`: passed for the explicitly selected Admin2 development profile; no release task was allowed.
- Android 15 arm64 Google APIs emulator (`emulator-5554`, not GrapheneOS):
  - `SessionHostEngineTest`, `SessionDriverEngineTest`, `CoordinatedVaultEngineTest`: 19 tests passed.
  - `CoordinatedVaultRestartTest`: two tests passed.
  - `SessionRestartTest`: two tests passed.

## Serial build closeout (7 November 2026)

The application source at `2e56e3cc34d8901ad63285a2378a0a01a41b4bec` passed
these separate invocations with `--max-workers=1 --no-parallel --no-daemon`,
a 4 GiB Gradle heap and in-process Kotlin compilation:

| Task | Result | Elapsed |
| --- | --- | --- |
| `:protocol:test` | Successful, existing result up to date | 9.72 seconds |
| `:app:lintDebug` | Passed | 872.29 seconds |
| `:app:lintRelease` | Passed | 859.69 seconds |
| `:app:assembleRelease` | Passed, including release-vital lint and R8 | 1151.63 seconds |

The unsigned release APK is 74,417,332 bytes, SHA-256
`43522f4cf42fccefa2c28e0beccce5dbc6768caa86d1c2b6e7418b4231e36aa7`,
and contains eleven arm64-v8a native libraries. It is a local qualification
artefact, not a signed or published app release. The matching R8 mapping
identity and commands are recorded in
[`evidence/android-vmls-interop-20261107-build.json`](evidence/android-vmls-interop-20261107-build.json).

The original parallel lint attempt exhausted its 3 GiB heap and was cancelled.
Its 4 GiB retry spent about eleven of its first twenty minutes collecting
garbage. The serial recovery retained a failed debug-lint attempt whose daemon
exited unexpectedly before the successful retry; it does not turn that attempt
into a passing result. Concurrent follow-up commits changed documentation and
a shell-script comment only; application code and build settings were unchanged.

## Signed 0.6.137 and physical native checks

The versioned release was built on M4 at `0a2efe5b05d2ba347e1bd7c1d2705f1336bcae5d`
with one Gradle worker, no parallel tasks and a 6 GiB heap. Release assembly,
including R8 and vital lint, passed in 173.42 seconds. The separate test APK
at `a6ad35568f1002ddaa5aa91a5a0a1ac81542bafa` adds an isolated native ABI probe;
its only other change is the probe's CI invocation. It changes no production code.

The production-signed APK is 74,429,004 bytes, SHA-256
`2fb51d1c7e6d556c17edc89824aebc2b3cbc547edfe4a152bc8d04ed86067246`.
It has the reviewed production certificate and preview-to-production lineage,
v3 signing only, min SDK 33, target SDK 35, eleven ARM64 libraries and a
non-debuggable manifest. The matching R8 mapping is retained privately.

The physical Pixel 10 Pro XL (owner-identified GrapheneOS, Android 17 build
2026110200, no enabled Google Play Services) was upgraded in place from
0.6.135 (158) to 0.6.137 (160). Its installed APK hash matches the signed
candidate; package UID and all recorded first-install dates are unchanged.
No production package uninstall, data clear or owner storage inspection ran.

- `VmlsNativeLoadTest`: two tests passed against the actual minified release,
  constructing and disposing both native platform variants and exercising
  typed malformed-input rejection through both revocation-aware invitation APIs.
- `WebRtcNativeLoadTest`: one test passed, creating native factory and sources
  without microphone or camera capture.
- The temporary, production-signed instrumentation package was removed, and
  its local signed APK was deleted.
- Foreground launch and home/background/resume passed with the same process.
  The first short resume observation encountered a transient Cambium NIP-55
  signer activity; KithMoot returned without agent input. The original observation
  is retained. No signer request was approved by the agent.

These checks establish installed identity, continuity and the native ABI path,
not retained account/vault correctness or end-to-end media acceptance. The
sanitised record is
[`evidence/android-0.6.137-native-device-20261107.json`](evidence/android-0.6.137-native-device-20261107.json).

## Hosted checks and remaining limits

- Hosted CI run [42376715868](https://github.com/forgesworn/kithmoot-android/actions/runs/42376715868)
  passed all 17 required jobs at `2e56e3cc34d8901ad63285a2378a0a01a41b4bec`
  on attempt 2. The earlier capture attempt failed its native frame-delivery
  assertion (five sink frames despite 369 decoded frames) with emulator
  graphics-buffer errors; the retry passed with unchanged assertions. Final
  0.6.137 follow-up changes require their own exact-head CI readback. PR #399
  merged as `6deaaca258ef276470841b03a0157fc0131c5046`.
- The `vmls-ffi-android-feea45f` prerelease is published at https://github.com/forgesworn/kithmoot-android/releases/tag/vmls-ffi-android-feea45f. Its archive and both manifests match the hosted CI artefact.
- The M4 live-box lab attempt stopped before Android instrumentation: the
  fixture could not connect, and its configured Link relay
  `wss://link1.forgesworn.dev/link` returned HTTP 410. The failed attempt is
  retained; its disposable emulator was stopped and its AVD deleted. The
  two-device stage did not run. An existing operator relay URL is needed to retry.
- A separate diagnostic used the fixture's explicitly supported loopback path
  with the pinned Link relay at `7aad8bf7f331303076b38398e191293af45cc9de`.
  Its real claimed-node/VMLS preflight passed. Android then ran all four lab
  tests and refused the pairing card in each before joining. This is a retained
  failed diagnostic, not successful join evidence. The local relay and emulator
  were stopped and the AVD deleted; the two-device stage did not run.
- `admissible`, `addJoins` and `openCapability` retain compile-time evidence only
  here. Reading the vault before inspection also means a pending witness can
  surface `JoinRefusedException` before the native inspection error that previously led.
- No successful public Link exchange or independent live mailbox/witness/allocation
  service was exercised. The lab fixture uses an in-process Ed25519 witness.
- No independent second-person end-to-end exchange is claimed from the in-memory fixtures.
- No screen or user interaction changed. No deployment, production enablement,
  human security approval or complete physical-device acceptance is claimed.
