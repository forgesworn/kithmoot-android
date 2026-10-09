package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException
import dev.forgesworn.kithmoot.session.RoomEpochState
import dev.forgesworn.kithmoot.session.RoomSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exclusive selected-foreground authority. Owns the journals and subscriptions,
 * but never opens or tears down a route. No fallback signer or incoming-root fanout. */
internal class NativeKeeperController private constructor(private val source: NativeKeeperJournal,
    private val receiver: EpochVault, private val live: RoomSession?, private val ledger: RoomRekeyLedger,
    private val endpoints: NativeKeeperEndpoints, parent: CoroutineScope,
    private val stillSelected: () -> Boolean, private val dispatcher: CoroutineDispatcher) : AutoCloseable {
    sealed interface State {
        data object Starting : State
        data class Ready(val epoch: Int, val phase: KeeperPhase) : State
        data class Pending(val originals: List<String>) : State
        data class Closed(val epoch: Int) : State
        data object Suspended : State
        data object Failed : State
    }
    private sealed interface Work {
        data class Request(val event: NostrEvent, val lane: RekeyLane) : Work
        data class Command(val action: suspend () -> Unit, val done: CompletableDeferred<Unit>) : Work
        data object Retry : Work
        data object RouteFailed : Work
    }
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner + dispatcher)
    private val queue = Channel<Work>(64)
    private val mutableState = MutableStateFlow<State>(State.Starting)
    val state = mutableState.asStateFlow()
    private val started = CompletableDeferred<Unit>(owner)
    private val releaseGate = Mutex()
    private var released = false
    @Volatile private var closed = false
    @Volatile private var routeFailed = false
    @Volatile private var verified = false
    private var ownsSource = false
    private var courier: RoomRekeyCourier? = null
    private var invitationId: String? = null
    private fun lifetimeSelected() = !closed && owner.isActive && runCatching(stillSelected).getOrDefault(false)
    private fun selected() = verified && lifetimeSelected()
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        try {
            check(lifetimeSelected())
            source.bind(::selected)
            ownsSource = true
            validateReceiverBeforeRecovery()
            check(lifetimeSelected()); verified = true
            courier = RoomRekeyCourier.startAuthority(ledger, endpoints, scope, ::selected, dispatcher)
            recover()
            subscribe()
            started.complete(Unit)
            scope.launch { while (isActive) { delay(5_000); queue.trySend(Work.Retry) } }
            for (work in queue) {
                if (!selected()) break
                check(!routeFailed) { "Keeper route subscription failed" }
                when (work) {
                    is Work.Request -> answer(work)
                    is Work.Command -> {
                        if (!work.done.isActive) continue
                        if (mutableState.value !is State.Ready) {
                            work.done.completeExceptionally(IllegalStateException("Native keeper is not ready for a new authority operation"))
                            continue
                        }
                        try {
                            work.action(); recover(); work.done.complete(Unit)
                        } catch (cancel: CancellationException) { work.done.cancel(cancel); throw cancel }
                        catch (error: Exception) {
                            work.done.completeExceptionally(error)
                            if (source.persistenceFailed() || ledger.persistenceFailed() || live?.epochState?.value !is RoomEpochState.Active)
                                throw error
                        }
                    }
                    Work.Retry -> recover()
                    Work.RouteFailed -> error("Keeper route subscription failed")
                }
            }
        } catch (cancel: CancellationException) { started.cancel(cancel); throw cancel }
        catch (error: Exception) { mutableState.value = State.Failed; started.completeExceptionally(error) }
        finally {
            closed = true; queue.close(); owner.cancel()
            while (true) {
                val work = queue.tryReceive().getOrNull() ?: break
                if (work is Work.Command) work.done.cancel()
            }
            release()
            if (mutableState.value != State.Failed) mutableState.value = State.Suspended
        }
    }

    suspend fun approve(participant: String) = command { source.verifyReceiver(receiver, live); source.approve(participant) }
    suspend fun rekey(credentials: List<NostrEvent>, removed: List<String> = emptyList(),
        closed: Boolean = false, destruct: Boolean = false, scheduled: Boolean = false) {
        val frozen = credentials.map { it.copy(tags = it.tags.map { row -> row.toList() }) }
        val gone = removed.toList()
        command {
            source.verifyReceiver(receiver, live)
            val session = requireNotNull(live); val b = source.binding
            session.holdKeeperTransition(b.room, b.authority, b.participant, b.device)
            source.prepareRekey(frozen, gone, closed, destruct, scheduled)
        }
    }
    suspend fun retire() = command { source.verifyReceiver(receiver, live); source.prepareRetirement() }
    /** Explicit foreground recovery; no fresh signing or re-admission. */
    suspend fun retry() {
        check(selected()); check(queue.trySend(Work.Retry).isSuccess)
    }
    private suspend fun command(action: suspend () -> Unit) {
        check(selected()) { "Native keeper is suspended" }
        val done = CompletableDeferred<Unit>()
        check(queue.trySend(Work.Command(action, done)).isSuccess) { "Native keeper is busy" }
        try { done.await() } catch (cancel: CancellationException) { done.cancel(cancel); throw cancel }
    }

    private fun subscribe() {
        val invitation = source.invitation()
        val invitationId = try { deriveInvitationId(invitation) } finally { invitation.bearer.fill(0) }
        this.invitationId = invitationId
        val filters = listOf(
            Filter(kinds = listOf(KIND_INVITATION_REQUEST), tags = mapOf("#d" to listOf(invitationId), "#p" to listOf(source.binding.authority))),
            Filter(kinds = listOf(KIND_EPOCH_REQUEST), tags = mapOf("#d" to listOf(source.binding.room), "#p" to listOf(source.binding.authority))),
        )
        for (lane in RekeyLane.entries) {
            if (!source.binding.permits(lane)) continue
            scope.launch {
                try {
                    val flow = if (lane == RekeyLane.NEARBY) requireNotNull(endpoints.nearby).subscribeInbound(filters)
                        else requireNotNull(endpoints.internet).subscribeKeeperRequests(filters)
                    flow.collect { event ->
                        if (selected()) queue.trySend(Work.Request(event.copy(tags = event.tags.map { it.toList() }), lane))
                    }
                    if (selected()) { routeFailed = true; queue.trySend(Work.RouteFailed) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { if (selected()) { routeFailed = true; queue.trySend(Work.RouteFailed) } }
            }
        }
    }
    private suspend fun answer(request: Work.Request) {
        if (mutableState.value !is State.Ready || !endpoints.ready(request.lane)) return
        if (request.event.kind !in setOf(KIND_INVITATION_REQUEST, KIND_EPOCH_REQUEST) ||
            request.event.tagValue("p") != source.binding.authority ||
            request.event.tagValue("d") != (if (request.event.kind == KIND_EPOCH_REQUEST) source.binding.room else invitationId)) return
        source.verifyReceiver(receiver, live)
        val generation = endpoints.generation(request.lane)
        val reserved = when (request.event.kind) {
            KIND_INVITATION_REQUEST -> source.answer(request.event, request.lane)
            KIND_EPOCH_REQUEST -> source.answerEpoch(request.event, request.lane)
            else -> null
        } ?: return
        if (offer(reserved, generation, answer = true)) source.offered(reserved)
    }
    private suspend fun offer(reserved: NativeKeeperJournal.Handoff, generation: Long, answer: Boolean): Boolean = try {
        val guard = { selected() && source.canHandoff(reserved) }
        if (answer) endpoints.offerAnswer(reserved.event, reserved.lane, generation, guard, 5_000)
        else endpoints.offer(reserved.event, reserved.lane, generation, guard, 5_000)
    } catch (_: PublicationUnconfirmedException) { reserved.lane == RekeyLane.NEARBY }
    catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); false }
    catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { if (source.persistenceFailed()) error("Authority persistence failed"); false }

    private suspend fun recover() {
        check(selected())
        val pending = source.snapshot().pending
        if (pending.isNotEmpty()) {
            mutableState.value = State.Pending(pending.map { it.id })
            validateReceiverBeforeRecovery()
            for (event in pending.filter { it.kind == KIND_ROOM_REKEY }) {
                requireNotNull(courier).admit(event); source.queued(event)
                val session = requireNotNull(live)
                val target = requireNotNull(peekRekeyEpoch(event, source.binding.room, source.binding.authority))
                if (session.epochKeys().epoch < target && session.epochState.value !is RoomEpochState.Closed) {
                    val b = source.binding
                    session.holdKeeperTransition(b.room, b.authority, b.participant, b.device)
                    session.applyKeeperRekey(event, b.participant, b.device)
                }
            }
            for (event in pending.filter { it.kind == KIND_INVITATION_RETIREMENT }) {
                if (!source.pendingNeedsOffer(event.id)) continue
                for (lane in RekeyLane.entries) {
                    if (!source.binding.permits(lane) || !endpoints.ready(lane)) continue
                    val generation = endpoints.generation(lane)
                    val reserved = source.reservePending(event.id, lane) ?: continue
                    if (offer(reserved, generation, answer = false)) { source.offered(reserved); break }
                }
            }
            if (pending.any { source.pendingNeedsOffer(it.id) }) return
            source.completePending(receiver, requireNotNull(live))
        }
        val phase = source.verifyReceiver(receiver, live)
        mutableState.value = if (phase == KeeperPhase.CLOSED) State.Closed(source.snapshot().epoch)
            else State.Ready(source.snapshot().epoch, phase)
    }

    /** Before any resumed export, validate actual owner/key/cause, including
     * either side of the exact pending transition. No SavedRoom epoch fallback. */
    private fun validateReceiverBeforeRecovery() {
        val snapshot = source.snapshot()
        if (snapshot.pending.isEmpty()) { source.verifyReceiver(receiver, live); return }
        val b = source.binding; val session = requireNotNull(live)
        require(session.keeperProfileMatches(b.room, b.authority, b.participant, b.device))
        val durable = requireNotNull(receiver.get(b.room))
        require(durable.authority == b.authority && durable.stableRoom == b.room)
        val original = snapshot.pending.singleOrNull { it.kind == KIND_ROOM_REKEY }
        val current = source.epoch()
        try {
            val keys = deriveEpoch(current); val actual = session.epochKeys()
            try {
                if (original != null && durable.phase == EpochPhase.ACTIVE && durable.currentEpoch == current.epoch + 1) {
                    require(durable.pending == null && durable.activationCause == original.id && session.keeperAppliedRekey(original, durable.activationCause))
                } else {
                    require(durable.currentEpoch == current.epoch && durable.currentSecret.contentEquals(current.secret) &&
                        actual.epoch == keys.epoch && actual.id == keys.id && actual.key.contentEquals(keys.key))
                    if (durable.phase == EpochPhase.CLOSED) require(original != null && durable.terminalCause == original.id)
                    else require(durable.phase == EpochPhase.ACTIVE && durable.pending == null && durable.activationCause == snapshot.epochCause &&
                        durable.removed == snapshot.removed)
                    val phase = session.epochState.value
                    require(phase is RoomEpochState.Active || original != null &&
                        (phase is RoomEpochState.Updating && phase.epoch == current.epoch + 1 ||
                         phase is RoomEpochState.RecoveryNeeded && phase.expectedEpoch == current.epoch + 1 ||
                         phase is RoomEpochState.Closed && phase.epoch == current.epoch + 1))
                }
            } finally { keys.key.fill(0); actual.key.fill(0) }
        } finally { current.secret.fill(0) }
    }

    /** Invalidates the handoff guard synchronously; cleanup stays on IO. */
    override fun close() { closed = true; queue.close(); owner.cancel() }
    suspend fun stop() { close(); worker.join(); release(); owner.join() }
    private suspend fun release() = withContext(NonCancellable + dispatcher) {
        releaseGate.withLock {
            if (!released) {
                released = true
                if (ownsSource) {
                    try { courier?.close() ?: ledger.closeIfUnbound() }
                    finally { source.suspendExports(); source.close() }
                }
            }
        }
    }

    companion object {
        suspend fun start(source: NativeKeeperJournal, receiver: EpochVault, live: RoomSession?, ledger: RoomRekeyLedger,
            endpoints: NativeKeeperEndpoints, parent: CoroutineScope, stillSelected: () -> Boolean,
            dispatcher: CoroutineDispatcher = Dispatchers.IO): NativeKeeperController = withContext(dispatcher) {
            val b = source.binding; val q = ledger.binding
            require(parent.isActive && stillSelected())
            require(q.pin == endpoints.binding.pin && b.room == q.room && b.authority == q.authority && b.device == q.device &&
                b.route == q.route && b.meshScope == q.meshScope && b.relays == q.relays)
            val controller = NativeKeeperController(source, receiver, live, ledger, endpoints, parent, stillSelected, dispatcher)
            controller.worker.start()
            try { controller.started.await(); controller }
            catch (error: Exception) { withContext(NonCancellable) { controller.stop() }; throw error }
        }
    }
}
