# Native BLE room binding

`createAndroidRoomMeshLink(context)` binds the room byte transport to the actual
shared `MeshBleRadio` engine. Construction is inert. The host must choose the room
route, obtain Bluetooth scan/advertise/connect permissions through explicit user
action, then call `start(RoomBleConfig(scope, selfId, serviceUuid))`. All three
values are supplied explicitly. Scope and peer IDs are routing labels, not room
secrets or membership proofs.

This binding is not yet selected by the production room UI. Existing rooms still
use their existing relay routes. Missing permissions or a failed radio never
opens a relay or switches route. The initial binding has hop zero and no
foreground service. Its eventual UI owner must close it when leaving the room or
foreground, until background operation is separately qualified.

## Dependency and ownership

`mesh-radio/source.json` pins the shared engine at `dddaacb` (provider PR 9), its source
archive digest and every compiled file. `python3 scripts/prepare-mesh-radio.py`
fetches and verifies the archive without executing upstream scripts. It can also
take an already downloaded archive. Gradle verifies all prepared sources and
rejects extra files on every build. CI and the production build script run the
same preparation. No sibling checkout, npm install or Capacitor is needed.
The upstream MIT licence is carried in the module resources.
The host supplies its API-33+ manifest, retaining scan/advertise/connect and the
optional connected-device service. It does not import the provider's pre-31
location/legacy Bluetooth permissions. The Java engine remains unmodified.

The host's single coroutine owns all engine calls on Android's main dispatcher.
Its channel holds at most 64 commands/events. At most 32 outbound frames and
128 KiB await handoff; a full queue rejects additional offers. Inputs are copied,
and receive callbacks are deferred outside the host lock. Frames are capped at
20 KiB, with a 32 KiB native envelope limit for base64 expansion. Peer labels are
limited to 256 characters. The shared engine separately caps each peer at 512 queued chunks / 128 KiB,
plus one in-flight chunk; it admits or rejects whole frames without evicting
earlier work. `queuedPeers` counts only accepted native queues. Overflow of inbound/control events visibly fails the
lane and prevents additional offers rather than growing memory.

The exposed state distinguishes idle, starting, ready, resetting, failed and
closed. Writable peer counts and `lastQueuedPeers` describe local radio state;
neither is a peer receipt. Room publication remains UNKNOWN after handoff,
including when the engine reports zero queued peers.

## Rekey and teardown

`RoomMeshLink.resetQueued` now suspends. `RoomMeshTransport.beginRekey` blocks
publication and invalidates its generation before awaiting that barrier outside
the room lock. The binding invalidates queued host commands, closes the old
engine, then constructs and starts a new one with the explicit configuration.
Only after successful closure and startup may rekey finish. Late callbacks from
the old generation are discarded. A failed teardown prevents this owner from
ever reopening, and `awaitClosed` reports it to the host.

`close` immediately invalidates commands and listeners without waiting on the
main thread. The host must then `awaitClosed()` before allowing a different room
to own the radio. Starting again after close is refused. Cancelling startup or a
reset closes the owner. The barrier discards unsent queues; it cannot recall bytes
already handed to Android or transmitted over the air.

## Validation and remaining work

JVM tests inject the radio boundary while exercising the actual owner and room
transport. They cover inert construction, denied permissions, deferred callbacks,
snapshotting, bounded queues, cancellation, stale callbacks, rekey barriers,
failed teardown and unconfirmed offers. Two actual RoomSessions exchange encrypted
chat through two native owners and repeated byte callbacks produce one chat row.
Compiling/packaging the pinned engine is separate from these simulated callbacks.

Still needed: route selection before invitation/account/profile lookup, UI
permissions and lifecycle ownership, nearby-only and mixed-path admission,
reconciliation, receipt/provenance states and physical Android BLE qualification.
No Heltec, BLE device, existing room or public relay was used for these tests.
