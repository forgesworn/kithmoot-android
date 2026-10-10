# Read-only workspace assignment observation

The Android assignment journal can observe an already admitted room without
receiving an identity, signer, device credential or writable storage. This is
the reader required for native cross-project Inbox and Work navigation; the
native navigation, lifecycle coordinator and chat attention UI are connected
by [Native Inbox and Work](workspace-inbox.md). This foundation alone does not
complete the product's G9 journey.

A caller supplies the current account's existing room keys, current traffic
epoch and the originating room's encrypted journal through `AssignmentSource`.
A project directory invitation grants no room access. Before opening a reader,
the caller must verify deliberately retained admission for that account,
exclude temporary or ended meetings, honour the room's private/public route,
and follow authorised rekeys. Account changes, forgetting and revocation must
close the reader and discard its view. The originating room remains the only
place to sign decisions after checking current credentials and canonical heads.

```kotlin
val reader = AssignmentJournal(
    roomId = admittedRoomId,
    roomKey = admittedRoomKey,
    identity = null,
    transport = roomTransport,
    storage = AssignmentSource { originVault.load() },
    scope = viewScope,
    policy = admittedPolicy,
    readOnly = true,
    readerParticipant = signedInAccountPubkey,
)
reader.open()
```

The same authenticated assignment records and canonical projection drive the
reader and the originating room. Cache records, including uncertain outbox
entries, retain signature and room checks. Reading does not save the journal,
reconcile an uncertain send or republish it. `submit` and `retry` reject before
signing, storage or publication. A reader constructor rejects an identity or
an `AssignmentStorage`; wrapping the origin vault in a load-only source makes
the limited capability explicit.

Navigation requests at most 128 envelopes from the last 24 hours by default;
limits of 1–512 and an explicit non-negative `historySince` are supported. It
uses best-effort bounded relay queries, never the complete-history writer query.
Initial replay processing is capped even when a relay ignores its limit.
These are processing and query bounds, not a bound on bytes sent by a dishonest
relay. After replay, live updates continue. Rekeying keeps the stable canonical
journal but changes the traffic address and key supplied by the caller.

Every reader snapshot has `historyComplete = false`. Missing causal parents
cannot become tasks, and `ready` cannot justify a claim that nothing older
needs attention. Retained records remain readable after their signing
credential expires; expiry does not renew permission to sign. Damaged caches
cannot leave partially decoded cards. Closing immediately clears the exposed
snapshot, stops the live collector and cancels an owned relay query, including
one waiting for a replay response. A cache load completed after closure cannot
start subscriptions.

The writable room journal continues to load its full retained history, persist
before publication and retry the exact encrypted operation. Its
`historyComplete` flag requires a successful retained query and a projection
with no unresolved parents; even that does not prove a relay holds every event
or guarantees permanent retention.
