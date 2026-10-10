# Source-verified invitation reading under contention

The IO reader for a native host's displayed and Home invitation now waits for
an in-flight source inspection before checking its current observation. The
foreground tap guard retains its immediate tryLock refusal. Both paths require
the same selected owner, revision, epoch, generation, ACTIVE lifecycle and
absence of pending work. IO sharing still reads the actual repository and
verifies its complete invitation against the independent source.

Previously the IO reader used the non-blocking guard. A source inspection just
after a Ready publication could deny that one read, publish a blank invitation
and leave it blank while unchanged hosting observations were conflated.
A deterministic test uses the real source's locked unknown-participant read,
actual replacement and repository to reproduce the old refusal. No production
sleep, polling, timeout extension, source/index write, signing or radio offer is
added by this correction.

Precode design is `mesh-kit-private/spec/native-invitation-sharing-read.md`.
The successor keeps all five replacement restart profiles and needs its own
complete hosted rendered/recovery gate. Local source contention measurements
alone do not establish resolution of the predecessor's emulator failure or
qualify physical BLE/RF, participant receipts or UK–Portugal delivery.
