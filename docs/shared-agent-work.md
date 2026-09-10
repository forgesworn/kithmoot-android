# Shared agent work on Android

The native implementation uses KithMoot’s signed assignment contract, rather
than a separate Android task model. The first protocol implementation lives in
`session/Assignments.kt`; `Chat.kt` now supports encrypted named channels and
checks each assignment’s inner participant signature against its room and
outer device credential.

`assignment-vectors.json` is generated from the TypeScript reference by
`scripts/generate-assignment-vectors.mjs`. Its recorded source digest identifies
the exact assignment implementation. Synthetic signed histories cover 29
projections, including question/answer, exact-result acceptance, cancellation,
stopped handoff, rejection, missing history, conflicting claims and forged
operations. Four payload, four catalogue, seven encrypted envelope and four
channel-derivation cases exercise the wire boundaries. Native signing is also
checked against a signer that changes the requested work.

The native Work tab is implemented beside Chat and Call. It shows decisions
before routine progress, offers advertised actions, preserves unfinished chat
and decision text between tabs, and binds acceptance to the displayed result and
assignment head. Task management controls are opened explicitly. It supports
question/answer, result review, requested changes, cancellation and stopped
handoff. A secondary device reads work but cannot impersonate the participant
signer.

`AssignmentJournal` keeps signed history independently of the chat window.
It saves an encrypted outbox before publication, waits for a relay receipt,
retains uncertain sends unchanged and retries only on an explicit request.
A reconnect rereads verified history without replaying pending operations.
Each room and participant has a separate Android-keystore-wrapped atomic vault;
forgetting the saved room also removes that vault. Crypto verification and
persistence run away from the UI thread.

Validation: 200 app unit tests and 147 protocol tests passed. Three emulator
screen tests verified creation, exact decision/result callbacks, saved retry,
chat and answer drafts, and no media activation when opening Work. A separate
native socket/vault test completed question, answer and exact acceptance against
a signed synthetic peer, then restored the complete accepted task with no relay
history. A fifth emulator test enters a room through the installed app, creates
work through the production view model, leaves and reopens it, and checks the
same task returns with microphone and camera off. It caught and now guards
against closing Work on the initial null epoch notification. These use local
synthetic data, not a live agent or physical phone. Debug and release lint pass.
The recovery-emulator CI script now includes all five shared-work checks.

Open acceptance: quiet-room history and confirmed quiet delivery, paired-device
signing, unsupported epoch following, cross-project attention and notifications,
and a real project/agent connection. The current app stops when a room moves to
an unsupported epoch; work closes at that boundary. An unsupported durable
transport fails closed and never falls back to an unconfirmed or public send.

The exact 0.5.8 (16) candidate passed the complete 17-test recovery/emulator
script. [Recorded evidence](evidence/shared-work-2026-09-10.json) includes the
APK, preview certificate and implementation digests.
