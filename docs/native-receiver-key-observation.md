# Disposable receiver key caller observation

This candidate preserves a48f4d4e and main 2f09. The committed mesh-kit-private
design b725adf precedes the observation code; the actual ac393 capture shows the
receiver-key failure moving from the committed window to the index window.
No receiver correction is claimed before its caller is measured.

An internal opt-in debug observer records actual receiver key creation requests
and CREATED/FAILED outcomes at AndroidKeyStoreSealKeys.create. The key boundary
filters the fixed receiver category; the observer receives no alias, key object,
key bytes, store content, room identity, capability, chat or exception message.
Only public class/method/line, thread/sequence and static test phase labels enter
a bounded in-memory queue. Capture has one owner, 128 events and 16 caller frames;
overflow, unfinished requests, active recorders, missing records and observation
errors make diagnostics incomplete. Writer exceptions retain their original type.

The original five cold windows observe direct entry and foreground construction,
startup, reopen and lease close. Finally closes the observation and reports
diagnostic-only stream records even when an original key/file assertion fails.
Reporting errors cannot replace the original store exception. The actual recovery
PID/mode, eleven matrix rows, ciphertext/key, route, owner-barrier and cleanup
assertions stay intact. No warmup, extra app test, delay, store/key initialisation
or reset of receiver credit is added. Default/release registration is disabled;
BuildConfig generation is explicitly enabled for that guard.

Eleven local JVM tests pass with three complete repository sources freshly
compiled; a separate two-case false-debug-flag experiment verifies registration
refusal and original provider failure. Both use explicit JVM-only BuildConfig
fixtures and unchanged cached dependencies; they do not prove the generated
Android flag, real Keystore operation, APK, lint or emulator qualification.
The initial missing BuildConfig symbol and caller-filter failure are preserved
in the lab evidence. The 11 tests cover ownership/stale close, concurrent pairs,
static labels/callers, request context conservation, overflow/in-flight/error
honesty, actual seal-boundary failure and category filtering.

The independent full-log collector binds observer/test/build-feature sources and
requires complete diagnostic headers and request/outcome pairs at this head.
Historical heads retain their original contract. Generated cases never qualify
real execution. All nine actual jobs, 150 ordinary checks/46 groups, six retired
rows, thirteen positive and five pending SIGKILL/new-PID drivers, 55 pending rows,
five reporting controls and five complete diagnostic captures remain required.
The unobserved a48/ac393 controls are preserved. Observation changing whether a
failure reproduces is not proof of a production correction. Actual caller
attribution, all remaining capacity/software/security and physical gates remain
open. No radio, firmware, MQTT/group or public relay is changed.
