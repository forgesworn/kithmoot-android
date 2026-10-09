package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl
import dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException
import dev.forgesworn.kithmoot.relay.RoomMeshTransport
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outgoing signed notices for an exclusive native authority owner. Inert until
 * explicitly started; not attached to the ViewModel before keeper transaction
 * qualification. Does not host admission, sign, follow incoming control or own
 * the routes. The authority journal must commit before calling admit(). */
internal class RoomRekeyCourier private constructor(private val ledger: RoomRekeyLedger,
    private val nearby: RoomMeshTransport?, private val internet: RoomTransport?,
    parentScope: CoroutineScope, private val stillSelected: () -> Boolean,
    dispatcher: CoroutineDispatcher, private val authority: NativeKeeperEndpoints? = null) : AutoCloseable {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob + dispatcher)
    private val pumpGate = Mutex()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var closed = false
    @Volatile private var failed = false
    private fun selected() = !closed && !failed && ownerJob.isActive &&
        runCatching { stillSelected() }.getOrDefault(false)

    init {
        try { ledger.bind(::selected) }
        catch (error: Exception) { ownerJob.cancel(); wakeups.close(); throw error }
        scope.launch {
            try { while (isActive) { pump(); withTimeoutOrNull(5_000) { wakeups.receive() } } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail() }
        }
        ownerJob.invokeOnCompletion { close() }
    }

    /** Returns only local durable admission, never delivery or relay acceptance. */
    fun admit(event: NostrEvent) {
        check(selected()) { "The keeper courier is suspended" }
        try { ledger.admit(event) }
        catch (error: Exception) {
            if (ledger.persistenceFailed()) fail()
            throw error
        }
        wakeups.trySend(Unit)
    }

    /** Intercept outgoing root rekeys only. Confirmed publication APIs cannot
     * bypass durable authority admission or turn local storage into a receipt. */
    fun transport(delegate: RoomTransport): RoomTransport = object : RoomTransport by delegate {
        override fun publish(event: NostrEvent) {
            if (event.kind == KIND_ROOM_REKEY) admit(event) else delegate.publish(event)
        }
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
            require(event.kind != KIND_ROOM_REKEY) { "Keeper rekeys require durable authority admission" }
            return delegate.publishConfirmed(event, timeoutMs)
        }
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
            stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
            require(event.kind != KIND_ROOM_REKEY) { "Keeper rekeys require durable authority admission" }
            return delegate.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
        }
        override fun publishRecovery(event: NostrEvent) {
            require(event.kind != KIND_ROOM_REKEY) { "Keeper rekeys are not incoming epoch recovery traffic" }
            delegate.publishRecovery(event)
        }
    }

    suspend fun pump() = pumpGate.withLock {
        if (!selected()) return@withLock
        for (row in ledger.status().entries) for (lane in RekeyLane.entries) {
            currentCoroutineContext().ensureActive()
            if (!selected()) return@withLock
            if (!ledger.binding.permits(lane)) continue
            val transport: RoomTransport? = if (lane == RekeyLane.NEARBY) nearby else internet
            if (authority?.ready(lane) != true && (authority != null || transport?.reachable() != true)) continue
            val generation = authority?.generation(lane) ?: requireNotNull(transport).publicationGeneration()
            val reservation = ledger.reserve(row.event.id, lane) ?: continue
            try {
                val guard = { selected() && ledger.canHandoff(reservation) }
                val accepted = if (authority != null) authority.offer(reservation.event, lane, generation, guard, 5_000)
                    else requireNotNull(transport).publishConfirmedGuarded(reservation.event, generation, guard, 5_000)
                if (accepted && !closed) {
                    if (lane == RekeyLane.INTERNET) ledger.relayAccepted(reservation)
                    else ledger.offered(reservation)
                }
            } catch (_: PublicationUnconfirmedException) {
                if (lane == RekeyLane.NEARBY && !closed) ledger.offered(reservation)
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (ledger.persistenceFailed()) { fail(); return@withLock } }
        }
    }
    fun isFailed() = failed || ledger.persistenceFailed()
    private fun fail() { failed = true; close() }
    override fun close() {
        if (closed) return
        closed = true; ledger.suspendExports(); ownerJob.cancel(); wakeups.close(); ledger.close()
    }

    companion object {
        /** Controller-owned original notices may finish while chat is held,
         * including closure. The selected owner's lifetime still gates every offer. */
        fun startAuthority(ledger: RoomRekeyLedger, endpoints: NativeKeeperEndpoints,
            scope: CoroutineScope, stillSelected: () -> Boolean,
            dispatcher: CoroutineDispatcher = Dispatchers.IO): RoomRekeyCourier {
            require(ledger.binding.pin == endpoints.binding.pin)
            require(scope.isActive && stillSelected())
            return RoomRekeyCourier(ledger, endpoints.nearby, endpoints.internet, scope, stillSelected, dispatcher, endpoints)
        }

        /** Caller must own the qualified live keeper transaction and these
         * exact foreground routes. The cheap guard must invalidate before their
         * teardown; no session/authority lock may be acquired at dispatch. */
        fun start(ledger: RoomRekeyLedger, nearby: RoomMeshTransport, internet: RoomTransport,
            scope: CoroutineScope, stillSelected: () -> Boolean,
            dispatcher: CoroutineDispatcher = Dispatchers.IO): RoomRekeyCourier {
            require(ledger.binding.route == dev.forgesworn.kithmoot.relay.RoomRoute.MIXED)
            require(nearby.hasScope(requireNotNull(ledger.binding.meshScope)))
            require(internet.describe().map(::canonicalRelayUrl).sorted() == ledger.binding.relays)
            require(scope.isActive && stillSelected()) { "Keeper courier requires the selected foreground owner" }
            return RoomRekeyCourier(ledger, nearby, internet, scope, stillSelected, dispatcher)
        }
    }
}
