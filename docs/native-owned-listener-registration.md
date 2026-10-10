# Native keeper waits for its actual local listeners

When native hosting starts, recovery previously published Ready before its
combined invitation/epoch collector had registered in the selected routes.
The controller now owns separate invitation and current-epoch collectors for
each selected lane, and awaits their real local registrations before recovery
can release startup and publish Ready. A failed or cancelled registration keeps
hosting unavailable and retains the original source; requests still enter the
bounded serial queue and use existing source/receiver/owner/handoff guards.

The callback comes from RelayPool's actual SharedFlow subscription installation
or RoomMeshTransport's actual inbound reader and query-offer boundary, outside
their monitors. It is not coroutine launch, socket connection, EOSE, replay
completeness or participant receipt. Each keeper's filters are frozen before
collection. Relay reconnect retains the same filters and local registration
does not repeat. Callback/query failure and cancellation use the real cleanup
paths; ordinary room subscriptions and bounded request retry dedup are preserved.
Exact kind/room/root checks at each owned collector reject a relay placing a
request on the wrong subscription before it reaches the authority queue.

Precode design mesh-kit **c5ab81c**, in
`lab/hybrid-room/NATIVE-OWNED-LISTENER-REGISTRATION.md`, precedes this boundary.
It implements the listener foundation of the committed invitation-replacement
design. Durable invitation generations, source/index reconciliation, switching
an existing owner's generation and replacement controls are still unimplemented.
There is no new link, signer, handoff credit, radio or relay selection.

Fresh Kotlin 2.0.21 compilation of the three production and three JVM test
sources, followed by **153 focused JVM checks/seven classes**, passes in
**30.184 s** (JUnit execution **5.690 s**), with source and immutable compiled
dependency hashes unchanged. Seven added cases exercise cold/frozen local
registration, actual inbound provenance, capacity/closed/query/callback refusal,
actual reader/subscription cleanup, relay reconnect and the actual controller's
independent filters on Nearby/Internet/Mixed and failed-registration startup hold.
The first compile found a missing helper import/reference and executed no JVM
tests. An intermediate 153-case run passed 152 cases and exposed an incorrect
new fixture assertion about pre-open REQ attempts; the correction checks actual
connection state without changing production dispatch. The passing fresh run
supplies the final local evidence. No instrumentation is locally executed.

The replacement branch now triggers the unchanged four hosted gates. The exact
new head remains unqualified/unmerged until its own full Gradle tests/lint/APKs,
both signing-lineage jobs, all **140 installed checks/44 groups**, six complete
store-refusal metric rows and all **eight external process-kill drivers** pass.
Previous main d9 qualification cannot qualify changed production code.

No physical BLE/RF, MQTT, existing group, firmware or public-relay operation is
performed. Replacement, terminal/migration, creation/abandonment, participant
receipts and the remaining physical/group/security/airtime gates stay open.
