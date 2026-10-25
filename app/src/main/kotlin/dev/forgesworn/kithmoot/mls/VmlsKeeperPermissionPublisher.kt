package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.*
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*

/** A separate finite Link protocol for exact keeper permission installation: account AUTH and one signed permission EVENT. */
class VmlsKeeperPermissionPublisher internal constructor(
    private val open: (String, String, RelaySocketListener, () -> Boolean) -> RelaySocket,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val budgets: VmlsPrivatePublishBudgets = VmlsPrivatePublishBudgets(),
    private val routeAllowed: (String, String) -> Boolean,
) {
    constructor(link: LinkTransportManager, now: () -> Long = { System.currentTimeMillis() / 1000 },
        budgets: VmlsPrivatePublishBudgets = VmlsPrivatePublishBudgets()) : this(link::openPrivateInboxGuarded, now, budgets, link::privateInboxRouteCurrent)

    fun routeCurrent(route: VmlsBoxRoute): Boolean = keeperOperationCurrent { routeAllowed(LinkRelayAddress.canonicalForNode(route.box), route.routeId) }

    suspend fun publish(route: VmlsBoxRoute, signer: ParticipantSigner, exactJson: String,
        operationCurrent: () -> Boolean): Boolean = coroutineScope {
        val event = KeeperInboxPermissionJson.read(exactJson)
        val url = LinkRelayAddress.canonicalForNode(route.box)
        require(signer.pubkey == route.persona && event.pubkey == route.persona && event.tagValue("server") == url)
        val expiration = event.tagValue("expiration")!!.toLong()
        val caller = currentCoroutineContext()
        val stopped = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val phase = AtomicInteger(0) // challenge, AUTH OK, EVENT OK, complete
        val frames = AtomicInteger()
        val chars = AtomicInteger()
        val opened = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val incoming = Channel<Incoming>(16)
        var floor = now()
        val clockLock = Any()
        fun live(): Boolean {
            if (stopped.get() || failed.get() || !caller.isActive || !keeperOperationCurrent(operationCurrent) || signer.pubkey != route.persona || !routeCurrent(route)) return false
            val at = try { now() } catch (_: Exception) { return false }
            return synchronized(clockLock) {
                if (at < floor || at !in 0..9_007_199_254_740_991L || at >= expiration || expiration - at > 30 * 86400L || event.createdAt > at + 300) false else { floor = at; true }
            }
        }
        fun checkLive() { caller.ensureActive(); check(live()) { "The private sender operation ended" } }
        var socket: RelaySocket? = null
        fun fail() { failed.set(true); ended.complete(Unit); socket?.close() }
        suspend fun <T> bounded(millis: Long, work: suspend () -> T): T? = coroutineScope {
            // Close on the phase deadline even when an external prompt needs non-cancellable cleanup.
            val deadline = launch { delay(millis); fail() }
            val pending = async { withTimeoutOrNull(millis) { work() } }
            try { select { pending.onAwait { it }; ended.onAwait { null } } }
            finally { pending.cancel(); deadline.cancel() }
        }
        suspend fun next(): Incoming? = select { incoming.onReceiveCatching { it.getOrNull() }; ended.onAwait { null } }
        var guard: Job? = null
        try {
            checkLive()
            socket = open(url, route.routeId, object : RelaySocketListener {
                override fun onOpen() { if (live()) opened.complete(Unit) else fail() }
                override fun onClosed(reason: String) { failed.set(true); ended.complete(Unit) }
                override fun onMessage(text: String) {
                    if (!live()) { fail(); return }
                    if (text.length > 4_096 || frames.incrementAndGet() > 32 || chars.addAndGet(text.length) > 32_768) { fail(); return }
                    val parsed = parse(text, phase.get())
                    if (parsed == null || !incoming.trySend(parsed).isSuccess) fail()
                }
            }, ::live)
            guard = launch { while (live()) delay(25); fail() }
            if (bounded(budgets.openMillis) { opened.await(); true } != true) return@coroutineScope false
            checkLive()
            val authenticated = bounded(budgets.authenticationMillis) {
                val challenge = next() as? Incoming.Challenge ?: return@bounded false
                checkLive()
                val tags = listOf(listOf("relay", url), listOf("challenge", challenge.value))
                val at = now()
                val auth = checkedSignedEvent(signer.sign(22242, at, tags, ""), signer.pubkey, 22242, at, tags, "")
                checkLive()
                phase.set(1)
                socket!!.send(RelayCodec.authFrame(auth))
                checkLive()
                val ack = next() as? Incoming.Ack ?: return@bounded false
                if (ack.phase != 1 || ack.id != auth.id || !ack.accepted) return@bounded false
                checkLive()
                true
            } == true
            if (!authenticated) return@coroutineScope false
            checkLive()
            val deposited = bounded(budgets.acknowledgementMillis) {
                checkLive()
                phase.set(2)
                socket!!.send("[\"EVENT\",$exactJson]")
                checkLive()
                val ack = next() as? Incoming.Ack ?: return@bounded false
                ack.phase == 2 && ack.id == event.id && ack.accepted
            } == true
            checkLive()
            if (!deposited) return@coroutineScope false
            phase.set(3)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { false }
        finally { stopped.set(true); ended.complete(Unit); guard?.cancel(); incoming.close(); socket?.close() }
    }

    private sealed interface Incoming {
        data class Challenge(val value: String) : Incoming
        data class Ack(val id: String, val accepted: Boolean, val phase: Int) : Incoming
    }
    private fun parse(text: String, phase: Int): Incoming? = try {
        val frame = Json.parseToJsonElement(text) as? JsonArray ?: return null
        fun string(i: Int) = (frame.getOrNull(i) as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (string(0)) {
            "AUTH" -> if (phase == 0 && frame.size == 2) string(1)?.takeIf { Regex("[0-9a-f]{64}").matches(it) }?.let(Incoming::Challenge) else null
            "OK" -> if (frame.size == 4 && string(3) != null) {
                val accepted = (frame[2] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                val id = string(1)?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
                if (id != null && accepted != null) Incoming.Ack(id, accepted, phase) else null
            } else null
            else -> null
        }
    } catch (_: Exception) { null }
}
