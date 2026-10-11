# Recording profile 4 parity gate

Live upstream audited on 2026-10-11. Web main `676edeb` includes merged PR #395
and signed desktop release 0.1.75. Draft PR #385 overlaps this newer work and
conflicts; its attempted merge was aborted with the worktree clean. Do not merge
that older profile-3 implementation over current participant recording.

Android candidate `877883ad` still advertises profile 3 only for a room authority,
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

The existing 28 profile-3 vectors and published canonical interop vectors remain
unchanged. Published fixture input must be copied verbatim from reviewed upstream,
with its source hash recorded. No profile-4 qualification follows from this audit.
