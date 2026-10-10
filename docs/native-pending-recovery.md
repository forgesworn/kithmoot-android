# Explicit recovery of saved native updates

Room details offers Recover saved update for a complete selected RECOVERING
observation in an active or retired room. Confirmation captures its public
binding, owner generation, revision, epoch, lifecycle and exact pending IDs.
Cancel does nothing; a source refresh or a new foreground owner invalidates it.
Closed/terminal recovery stays outside this control.

A separate serialized controller work item verifies that expectation against
the actual source, then uses pending-transition receiver validation and existing
recover(). It awaits the actual outcome. The ViewModel retains its busy claim
until that result and says whether the update remains pending or has recovered;
neither message claims member delivery. It never signs or reconstructs another
original, resets expiry/attempts/charged bytes, retries an archive, re-admits a
cleared courier or chooses an unapproved lane. Invalid actual receiver state
fails the owner. A stale expectation refuses before validation or recovery.

Recovery republishes a current source observation after an unconfirmed offer,
including its saved retry revision. The projection itself only reads source
state. Mesh-kit designs 5d54921 and 1628f32 precede the corresponding changes.

Qualification retains every old gate and adds two rendered host cases:
Nearby cancel, unavailable lane, actual encrypted-store reopen, previous-owner
refusal and recovery of the same original after that lane returns, with permanent
attempts/charged bytes and approved two-way chat; Mixed actual rejected-offer
source refresh disables an open confirmation and retains the same original.
The loopback relay and BLE byte boundary are fixtures; real Keystore/AtomicFile,
controller, ViewModel and controls are production. These are not radio or process
kill measurements. The runner requires 134 ordinary checks in 43 groups,
11 native host cases, five member cases, one composer case and every retained
active process-kill driver. Own four-job hosted qualification remains required.

Retirement-specific SIGKILL windows, replacement, closure, terminal recovery,
migration, Internet/timed creation, abandonment, authenticated receipts,
independent sync review and physical BLE/RF/group/airtime gates remain open.
No firmware, channel, MQTT, real group or public-relay setting changes here.

Local direct Kotlin 2.0.21 compilation passes for the changed production UI and
Android test sources against immutable cached dependencies. The focused JVM run
passes 89 checks/five classes in 8.199 s; source and read-only baseline hashes
are unchanged through the run. Android instrumentation is compiled, not locally
executed. Eleven runner fault checks pass in 60.692 s; shell syntax and diff
checks pass. The first compile caught a missing NativeHostingStatus import and
ran no JVM checks; the corrected fresh run supplies this result. Full hosted
Gradle/lint/APK/installed acceptance remains a separate requirement.
