package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** One keeper per closed room while the app runs, standing aside for an open or watched room's own. */
class NodeClosedRoomKeepersTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })

    private fun custody(seed: Int, at: Long = now): NodeCustody {
        val secret = ByteArray(32) { seed.toByte() }; val room = deriveRoom(secret)
        val owner = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
        val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, at,
            existing = NodeExistingRoomPolicy(false)).toJson(), at))
        return NodeCustody.read(buildJsonObject {
            put("v", 1); put("node", node.wire); put("participant", owner.participant); put("deviceKey", owner.deviceSecretKey.toHex())
            put("credential", owner.credential.toJson()); put("secret", secret.toHex()); put("rootKey", root.secretKeyForStorage().toHex()); put("phase", "creating")
        })
    }

    @Test fun `keepers start for due rooms, stand aside for a claimed or open room, and follow the saved rooms`() {
        val scope = CoroutineScope(SupervisorJob())
        val first = custody(61); val second = custody(62)
        var due = listOf(first, second)
        val open = mutableSetOf<String>()
        val started = mutableListOf<String>(); val stopped = mutableListOf<String>()
        val keepers = NodeClosedRoomKeepers(scope, { due },
            gate = { { room -> room !in open && !NodeKeeperClaims.claimed(room) } },
            start = { held, _, _ -> started += held.node.room; AutoCloseable { stopped += held.node.room } })
        try {
            keepers.refresh()
            assertEquals(setOf(first.node.room, second.node.room), keepers.rooms)
            keepers.refresh()
            assertEquals(2, started.size)

            // The room opens here: its own keeper claims it, and the closed one stops.
            val release = NodeKeeperClaims.claim(first.node.room)
            keepers.refresh()
            assertEquals(setOf(second.node.room), keepers.rooms); assertEquals(listOf(first.node.room), stopped)
            keepers.refresh(); assertEquals(setOf(second.node.room), keepers.rooms)
            release()
            keepers.refresh(); assertEquals(setOf(first.node.room, second.node.room), keepers.rooms)

            open += second.node.room
            keepers.refresh(); assertEquals(setOf(first.node.room), keepers.rooms)
            open.clear()

            // A forgotten room stops; a new destination for the same room restarts.
            due = listOf(custody(61, now + 5))
            keepers.refresh()
            assertEquals(setOf(first.node.room), keepers.rooms)
            assertEquals(listOf(first.node.room, second.node.room, first.node.room), stopped)
            assertEquals(4, started.size)

            due = emptyList(); keepers.refresh(); assertTrue(keepers.rooms.isEmpty())
        } finally { keepers.close(); scope.cancel() }
    }

    @Test fun `a keeper that fails to start is tried again at the next refresh`() {
        val scope = CoroutineScope(SupervisorJob())
        val held = custody(63); var fail = true; var starts = 0
        val keepers = NodeClosedRoomKeepers(scope, { listOf(held) }, gate = { { true } },
            start = { _, _, _ -> starts++; if (fail) error("Node unreachable"); AutoCloseable { } })
        try {
            keepers.refresh(); assertTrue(keepers.rooms.isEmpty())
            fail = false; keepers.refresh(); assertEquals(setOf(held.node.room), keepers.rooms); assertEquals(2, starts)
        } finally { keepers.close(); scope.cancel() }
    }
}
