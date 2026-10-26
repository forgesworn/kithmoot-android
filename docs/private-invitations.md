# Private invitations account networking

The installation has one persisted `private-invitations` or `nostr` account-network
choice, available before sign-in in Settings > Privacy. Normal distributions default
to `nostr`; build an invitation distribution with
`-Pkithmoot.accountNetworkDefault=private-invitations`. The property is validated
and independent of experimental invitation/native qualification flags. A successful
first load persists the selected default, so a later distribution cannot silently
change an existing installation's choice.

The Application loads this policy before Activities, receivers or background services
restore accounts. Preference read/default-save failures hold private mode and show
an actionable error. Selecting private takes effect before persistence; a failed save
keeps a visible in-memory private hold. Enabling Nostr requires successful durable
persistence. Re-reading the old stored choice cannot undo the in-memory hold.

Private mode pauses automatic Signet contacts directory polling, DM relay discovery,
kind-1059 contact/project inboxes, kind-30078 room bookmarks, Spry account sync and
read positions, public profiles/follows, remote avatars and ordinary public-relay
room/background work. Ordinary account actions explain how to enable the mode
instead of waiting for intentionally stopped services. The saved public-profile
preference remains unchanged; effective rendering and lookup require the network
mode as well as that preference.

The protected signer/root keys, room admissions, encrypted caches, pending records
and tombstones remain. Paused sync does not claim success. Local expiry and deletion
journals keep their existing deferred-work semantics when a relay is unavailable.
VMLS invitations/membership, restore witness and paired Link/Bothy transports retain
their separate composition. Public Nostr box/keeper discovery (10640/30640) is
paused too: readers close, while saved watches, watermarks and local caches stay
unchanged. Explicit Nostr enablement resumes the retained watches with new readers. Existing relay rooms with exclusively
active paired routes are distinguished by current consent authority, not host/key
lists. Remote signer transport (including explicit NIP-46 and saved signer restore)
and update checks are separate boundaries and can expose their own metadata.

A shared installation account gate serializes account replacement, refresh and mode
recomposition. Generations close ordinary sockets, cancel async work immediately,
and reject stale sends/reconnects even after a rapid private-to-Nostr round trip.
The transition joins registered jobs and service-owned scopes. Shared background
pools and mixed public/Link background handles also carry a policy generation, so
account replacement cannot reuse an old factory. Foreground ordinary sessions pause
on a generation mismatch even when an intermediate private emission was conflated;
exclusively paired sessions remain and their optional public profile readers recompose. Profile HTTP resources are registered before execution and canceled on
invalidation; cached remote URLs cannot trigger private-mode requests.

`AccountNetworkPolicyTest` exercises the actual extracted account-service composition
wired into RoomViewModel with real SharedProjects, RoomBookmarks, ContactInbox and
RelayPools, replacing only disk/socket adapters. It covers private cold restore,
manual/relay refresh, account replacement, multiple owners, explicit Nostr enable,
active transition, queued publication, reconnect, delayed signing, persistence
failures and avatar HTTP resource cancellation. SharedRelayPools tests cover stale
factory replacement. These JVM tests and a build do not establish installed-app,
real-signer, physical-device or production acceptance.
