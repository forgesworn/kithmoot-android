# Receive-buffer loss, 27 October 2026

## Reproduced failure

A tester reported apparently permanent message loss and unreliable phone/desktop
history on current clients. The particular conversation and missing event IDs
are unknown. This reproduction establishes a defect, not its attribution to
that field report.

A deterministic 600-event history burst through `RelayPool` delivered only 256
events before the patch. `PoolSubscription.offer()` entered every event ID in
`SeenEvents` before `MutableSharedFlow.tryEmit()`. Events refused by the full
256-slot buffer were therefore also rejected when another relay or a reconnect
replayed them. Ordinary subscribers had no repair path.

## Change and soundness checks

- Preserve bounded buffering and duplicate suppression. Failed admissions do
  not enter the delivered-ID set or evict successful IDs while the buffer is full.
- Drain the existing buffer, close the old wire subscription and retry with a
  fresh ID. Late events and EOSE for the old ID cannot complete the retry.
- Retain successful IDs and the original history window across retries, even
  if a background inbox advances its receive cursor while processing events.
- Require real EOSE after loss-free processing for replay completion. Use at
  most 15 recovery requests after the initial attempt, within the 4096-ID
  deduplication window. Exhaustion keeps live delivery open and leaves history
  unconfirmed; this is not general history pagination.
- Close abandoned subscription objects, so late socket callbacks cannot admit
  IDs into a subsequent retry's shared duplicate set.
- Keep keeper request repetition budgets and their fail-closed replay rule.
  Retry only the private overflow signal; cancellation and caller failures do
  not restart subscriptions.

The transport does not gain room authority and does not relax signature,
credential, epoch or membership checks. It reuses the same selected relays and
existing transport controls. It adds no signer, radio, endpoint or telemetry.
Recovery requests repeat an already selected receive window.

## Regression coverage

The new tests cover a 600-message burst without duplicates, a descending
history burst while a dynamic receive cursor advances, and recovery-budget
exhaustion without false completion or an orphaned worker. Existing relay
reconnect, keeper registration, room-session and durable-send checks remain
part of the validation command.

## Field acceptance still required

This task does not install an APK or qualify physical GrapheneOS delivery.
Run numbered messages in both directions between current Android and desktop,
with the receiving device closed and after network interruption and sleep.
For any gap, capture exact versions, conversation, time and relay diagnostics.
Recovery requires some relay or device still to retain the original valid
events. Companion KithMoot branch `fix/message-delivery-sync-20261027` prevents
web/desktop sends under an expired credential from being silently acknowledged
and then rejected by all readers.

## Local validation

`JAVA_HOME=<JDK 21> ANDROID_HOME=<SDK> ./gradlew :protocol:test :app:testDebugUnitTest --tests '*RelayPoolTest' --tests '*SeenEventsTest' --tests '*RoomSessionTest' --tests '*DurableChatDispatchTest' --tests '*BackgroundDeliveryTest' --tests '*RoomWorkHistoryTransitionTest' --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx3g -Pkotlin.compiler.execution.strategy=in-process` passed on the final patch.

- 106 Android app tests passed, zero failures/errors/skips, including all three
  new overflow regressions.
- The unchanged 594-test protocol result was reused by Gradle (up to date);
  it was not freshly executed on the final run.
- `git diff --check` passed. Native Link and VMLS inputs were checked by their
  existing prepared-input verification tasks. No native Rust rebuild ran.
- No emulator, test server or fixture service was started. The task leaves
  no such process running. No APK was built, installed or published.
