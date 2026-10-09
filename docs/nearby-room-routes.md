# Room connections on Android

An already joined room can be reopened with **Internet**, **Nearby only**, or
**Nearby + Internet**, selected under its home-row menu's **Connection** action.
The setting belongs to this phone and is retained in the encrypted room record.
It does not change the room identity, membership, keys or signed message IDs.
Old records retain Internet. Unknown route values refuse entry.

Nearby currently means foreground Bluetooth between provisioned KithMoot
clients. It does not connect the phone directly to a Heltec's companion service.
The separately configured native-radio gateway maps Meshtastic channel text to
rooms. Neither Bluetooth availability nor inclusion in an APK proves physical
BLE, LoRa or UK/Portugal delivery.

## Entry and lifecycle

Choose the connection while the room is closed. Tapping its row requests Android
Nearby devices permission where needed. Refusal leaves the route unchanged and
opens no other path. A failed Bluetooth start fails this entry, including mixed
entry; an already running mixed room can continue over its selected Internet
path if Bluetooth subsequently fails.

Nearby-only does not construct the room's relay sockets or profile pool, renew
its credential through an account signer, publish its account bookmark, upload
images or start WebRTC. A valid cached account device credential can reopen the
room offline while the same account remains selected. An expired credential
requires an explicit Internet renewal. Unrelated account synchronisation and
other rooms keep their existing settings; this is not an app-wide offline switch.

Background room delivery, notification replies and credential renewal check the
saved route. Switching routes waits for in-flight background handoffs and stops
shared relay queues before saving. Other authorised room watches then reconnect,
including when the save fails. Bytes already transmitted cannot be recalled.

One process-wide lease owns Bluetooth. Leaving or hiding the app immediately
closes it, discards pending offers, and awaits teardown before another owner
starts. Returning after a brief hide still completes teardown before reopening.
A teardown failure refuses another owner for the rest of that process. No nearby
foreground service is enabled. Anonymous, quiet/cadence, Bothy and concurrent-call
combinations are refused; calls currently require Internet only. Nearby-only
also refuses self-destruct rooms, whose authoritative cleanup needs Internet.

## Discovery and wire identity

Only already admitted rooms can choose nearby. Fresh offline invitations and
persistent invitation retirement checks are not implemented here. Their design
requires a fresh, authenticated authority response, not a timeout or synthetic
EOSE from a partial mesh cache.

Discovery uses the stable root room ID, unchanged by rekey:

- Scope: SHA-256 of ASCII `kithmoot/nearby/scope/v1`, one zero byte, then the
  32 decoded root-ID bytes. Encode the result as lowercase hex.
- Service UUID: first 16 bytes of SHA-256 of ASCII
  `kithmoot/nearby/service/v1`, one zero byte, then the 32 decoded scope bytes.
  Set the version nibble to 8 and RFC variant bits to `10`, then format as UUID.
- Self routing ID: fresh random 32 bytes for each opening, encoded as hex.

These are routing labels, not admission capabilities. They reveal repeat
presence to an observer who knows the label. The four fixtures in
`app/src/test/resources/nearby-discovery-v1.json` are checked independently by
Kotlin and `node scripts/check-nearby-discovery.mjs`.

## Mixed traffic and receipts

The same signed event is offered on both selected paths. Subscriptions produce
one event per ID; receiving another member's message does not forward it. A
separate explicit forwarding service is still required to carry mesh-only peers
to Internet-only peers. Relay provenance is kept independently from deduplication.
A mesh echo cannot confirm the durable outbox. An actual relay acknowledgement
retains its existing meaning; a Bluetooth offer remains UNKNOWN, with the same
signed event retained for retry. The header shows nearby links separately from
relay counts, not a delivered indicator. Complete-history queries use only the
selected relay path; mesh-only refuses that claim.

## Evidence boundaries

JVM tests exercise route persistence, offline credentials, background exclusion,
route-change serialization, exclusive native ownership, duplicate native byte
callbacks, mixed RoomSession chat, rekey and receipt provenance. They do not
exercise physical Android permissions/GATT or prove the entire ViewModel cannot
reach a network service. The full entry/UI and rapid foreground-transition flows
still need emulator/instrumentation and physical acceptance evidence. Fresh
nearby admission, authenticated mesh receipts, full cold keeper restart and
native forwarding remain separate requirements.
