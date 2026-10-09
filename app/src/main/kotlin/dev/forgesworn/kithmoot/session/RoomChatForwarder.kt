package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.canonicalRelayUrl
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.LinkConsentVault
import dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException
import dev.forgesworn.kithmoot.relay.RoomMeshTransport
import dev.forgesworn.kithmoot.relay.RoomNearbyDiscovery
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit foreground chat sharing, separate from HybridRoomTransport's own
 * participant fanout. Does not own the supplied transports, host admission or
 * relay other people's rekey/control/presence. The app must close this owner
 * before route/background teardown. Dispatch also reads [stillSelected].
 * Enabled only by explicit foreground ViewModel/UI consent. */
internal class RoomChatForwarder private constructor(
    private val session: RoomSession,
    private val nearby: RoomMeshTransport,
    private val internet: RoomTransport,
    private val ledger: RoomForwardingLedger,
    parentScope: CoroutineScope,
    private val stillSelected: () -> Boolean,
    dispatcher: CoroutineDispatcher,
) : AutoCloseable {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob + dispatcher)
    private val pumpGate = Mutex()
    @Volatile private var closed = false
    @Volatile private var failed = false
    private val binding = ledger.binding

    private fun selected(): Boolean = !closed && !failed && ownerJob.isActive &&
        runCatching { stillSelected() }.getOrDefault(false)

    init {
        ledger.bindWithDispatch(binding.room, binding.participant, binding.device,
            { event, at -> if (selected()) session.forwardingVerdict(event, binding, at) else ForwardingVerdict.WAITING },
            { event, at -> if (selected()) session.forwardingVerdict(event, binding, at, waitForState = false) else ForwardingVerdict.WAITING })
        scope.launch {
            try {
                session.epochState.collectLatest { state ->
                    if (state is RoomEpochState.Closed || state is RoomEpochState.Removed) { close(); return@collectLatest }
                    if (state !is RoomEpochState.Active) return@collectLatest
                    val since = maxOf(0L, ledger.status().high / 1000 - RoomForwardingLedger.TTL_MS / 1000)
                    val filters = listOf(Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(state.trafficRoom)), since = since))
                    coroutineScope {
                        launch { nearby.subscribeInbound(filters).collect { receive(it, ForwardingLane.NEARBY) } }
                        launch { internet.subscribe(filters).collect { receive(it, ForwardingLane.INTERNET) } }
                        awaitCancellation()
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail() }
        }
        scope.launch {
            try { while (isActive) { pump(); delay(5_000) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { fail() }
        }
        ownerJob.invokeOnCompletion { close() }
    }

    private fun receive(event: NostrEvent, lane: ForwardingLane) {
        if (!selected()) return
        try { ledger.observe(event, lane) }
        catch (_: Exception) { if (ledger.persistenceFailed()) fail() }
    }

    /** Guarded and serialised drain, also useful for a reconnection wake-up.
     * Reservation survives cancellation; no timeout is a delivery receipt. */
    suspend fun pump() = pumpGate.withLock {
        if (!selected()) return@withLock
        for (row in ledger.status().entries) for (lane in ForwardingLane.entries) {
            currentCoroutineContext().ensureActive()
            if (!selected()) return@withLock
            val transport: RoomTransport = if (lane == ForwardingLane.NEARBY) nearby else internet
            if (!transport.reachable()) continue
            // Capture before reading live session authority in reserve().
            val generation = transport.publicationGeneration()
            val reservation = ledger.reserve(row.event.id, lane) ?: continue
            try {
                val accepted = transport.publishConfirmedGuarded(reservation.event, generation,
                    { selected() && ledger.canHandoff(reservation) }, 5_000)
                if (accepted && lane == ForwardingLane.INTERNET && !closed) ledger.relayAccepted(reservation)
            } catch (_: PublicationUnconfirmedException) {
                if (lane == ForwardingLane.NEARBY && !closed) ledger.offered(reservation)
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (ledger.persistenceFailed()) { fail(); return@withLock } }
        }
    }

    fun isFailed(): Boolean = failed || ledger.persistenceFailed()
    private fun fail() { failed = true; close() }
    override fun close() {
        if (closed) return
        closed = true; ledger.suspendExports(); ownerJob.cancel(); ledger.close()
    }

    companion object {
        /** All paths already belong to this foreground room. Checking consent
         * opens no network, signer or radio. [stillSelected] must be a cheap
         * live foreground/route/profile check, read again at actual dispatch. */
        fun start(saved: SavedRoom, consents: LinkConsentVault, session: RoomSession,
            nearby: RoomMeshTransport, internet: RoomTransport, ledger: RoomForwardingLedger,
            scope: CoroutineScope, stillSelected: () -> Boolean,
            dispatcher: CoroutineDispatcher = Dispatchers.IO): RoomChatForwarder {
            val b = ledger.binding
            require(b.senders.isNotEmpty()) { "Choose people before starting sharing" }
            require(saved.route == RoomRoute.MIXED && !saved.anonymous && saved.policy?.quiet != true && !saved.destruct)
            require(consents.all().none { it.roomId == saved.id }) { "Bothy sharing is not qualified" }
            require(saved.id == b.room && saved.participant == b.participant && saved.devicePubkey == b.device)
            require(session.forwardingProfileMatches(b)) { "This live room is not qualified for sharing" }
            require(b.meshScope == RoomNearbyDiscovery.scope(saved.id) && nearby.hasScope(b.meshScope))
            require(internet.describe().map(::canonicalRelayUrl).sorted() == b.relays)
            require(scope.isActive && stillSelected()) { "Sharing requires the selected foreground room" }
            return RoomChatForwarder(session, nearby, internet, ledger, scope, stillSelected, dispatcher)
        }
    }
}
