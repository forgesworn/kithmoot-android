# Orbot attempt diagnostics

This observer is for the unresolved actual-Orbot reconnection failure tracked
by Vennel DG06.1. It changes no route, authentication, TLS policy, timer, retry
draw or message. Source/unit acceptance is not actual-Orbot or physical acceptance.

## Opt-in and collection

The first MainActivity launch must include boolean extra
`dev.forgesworn.kithmoot.TOR_TRACE_ENABLE=true`. Only a debug build honours it;
ordinary/debug startup and all release startup keep tracing off. The switch is
process-local, with no preference, manifest receiver or new permission.

An enabled process keeps at most 2,048 numeric event entries. A later
MainActivity intent with `TOR_TRACE_SNAPSHOT=true` and positive long
`TOR_TRACE_REQUEST` (both under the same package prefix) samples the main and
visitor view models, then requests export on a separate short-lived daemon.
It returns before the normal intent/link handler and does not leave/rejoin a
room. Export goes to the fixed app-private `files/tor-trace.json`, via a fixed
`.part` file and rename. The opted-in lab reads it with `run-as`, verifies the
request and PID and retains it privately. No callback does file/logcat I/O.

The runner removes only its fixed prior export before each request. Missing,
stale, interrupted or restarted/unarmed exports cannot establish completeness.
Private debug exports are removed by the run's app-data cleanup; raw logs,
clock/boot qualification and selected evidence remain in the private lab archive.

## Event interpretation

`RelaySocketListener.attemptTrace` defaults to null. The actual Orbot adapter
uses that optional context; it does not use OkHttp EventListener. Trace session
IDs, link indices and attempt IDs are local counters, unrelated to room,
account, device, destination or retry-backoff counters. A replacement visit has
a distinct trace session, so late callbacks retain their original identity.

Each event carries sequence, Android elapsedRealtimeNanos, session/link/attempt,
an enum and two numeric values. No URL, onion name, room/device/account ID,
header, frame, message, signing material or arbitrary exception/close text is
accepted by the recorder. Failure categories are the fixed RelayTraceFailure
enum; an optional numeric response code is recorded only if the actual callback
has a response. A generic failure does not reveal the CONNECT/TLS stage.

| Event | value / detail |
| --- | --- |
| OPEN_DECISION, WATCHDOG_DECISION | Direct CAS result 1/0; subsequent phase sample (0 connecting, 1 open, 2 abandoned). |
| CONNECTED_PUBLISHED | Observation revision; count of the exact set assigned. Revisions are not flow-emission identities or an atomic ordering across threads. |
| CONSUMER_OBSERVED | Collected count; independently sampled observation revision. StateFlow may conflate transient publications. |
| CONSUMER_APPLIED | Collected count; relaysUp sampled after the existing update. No trace runs inside a retryable update transform. |
| CONSUMER_SNAPSHOT | relaysUp; flags: visible=1, session present=2, selected UI model=4. |
| STATE_SNAPSHOT | Sampled publication revision and connected count. |
| LINK_SNAPSHOT | Phase from the same immutable attempt context; independently sampled link-open flag. |
| RETRY_SCHEDULED | The existing randomly chosen delay in milliseconds. No extra random draw. |
| SNAPSHOT_REQUEST, EXPORT_REQUEST | Caller request number; brackets the sampled view-model state. |

The ring uses atomic sequence reservation and slots, without application locks
or a blocking queue. Recording catches errors. Overflow, missing sequences,
errors and concurrent snapshot changes are explicit. An intact exported prefix
means only that its recorded events are accounted for; marker times are source
observations, not proofs of cross-thread linearization or zero instrumentation
overhead. Timing uncertainty or a callback paused before its marker can prevent
a deadline classification.

Arm before baseline/cut/reopen and require current-session creation through the
unchanged reconnect deadline, including opens before the UI wait. At wait start
the lab brackets a state sample; it is not one atomic transport/UI snapshot.
Unaccounted starting state, loss, a late trace start, stale generation, process
replacement or unmatched clocks makes absence **inconclusive**. No observed open
alone does not establish Tor failure: loop/scope/consumer progress matters too.

## Validation and next boundary

The focused branch workflow compiles/tests both variants using the actual
OrbotPoolRecoveryTest cases with tracing off/on and with a failing trace clock.
RelayAttemptTraceTest covers concurrent recording, overflow/errors, safe failure
classification and late callbacks across replacement sessions. The required
whole PR/main pipeline remains unchanged.

Vennel's opt-in `LAB_ANON_TRACE=1` records failure-preserved milestones, clock
uncertainty and snapshots within the existing 180-second connection/120-second
message limits. Frozen app logs precede any snapshot intent after failure and
all independent probes. Actual runtime execution requires separately pinned
candidate APK, lab/Orbot/tool identities and owned runner qualification; this
document does not supply that acceptance.
