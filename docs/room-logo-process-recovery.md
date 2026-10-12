# Native room artwork after process death

`RoomLogoRestartTest` qualifies two real process boundaries on a disposable
Android emulator. An actual guest requests access to a synthetic room, waits
outside its private control channel and enters only after a signed host grant.
The production room model accepts either the current logo or a removal and
commits that exact record to encrypted saved-room storage.

The preparing instrumentation stays alive at a checkpoint. The guarded driver
verifies its PID against the live application, sends SIGKILL to that PID and
confirms its disappearance. Recovery runs in a different app process and uses
the production saved-room store and reopen path. The fixture relay reuses its
previous loopback address and answers epoch requests, but holds no logo history.

Both cases require the same room, participant, device and accepted record after
restart. Image recovery requires the exact image hash; removal recovery requires
the accepted tombstone and no image. A stale encrypted image must be sent to an
active recovered subscription; it cannot replace the accepted record or revive
the removed image. Artwork produces no ordinary chat and starts no call,
microphone or camera.

Synthetic capability material used to reconstruct the loopback host resides
only in an encrypted lab checkpoint. Successful recovery deletes that checkpoint
and the fixture room. The driver refuses physical serials and non-emulator
devices; it does not erase other saved rooms or accounts.

The installed API 35 ARM emulator qualification passed both forced-restart
cases and the six admission-entry cases. The driver suite passed 268 checks.
The [receipt](evidence/room-logo-process-recovery-20261011.json) records the exact
source and APKs. CI runs both image and removal boundaries and preserves their
reports. The first draft's immediate subscription assertion failed after the
logo had recovered; the final test awaits the real subscription before sending
the stale event. That initial failure is retained separately and is not a pass.

This is native room-model recovery with synthetic identities and a local relay.
Physical-phone acceptance, independent browser/native replacement-device
recovery, project-logo process recovery and the complete G15/G17 journeys remain
open. These checks publish no production APK or store update.
