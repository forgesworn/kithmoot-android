# Invitation replacement process-death acceptance

The five `NativeReplacementRestartTest` windows pause after an actual encrypted
source/index commit and before its return. The external driver verifies the
live app PID, SIGKILLs it, verifies absence, then requires a different process
to reconcile that exact transaction. These are disposable emulator fixtures;
they do not access BLE radios, public relays or personal accounts.

```sh
python3 scripts/check-native-replacement-restart-emulator.py committed-source-before-offer
python3 scripts/check-native-replacement-restart-emulator.py charged-original-before-offer
python3 scripts/check-native-replacement-restart-emulator.py offered-before-index
python3 scripts/check-native-replacement-restart-emulator.py index-committed-before-source-acknowledgement
python3 scripts/check-native-replacement-restart-emulator.py reference-installed-before-subscription-switch
```

Set `ANDROID_HOME` and an explicit disposable `ANDROID_SERIAL=emulator-...`.
The driver also checks `ro.kernel.qemu`; physical devices are refused. It clears
the fixture application's data/keys even after a failed preparation or recovery.
Do not run against an emulator containing work to preserve.

Setup uses real source/controller/index, three qualified devices including one
offline device, an approved peer, and a prior actual traffic rekey to epoch one.
The first three windows retain the previous invitation reference. The fourth
has the next index with NOTICE_ARCHIVED still in the source. The fifth has the
next index and INDEX_VERIFIED, before the next actual invitation subscription.
Only the fourth wraps the application's existing repository's private storage
field with a test delegate; it retains that same repository and monitor. It
does not create another repository, change validation, or synthesize a commit.

Cold comparison checks the exact full source/index digests, original signed
welcome/retirement, retained cached grant, spending multiplicity, device audience,
epoch/secret/cause, both actual receivers and marked courier before routes or
credential refresh. Recovery keeps the staged originals and charged attempts.
The first two modes add one retirement attempt; the other three add none.
Nearby emits no standalone welcome. Sharing stays unavailable while pending.
Actual next invitation admission/current-epoch recovery and approved chat are
exercised through byte callbacks and the real foreground session. A later owner
refuses the stale replacement callback without a write.

The test emits its original IDs/time/size, attempt/debt values, three devices,
traffic epoch one, invitation generation one and zero Internet requests only
after assertions and checked file/key cleanup succeed. The new driver requires
the complete unique checkpoint bundle and exact requested mode before killing;
it also refuses incomplete/duplicate measurement fields, same original IDs,
changed timestamps, oversized originals, lost attempts/debt, changed audience/
epoch/generation/route and failed cleanup. All eight earlier driver profiles
and production bounds remain unchanged.

Local validation compiled the new instrumentation and shared fixture against
unchanged compiled generation production dependencies. The initial full
fake-adb suite passes 361 checks in 423.422 seconds. After tightening duplicate
checkpoint handling, 34 targeted checks pass in 55.727 seconds, including all
five exact profiles and twelve malformed numerical receipts. These are driver
and compilation results; no new Android process-death window is yet measured.

The predecessor bb2 own CI 38076268352 passes build/lint/unit tests and both
signing-lineage jobs, but fails recovery: the new three-case replacement group
runs in 170.217 seconds with two timeouts at the completed-generation-one sharing
assertion. Seven prior groups/34 checks pass, including eleven host cases in
161.594 seconds. The failed log lacks invitation/index generation and sharing
diagnostics, so the exact cause is unproved. This successor adds those bounded
public diagnostics; it does not weaken that assertion or alter production code.

The successor requires its own complete four-job CI, 144 ordinary checks/45
groups, six numerical store refusals, eight retained process-death drivers and
all five new drivers. The predecessor's failure and passing local results remain
separate. Full replacement acceptance, remaining capacity/store/listener gates
and hardware acceptance are still incomplete.
