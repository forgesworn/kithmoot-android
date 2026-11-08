package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Signed fixtures produced by Fold's independent TypeScript implementation. */
class NodeMigrationVectorsTest {
    private val v = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/node-migration-vectors.json")).readText()).jsonObject
    private val now = v.getValue("now").jsonPrimitive.long
    private val keys = v.getValue("keys").jsonObject
    private val authority = keys.getValue("authority").jsonPrimitive.content.hexToBytes()
    private val node = requireNotNull(NodeRooms.read(v["node"], now))
    private val p = requireNotNull(NodeMigrations.readPreparation(v["preparation"], now, node.authority))
    private val ready = v.getValue("ready").jsonArray.map { it.jsonObject }
    private val history = NodeMigrationHistory(v.getValue("history").jsonObject.getValue("events").jsonPrimitive.long,
        v.getValue("history").jsonObject.getValue("digest").jsonPrimitive.content)

    @Test fun readsTheExistingRoomPolicyAndReproducesItsSignedBody() {
        assertEquals(NodeExistingRoomPolicy(false), node.existing)
        assertEquals(deriveRoom(keys.getValue("secret").jsonPrimitive.content.hexToBytes()).roomId, node.room)
        assertEquals(node.event.id, NodeRooms.sign(authority, node.room, node.origin, node.serviceKey, now,
            turnPorts = node.turnPorts, existing = NodeExistingRoomPolicy(false)).id)
        val strict = NodeRooms.sign(authority, node.room, node.origin, node.serviceKey, now)
        assertNull(requireNotNull(NodeRooms.read(strict.toJson(), now)).existing)
    }
    @Test fun retainsAndReproducesTheOriginalQuietAdmissionPolicy() {
        val migrated = requireNotNull(NodeRooms.read(v["policyNode"], now))
        val existing = requireNotNull(migrated.existing)
        assertEquals(true, existing.policy?.get("quiet")?.jsonPrimitive?.boolean)
        assertEquals(2, existing.policy?.get("members")?.jsonArray?.size)
        assertEquals(migrated.event.id, NodeRooms.sign(authority, migrated.room, migrated.origin, migrated.serviceKey, now,
            turnPorts = migrated.turnPorts, existing = existing).id)
        val receiver = NodeMigrationFrameReceiver()
        val completed = NodeMigrationFrames.encode(migrated.wire).mapNotNull { receiver.push(it.toJson(), node.authority, now) }
        assertEquals(listOf(migrated.wire), completed)
    }
    @Test fun readsEveryEndpointAndReproducesPreparationReadyAndCommitIds() {
        assertEquals(3, p.endpoints.size)
        assertEquals(3L, p.epoch)
        assertEquals(p.event.id, NodeMigrations.signPreparation(node, authority, p.operation, p.endpoints, p.epoch, now).id)
        for ((index, raw) in ready.withIndex()) {
            val r = requireNotNull(NodeMigrations.readReady(raw, p, now + 2))
            assertEquals(history, r.history)
            val device = keys.getValue("devices").jsonArray[index].jsonPrimitive.content.hexToBytes()
            assertEquals(r.event.id, NodeMigrations.signReady(p, device, r.endpoint.sid, r.grantWire, r.history, now + 1).id)
        }
        val commit = requireNotNull(NodeMigrations.readCommit(v["commit"], now + 3, node.authority))
        assertEquals(3, commit.ready.size)
        assertEquals(commit.event.id, NodeMigrations.signCommit(p, authority, ready, now + 2).id)
    }
    @Test fun requiresAllThreeClientsAndBindsEachSession() {
        assertTrue(runCatching { NodeMigrations.signCommit(p, authority, ready.take(2), now + 2) }.isFailure)
        assertTrue(runCatching { NodeMigrations.signCommit(p, authority, listOf(ready[0], ready[0], ready[2]), now + 2) }.isFailure)
        val raw = NostrEvent.fromJson(ready[0]); val body = Json.parseToJsonElement(raw.content).jsonObject
        val device = keys.getValue("devices").jsonArray[0].jsonPrimitive.content.hexToBytes()
        val wrong = Events.sign(device, raw.kind, raw.createdAt, raw.tags, JsonObject(body + ("sid" to JsonPrimitive("other-session"))).toString())
        assertNull(NodeMigrations.readReady(wrong.toJson(), p, now + 2))
    }
    @Test fun recoversCommittedEvidenceAfterThePreparationExpiresButRefusesLateCommit() {
        assertNull(NodeMigrations.readPreparation(v["preparation"], now + 600, node.authority))
        assertNotNull(NodeMigrations.readCommit(v["commit"], now + 3600, node.authority))
        assertNull(NodeMigrations.readCommit(v["commit"], now + NODE_HORIZON_SECONDS + 2, node.authority))
        assertTrue(runCatching { NodeMigrations.signCommit(p, authority, ready, now + 600) }.isFailure)
    }
    @Test fun rejectsCorrectlySignedButIncompleteCommitAndWrongAuthority() {
        val raw = NostrEvent.fromJson(v.getValue("commit")); val body = Json.parseToJsonElement(raw.content).jsonObject
        val changed = Events.sign(authority, raw.kind, raw.createdAt, raw.tags, JsonObject(body + ("ready" to JsonArray(ready.take(2)))).toString())
        assertNull(NodeMigrations.readCommit(changed.toJson(), now + 3, node.authority))
        assertNull(NodeMigrations.readCommit(v["commit"], now + 3, "00".repeat(32)))
    }
    @Test fun hashesTheSameUniqueOriginalEventIdsAsTypeScript() {
        assertEquals(history.digest, NodeMigrations.digest(listOf("02".repeat(32), "01".repeat(32), "01".repeat(32))))
        assertTrue(runCatching { NodeMigrations.digest(listOf("invalid")) }.isFailure)
    }
}
