# People profile lookup and local cache

People resolves a contact's real Signet name first, then their saved contact
card name, then a verified public kind-0 name. An imported npub or hex key
used as a name is a placeholder and does not hide a learned name. With no
name available, the row shows a shortened npub. Public profiles are
self-reported metadata, not identity verification or contact permission.

When public profiles and Nostr account networking are enabled, visible rows
are looked up in batches of at most 25 after scrolling settles. Following
also resolves names gradually, 100 at a time, prioritising waiting inviters.
Reads use the existing public profile relays (`purplepag.es` and
`relay.primal.net`), keeping contact queries off room relays. Requests are read-only and
contain public keys; no contact directory or profile is published to a relay.

Each lookup reads this account's local cache first. Fresh entries need no
relay request for 24 hours. Older cached metadata stays visible while a
streaming subscription refreshes it. An empty EOSE from a fast relay cannot
exclude a slower relay that connects afterwards. Missing profiles get three
15-second attempts, separated by one and five seconds. Duplicate requests
are suppressed for ten minutes; showing the rows again after that permits
another bounded lookup. There is no permanent background poller.

The cache stores signed kind-0 events and their local check time in a
dedicated, account-bound Android Keystore encrypted vault outside device
backup. Returned events are signature-checked and use newest timestamp,
lowest event id on a tie. Older answers cannot replace or refresh a newer
cached version. The cache is capped at 2,000 entries and 2 MiB. Entries more
than 30 days since their last successful check are not returned and are
dropped at the next cache write. An unreadable cache falls back to live
lookup without resetting any vault. Signing out clears this account's cache.

Names and HTTPS picture URLs persist locally. Picture files use the existing
4 MiB memory cache and bounded, cookie-free HTTPS downloads; image files are
not persisted. Turning public profiles off hides cached metadata, cancels
People lookups and clears picture memory. Account/network changes invalidate
old work before it can update the next account's screen or cache. Local
metadata remains encrypted until expiry/pruning or sign-out.

`PeopleSectionsTest` covers imported-key placeholders and contact-name
priority. `PeopleProfileCacheTest` covers restore without network requests,
account separation, invalid events, freshness/expiry, bounded misses,
late-policy rejection, unreadable-cache fallback, replacement ordering and a
real relay-pool subscription with a fast empty relay and a late profile relay.
These checks use synthetic identities and do not contact public relays.
