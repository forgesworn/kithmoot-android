# Native invitation retirement storage

The schema-4 authority journal discarded the signed retirement and its attempt
counters when Pending completed. Schema 5 transfers the exact event and its
per-lane maps to a bounded archive in that same source write. Explicit retries
use the retained event and the existing rolling byte debt; the eight-attempt
limit survives completion and reopen. Ordinary recovery never retries an
archived original automatically. Local offer custody is not participant receipt.

Retirement and closure prove archive capacity and full unsigned pending and
completed record layouts before signing. The final archive slot is reserved for
closure. Restore verifies strict fields, signatures, invitation binding,
canonical bodies/tags, epoch/time bounds, offer/attempt relationships and
pending/archive separation. A source-issued retirement proposal is consumed
only at its exact revision; retirement alone does not hold the live chat epoch.

Fully validated schema-4 sources restore without a write. The next authorised
write preserves their fields while encoding schema 5. Existing pending notices
retain their original counters. Missing completed notices remain explicitly
missing: retired records reserve one unknown slot; pending closure reserves one
for possible earlier retirement; completed closure reserves two. No missing
notice is reconstructed, signed or given new credit. Earlier schemas still
require explicit device-registry migration. Public observations report missing
evidence, and new approvals/cards stop at actual retirement.

Qualification is pending. Six new JVM cases exercise all selected routes,
completion/reopen and attempt exhaustion, schema-4 active/pending/missing
compatibility, foreign/stale/withdrawn proposals, ambiguous completion, strict
archive corruption and expiry/rollback refusal. The existing closure case adds
actual terminal schema-4 pending/completed compatibility. Two emulator cases use
the actual controller, sessions and encrypted Keystore/AtomicFile stores,
including an approved offline device, exact original retry and missing-history
refusal. Only the Nearby byte boundary is injected. All earlier host, member
and process-death drivers remain required, as do all four own hosted gates.

The broader design was committed in mesh-kit-private as e21de40 before this
implementation, in lab/hybrid-room/NATIVE-RETIREMENT-STORE.md. Rendered retire
and retry controls, every invitation-sharing surface, index-hint repair,
replacement, terminal/destructive controls and their process-death gates still
need implementation and acceptance. This storage increment does not qualify
them, physical RF, MQTT or a real group deployment.
