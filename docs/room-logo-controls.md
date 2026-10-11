# Private room logo controls

G15 remains open. The bounded inline image reader is on main via PR #242;
encrypted control chat and the native metadata follower landed in PR #243.
The [room logo display](room-logo-display.md) adds the local cache and renderer.

## Wire boundary

A logo operation contains a random ordering ID, Unix millisecond time and
image hash (or explicit null for removal). Image bytes sit in `roomLogo` beside
that operation inside the existing encrypted, device-signed chat envelope.
The reader checks the device credential and room admission, the control
channel, exact operation fields and the matching image hash. Mixed reactions,
assignments, replies, artwork, attachments or other conversation fields are
refused. Missing payloads differ from deliberate null removals. No public
image URL, relay tag or upload is introduced.

`app/src/test/resources/room-logo-chat-v1.json` is a verbatim copy of the
[web fixture](https://github.com/forgesworn/kithmoot/blob/feat/room-project-logos/vectors/room-logo-chat-v1.json).
Its 20 signed/encrypted messages use synthetic identities and rooms. Three
valid changes/removals retain every modelled field on decoding; 17 malformed,
mixed, mismatched or misattributed messages are refused. The web generator
lives alongside the fixture and requires the library build first.

## Following and publication

`RoomLogoBook` orders changes by time, ID and image hash, retaining removals.
It records the epoch each message arrived under and discounts removed-epoch
writes after the same rekey grace as shared room names. Cached seeds retain
an optional accepted epoch and never count as a live copy in the relay log.
A carried copy retains its original ordering key and claims no authorship.

`RoomWork` reads overrides through the credential-checked control channel,
notifies metadata changes once and carries eligible images/removals after
rekey or retention. Replacement uses confirmed, guarded publication. The
guard is checked at dispatch and again after confirmation; a closed or
rekeyed room cannot adopt a late result. Transports without guarded
publication refuse rather than silently send without that protection.

## Qualification and remaining work

M4 qualification passed all 427 pure-JVM protocol tests, 1,959 debug app unit
tests and 1,959 release app unit tests, with no failures, errors or skipped
cases. Debug and release lint passed with no errors; existing warning and
information findings remain. Focused tests cover actual reference ciphertext,
room admission, explicit removal, duplicate delivery, cache/rekey carrying,
removed-epoch invalidation and delayed writes across closing or rekeying.
This is automated protocol/session evidence, not a published APK or physical
phone acceptance.

The cache and renderer have their own qualification in the display document.
Still required: the local crop editor and permission/cancellation handling,
project companion journal support, account recovery and cross-client device
acceptance. G15 is not complete.
