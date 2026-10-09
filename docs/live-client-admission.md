# Fresh Nearby admission owner and root gate

The independent Kotlin codecs are now followed by an explicit client exchange.
The caller chooses and owns a transport before entry. The exchange never
constructs a relay, widens a relay list, treats EOSE as authority, or calls
`queryStored`. A descriptor is verified against the original persistent link.
An unverified room identifier must not select an identity or save a room.

Allow one active challenge per local device/room, at most eight process-wide.
Generate one disposable reply key. Offer the identical signed request at
0/10/20 seconds, within one 90-second monotonic deadline. These timings correct
the original 0/30/60 design: a cached answer expires after 30 seconds and must
not be re-signed to extend it. Observe wall/monotonic rollback, local retirement
and coroutine cancellation. Bound response work before cryptography, consume
one reply, cancel all children/collectors and zero the disposable key on every
exit. Publication admission is not a delivery receipt.

A verified answer supplies epoch-zero capability plus a hint. Before presence,
chat, calls or member-desk output, require a fresh answer from the pinned root,
including at epoch zero. Neither member catch-up, rekey replay nor its completion
may release this initial gate. The existing root desk answers each ID once;
make at most three fresh epoch requests under one deadline and accept only the
current request ID. Reject grants below the greatest known hint/rekey/current
epoch. Preserve removed/unknown policy and reject cancellation or a late reply.
A positive epoch already held must derive the same traffic room as the root's
grant; an equal epoch number with a different key is a conflict.
Persist a newer epoch through the existing epoch gate before opening traffic.

The original invitation's retirement must remain watched across both exchanges.
`joinLivePersistentRoom` retains that watcher until `RoomSession.join` finishes,
binds the factory's session to the verified root/device/room/hint and exact
selected transport, and leaves any created session on failure. Its proof expiry
bounds the second stage. The session checks that expiry before opening traffic.
Route selection and permission are an outer owner: no failure chooses Internet.
The new library/session path must be measured before a fresh-join screen enables
it. Saved-room reopening keeps its existing recovery behaviour.

Tests cover lost request/reply/grants, duplicates, wrong/expired replies, silence,
resource caps, clock rollback, cancellation, retirement between exchanges,
epoch-zero denial, stale hints and both-way actual RoomSession chat. Subsequent
gates are native route/UI integration, keeper/client process death, explicit
mixed forwarding, authenticated receipts and physical BLE/LoRa. A single-root
fixture does not implement the production aggregate durable responder quota.


## App entry integration

The explicit Nearby action keeps the original invitation and a separate nearby
code. It requests Bluetooth permission before entry and asks the person to join
with a new local identity. It cannot use an account signer or switch to Internet
on failure. A signed-in account's independent background activity retains its
own settings; this is a route guarantee for this room, not whole-phone isolation.

The discovery owner and device key exist before the challenge. Only a verified
proof may choose the room identity or prepare the session. The same transport
survives both gates. Save the new room only after root confirmation; refuse an
existing saved room rather than overwrite it. On later entry failure, roll back
only this attempt's matching participant/device. Epoch/member journals are
internal provisional state, excluded from the saved-room UI and swept on a
subsequent startup if no room was committed. Never use them to bypass live
admission. Backgrounding before handoff cancels the entry and closes Bluetooth.

Native transport now allows three identical offers of kinds 20466–20469, at
least one second apart per cached ID, matching the measured shared-mesh policy.
The 512-ID cache remains bounded; these controls are never retained or replayed
as history. The responder's durable quota is independent of cache eviction.
Ordinary chat deduplication is unchanged. A regression first demonstrated that
the old native seen-set delivered only one of three spaced requests.

Saved rooms can subsequently choose the existing Internet/Nearby/mixed routes.
Fresh mixed admission, account-backed offline identity, quiet/anonymous/Bothy
combinations and self-destruct cleanup need their existing separate authority
and route qualification; the Nearby action does not bypass those restrictions.

The real ViewModel/emulator journey exposed the prior saved-room requirement
for at least one relay. A new room may now store an empty relay list only with
an explicit Nearby route; Internet/mixed switching still requires configured
relays. A signed room relay list may be kept without opening it. The UI remains
at the entry screen until both gates and setup finish. Current emulator checks
cover three identical offers after dropped request/reply, both-way chat,
retained local identity/route on reopen, cancellation at the epoch gate,
background cancellation during the challenge and invalid-code refusal before
radio construction. A rendered UI check verifies the local-identity choice.
These are simulated radio bytes, not GATT, physical permission or LoRa evidence.
