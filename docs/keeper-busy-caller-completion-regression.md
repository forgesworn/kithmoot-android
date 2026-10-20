# Fatal BUSY caller completion regression

The exact b8bd9a1 source has a separately reviewed static blocker: both post-start
command and explicit PendingRecovery BUSY catches call holdReceiverVerification
before completing their current caller deferred. If real ledger/courier health
is fatal, that hold throws. Its sibling generic catch cannot catch an exception
from a catch body. The current work was dequeued and its deferred has no owner
parent, so owner cancellation/remaining-queue drain can leave the caller waiting.
The accepted 26-case focused result remains valid; it covers healthy callers and
Retry-ledger failures separately, not this combined path. PR #286 is unmergeable
until the combined caller case has its own correction and qualification.

This baseline draft changes no production or either accepted test file. A separate
class adds four deterministic methods: otherwise-valid approval on Nearby,
Internet and Mixed, plus actual Nearby pending replacement retry. Real signed
requests and actual unavailable-lane replacement establish actionable preconditions.
The caller runs in a sibling test scope. The test holds the actual epoch mutex,
latches actual ledger.status storage-write failure, starts the real caller and
runs current work. The owner must fail and the caller must complete exceptionally
without timeout/external cancellation, with the fatal cause and no Ready/chat,
store repair/reset or automatic replay. On broken source, all four cases fail
promptly at caller.isCompleted; finally cancellation only prevents a leaked test
caller and is not evidence that the operation completed.

The separate branch-only focused workflow preserves the original six methods and
seven supplementary methods in both variants and adds these four: 34 JUnit
executions, with eight intended new completion assertion failures and 26 passing
controls on unchanged b8bd production. Both variants compile and execute without
build cache; reports are retained even on failure. Setup/compiler errors, omitted
cases/variants or unrelated failures are not semantic red. Every frozen accepted
test file remains byte-identical. No old timeout or assertion is relaxed.

No producer is started by this draft. The obsolete full candidate run 39362151483
must first be terminal with all available history retained. Exact baseline source,
new workflow, task/source/checkout/report contract and one producer need their own
review before a single push of diagnostic/keeper-busy-completion; no PR is opened
for that branch. A later correction must keep the new tests identical and pass
all 34 outcomes before separately reviewed full candidate/main gates. No local
build/install, physical/device/Orbot/runtime action or historic cause attribution.
