package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NodeDoor { ROOM, ASK }

/** A capability for precisely one signed node and one device. The underlying
 * pool cannot escape through addRelays, identity replacement or a public route. */
internal class NodeRoomTransport private constructor(
    val node: NodeRoomRecord,
    val device: String,
    private val door: NodeDoor,
    private val pool: RelayPool,
    private val live: AtomicBoolean,
    private val current: () -> Boolean,
    private val now: () -> Long,
    scope: CoroutineScope,
) : RoomTransport by pool {
    @Volatile private var closed = false
    private var watcher: Job? = null
    init { watcher = scope.launch {
        while (isActive && !closed) { if (!current()) { close(); break }; delay(1000) }
    } }
    val health get() = pool.health
    val connected get() = pool.connected
    fun start() { checkCurrent(); pool.start() }
    fun close() { closed = true; live.set(false); watcher?.cancel(); pool.stop() }
    fun boundTo(record: NodeRoomRecord, pubkey: String): Boolean = !closed && door == NodeDoor.ROOM && current() && node.event.id == record.event.id && device == pubkey
    private fun checkCurrent() { check(!closed && current()) { "Node room permission is unavailable" } }
    private fun checkEvent(event: NostrEvent) { checkCurrent(); require(accepts(event, node, door, now())) { "Node room publication refused" } }
    override fun publish(event: NostrEvent) { checkEvent(event); pool.publish(event) }
    override fun publishRecovery(event: NostrEvent) { checkEvent(event); pool.publishRecovery(event) }
    override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean { checkEvent(event); return pool.publishConfirmedGuarded(event, pool.publicationGeneration(), { !closed && current() && accepts(event, node, door, now()) }, timeoutMs) }
    override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long, stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
        checkEvent(event); return pool.publishConfirmedGuarded(event, generation, { !closed && current() && accepts(event, node, door, now()) && stillAllowed() }, timeoutMs)
    }
    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> { checkCurrent(); return pool.subscribe(filters).filter { !closed && current() } }
    override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> {
        checkCurrent(); return pool.subscribeReplayed(filters) { if (!closed && current()) onReplayComplete() }.filter { !closed && current() }
    }
    override fun subscribeCatchingUp(filters: () -> List<Filter>, skewSeconds: Long): Flow<NostrEvent> {
        checkCurrent(); return pool.subscribeCatchingUp(filters, skewSeconds).filter { !closed && current() }
    }
    override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
        checkCurrent(); val result = pool.queryStored(filters, timeoutMs); checkCurrent(); return result
    }
    override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
        checkCurrent(); val result = pool.queryAvailable(filters, timeoutMs); checkCurrent(); return result
    }
    companion object {
        fun create(record: NodeRoomRecord, signer: ParticipantSigner, door: NodeDoor, sockets: RelaySocketFactory, scope: CoroutineScope,
            now: () -> Long = { System.currentTimeMillis() / 1000 }, allowed: () -> Boolean = { true }): NodeRoomTransport {
            val node = requireNotNull(NodeRooms.read(record.wire, now(), record.authority))
            require(signer.pubkey != node.serviceKey && (door != NodeDoor.ROOM || signer.pubkey != node.authority))
            val live = AtomicBoolean(true)
            fun current() = live.get() && allowed() && (node.endsAt?.let { now() < it } ?: true)
            check(current())
            val authenticator = object : RelayAuthenticator {
                override val pubkey = signer.pubkey
                override fun authenticationTags(url: String, challenge: String) = listOf(listOf("relay", url), listOf("challenge", challenge), listOf("room", node.room), listOf("mode", door.name.lowercase()))
                override suspend fun sign(url: String, challenge: String): NostrEvent {
                    check(current()); require(url == node.relay && challenge.isNotEmpty() && challenge.length <= 512)
                    val tags = authenticationTags(url, challenge)
                    val event = signer.sign(22242, now(), tags.map { it.toList() }, "")
                    check(current()); require(event.pubkey == pubkey && event.kind == 22242 && event.tags == tags && event.content.isEmpty() && event.createdAt in now() - 60..now() + 30 && Events.verify(event))
                    return event
                }
            }
            val pool = RelayPool(listOf(node.relay), sockets, scope, now = { now() * 1000 },
                authenticators = RelayAuthenticatorProvider { url -> authenticator.takeIf { url == node.relay && current() } },
                sendGuard = { frame -> current() && runCatching {
                    val values = Json.parseToJsonElement(frame).jsonArray
                    values.first().jsonPrimitive.content != "EVENT" || accepts(NostrEvent.fromJson(values[1]), node, door, now())
                }.getOrDefault(false) })
            return NodeRoomTransport(node, signer.pubkey, door, pool, live, ::current, now, scope)
        }
        private fun accepts(event: NostrEvent, node: NodeRoomRecord, door: NodeDoor, now: Long): Boolean {
            if (event.kind in listOf(27541, 27542, 27543, 27544) || door == NodeDoor.ASK && event.kind != 1059 || !Events.verify(event)) return false
            val tags = event.tags.filter { it.firstOrNull() == "expiration" }
            if (tags.size != 1 || tags[0].size != 2 || !Regex("^[1-9][0-9]*$").matches(tags[0][1])) return false
            val deadline = tags[0][1].toLongOrNull() ?: return false
            return deadline > now && deadline <= minOf(now + NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE)
        }
    }
}
