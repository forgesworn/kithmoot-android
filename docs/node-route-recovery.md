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
