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
