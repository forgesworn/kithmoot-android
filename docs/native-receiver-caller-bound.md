# Complete bounded receiver caller traces

The debug-only receiver recorder now retains at most 64 application frames per
event, following mesh-kit-private precode `a4c057a2`,
`spec/native-receiver-caller-bound-refinement.md`. The 128-event queue, paired
outcomes, every counter, static class/method/line formatting, all case cleanup
and release refusal rules are unchanged. More than 64 frames still marks the
capture truncated and incomplete. No diagnostic event is discarded to obtain
a complete-looking result.

Two new debug unit methods recurse through actual application stack frames and
exercise the actual recorder. At exactly 64, the paired trace is complete; at
65, both traces retain 64 frames and explicitly refuse completeness. Context,
pairing, zero counters, static formatting and owner closure are asserted.
All eleven existing debug observer methods and both release controls remain
byte-identical. The seal factory and pending Android instrumentation are unchanged.

The original sixteen-frame implementation fails exactly the two new methods
out of thirteen (10.719 s). With the bounded change, all thirteen debug methods
pass (11.307 s) and the two release controls pass (8.207 s). These are fresh
Kotlin/direct JUnit runs with explicitly labelled JVM BuildConfig flags and
7,910 immutable dependency files. They measure the real portable stack recorder;
they do not qualify Gradle variants, APKs, Android Keystore, actual pending
diagnostics or radio traffic.

The ordinary Android gate remains byte-identical to the parent: 166 cases in
50 groups, including all four missing-index regression methods. The successor
branch triggers the same nine hosted jobs. The root lab collector recognises
only advertised 64-frame successors with the real boundary fixture; historical
heads retain their sixteen-frame allowance. Truncation, unpaired/malformed
callers or nonzero counters still refuse. All five actual pending captures and
actual debug/release reports must be collected at this final head before merge.

The separate controlled AtomicFile overlap investigation, capacity/security,
and physical qualification remain open. No radio, firmware, MQTT/group, public
relay, registration, purchase or contact operation is included.
