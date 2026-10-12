# Read-only Link route enumeration

2026-10-10. Before this change, foreground orphan-route inspection called
`LinkTransportVault.state()` and created a transport seed/wrapping key when
the vault was absent. Five complete pending-store shards on immutable 7f0
fail at the unchanged-key assertion after a real SIGKILL because that startup
inspection adds `kithmoot.link-transport.v1`. No numeric refusal rows were
reported. The original failures remain evidence, not qualification.

Mesh-kit-private precode `b8cac8a`, `spec/native-link-route-read-design.md`,
precedes this repair on an isolated successor of integrated abb97ecb. Current
main 44857b8 is preserved. The pending fault fixture, snapshots, exact file/key
assertions, cold readers, physical-device guards and SIGKILL drivers are unchanged.

The actual vault now offers read-only route enumeration using its existing
strict decoder. An absent vault returns no route IDs without minting a seed or
writing storage; corrupt or unavailable state still throws. Successful reads
wipe decoded seed/card/route-secret copies after extracting IDs. The manager's
startup enumeration uses that method. Explicit state creation, pairing and
consent retain their existing behaviour.

A fresh JVM regression first reproduces one unwanted seed-generation call on
the unchanged production source (15 of 16 tests pass). With the repair, all
16 Link tests pass. New measured cases cover absent state, existing two-route
state with unchanged bytes/seed, corruption and unreadable storage. Each observes
zero seed-generation calls, writes and native starts during enumeration. Both
production and test sources freshly compile against 6,451 unchanged read-only
dependency files. These direct Kotlin/JUnit results do not qualify Gradle/lint,
APK installation, Android key preservation or the new source head's hosted run.

Own acceptance still requires all nine CI jobs, 150 ordinary checks/46 groups,
six retired-store rows, thirteen positive restart drivers, five pending
SIGKILL/new-PID drivers and all 55 actual refusal rows with exact APK/head binding
and checked deletion. No earlier head's success qualifies this successor.
No restart/cancellation of earlier workflows follows. Full project acceptance,
capacity/security/physical gates and the restricted Rust full suite remain open.
No firmware, port, MQTT/group, public relay or personal signer operation occurs.
