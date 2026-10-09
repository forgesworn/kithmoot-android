# Saved native authority references

A native saved room references the independently encrypted authority journal;
it contains no root signing key. Its versioned reference fixes the original
room/root/participant/device, route, canonical relays, binding pin and invitation
ID. A successfully persisted source installs it while still suspended, at
epoch zero with no pending transition, after checking the actual invitation,
base secret and signed welcome lifetime. Existing legacy hosts cannot be
converted through this operation or an ordinary repository replacement.

Restore validates the reference and excludes a simultaneous legacy host.
`NativeKeeperVault.forSavedRoom(...).open()` still needs the independent source
record and verifies that source's actual invitation and signed base welcome.
Missing/corrupt state never falls back to a saved key or initialises a new
journal. Actual controller/receiver readiness remains a separate gate.

Repositories containing native references use version 2. Old clients accept
only version 1 and therefore refuse the whole repository rather than discard
unknown authority metadata. Legacy-only repositories remain version 1; this
code does not migrate an existing legacy authority. Repository save/update
cannot erase or change a native reference. Explicit forgetting and authority
store cleanup still need their foreground lifecycle integration.

Bookmark refresh preserves the exact native binding and bearer link for the
same owner, including its selected route, relays, epoch hint and retirement/
key-change status. Owner replacement and an added legacy signer refuse. A
route/relay edit that changes the source pin currently needs an explicit journal
policy transition; ordinary preference changes cannot reset retry debt.

Nine new storage checks exercise the actual RoomRepository and source journal
in Internet, Nearby and mixed modes: durable original-answer retry/debt, missing
or corrupt source refusal, policy/owner edits, bookmark refresh, malformed or
forged references, old-header downgrade, signed lifetime, legacy transfer
refusal and actual-source invitation disagreement. Direct cached Kotlin/JUnit
qualification is recorded in mesh-kit-private/lab/hybrid-room; it does not
replace full Gradle/lint/APKs, Android keystore or process-death acceptance.

Fresh creation coordination, explicit legacy transfer, ViewModel/foreground/UI
attachment, approval controls, root metadata, closure teardown and wipe remain.
No physical radio, channel/group, MQTT setting or public relay was touched.
