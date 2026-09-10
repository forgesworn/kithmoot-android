# Verified box discovery

A contact card endorses a box and pins its Link transport identity. Its relay
hints do not identify Nostr message endpoints. `BoxStatuses` verifies a
keeper-signed claim and box-signed status before exposing an advertised drops
endpoint. It follows Bothy's V1/V2 draft at `ef051af` and the existing daemon
URL extension at `b89cf9b`. `charge-control=mains` is accepted by the draft;
the inspected daemon enum still needs alignment. The public drop tier remains
disabled pending the shared design agreement.

The native verifier checks the exact endorsed claim, master/node/stash
bindings, retirement, Nostr signatures, pinned Link identity and signature,
serial and status replay protection, freshness, required and optional status
fields, and endpoint shape. Byte-identical Link card reannouncements require
previously verified bytes; the contact book now retains those bytes beside
the highest serial, including refresh and contact replacement.

`protocol/src/test/resources/box-discovery.json` is copied byte-for-byte from
KithMoot's `vectors/box-discovery.json`. Its generator uses deterministic test
keys, real signatures and Link cards. Both implementations check all 34 status
and 8 claim cases, including unsigned substitution, retirement, replay,
capacity rounding, u64 overflow, malformed optional fields, endpoint URL
normalisation and expiry. These are client interoperability cases, not proof
of events issued by a running Bothy daemon. Native tests also mutate every
Link-card byte and re-sign the surrounding status; every mutation is refused.

Relay comparisons preserve query strings and escaped paths. A verified
`/drops?tenant=family` must not label `/drops?tenant=public` as sheltered.
The add-card and arrival screens no longer promise sheltered delivery merely
because a contact was added.

This branch is not yet a complete Android discovery feature. The next work
is an explicit consent action, strict current relay history and live updates,
encrypted replay-state persistence, invalidation on disconnect/expiry/retire,
and device UI acceptance. `RelayPool.queryStored` already fails incomplete
history reads; subscriptions alone do not establish completion. Neither the
verifier nor cached state is wired to grant automatic attribution yet.

Local validation before publishing the draft: 152 protocol and 189 app tests
passed, with lint and debug assembly. Hosted native/recovery checks and actual
Bothy/physical-device interoperability remain separate gates. No APK from
this branch has been published.
