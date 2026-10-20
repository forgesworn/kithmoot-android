package dev.forgesworn.kithmoot.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Somebody this account blocked on this phone, and when (milliseconds). */
data class DeviceBlock(val participant: String, val blockedAt: Long)

/** A generous cap, refused rather than evicted: dropping the oldest block
 *  would quietly let somebody back in. The web client's `MAX_BLOCKS`. */
const val MAX_DEVICE_BLOCKS = 2000

private val HEX64 = Regex("^[0-9a-f]{64}$")

/** The stored list, newest first. Anything unreadable is left out rather
 *  than guessed at; a block holds nobody's secret. */
internal fun parseDeviceBlocks(text: String?, account: String): List<DeviceBlock> {
    if (text.isNullOrBlank()) return emptyList()
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
    if (root["account"]?.jsonPrimitive?.content != account) return emptyList()
    val entries = runCatching { root["blocks"]!!.jsonArray }.getOrNull() ?: return emptyList()
    return entries.mapNotNull { entry ->
        val item = entry as? JsonObject ?: return@mapNotNull null
        val p = runCatching { item["p"]!!.jsonPrimitive.content }.getOrNull() ?: return@mapNotNull null
        val at = runCatching { item["at"]!!.jsonPrimitive.longOrNull }.getOrNull() ?: return@mapNotNull null
        if (!HEX64.matches(p) || p == account) null else DeviceBlock(p, at)
    }.distinctBy { it.participant }.sortedByDescending { it.blockedAt }
}

internal fun encodeDeviceBlocks(account: String, blocks: List<DeviceBlock>): String = buildJsonObject {
    put("v", 1)
    put("account", account)
    put("blocks", JsonArray(blocks.map { block -> buildJsonObject { put("p", block.participant); put("at", block.blockedAt) } }))
}.toString()

/**
 * Who this account has blocked on this phone (owner decision, 20 October
 * 2026). Kept in the account's own encrypted vault and never published: not
 * to the Nostr mute list, which anybody can read, and not to the person
 * blocked, who is never told. A block refuses what a Signet block refuses:
 * their private invitations, their requests to join a room, and starting a
 * private chat with them. A private chat already open stays until it is left.
 */
class DeviceBlocks(private val account: String, private val storage: ProjectStorage,
    private val now: () -> Long = { System.currentTimeMillis() }) {
    private val gate = Mutex()
    private var opened = false
    private val mutable = MutableStateFlow<List<DeviceBlock>>(emptyList())
    /** Newest first. */
    val state: StateFlow<List<DeviceBlock>> = mutable.asStateFlow()
    @Volatile private var blocked = emptySet<String>()

    init { require(HEX64.matches(account)) { "An account key is 64 hex characters" } }

    /** Read on every invitation and request to join, so a set, not the list. */
    fun contains(peer: String): Boolean = peer in blocked

    suspend fun open() = gate.withLock {
        if (opened) return@withLock
        storage.acquire(); opened = true
        publish(parseDeviceBlocks(storage.load(), account))
    }

    suspend fun block(peer: String) = gate.withLock {
        check(opened) { "Blocks are not open" }
        require(HEX64.matches(peer)) { "A participant key is 64 hex characters" }
        require(peer != account) { "You cannot block yourself." }
        val rest = mutable.value.filter { it.participant != peer }
        check(rest.size < MAX_DEVICE_BLOCKS) { "This phone already holds as many blocks as it can. Unblock somebody first." }
        save(listOf(DeviceBlock(peer, now())) + rest)
    }

    suspend fun unblock(peer: String) = gate.withLock {
        check(opened) { "Blocks are not open" }
        save(mutable.value.filter { it.participant != peer })
    }

    suspend fun close() = gate.withLock {
        if (opened) storage.release()
        opened = false
        publish(emptyList())
    }

    private suspend fun save(next: List<DeviceBlock>) {
        storage.save(encodeDeviceBlocks(account, next))
        publish(next)
    }

    private fun publish(next: List<DeviceBlock>) {
        blocked = next.map { it.participant }.toSet()
        mutable.value = next
    }
}
