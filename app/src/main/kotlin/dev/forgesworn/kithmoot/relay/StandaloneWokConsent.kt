package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.canonicalStandaloneRoomServer
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class StandaloneWokState { INSTALLING, ACTIVE, WITHDRAWING }

data class StandaloneWokConsent(
    val accountPubkey: String,
    val roomId: String,
    val canonicalUrl: String,
    val previousRelays: List<String>,
    val state: StandaloneWokState,
    val grants: List<CircleGrantPlan> = emptyList(),
) {
    init {
        require(HEX64.matches(accountPubkey) && HEX64.matches(roomId))
        require(canonicalStandaloneRoomServer(canonicalUrl))
        require(previousRelays.size in 1..16 && previousRelays.all { it.startsWith("ws://") || it.startsWith("wss://") })
        require(grants.size <= 16)
    }
    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
    }
}

class StandaloneWokConsentVault(private val storage: RoomStorage) {
    @Synchronized fun all(): List<StandaloneWokConsent> = read()
    @Synchronized fun put(consent: StandaloneWokConsent) = write(read().filterNot { same(it, consent) } + consent)
    @Synchronized fun remove(accountPubkey: String, roomId: String) =
        write(read().filterNot { it.accountPubkey == accountPubkey && it.roomId == roomId })
    @Synchronized fun reset() = guarded { storage.reset() }

    @Synchronized fun activeRoute(accountPubkey: String, roomId: String, canonicalUrl: String): String? = read()
        .singleOrNull {
            it.accountPubkey == accountPubkey && it.roomId == roomId && it.canonicalUrl == canonicalUrl &&
                it.state in setOf(StandaloneWokState.ACTIVE, StandaloneWokState.WITHDRAWING)
        }?.canonicalUrl

    private fun read(): List<StandaloneWokConsent> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        try {
            require(bytes.size <= 128 * 1024)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root.getValue("version").jsonPrimitive.content == "1")
            val entries = root.getValue("consents").jsonArray.map { value ->
                val o = value.jsonObject
                StandaloneWokConsent(
                    o.getValue("account").jsonPrimitive.content,
                    o.getValue("room").jsonPrimitive.content,
                    o.getValue("url").jsonPrimitive.content,
                    o.getValue("previousRelays").jsonArray.map { it.jsonPrimitive.content },
                    StandaloneWokState.valueOf(o.getValue("state").jsonPrimitive.content),
                    o["grants"]?.jsonArray?.map { grant ->
                        val pair = grant.jsonObject
                        CircleGrantPlan(
                            NostrEvent.fromJson(pair.getValue("active")),
                            NostrEvent.fromJson(pair.getValue("revoked")),
                        )
                    }.orEmpty(),
                )
            }
            require(entries.size <= 100 && entries.distinctBy(::key).size == entries.size)
            entries
        } finally { bytes.fill(0) }
    }

    private fun write(consents: List<StandaloneWokConsent>) = guarded {
        require(consents.size <= 100)
        val bytes = buildJsonObject {
            put("version", 1)
            put("consents", buildJsonArray { consents.forEach { consent -> add(buildJsonObject {
                put("account", consent.accountPubkey)
                put("room", consent.roomId)
                put("url", consent.canonicalUrl)
                put("previousRelays", buildJsonArray { consent.previousRelays.forEach { add(JsonPrimitive(it)) } })
                put("state", consent.state.name)
                if (consent.grants.isNotEmpty()) put("grants", buildJsonArray { consent.grants.forEach { grant -> add(buildJsonObject {
                    put("active", grant.active.toJson())
                    put("revoked", grant.revoked.toJson())
                }) } })
            }) } })
        }.toString().encodeToByteArray()
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }

    private fun same(a: StandaloneWokConsent, b: StandaloneWokConsent) = key(a) == key(b)
    private fun key(consent: StandaloneWokConsent) = "${consent.accountPubkey}/${consent.roomId}"
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }
}
