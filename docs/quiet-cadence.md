# Quiet cadence hand-off to Bothy

KithMoot can schedule a paired Bothy to send one phone device's fixed quiet-room
cadence for a bounded window of up to twelve hours. The control plane uses the
pinned ForgeSworn Link bridge; the scheduled traffic still uses the room's existing
dead-drop wire format and public WSS relays.

This hand-off is available only in a persistent quiet room after its creator has
connected Bothy. Pairing installs a room- and device-scoped self grant alongside
the guest grants. The app refuses a schedule unless the saved route is a
canonical Link address, the self grant and device credential are unexpired, and
at least two previous public WSS relays remain available.

## Ownership and recovery

Before sending the lease request, KithMoot writes the exact request body and the
delegated counter range to its encrypted cadence journal. Those counters are
then unavailable to the phone even if the request times out or the process
restarts. Retry sends the retained bytes unchanged; a status lookup resolves an
uncertain reply without creating a second lease.

Bothy owns real sends only from the acknowledged start epoch up to the original
end epoch. Before the start, KithMoot continues its normal cadence. The app will
not start a hand-off while a locally queued quiet message is waiting for its
phone slot, and it pauses chat submissions while ownership is changing. During
the delegated window, a new chat event is queued through Bothy and its receipt
is written to the journal. Its exact inner event remains in the saved phone
queue until that receipt is durable. The queue request id is derived from the
event id, so restart recovery can query the lease and retry the same event
idempotently without creating another logical message. The phone emits neither
a real wrapper nor filler for the delegated counters. If the queue receipt is
still unknown when the lease ends, the phone keeps that event and emits filler
instead of risking a duplicate real send; Retry must resolve the receipt first.

Stop requests choose a future safe epoch. Bothy stops real sends at that
boundary but keeps the fixed cover pattern until the original end epoch, so the
phone does not reclaim counters early. Disconnecting Bothy first resolves any
uncertain lease and requests this safe stop; only then can the Link route and
circle authority be retired.

A room rekey uses the stricter `/rekey` mutation. Android first persists the
successor secret as pending, blocks every ordinary publication path and asks
Bothy to advance the room-generation high-water mark. Bothy fails uncommitted
old-generation messages, refuses later lower-generation work and continues the
old lease's promised fillers. Android activates the successor only after that
receipt is durable. A lost reply or process death resumes the same request from
the pending epoch and cadence journals. If retirement cannot be proved, the old
counters remain reserved and the room stays read-only.

The room panel exposes `off`, `staged`, `active`, `stopping`, `cover`, `ended`,
`unresolved`, `not-ready` and `blocked` states. It shows the scheduled window
and Bothy's queued, sent and failed counts. **Retry** resolves retained state;
it does not create a fresh request while ownership is uncertain.

## Evidence and remaining release gates

Protocol and JVM tests cover exact lease and queue bodies, route and identity
pinning, lost lease-response retry, encrypted journal ownership, cadence routing,
phone suppression and durable local quiet queueing through a lost box reply.
The Compose instrumentation
test covers the acknowledged schedule evidence and safe-stop action. The normal
Android release build runs protocol tests, app tests, debug and release lint,
and both APK assemblies with the pinned Link archive.

The retained Vennel composition checks the exact web, Kotlin and Rust vectors,
then drives stage, queue, successor rekey, restart recovery, retained cover and
lower-generation refusal through Android JNI, ordinary paired Link and the
Bothy fixture. This is candidate integration evidence, not live production
acceptance. Promotion still requires the matching Bothy worker to be reviewed
and deployed, a full cycle against that exact deployment over live Tor and I2P,
and physical Android background and battery acceptance.
