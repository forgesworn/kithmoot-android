# Native room mesh transport

The Android room session can now use `RoomMeshTransport` over an injected byte
link. This is an integration component, not an enabled Bluetooth option. No
production route selects it yet; the app still opens rooms over its existing
relay routes.

The event and query frames use the JSON-only profile of `mesh-webrtc-lan`'s
length-prefixed `MeshFrame` format, as used by Meatchat's native adapter. Frozen
bytes produced by that TypeScript codec are checked in `mesh-room-wire.json`.
The existing experimental `kithmoot-lab/event/v1` and `kithmoot-lab/query/v1`
identifiers remain explicit. This does not assign a MeshCore data type.

A 32-byte hex discovery scope is supplied by the host. It is a routing label,
not a room secret or membership proof. The adapter validates event signatures,
JSON types, future timestamps and every expiration tag. RoomSession still
validates credentials, membership, epochs and encrypted chat. Sender addresses
are routing hints only. Receiving an event never forwards it to the internet.

## Bounds and evidence

- At most 16 KiB per event and 20 KiB per frame; strict UTF-8, framing and bounded
  JSON nesting. Binary sidecars are outside this room profile.
- 64 retained chat/invitation events, at most one hour or signed expiry; 512 seen
  event IDs. Presence and signalling are live only. The cache is volatile and is
  not a durable outbox or a complete archive.
- 32 subscriptions. A stalled collector fails explicitly when its channel fills.
- Queries carry at most eight filters; at most eight incoming queries and eight
  responses are served per second globally, even if a sender changes addresses.
- Rekey blocks normal publication and resets queued link traffic before reopening.
  A failed reset stays blocked. Only the four epoch-recovery request/grant kinds
  can use the recovery path during the barrier. The link owns the actual queue
  reset and must implement that contract before a platform adapter is enabled.

`queryStored` remains unsupported and mesh subscriptions never report complete
history. `queryAvailable` is a bounded best-effort collection. There is no
periodic reconciliation or durable mesh receipt here yet.

`publishConfirmed` raises `PublicationUnconfirmedException` after a successful
local offer. Returning false would incorrectly mean refusal to the existing
outbox. RoomSession preserves UNKNOWN after an unconfirmed offer; local echoes
and mesh replay cannot reconcile it away. Retrying retains the original signed
event. Quiet/cadence wrappers propagate the observation-evidence policy; that
alone does not qualify their use over mesh.

Twelve focused JVM tests cover adversarial input, retention, replay amplification,
rekey queue barriers, teardown despite callback failure, history/receipt distinctions, byte-exact TypeScript codec
interop, actual RoomSession encrypted chat both ways and one row per repeated
event, and UNKNOWN outbox state across local echo, retry and journal reopen.
The journal-reopen test initially exposed REFUSED after local queue admission; the typed
unconfirmed outcome and observation gate fix it. Background flushing also persists possible handoff
before offering an event and preserves UNKNOWN without a receipt.

## Remaining client work

The shared Android engine now has a [native binding](native-room-ble.md) with
serialized calls, an awaited queue-reset barrier and terminal teardown. The
production UI still needs to select it and own permissions/lifecycle. Hop zero is
fixed in the initial binding.

Select nearby-only or mixed routes before invitation lookup and session creation.
All profile, saved-room and account paths need an explicit privacy boundary;
permissions or radio failure must not select public relays. Qualify Bothy and
quiet-room combinations separately. Add shared reconciliation, authenticated
mesh receipts, mixed-lane provenance and forwarding, durable restart recovery,
and truthful UI states. Physical BLE and BLE/radio/internet tests remain open.
