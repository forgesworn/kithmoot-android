# Native keeper foreground controller

The internal NativeKeeperController owns one source journal, durable original
notice courier, bounded 64-slot request/command queue and subscriptions on the
exact already selected Internet, Nearby or mixed endpoints. It does not create
or tear down those routes. Before answers or a new transition, it verifies the
actual EpochVault and RoomSession against the source journal. It never restores
a signer from SavedRoom or forwards received root controls as its own.

Approval, challenge/epoch answers, rekey, retirement and closure share one owner.
Rekeys hold ordinary chat before source preparation, queue the exact original
in the actual courier, and apply it locally through the normal receiver gate.
Local adoption does not depend on Internet relay echo. Receiver failure keeps
the original durable transaction; reopening that journal/controller resumes it
without another signature. Retirement handoff is separately recorded before
source completion. Closure leaves ordinary chat blocked while guarded original
control remains available; rejecting a new closed-room operation does not stop
the courier.

Internet authority request subscriptions admit at most three locally spaced
copies of a request ID and collapse simultaneous relay copies. Normal room/chat
subscriptions retain their existing deduplication. Stable-room answer dispatch
is restricted to signed root challenge/epoch grants and the current reservation,
transport generation and selected-owner guard.

Closing or cancelling the owner invalidates that guard before queued work or
subscriptions can export again. Journal cleanup remains on the IO dispatcher;
stop() waits for worker and child cleanup. Both journal leases are released,
while the caller retains ownership of its endpoints. A rejected duplicate
controller claim leaves the first owner and both of its journals alive.
Dispatch remains held until actual receiver verification succeeds. A cancelled or timed-out
answer preserves its original signed cache, expiry, offers and byte debt.

## Journal schema 2

The source journal separates latest lifecycle cause from epoch activation cause.
Retirement changes the first and preserves the second. A committed closure also
retains the predecessor epoch/secret needed to check the actual receiver
tombstone, which intentionally retains that predecessor. Cold agreement on a
closed record never grants hosting readiness.

Older source schema 1 raises NativeKeeperMigrationRequiredException; no missing
evidence is inferred and no store/debt is reset. The source journal has not been
attached to production room creation, so this is a predeployment schema gate,
not a claim of an accepted device migration.

## Qualification

Direct pinned Kotlin/Compose/JUnit passes 504 checks in 62 classes, including
four new receiver-readiness tests and eight new controller tests. An actual
RoomSession and source controller are replaced with Nearby down. The same live
peer receives the original notice when the lane returns and both show two fresh
chat rows without peer rejoin. A cancelled Internet answer retries the identical
original after journal reopen, preserving and doubling its charged debt rather
than acquiring fresh credit. Other tests cover all selected modes, actual
subscriptions and decoding, unknown/approved devices, closure, receiver-write
failure, modified/missing causes, terminal keys and stopped-owner withdrawal.

This is in-process cached-class JVM evidence, not SIGKILL, encrypted Android
keystore, physical BLE or radio qualification. Changed listed source/test files
are freshly compiled; other app/protocol classes use the qualified courier
build. Full final-head Gradle/lint/APKs and all four hosted checks remain
required. Source-hashed evidence and failures are in mesh-kit-private's
lab/hybrid-room/NATIVE-KEEPER-CONTROLLER.md.

## App attachment still required

The controller is not attached to RoomViewModel. Fresh room creation needs a
non-signing saved authority reference and exclusive source persistence before
room metadata; existing saved host keys need explicit migration. The old epoch
responder/welcome/root signer must not run alongside this controller. Adapt
foreground ownership, approval UI, metadata controls, closure teardown and room
wipe/forget to that reference, then perform the real native keeper process-death
and full build/hosted acceptance gates.
