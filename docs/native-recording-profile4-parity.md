# Recording profile 4 integration gate

Current Android main `db9eb36c` includes merged PR #254 and the published
0.6.82 release. It implements device-signed participant recording, independent
warnings, retained Stop recovery after leaving/restarting, and the persistent
call gallery with Hide/Show controls. These shipped features are retained by
this integration. The original published protocol vectors remain unchanged.

This branch adds source grants checked before retaining, mixing and drawing
media; bounded history covering the two-second PCM window; complete independent
warning details; and consent read directly from authenticated presence before
collectors run. Consent binds every active recorder's call, person, device,
recording ID and capture mode, while equivalent refreshes keep consent stable.
Capture permission reads the original device's exact accepted notice directly;
a matching public recording ID from another device cannot replace it.

The pre-integration reception candidate `98395351` passed 490 protocol and 47
focused app tests, then both debug APK assemblies. Its
[build receipt](evidence/native-recording-profile4-reception-build-2026-10-11.json)
records those artifacts. Those checks do not qualify the merged candidate.
The strict capture driver now requires all 25 checks, preserving the upstream
non-owner/gallery/export regression and every added source-revocation/history
check. Its six fault checks pass, including readiness and complete-result
requirements. The merged Kotlin candidate still needs fresh verification.

Remaining release requirements include fresh complete JVM/lint/build, mixed
profile-4 native/browser capture and Stop/Send recovery, source/own credential
and access-expiry fences, quiet capacity and delegated acknowledgement, rekey,
physical 45-minute capture/interruption/Save-provider acceptance and a signed
production release containing these additional fixes. The earlier source-grant
hosted run qualified `e3607e11` only; it does not prove this integration.

The original 28 live-warning fixtures are archived verbatim in
`protocol/src/test/resources/recording-live-profile3.json`. The reviewed SDK
now regenerates 29 cases, including profile-4 carriage of legacy warnings and
rejection of future profile 99. Both independent participant fixture suites
remain alongside these complete-JSON wire checks.

## 12 October runtime and admission follow-up

Integrated source `199b39e2` failed hosted recovery instrumentation with an
Android `VerifyError` in `RoomScreenKt`; the verify runner also lacked ripgrep
for the driver fault checks. Commit `f6fdad67` extracts the recording-start
dialog and installs that dependency. Fresh run
[38184180726](https://github.com/forgesworn/kithmoot-android/actions/runs/38184180726)
failed with the same room-screen verifier rejection. The [blocker receipt](evidence/native-recording-profile4-runtime-blocker-2026-10-12.json)
records the original failure; the first refactor did not resolve the runtime rejection. The working tree now
separates the complete room composition from its public callback API, preserving
the existing UI and saved-state keys; fresh runtime verification remains required.
The local full build of the known failing revision was deliberately terminated.

The working tree also adds capture/heartbeat credential and signed-admission
deadlines, per-source expiry checked without roster/UI refresh, retirement of
an owned On before a failed Stop acknowledgement, and fail-closed heartbeat
handling when the owner callback throws. Consent now distinguishes a new
recording episode after a known Off, even when the same ID is reused within one
second; equivalent On refreshes keep the consent episode stable. Focused tests
for publication, reception, source grants, participant ownership and meeting
consent passed: 43 tests, no failures, errors or skips. This proves the focused
shutdown, expiry and consent cases; it does not qualify the subsequent room-screen
composition refactor or replace full release gates.

The complete composition refactor `c1457a24` has now passed hosted JVM tests,
debug/release lint, debug/release APK assembly, 61 recovery instrumentation
invocations (204 tests), and all nine recording Stop/Send restart checks. The
[current verification receipt](evidence/native-recording-room-content-jvm-2026-10-12.json)
records the exact source and log hashes. This clears the previous ART verifier
blocker. Capture and mixed-client Session jobs failed before instrumentation:
the emulator action ran each line of the shell conditional separately. Their
launcher is now a single script, with capture/session dispatch, readiness
failure and unknown-suite behaviour checked locally; fresh hosted qualification
is still required. Browser interoperability now pins merged web main
`9993950a`, including the recording credential/admission expiry guards from
PR #400. The older fixture provenance remains historical rather than rewritten.

The restart follow-up retains the original participant alongside the call and
device-signed Off. Retry refuses another account even when it retains the same
device key, and preserves the pending notice for the original account. Older
call-only entries remain readable. All ten focused journal and participant-recording checks pass, with no failures
or skips; the [original-person Stop receipt](evidence/native-recording-original-person-stop-2026-10-12.json)
records the tested source hashes. Fresh hosted restart qualification remains
required because the earlier `c1457a24` result predates this follow-up. Both
private-node emulator images passed real Wildbloom Upload/Send/Show/Play,
including 256 MiB and slow transfers, on the earlier `c1457a24` source.

The [participant quiet-capacity receipt](evidence/native-recording-participant-quiet-capacity-2026-10-12.json)
adds a 45-minute simulated-clock check with the quiet slot timer enabled and
the complete phone counter range owned by a delegated lease. A non-moderator's
gallery warning reaches a separate Session, refreshes every 30 seconds, and
retries its exact retained Off after a refused Stop. It spends no chat counters,
writes no quiet queue state and makes no box queue calls. All six participant
tests pass. This qualifies warning routing and simulated capacity, not physical
capture endurance or guarded recording-message delivery through a node.

Guarded delegated Send must not reuse ordinary chat's durable box queue:
`QuietTransport.queuedEvents()` exposes those entries to ordinary queue retry,
which does not retain the original recording Send guard. A box queue receipt
also does not establish relay delivery. The current refusal remains until the
original guard, actual authenticated relay confirmation, cancellation/withdrawal
and process-restart behaviour are integrated and qualified together.

The `d6864b1f` hosted run passed eight of nine gates, including capture,
Session interoperability and original-person restart recovery. The Google APIs
private-node slow 256 MiB Upload timed out before any receipt or Send. Recording
clients now retain a bounded 60-second read/write idle allowance alongside the
ten-minute total limit, including when supplied a fixture TLS client. The
[transfer idle receipt](evidence/native-recording-transfer-idle-2026-10-12.json)
records eleven passing focused tests and a real socket receipt delayed beyond
the original ten-second idle default. Fresh hosted slow-transfer qualification
is required; the prior failure is retained rather than counted as a pass.

## Historical initial audit

# Recording profile 4 parity gate

Live upstream audited on 2026-10-11. Web main `676edeb` includes merged PR #395
and signed desktop release 0.1.75. Draft PR #385 overlaps this newer work and
conflicts; its attempted merge was aborted with the worktree clean. Do not merge
that older profile-3 implementation over current participant recording.

At the initial audit, Android candidate `877883ad` advertised profile 3 only for a room authority,
otherwise profile 2. Its roster codec discards profile 4 and its live-state parser
rejects the participant call/recorder/device fields. RoomWork requires moderator
and room-authority secret to record and holds one global meeting warning. This
cannot explain concurrent member warnings or qualify recording alongside current
web/desktop clients. Earlier pinned browser checks cover profile 3, not current
profile 4. The current native source-permission candidate remains useful but is
not the complete release candidate.

Required integration and evidence:

- Independently decode and re-encode the five published participant-recording
  fixtures. Preserve legacy authority warnings. Verify member signatures only
  against the authenticated roster device; bind recorder, device and original
  call. Reject wrong room/device/person/call, malformed state and transplant.
- Accept and advertise profile 4 only once the native receiving/warning UI and
  recording ownership implement it. Profile 4 camera/screen inputs must retain
  the same per-source policy checks as profile 2/3.
- Maintain independently ordered warnings for each recording device and call.
  One recorder's Off cannot erase another recorder's On. Show active warnings
  from every advertised call and obtain consent before joining a recorded call.
- Allow an admitted participant to record without the moderator key, using its
  original session device key. This confers no meeting/moderation authority.
  Retain exact Off intent before publishing On; process-death and rekey must
  retry the original device's warning, independently of other recorders.
- Confirm guarded relay delivery before capture, including quiet schedules;
  queue acceptance/local echo is insufficient. Retain current call, admission,
  credential expiry, epoch and capture-ownership guards through ACK and refresh.
- Update the pinned actual browser reference and exercise native/web mixed
  member recording, two independent recorders, stop/restart, compatibility loss,
  quiet/delegated ACK and retained Send/rekey. Keep all existing capture/export,
  privacy, private-node, recovery, signing and physical acceptance gates.

The original 28 profile-3 vectors are preserved verbatim in
`protocol/src/test/resources/recording-live-profile3.json`; published canonical
interop vectors remain unchanged. Published fixture input must be copied verbatim from reviewed upstream,
with its source hash recorded. No profile-4 qualification follows from this audit.

The five fixture cases are now copied verbatim to
`protocol/src/test/resources/participant-recording.json`; both declared source
hashes match reviewed web main. The [protocol verifier receipt](evidence/native-recording-profile4-protocol-2026-10-11.json)
records 489 protocol tests passing, including 15 new checks against all five
published fixtures and all 28 existing live-warning vectors. Complete JSON is
re-encoded, and wrong rooms, calls, people, devices, profiles, signatures and
malformed/partial bindings are refused. The exact member Off remains verifiable
after leaving the original call. Context-free authority verification does not
adopt a participant warning. The roster codec still uses its previous profiles;
receiving/UI, capture lifecycle and restart integration remain open before
advertising profile 4. The earlier local release-gates run was stopped only after
[complete equivalent hosted verification](evidence/native-recording-source-grants-hosted-verify-2026-10-11.json)
passed on the unchanged source-permission app code. Its local release lint is
recorded as incomplete rather than passed.

The current reception candidate accepts roster profiles 3/4 and keeps separately
ordered, context-verified member warnings. Consent reads accepted Session state
before collectors run and covers every active advertised recording call; member
warnings never confer meeting authority. The deterministic live-warning generator
now uses the reviewed participant-recording SDK and produces 29 cases, including
legacy authority warnings carried by profile 4 and rejection of future profile 99.
All 490 protocol tests pass with no failures or skips. App compilation, the signed
presence reception/consent tests and APK assembly are still running, so reception
and UI are not yet qualified. Native still advertises its previous capability.
Device-owned publication, durable member Off recovery, capture ownership, all
mixed-client gates and the original physical/release requirements remain open.

The [reception unit receipt](evidence/native-recording-profile4-reception-unit-2026-10-11.json)
now records all 47 focused app tests passing alongside the 490 protocol checks.
Five member-reception tests cover two independently signed recorders, one device's
Off preserving another's warning, stale/conflicting On rejection, malformed or
forged context, active-call filtering and reading authenticated presence before
collectors run. APK assembly is still live; no instrumentation or physical claim
follows. Before release, the consent fingerprint must also bind capture mode and
person/device metadata, and the participant-owned lifecycle remains incomplete.
