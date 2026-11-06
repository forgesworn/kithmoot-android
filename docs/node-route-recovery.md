# Sequential routes for hosted calls

Each Android node peer gathers one authorised TURN URL at a time. The order is
UDP, TLS on port 443, other TLS, then TCP. New peers reuse the selected route;
an explicit network change tries UDP again. A node response may restrict the
available URLs, and no route outside that response or the signed room is used.

A four-second gathering watchdog gives another address family and a replacement
description time to produce a relay candidate. A 701 address-family error does
not cause immediate fallback. Authenticated refusal codes 401, 403, 486 and 508
stop route cycling. Connected peers are not replaced just because continual
gathering reports an empty round. Closed, retired and unauthorised peers cannot
request another route.

Fallback requests the existing managed recovery owner. It obtains fresh node
access and TURN credentials before replacing the affected peer. Healthy peers
retain their own allocation and original renewal deadline. The wall-clock and
monotonic five-minute ceiling, bounded retiring transport, relay-only policy,
admission checks and removal behaviour remain in force. Trying a different
route does not extend a cached credential.

After a native link connects, its gathering watchdog stops spending routes.
The existing recovery owner obtains fresh credentials and replaces a lost
connection on its current route first. A fresh replacement can still discover
a blocked route and advance the ladder. Old gathering callbacks must not
change that preference while the owner's access retry is waiting. A fresh
failed pair also retries the current route when another connected pair,
including a bounded handover transport, proves that route reaches the node.
An abandoned local description cannot spend a route or request recovery.

A fallback request does not immediately change the shared preference. Fresh
authorised login installation commits at most one pending advance. A retryable
node read failure discards offline gathering evidence, including when an inner
HTTP retry later succeeds. Retired or subsequently connected attempts cannot
commit a pending route change. The first accepted login after that failure retries
the current route; only a fresh subsequent gathering can select another URL.

TURN TLS uses the full-chain, hostname-aware Android platform verifier in
WebRTC 150.7871.04. The earlier 144.7559.12 binary lacked the ISRG roots used
by the public node's Let's Encrypt chain and failed its native TLS handshake.
The same node produced valid TLS-443 relay candidates in the browser and
desktop. The SDK update retains certificate and hostname validation; it does
not install a trust-all callback or change the device trust store. Its public
native media and teardown checks remain separate from build and unit results.

The lease regression first failed against the previous implementation because
it exposed all four URLs. Lease, watchdog and recovery unit tests cover the
route order, concurrent old-route callbacks, issuer subsets, refusal, expiry,
withdrawal, empty gathering, healthy gathering and teardown. Compilation checks
the observer hooks against the pinned native WebRTC API. Those checks do not
establish physical GrapheneOS, public native TLS443, Wi-Fi/cellular, VPN handoff
or perceived audio/video quality. No physical phone is available for this pass.

## Queued setup after a route replacement

Fixed-slot opening checks whether its link was closed or retired after taking
its operations lock. A setup coroutine queued before a route replacement can
run after teardown; it now exits before adding native transceivers, writing a
local description or sending an offer. Retiring a connected transport still
leaves its existing media available for the bounded handoff.

Both regression sequences failed against 0.6.135 and passed with this guard.
They cover queued work starting after close and after retirement. A public
released-client attempt on 6 November reached ordinary web-managed admission
on desktop 0.1.129 and Android 0.6.135, but the native process then crashed in
`PeerConnection.nativeAddTransceiverOfType` before its media baseline. That
trace prompted this check; the unit reproduction does not establish that this
was the crash's only cause. Public sustained three-client media and physical
network handoff remain unqualified until their separate runtime checks pass.

## Native callbacks and disposal

A peer handle serialises synchronous JNI entry with the decision to dispose.
Closing marks that handle closed, rejects later entries and then invokes native
teardown without holding the entry monitor. The monitor is the same peer
monitor used while reading transient transceiver wrappers. Native statistics
callbacks ignore an already cancelled continuation.

Track callbacks dispatch snapshot work off the native signalling thread.
Otherwise a JNI caller can hold the peer monitor while waiting for that thread,
whose callback is itself waiting for the monitor. The concurrency regression
rejects inline callback execution and verifies that dispatch returns while the
monitor is occupied. Further tests cover disposal waiting for an entered call,
late-entry refusal, callback access during close, single disposal and cleanup
after a failed operation. The native media gate remains distinct from this
model of the lifetime and callback boundary.

## Received video ownership

A received video wrapper is retained independently before its callback returns.
This keeps its native track and Java sink map separate from the receiver's
teardown. Repeated callbacks for the same native track reuse the held wrapper.
Replacement, removal and peer closure release it once. Sink attachment,
detachment and disposal are serialised; a view that detaches after closure
performs no further native sink operation.

The previous private candidate crashed in `VideoTrack.removeSink` while Compose
removed a video view during room closure. Ownership and concurrency tests cover
that boundary, and a native test requires frame delivery after disposing the
callback's original wrapper, followed by late view cleanup. Public call and
closure qualification remains an additional gate.

Camera and screen tracks use the same guarded ownership. Stopping capture
disposes their sink maps before releasing the source and factory. Their views
can detach later without calling a freed native sink. The native frame test
also covers adopted local tracks and performs late cleanup after disposing
the complete factory. A public closure attempt with received-track ownership
alone still crashed; that failed candidate remains in the evidence.

## Removal completes after closing its caller

Node removal and closure callbacks can run in the access poller's coroutine.
Leaving the room closes that poller and cancels the room scope. Once a
self-destruct run has claimed the room, its leave and durable wipe therefore
run together in a non-cancellable context, releasing the claim afterwards.
Protecting only the leave step could stop calls and show the self-destruct
notice while retaining the saved room and its asking-device keys.

The private signed candidate reproduced that failure after a twelve-minute
web, desktop and Android call: media stopped on removal, but the saved local
identity remained. The corrected signed APK must pass the same actual removal
and storage readback before publication. The preceding media, renewal and
network-recovery result does not establish successful erasure.
