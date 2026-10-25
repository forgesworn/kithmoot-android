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

/** The saved route and keeper permission selected for one explicit private send. */
data class VmlsPrivateInboxTarget(val route: VmlsBoxRoute, val keeper: String, val permission: NostrEvent)

/** Public metadata frozen in the protected outbox before sealing or opening a socket. */
data class VmlsPrivateRequestDestination(
    val routeId: String,
    val eventUrl: String,
    val node: String,
    val keeper: String,
    val permissionId: String,
    val transmittingDevice: String,
) {
    init {
        require(Regex("[A-Za-z0-9._:-]{1,128}").matches(routeId))
        require(listOf(node, keeper, permissionId, transmittingDevice).all { Regex("[0-9a-f]{64}").matches(it) })
        require(eventUrl == LinkRelayAddress.canonicalForNode(node))
    }
    internal fun json() = buildJsonObject {
        put("routeId", routeId); put("eventUrl", eventUrl); put("node", node); put("keeper", keeper)
        put("permissionId", permissionId); put("transmittingDevice", transmittingDevice)
    }
    internal companion object {
        fun decode(o: JsonObject): VmlsPrivateRequestDestination {
            require(o.keys == setOf("routeId", "eventUrl", "node", "keeper", "permissionId", "transmittingDevice"))
            fun text(k: String) = o.getValue(k).jsonPrimitive.also { require(it.isString) }.content
            return VmlsPrivateRequestDestination(text("routeId"), text("eventUrl"), text("node"), text("keeper"), text("permissionId"), text("transmittingDevice"))
        }
    }
}

/** Downward-injectable ceilings; every phase gets its own fresh allowance. */
data class VmlsPrivatePublishBudgets(val openMillis: Long = 30_000, val authenticationMillis: Long = 75_000, val acknowledgementMillis: Long = 5_000) {
    init { require(openMillis in 1..30_000 && authenticationMillis in 1..75_000 && acknowledgementMillis in 1..5_000) }
}

/** Only this publisher's receipt identity can confirm its exact private EVENT. */
class VmlsPrivatePublishReceipt internal constructor(val eventId: String)

/** One finite, pinned Link connection. Device AUTH is separate from the keeper identity reader. */
class VmlsPrivateInboxPublisher internal constructor(
    private val open: (String, String, RelaySocketListener, () -> Boolean) -> RelaySocket,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val budgets: VmlsPrivatePublishBudgets = VmlsPrivatePublishBudgets(),
    private val routeAllowed: (String, String) -> Boolean,
) {
    constructor(link: LinkTransportManager, now: () -> Long = { System.currentTimeMillis() / 1000 },
        budgets: VmlsPrivatePublishBudgets = VmlsPrivatePublishBudgets()) : this(link::openPrivateInboxGuarded, now, budgets, link::privateInboxRouteCurrent)

    /** The real adapter checks the saved node pin; channel guards use it before crypto and confirmation too. */
    internal fun routeCurrent(destination: VmlsPrivateRequestDestination): Boolean =
        keeperOperationCurrent { routeAllowed(destination.eventUrl, destination.routeId) }

    private class ReceiptOrigin(val destination: VmlsPrivateRequestDestination, val eventId: String)
    private val made: MutableMap<VmlsPrivatePublishReceipt, ReceiptOrigin> = Collections.synchronizedMap(WeakHashMap())
    internal fun accepts(destination: VmlsPrivateRequestDestination, event: NostrEvent, receipt: VmlsPrivatePublishReceipt): Boolean =
        made[receipt]?.let { it.destination == destination && it.eventId == event.id && receipt.eventId == event.id } == true

    suspend fun publish(ctx: VaultContext, vault: MlsVault, destination: VmlsPrivateRequestDestination,
        permission: NostrEvent, prompt: KeeperInboxConsentPrompt, event: NostrEvent, operationCurrent: () -> Boolean): VmlsPrivatePublishReceipt? = coroutineScope {
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
            if (stopped.get() || failed.get() || !caller.isActive || !keeperOperationCurrent(operationCurrent) || !vault.isCurrent(ctx) || !routeCurrent(destination)) return false
            val at = try { now() } catch (_: Exception) { return false }
            return synchronized(clockLock) {
                if (at < floor || at !in 0..9_007_199_254_740_991L) false else { floor = at; true }
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
            val p = snapshotKeeperInboxPermission(permission) ?: return@coroutineScope null
            if (keeperInboxPermissionRefusal(destination.node, destination.keeper, p, now()) != null ||
                p.id != destination.permissionId || p.tagValue("device") != destination.transmittingDevice ||
                event.kind != 1059 || event.tags != listOf(listOf("p", destination.keeper)) || event.content.length > 40_000 || event.content.toByteArray().size > 40_000 || !Events.verify(event)) return@coroutineScope null
            socket = open(destination.eventUrl, destination.routeId, object : RelaySocketListener {
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
            if (bounded(budgets.openMillis) { opened.await(); true } != true) return@coroutineScope null
            checkLive()
            val authenticated = bounded(budgets.authenticationMillis) {
                val challenge = next() as? Incoming.Challenge ?: return@bounded false
                checkLive()
                val request = KeeperInboxAuthRequest(destination.node, destination.keeper, p, challenge.value, now() + 120)
                val signed = vault.signKeeperInboxAuthV1(ctx, request, prompt, ::live) as? VaultResult.Ok ?: return@bounded false
                checkLive()
                val accepted = vault.acceptKeeperInboxAuthReply(request, signed.value) as? VaultResult.Ok ?: return@bounded false
                checkLive()
                if (accepted.value.device != destination.transmittingDevice) return@bounded false
                phase.set(1)
                socket!!.send(RelayCodec.authFrame(accepted.value.event))
                checkLive()
                val ack = next() as? Incoming.Ack ?: return@bounded false
                if (ack.phase != 1 || ack.id != accepted.value.event.id || !ack.accepted) return@bounded false
                checkLive()
                if (vault.acceptKeeperInboxAuthReply(request, accepted.value) !is VaultResult.Ok) return@bounded false
                checkLive()
                true
            } == true
            if (!authenticated) return@coroutineScope null
            checkLive()
            val deposited = bounded(budgets.acknowledgementMillis) {
                checkLive()
                phase.set(2)
                socket!!.send(RelayCodec.publishFrame(event))
                checkLive()
                val ack = next() as? Incoming.Ack ?: return@bounded false
                ack.phase == 2 && ack.id == event.id && ack.accepted
            } == true
            checkLive()
            if (!deposited) return@coroutineScope null
            phase.set(3)
            VmlsPrivatePublishReceipt(event.id).also { made[it] = ReceiptOrigin(destination, event.id) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
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
