package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class LinkConsentState { PENDING, ACTIVATING, ACTIVE, REVOKING, WITHDRAWING, RETIRED }

/** Both signed halves are retained so an interrupted install can always be withdrawn safely. */
data class CircleGrantPlan(val active: NostrEvent, val revoked: NostrEvent) {
    init {
        require(valid(active, CircleGrantStatus.ACTIVE) && valid(revoked, CircleGrantStatus.REVOKED))
        require(revoked.createdAt > active.createdAt)
        require(active.pubkey == revoked.pubkey)
        require(active.tags.dropLast(1) == revoked.tags.dropLast(1))
    }

    private fun valid(event: NostrEvent, status: CircleGrantStatus): Boolean =
        event.kind == KIND_CIRCLE_EVENT_GRANT && event.content.isEmpty() && Events.verify(event) &&
            event.tags.lastOrNull() == listOf("status", status.wire)
}

/** This vault holds permission only. Link cards and route secrets stay in LinkTransportVault. */
data class LinkConsent(
    val accountPubkey: String,
    val roomId: String,
    val bothyNodeId: String,
    val routeId: String,
    val canonicalUrl: String,
    val previousRelays: List<String>,
    val state: LinkConsentState,
    val grants: List<CircleGrantPlan> = emptyList(),
    val grantsRevoked: Boolean = false,
) {
    init {
        require(HEX64.matches(accountPubkey) && HEX64.matches(roomId) && HEX64.matches(bothyNodeId))
        require(routeId.matches(ROUTE_ID))
        require(LinkRelayAddress.canonical(canonicalUrl) != null)
        require(previousRelays.size in 1..16 && previousRelays.all { it.startsWith("ws://") || it.startsWith("wss://") })
        require(grants.size <= 16)
        require(!grantsRevoked || grants.isNotEmpty())
    }
    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        val ROUTE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

class LinkConsentVault(private val storage: RoomStorage) {
    @Synchronized fun all(): List<LinkConsent> = read()
    @Synchronized fun put(consent: LinkConsent) = write(read().filterNot { same(it, consent) } + consent)
    @Synchronized fun remove(accountPubkey: String, roomId: String, bothyNodeId: String) =
        write(read().filterNot { it.accountPubkey == accountPubkey && it.roomId == roomId && it.bothyNodeId == bothyNodeId })
    @Synchronized fun reset() = guarded { storage.reset() }

    /** The only lookup the hybrid factory needs. Grant revocation keeps Alice's route live. */
    @Synchronized fun activeRoute(accountPubkey: String, roomId: String, canonicalUrl: String): String? = read()
        .singleOrNull { it.accountPubkey == accountPubkey && it.roomId == roomId && it.canonicalUrl == canonicalUrl && it.state in setOf(LinkConsentState.ACTIVE, LinkConsentState.REVOKING) }
        ?.routeId

    private fun read(): List<LinkConsent> = guarded {
        val bytes = storage.read() ?: return@guarded emptyList()
        try {
            require(bytes.size <= 128 * 1024)
            val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            require(root.getValue("version").jsonPrimitive.content == "1")
            val entries = root.getValue("consents").jsonArray.map { value ->
                val o = value.jsonObject
                LinkConsent(
                    o.getValue("account").jsonPrimitive.content,
                    o.getValue("room").jsonPrimitive.content,
                    o.getValue("bothy").jsonPrimitive.content,
                    o.getValue("route").jsonPrimitive.content,
                    o.getValue("url").jsonPrimitive.content,
                    o.getValue("previousRelays").jsonArray.map { it.jsonPrimitive.content },
                    LinkConsentState.valueOf(o.getValue("state").jsonPrimitive.content),
                    o["grants"]?.jsonArray?.map { grant ->
                        val pair = grant.jsonObject
                        CircleGrantPlan(
                            NostrEvent.fromJson(pair.getValue("active")),
                            NostrEvent.fromJson(pair.getValue("revoked")),
                        )
                    }.orEmpty(),
                    o["grantsRevoked"]?.jsonPrimitive?.boolean ?: false,
                )
            }
            require(entries.size <= 100 && entries.distinctBy(::key).size == entries.size)
            entries
        } finally { bytes.fill(0) }
    }

    private fun write(consents: List<LinkConsent>) = guarded {
        require(consents.size <= 100)
        val bytes = buildJsonObject {
            put("version", 1)
            put("consents", buildJsonArray { consents.forEach { c -> add(buildJsonObject {
                put("account", c.accountPubkey); put("room", c.roomId); put("bothy", c.bothyNodeId)
                put("route", c.routeId); put("url", c.canonicalUrl); put("state", c.state.name)
                put("previousRelays", buildJsonArray { c.previousRelays.forEach { add(JsonPrimitive(it)) } })
                if (c.grants.isNotEmpty()) put("grants", buildJsonArray { c.grants.forEach { grant -> add(buildJsonObject {
                    put("active", grant.active.toJson()); put("revoked", grant.revoked.toJson())
                }) } })
                if (c.grantsRevoked) put("grantsRevoked", true)
            }) } })
        }.toString().encodeToByteArray()
        try { storage.write(bytes) } finally { bytes.fill(0) }
    }

    private fun same(a: LinkConsent, b: LinkConsent) = key(a) == key(b)
    private fun key(c: LinkConsent) = "${c.accountPubkey}/${c.roomId}/${c.bothyNodeId}"
    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is RoomStorageException) throw e
        throw RoomStorageException(e)
    }
}
