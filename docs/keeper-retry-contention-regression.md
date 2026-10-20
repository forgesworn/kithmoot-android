# Keeper Retry contention regression

The retained actual-main run `39353452235`, attempt 1, has a final replacement
restart refusal at `NativeKeeperJournal.verifyReceiver:342`. Its worker frame
134 identifies post-start `Work.Retry`. The checked live predicate conflates
`epochMutex.tryLock` contention with stable publication/state/epoch/id/key/removal
mismatch. The hosted stack does not distinguish those branches.

This draft introduces no production change. It adds four deterministic JVM
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

Three separately named contention cases (one per route) are expected to fail
against unchanged production; the three controls each loop all routes and must
pass. Six selected methods in both variants give twelve JUnit executions. A
failed route does not omit the other two contention cases. Debug and release select only the six exact test methods with
`--continue`, so a failed debug test does not omit release. Reports are retained
even on failure. The focused producer uses the existing pinned action versions,
three native bundle preparation paths, JDK/SDK and two-worker limit. No build
cache is used for that diagnostic producer. No production source, normal full CI,
Orbot focused workflow, dependency, APK or runtime pin changes.

Publication and the single proposed branch-triggered producer require review of
the exact draft first. A red result must bind test source, actual checkout, task
execution and exact JUnit failures; compilation/setup failures are not a semantic
red. A later correction must preserve byte-identical tests and pass the controls,
followed by its own full candidate/main gates. Neither a generic reproduction nor
its correction establishes the historical hosted failure's unobserved branch.
No local builds, device/ADB/account/Orbot operations, manual dispatch, unchanged
retry, actual-main acceptance, runtime journey or release is authorised here.
