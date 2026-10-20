# Keeper Retry contention regression

The retained actual-main run `39353452235`, attempt 1, has a final replacement
restart refusal at `NativeKeeperJournal.verifyReceiver:342`. Its worker frame
134 identifies post-start `Work.Retry`. The checked live predicate conflates
`epochMutex.tryLock` contention with stable publication/state/epoch/id/key/removal
mismatch. The hosted stack does not distinguish those branches.

The baseline commit `882eacb552b21e864683200a69bd6b7b72ae0206` introduced no
production change. It added four deterministic JVM
scenarios to the actual controller suite on Nearby, Internet and Mixed:

1. Complete an actual traffic rekey to epoch one and invitation replacement to
   generation one, then close and reopen the actual journals under a second
   owner. Verify the source/receiver/live agreement. Lock the real live epoch
   mutex through test-only reflection, queue an actual controller Retry and
   require a recoverable hold rather than Ready or permanent Failed. While held,
   no authority command, chat release, new source/courier write or original is
   allowed. Release and require automatic existing-cadence recovery to the same
   epoch/generation/original, with the actual verifier agreeing again.
2. Commit an exact source-created rekey through a separate real receiver session
   and the actual courier while the selected controller's live session remains
   at its old epoch. Retry must refuse readiness: a stable mismatch cannot be
   treated as lock contention or restored from assumed cached state.
3. Withdraw the selected owner during controlled contention. Releasing the lock
   and advancing the existing retry interval cannot republish Ready or authority.
4. Replay the actual already-adopted authenticated rekey through the selected
   transport, then Retry. The current source/cause/epoch and readiness remain;
   this ordered replay is a control, not proof it caused the hosted failure.

The lock helper reads only the private `epochMutex` reference. It does not change
keys, publication flags, epoch state, source data or readiness outcomes. This is
a narrow fixture barrier, not a production hook or a simulated authority result.
No test sleeps or timeout/assertion changes are introduced.

Three separately named contention cases (one per route) fail
against unchanged baseline production; the three controls each loop all routes and must
pass. Six selected methods in both variants give twelve JUnit executions. A
failed route does not omit the other two contention cases. Debug and release select only the six exact test methods with
`--continue`, so a failed debug test does not omit release. Reports are retained
even on failure. The focused producer uses the existing pinned action versions,
three native bundle preparation paths, JDK/SDK and two-worker limit. No build
cache is used for that diagnostic producer. The baseline changed no production source, normal full CI, Orbot focused
workflow, dependency, APK or runtime pin.

Publication and the single proposed branch-triggered producer require review of
the exact draft first. A red result must bind test source, actual checkout, task
execution and exact JUnit failures; compilation/setup failures are not a semantic
red. A later correction must preserve byte-identical tests and pass the controls,
followed by its own full candidate/main gates. Neither a generic reproduction nor
its correction establishes the historical hosted failure's unobserved branch.
No local builds, device/ADB/account/Orbot operations, manual dispatch, unchanged
retry, actual-main acceptance, runtime journey or release is authorised here.

## Reviewed baseline and proposed correction

Baseline run `39358396442`, attempt 1, compiled and executed both variants
without build cache: all 85 tasks executed. Its exact twelve JUnit executions
contain six intended contention assertion failures and six passing controls,
with no errors or skips. The independent terminal review accepted this expected
red experiment. The positive tests stopped at their recoverable-hold assertion;
the later safety, release and same-owner recovery assertions did not execute.
The workflow remains failed. The original main run remains failed too.

The correction separates MATCH, BUSY and MISMATCH under the actual epoch mutex.
The existing boolean predicate still refuses BUSY and MISMATCH. The journal
checks the same durable room/root/phase/cause/epoch/secret/removal, live profile,
and final source revision, and wipes the same temporary key buffers. Only a
failed real mutex acquisition emits the distinct busy exception. A stable live
mismatch remains an IllegalArgumentException; no stored state is repaired.

After startup, only this distinct exception is recoverable. The serialized
worker holds chat startup, removes approval cards, and publishes Starting.
It keeps the same owner and existing five-second retry; it does not wait under
journal locks or automatically replay a foreground command. Commands and explicit
pending-recovery callers receive the incomplete-verification exception. Incoming
requests are dropped while held, including the replacement exception to the
ordinary Ready guard. Partial replacement or retirement recovery cannot release
chat while held. Full successful receiver verification clears the hold before
normal Ready publication and guarded chat release. Closure, deselection, route
failure and other exceptions keep their existing refusal/cancellation behavior.
Initial startup still fails closed if verification is busy; this change does not
make start return an unverified controller.

The proposed first changed-source producer keeps all twelve regression executions
and the entire original test file byte-identical, and adds seven supplementary
methods in each variant (26 total executions). It must pass the previously
unexecuted safety and recovery postconditions as well as all six controls. It
uses the same branch-triggered workflow, pinned tools, no build cache and native
bundle inputs. The workflow corrects the step label and selects the separate supplementary
class in both variants. This concrete source/producer packet needs its own review before a
single new fast-forward branch push; the consumed red allowance cannot be reused.
A focused green is followed by separate full candidate and actual-main gates.
No compilation, test execution or correctness of this correction is claimed by
source inspection. No device/runtime action or historical cause attribution.

### Supplementary boundaries and durability

The separate `NativeKeeperReceiverBusyTest` copies the established actual
Rig/Store/Link fixture locally rather than refactoring the frozen baseline file.
It adds seven methods: command-first BUSY, request-first BUSY, cold-start BUSY,
pending replacement BUSY with partial return and a live-lane epoch request,
pending-owner withdrawal, ledger failure before first BUSY, and ledger failure
while held. The command/request/cold-start/ledger controls loop all three routes;
replacement/withdrawal use the actual Nearby unavailable-lane path. These tests
control the real epoch mutex and actual storage write failure, without mocking
the verification result. The failed approve cannot replay; only a fresh incoming
request after full verification becomes an approval. Pending recovery must keep
its exact welcome/retirement originals and owner identity through final recovery.

BUSY handling holds traffic before checking source, ledger and courier health.
A failed store/courier escalates to the existing failed-owner cleanup. A held
owner repeats this check at recovery entry and immediately before clearing the
hold and publishing Ready. Successful live agreement cannot repair or reset failed
custody. The original stable mismatch and withdrawal controls remain unchanged.

A retirement partial return while already held is not created by an out-of-contract
external authority mutation in this fixture. Serialized ordinary commands refuse
non-Ready after a hold; normal retirement preparation and partial recovery occur
within one worker command before another BUSY work item can intervene. Replacement
recovery is reachable because its pre-recovery validation uses verifyReceiver
while the source has a replacement but no traffic rekey pending. The explicit
retirement no-release guard remains defensive; no retirement-busy execution proof
is claimed. Initial startup still refuses BUSY rather than returning an owner.
