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
