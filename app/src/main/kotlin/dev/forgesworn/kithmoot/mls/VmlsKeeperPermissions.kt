package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** UI operation custody binds the exact signer object, even if a replacement has the same public key. */
class KeeperWorkflowLease(private val persona: String, val actor: ParticipantSigner,
    private val latest: () -> ParticipantSigner?, private val foreground: () -> Boolean) {
    private val active = java.util.concurrent.atomic.AtomicBoolean(true)
    fun current(): Boolean = active.get() && foreground() && latest() === actor && actor.pubkey == persona
    fun withdraw() { active.set(false) }
}

/** Ordinary signed Event JSON on exchange; access still needs the named device's AUTH. */
object KeeperInboxPermissionJson {
    fun read(text: String): NostrEvent {
        require(text.length <= 8192 && text.toByteArray().size <= 8192) { "Permission JSON is too large." }
        val o = Json.parseToJsonElement(text).jsonObject
        require(o.keys == setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig")) { "Paste a signed Nostr permission event." }
        for (key in listOf("id", "pubkey", "content", "sig")) require(o.getValue(key).jsonPrimitive.isString)
        require(!o.getValue("kind").jsonPrimitive.isString && !o.getValue("created_at").jsonPrimitive.isString)
        val tags = o.getValue("tags").jsonArray
        require(tags.size == 6 && tags.all { t -> t.jsonArray.size == 2 && t.jsonArray.all { it.jsonPrimitive.isString && it.jsonPrimitive.content.length <= 128 } })
        val e = NostrEvent.fromJson(o)
        require(e.kind == 24242 && e.content.isEmpty() && e.createdAt in 0..9_007_199_254_740_991L)
        require(listOf(e.pubkey, e.id).all { Regex("[0-9a-f]{64}").matches(it) } && Regex("[0-9a-f]{128}").matches(e.sig))
        require(e.tags.map { it[0] }.toSet() == setOf("t", "server", "device", "grant", "expiration", "status"))
        require(e.tagValue("t") == "keeper-inbox-deposit" && e.tagValue("status") in setOf("active", "revoked"))
        require(Regex("[0-9a-f]{64}").matches(e.tagValue("device")!!) && Regex("[0-9a-f]{32}").matches(e.tagValue("grant")!!))
        require(Regex("[1-9][0-9]*").matches(e.tagValue("expiration")!!) && e.tagValue("expiration")!!.toLong() in 1..9_007_199_254_740_991L)
        require(LinkRelayAddress.canonical(e.tagValue("server")!!) != null)
        require(Events.verify(e)) { "Permission signature is invalid." }
        return e
    }
    fun preview(text: String, route: VmlsBoxRoute, keeper: String, transmitter: String, now: Long): NostrEvent = read(text).also {
        require(keeperInboxPermissionRefusal(route.box, keeper, it, now) == null) { "Permission is expired, revoked or for another keeper/node." }
        require(it.tagValue("device") == transmitter) { "Permission must name this phone's transmitting MLS device, not the lost device." }
    }
}

enum class KeeperPermissionStatus { PREPARED, INSTALL_UNKNOWN, INSTALLED, REVOKE_UNKNOWN, REVOKED }
data class KeeperPermissionRecord(val route: VmlsBoxRoute, val activeJson: String, val revokedJson: String,
    val status: KeeperPermissionStatus = KeeperPermissionStatus.PREPARED, val revision: Long = 0) {
    val active get() = KeeperInboxPermissionJson.read(activeJson)
    val revoked get() = KeeperInboxPermissionJson.read(revokedJson)
    val id get() = active.id
    val expiresAt get() = active.tagValue("expiration")!!.toLong()
    fun validate() {
        val a = active; val r = revoked
        require(a.pubkey == route.persona && r.pubkey == a.pubkey && a.tagValue("server") == LinkRelayAddress.canonicalForNode(route.box))
        require(a.tagValue("status") == "active" && r.tagValue("status") == "revoked" && r.createdAt == a.createdAt + 1)
        require(a.tags.filter { it[0] != "status" } == r.tags.filter { it[0] != "status" })
        require(expiresAt > r.createdAt && expiresAt - a.createdAt <= 30 * 86400L && revision in 0..9_007_199_254_740_991L)
    }
    fun json() = buildJsonObject {
        put("route", buildJsonObject { put("persona", route.persona); put("box", route.box); put("routeId", route.routeId); put("boxName", route.boxName) })
        put("active", activeJson); put("revoked", revokedJson); put("status", status.name); put("revision", revision)
    }
    companion object {
        fun decode(o: JsonObject): KeeperPermissionRecord {
            require(o.keys == setOf("route", "active", "revoked", "status", "revision"))
            val route = o.getValue("route").jsonObject
            require(route.keys == setOf("persona", "box", "routeId", "boxName"))
            fun s(k: String) = route.getValue(k).jsonPrimitive.content
            return KeeperPermissionRecord(VmlsBoxRoute(s("persona"), s("box"), s("routeId"), s("boxName")),
                o.getValue("active").jsonPrimitive.content, o.getValue("revoked").jsonPrimitive.content,
                KeeperPermissionStatus.valueOf(o.getValue("status").jsonPrimitive.content), o.getValue("revision").jsonPrimitive.long).also { it.validate() }
        }
        suspend fun prepare(route: VmlsBoxRoute, signer: ParticipantSigner, transmitter: String, lifetime: Long,
            now: () -> Long, current: () -> Boolean, random: SecureRandom): KeeperPermissionRecord {
            require(signer.pubkey == route.persona && Regex("[0-9a-f]{64}").matches(transmitter))
            require(lifetime in 60..30 * 86400L)
            val at = now()
            require(at in 0..(9_007_199_254_740_991L - lifetime) && at + 1 <= 9_007_199_254_740_991L) { "A trusted permission time is unavailable." }
            val expiry = at + lifetime
            require(expiry > at + 1)
            val grant = ByteArray(16).also(random::nextBytes).toHex()
            fun tags(status: String) = listOf(listOf("t", "keeper-inbox-deposit"), listOf("server", LinkRelayAddress.canonicalForNode(route.box)),
                listOf("device", transmitter), listOf("grant", grant), listOf("expiration", expiry.toString()), listOf("status", status))
            var floor = at
            fun currentTime(): Boolean { val observed = now(); return current() && observed >= floor && observed < expiry && (observed.also { floor = it }) >= 0 }
            check(currentTime()); currentCoroutineContext().ensureActive()
            val a = checkedSignedEvent(signer.sign(24242, at, tags("active"), ""), signer.pubkey, 24242, at, tags("active"), "")
            currentCoroutineContext().ensureActive(); check(currentTime())
            val r = checkedSignedEvent(signer.sign(24242, at + 1, tags("revoked"), ""), signer.pubkey, 24242, at + 1, tags("revoked"), "")
            currentCoroutineContext().ensureActive(); check(currentTime())
            require(keeperInboxPermissionRefusal(route.box, route.persona, a, now()) == null)
            return KeeperPermissionRecord(route, a.toCompactJson(), r.toCompactJson()).also { it.validate() }
        }
    }
}
