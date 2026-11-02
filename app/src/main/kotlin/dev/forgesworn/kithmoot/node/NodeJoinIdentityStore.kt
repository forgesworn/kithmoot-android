package dev.forgesworn.kithmoot.node

import android.content.Context
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Keeps a stable asking identity before admission, without a room secret.
 * A failed signer, cancellation, lost answer or restart reuses the same device.
 * Missing/corrupt existing ciphertext never silently creates another identity. */
internal class NodeJoinIdentityStore(private val storage: RoomStorage) {
    companion object {
        private val monitor = Any()
        private val signing = Mutex()
        fun android(context: Context) = NodeJoinIdentityStore(EncryptedRoomStorage(context, "kithmoot.node-ask-identities.v1", 1024 * 1024,
            "KithMoot node ask identities v1".toByteArray()))
    }
    private fun entries(): JsonObject = storage.read()?.let { bytes -> try {
        require(bytes.size <= 1024 * 1024)
        val raw = NodeEvidence.utf8(bytes)
        Json.parseToJsonElement(raw).jsonObject.also { require(it.size <= 100 && it.toString() == raw) }
    } finally { bytes.fill(0) } } ?: buildJsonObject {}
    private fun write(values: Map<String, JsonElement>) {
        require(values.size <= 100)
        val bytes = JsonObject(values).toString().toByteArray(Charsets.UTF_8)
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }
    private fun checked(raw: JsonElement, node: NodeRoomRecord, account: String?): JsonObject {
        val row = raw.jsonObject
        require(row.keys.containsAll(setOf("v", "node", "participant", "deviceKey")) && row.keys.all { it in setOf("v", "node", "participant", "deviceKey", "participantKey", "credential") }
            && NodeEvidence.number(row, "v") == 1L)
        val event = NostrEvent.fromJson(row.getValue("node"))
        require(requireNotNull(NodeRooms.read(row["node"], event.createdAt)).event.id == node.event.id)
        val participant = NodeEvidence.string(row, "participant"); require(NodeEvidence.key.matches(participant))
        val key = NodeEvidence.string(row, "deviceKey").also { require(NodeEvidence.key.matches(it)) }.hexToBytes()
        try { require(Schnorr.publicKeyHex(key) !in listOf(node.authority, node.serviceKey)) } finally { key.fill(0) }
        if (account == null) {
            val person = NodeEvidence.string(row, "participantKey").also { require(NodeEvidence.key.matches(it)) }.hexToBytes()
            try { require(Schnorr.publicKeyHex(person) == participant) } finally { person.fill(0) }
        } else require(participant == account && row["participantKey"] == null)
        row["credential"]?.let { rawCredential ->
            val credential = NostrEvent.fromJson(rawCredential)
            val proof = verifyDeviceCredential(credential, node.room, credential.createdAt)
            val deviceKey = NodeEvidence.string(row, "deviceKey").hexToBytes()
            try { require(proof is CredentialCheck.Valid && proof.participant == participant && proof.device == Schnorr.publicKeyHex(deviceKey)) }
            finally { deviceKey.fill(0) }
        }
        return row
    }
    suspend fun identity(record: NodeRoomRecord, signer: ParticipantSigner? = null,
        now: () -> Long = { System.currentTimeMillis() / 1000 }, allowed: () -> Boolean = { true }): PrimaryIdentity = signing.withLock {
        check(allowed())
        val node = requireNotNull(NodeRooms.read(record.wire, now(), record.authority))
        val account = signer?.pubkey
        require(account == null || NodeEvidence.key.matches(account))
        val id = "${account ?: "local"}:${node.room}"
        val row = synchronized(monitor) {
            val values = entries()
            values[id]?.let { checked(it, node, account) } ?: run {
                val device = Entropy.bytes(32); val person = if (account == null) Entropy.bytes(32) else null
                try {
                    val value = buildJsonObject {
                        put("v", 1); put("node", node.wire); put("participant", account ?: Schnorr.publicKeyHex(requireNotNull(person)))
                        put("deviceKey", device.toHex()); person?.let { put("participantKey", it.toHex()) }
                    }
                    checked(value, node, account)
                    write(values + (id to value)); value
                } finally { device.fill(0); person?.fill(0) }
            }
        }
        val key = NodeEvidence.string(row, "deviceKey").hexToBytes()
        val local = if (account == null) NodeEvidence.string(row, "participantKey").hexToBytes() else null
        var released = false
        try {
            val actor = signer ?: LocalSigner(requireNotNull(local))
            val kept = row["credential"]?.let(NostrEvent::fromJson)
            val minimum = minOf(43200L, node.endsAt?.let { maxOf(1, (it - now()) / 2) } ?: 43200L)
            val usable = kept?.takeIf { verifyDeviceCredential(it, node.room, now()) is CredentialCheck.Valid &&
                (it.tags.single { tag -> tag[0] == "expiration" }[1].toLong() > now() + minimum) }
            val identity = if (usable != null) PrimaryIdentity(actor, key, usable) else
                PrimaryIdentity.createWith(actor, node.room, minOf(now() + 86400, node.endsAt ?: Long.MAX_VALUE), now(), key)
            check(allowed()); require(identity.participant == NodeEvidence.string(row, "participant"))
            synchronized(monitor) {
                val values = entries(); val current = checked(requireNotNull(values[id]), node, account)
                require(current["deviceKey"] == row["deviceKey"] && current["participantKey"] == row["participantKey"])
                write(values + (id to JsonObject(current + ("credential" to identity.credential.toJson()))))
            }
            released = true; identity
        } finally { if (!released) { key.fill(0); local?.fill(0) } }
    }
    fun forgetRoom(room: String) = synchronized(monitor) {
        require(NodeEvidence.key.matches(room))
        write(entries().filterValues { value ->
            val row = value.jsonObject; val event = NostrEvent.fromJson(row.getValue("node"))
            requireNotNull(NodeRooms.read(row["node"], event.createdAt)).room != room
        })
    }
}
