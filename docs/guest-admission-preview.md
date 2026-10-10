# Native guest admission preview work

The temporary-room request now distinguishes signing, sending, acknowledged
waiting and reconnecting. Waiting begins after a relay accepts the request;
the receipt does not imply that a host admitted the guest. Retries reuse the
same signed event and do not flash back to Sending after acknowledgement.

Publication uses the guarded confirmed-send path, with the account, operation
and bounded invitation lifetime checked at dispatch. Relay fanout rechecks the
guard before each socket write, including when the preceding write's callback
withdraws authority. Cancellation closes the reply collector and retires the
request key. It cannot retract an event already written to a relay.

`GuestAdmissionGate` holds a private invitation and exposes only the room label,
entered name and phase. A deliberate request owns one attempt; repeated taps
cannot own another. Cancellation, refusal, expiry, replacement and clear retire
that attempt. Retry retains the name and reopens Preview before another request
can start. An admitted attempt keeps its guard until entry commits.

This gate is not yet wired to the native entry screen. The remaining work is
the explicit preview/request UI, independently owned local camera/microphone
checks, lifetime and permission cleanup, and complete ViewModel/device journeys.
It does not establish native preview parity or physical acceptance.

Local qualification on the M4: 417 protocol tests, 1,949 app tests in each debug
and release variant, both lint variants and both APK builds passed. The focused
52 transport/request cases and six entry-token cases also passed. These use
synthetic events and JVM fakes; no new native release is published by this work.
