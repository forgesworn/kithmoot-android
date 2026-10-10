package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.PublicationUnconfirmedException
import dev.forgesworn.kithmoot.session.RoomEpochState
import dev.forgesworn.kithmoot.session.RoomSession
import dev.forgesworn.kithmoot.session.codeLocationDiagnostic
import dev.forgesworn.kithmoot.storage.RoomRepository
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
    private val stillSelected: () -> Boolean, private val dispatcher: CoroutineDispatcher,
    private val rooms: RoomRepository? = null) : AutoCloseable {
    sealed interface State {
        data object Starting : State
        data class Ready(val epoch: Int, val phase: KeeperPhase) : State
        data class Pending(val originals: List<String>) : State
        data class Closed(val epoch: Int) : State
        data object Suspended : State
        data object Failed : State
    }
    private sealed interface Work {
        data class Request(val event: NostrEvent, val lane: RekeyLane, val invitationGeneration: Int? = null) : Work
        data class Command(val action: suspend () -> Unit, val done: CompletableDeferred<Unit>) : Work
        data class PendingRecovery(val expected: NativeHostingState,
            val done: CompletableDeferred<NativeHostingState>) : Work
        data object Retry : Work
        data object RouteFailed : Work
    }
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + owner + dispatcher)
    private val queue = Channel<Work>(64)
    private val mutableState = MutableStateFlow<State>(State.Starting)
    val state = mutableState.asStateFlow()
    @Volatile internal var failureDiagnostic: String? = null
        private set
    internal fun receiverFailureDiagnostic() = live?.epochFailureDiagnostic
    private val publicationGate = Any()
    private val mutableHosting = MutableStateFlow(NativeHostingState.starting(source.binding,
        observationGenerations.incrementAndGet().also { check(it > 0) }))
    val hosting = mutableHosting.asStateFlow()
    private val mutableUnknown = MutableStateFlow<List<String>>(emptyList())
    val unknownParticipants = mutableUnknown.asStateFlow()
    private val started = CompletableDeferred<Unit>(owner)
    private val releaseGate = Mutex()
    private var released = false
    @Volatile private var closed = false
    @Volatile private var routeFailed = false
    @Volatile private var verified = false
    @Volatile private var ownsSource = false
    private var courier: RoomRekeyCourier? = null
    private var invitationId: String? = null
    private class InvitationListeners(val generation: Int, val id: String) {
        var jobs: List<Job> = emptyList()
        @Volatile var installed = false
    }
    @Volatile private var invitationListeners: InvitationListeners? = null
    internal fun ownsInvitationInstallation(expectedSource: NativeKeeperJournal, generation: Int, id: String): Boolean {
        val current = invitationListeners
        return source === expectedSource && selected() && !routeFailed && current?.generation == generation &&
            current.id == id && current.installed && current.jobs.isNotEmpty() && current.jobs.all { it.isActive }
    }
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
            subscribe()
            check(selected() && !routeFailed)
            recover()
            started.complete(Unit)
            queue.trySend(Work.Retry)
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
                    is Work.PendingRecovery -> {
                        if (!work.done.isActive) continue
                        try {
                            val expected = work.expected
                            val current = source.snapshot()
                            require(mutableState.value is State.Pending && expected.canRetry &&
                                expected.binding == mutableHosting.value.binding &&
                                expected.ownerGeneration == mutableHosting.value.ownerGeneration &&
                                expected.revision == current.revision && expected.epoch == current.epoch &&
                                expected.lifecycle?.name == current.phase.name && current.phase != KeeperPhase.CLOSED &&
                                expected.pendingOriginals == pendingIds(current)) {
                                "Room hosting changed. Open the confirmation again."
                            }
                        } catch (error: Exception) {
                            work.done.completeExceptionally(error)
                            if (source.persistenceFailed() || ledger.persistenceFailed()) throw error
                            continue
                        }
                        try {
                            validateReceiverBeforeRecovery()
                            recover()
                            work.done.complete(mutableHosting.value)
                        } catch (cancel: CancellationException) { work.done.cancel(cancel); throw cancel }
                        catch (error: Exception) { work.done.completeExceptionally(error); throw error }
                    }
                    Work.Retry -> { recover(); publishWelcome() }
                    Work.RouteFailed -> error("Keeper route subscription failed")
                }
            }
        } catch (cancel: CancellationException) { started.cancel(cancel); throw cancel }
        catch (error: Exception) {
            failureDiagnostic = codeLocationDiagnostic(error)
            publishState(State.Failed); started.completeExceptionally(error)
        }
        finally {
            if (ownsSource) live?.holdKeeperStartup()
            closed = true; withdrawPublicState(); queue.close(); owner.cancel()
            while (true) {
                val work = queue.tryReceive().getOrNull() ?: break
                if (work is Work.Command) work.done.cancel()
                if (work is Work.PendingRecovery) work.done.cancel()
            }
            release()
        }
    }

    suspend fun approve(participant: String) = command { source.verifyReceiver(receiver, live); source.approve(participant) }
    suspend fun rekey(credentials: List<NostrEvent>, removed: List<String> = emptyList(),
        closed: Boolean = false, destruct: Boolean = false, scheduled: Boolean = false) {
        val frozen = credentials.map { it.copy(tags = it.tags.map { row -> row.toList() }) }
        val gone = removed.toList()
        command {
            source.verifyReceiver(receiver, live)
            val proposal = source.preflightRekey(frozen, gone, closed, destruct, scheduled)
            submitRekey(proposal)
        }
    }
    /** Controls name public participants; only the source selects their
     * complete qualified device audience, including offline devices. */
    suspend fun rekeyMembers(removed: List<String> = emptyList(), closed: Boolean = false,
        destruct: Boolean = false, scheduled: Boolean = false) {
        val gone = removed.toList()
        command {
            source.verifyReceiver(receiver, live)
            submitRekey(source.preflightMembers(gone, closed, destruct, scheduled))
        }
    }
    /** A rendered confirmation is only an expectation. The real selected
     * owner checks it in the serialized worker before holding or signing. */
    suspend fun rekeyObservedMembers(expected: NativeHostingState, removed: List<String> = emptyList()) {
        val gone = removed.toList()
        command {
            source.verifyReceiver(receiver, live)
            val current = source.snapshot()
            require(expected.canChangeMembers && expected.binding == mutableHosting.value.binding &&
                expected.ownerGeneration == mutableHosting.value.ownerGeneration &&
                expected.epoch == current.epoch && expected.revision == current.revision &&
                expected.lifecycle?.name == current.phase.name && current.pending.isEmpty()) {
                "Room hosting changed. Open the confirmation again."
            }
            submitRekey(source.preflightMembers(gone))
        }
    }
    private suspend fun submitRekey(proposal: NativeKeeperJournal.RekeyProposal) {
        val session = requireNotNull(live); val b = source.binding
        session.holdKeeperTransition(b.room, b.authority, b.participant, b.device)
        try { source.prepareRekey(proposal) }
        catch (refused: NativeRekeyRefusedException) {
            check(selected() && !ledger.persistenceFailed())
            source.resumeRejectedRekey(proposal, receiver, session)
            throw refused
        }
    }
    fun canShareObservedInvitation(expected: NativeHostingState): Boolean = selected() &&
        expected.canShareInvitation && expected.binding == mutableHosting.value.binding &&
        expected.ownerGeneration == mutableHosting.value.ownerGeneration &&
        mutableHosting.value.canShareInvitation && expected.revision == mutableHosting.value.revision &&
        expected.epoch == mutableHosting.value.epoch &&
        expected.invitationGeneration == mutableHosting.value.invitationGeneration &&
        expected.replacementGeneration == null &&
        source.canShareInvitation(requireNotNull(expected.revision), requireNotNull(expected.epoch))

    suspend fun retireObservedInvitation(expected: NativeHostingState) = command {
        source.verifyReceiver(receiver, live)
        val current = source.snapshot()
        require(expected.canRetireInvitation && expected.binding == mutableHosting.value.binding &&
            expected.ownerGeneration == mutableHosting.value.ownerGeneration && expected.epoch == current.epoch &&
            expected.revision == current.revision && expected.lifecycle?.name == current.phase.name &&
            current.phase == KeeperPhase.ACTIVE && current.pending.isEmpty()) {
            "Room hosting changed. Open the confirmation again."
        }
        source.prepareRetirement(source.preflightRetirement())
    }
    suspend fun retire() = command { source.verifyReceiver(receiver, live); source.prepareRetirement() }
    suspend fun replaceObservedInvitation(expected: NativeHostingState) = command {
        source.verifyReceiver(receiver, live)
        val current = source.snapshot()
        require(expected.canReplaceInvitation && expected.binding == mutableHosting.value.binding &&
            expected.ownerGeneration == mutableHosting.value.ownerGeneration && expected.epoch == current.epoch &&
            expected.revision == current.revision && expected.lifecycle?.name == current.phase.name &&
            expected.invitationGeneration == current.invitationGeneration && current.pending.isEmpty() && current.replacement == null) {
            "Room hosting changed. Open the confirmation again."
        }
        val index = requireNotNull(rooms) { "Replacement requires the actual saved-room repository" }
        source.prepareReplacement(source.preflightReplacement(index), index)
        // Source commit already rejects old work; invalidation also prevents an
        // old collection's late failure from withdrawing its successor.
        val old = invitationListeners; invitationListeners = null
        old?.jobs?.forEach { it.cancel() }; old?.jobs?.joinAll()
        mutableUnknown.value = emptyList()
    }
    /** Explicit selected-owner command. Ordinary recovery never spends an
     * archived original's remaining lifetime attempts. */
    suspend fun retryObservedRetirement(expected: NativeHostingState, id: String) = command {
        source.verifyReceiver(receiver, live)
        val current = source.snapshot()
        require(expected.canChangeMembers && expected.binding == mutableHosting.value.binding &&
            expected.ownerGeneration == mutableHosting.value.ownerGeneration && expected.epoch == current.epoch &&
            expected.revision == current.revision && expected.lifecycle?.name == current.phase.name &&
            current.pending.isEmpty() && current.replacement == null && id in current.retirementOriginals) {
            "Room hosting changed. Open the confirmation again."
        }
        for (lane in RekeyLane.entries) {
            if (!source.binding.permits(lane) || !endpoints.ready(lane)) continue
            val generation = endpoints.generation(lane)
            val reserved = source.reserveRetirement(id, lane) ?: continue
            if (offer(reserved, generation, answer = false)) { source.offered(reserved); break }
        }
    }
    /** Explicit foreground recovery; no fresh signing or re-admission. */
    suspend fun retry() {
        check(selected()); check(queue.trySend(Work.Retry).isSuccess)
    }
    /** Waits for recovery of these retained originals, including a still-pending
     * outcome. It never signs a replacement or retries an archived notice. */
    suspend fun retryObservedPending(expected: NativeHostingState): NativeHostingState {
        check(selected()) { "Native keeper is suspended" }
        val done = CompletableDeferred<NativeHostingState>()
        val frozen = expected.copy(pendingOriginals = expected.pendingOriginals.toList())
        check(queue.trySend(Work.PendingRecovery(frozen, done)).isSuccess) { "Native keeper is busy" }
        try { return done.await() } catch (cancel: CancellationException) { done.cancel(cancel); throw cancel }
    }
    private suspend fun command(action: suspend () -> Unit) {
        check(selected()) { "Native keeper is suspended" }
        val done = CompletableDeferred<Unit>()
        check(queue.trySend(Work.Command(action, done)).isSuccess) { "Native keeper is busy" }
        try { done.await() } catch (cancel: CancellationException) { done.cancel(cancel); throw cancel }
    }

    private suspend fun subscribe() {
        val epoch = Filter(kinds = listOf(KIND_EPOCH_REQUEST), tags = mapOf("#d" to listOf(source.binding.room), "#p" to listOf(source.binding.authority)))
        val registrations = mutableListOf<CompletableDeferred<Unit>>()
        for (lane in RekeyLane.entries.filter(source.binding::permits)) {
            val installed = CompletableDeferred<Unit>(owner); registrations += installed
            attachListener(lane, epoch, installed, null)
        }
        withTimeout(15_000) { registrations.awaitAll() }
        val snapshot = source.snapshot()
        if (snapshot.replacement == null) {
            val invitation = source.invitation()
            val id = try { deriveInvitationId(invitation) } finally { invitation.bearer.fill(0) }
            installInvitation(snapshot.invitationGeneration, id)
        } else if (snapshot.replacement.stage == NativeKeeperJournal.ReplacementStage.INDEX_VERIFIED) {
            installInvitation(snapshot.replacement.proposedGeneration, snapshot.replacement.proposedInvitation)
        }
    }
    private fun attachListener(lane: RekeyLane, filter: Filter, installed: CompletableDeferred<Unit>,
        invitationOwner: InvitationListeners?): Job = scope.launch(start = CoroutineStart.LAZY) {
        fun currentOwner() = selected() && (invitationOwner == null || invitationListeners === invitationOwner)
        try {
            val onInstalled = { installed.complete(Unit); Unit }
            val flow = if (lane == RekeyLane.NEARBY)
                requireNotNull(endpoints.nearby).subscribeKeeperRequests(listOf(filter), onInstalled)
            else requireNotNull(endpoints.internet).subscribeKeeperRequests(listOf(filter), onInstalled)
            flow.collect { event ->
                if (currentOwner() && event.kind == requireNotNull(filter.kinds).single() &&
                    event.tagValue("d") == filter.tags.getValue("#d").single() &&
                    event.tagValue("p") == filter.tags.getValue("#p").single())
                    queue.trySend(Work.Request(event.copy(tags = event.tags.map { it.toList() }), lane, invitationOwner?.generation))
            }
            installed.completeExceptionally(IllegalStateException("Keeper listener closed before registration"))
            if (currentOwner()) { routeFailed = true; queue.trySend(Work.RouteFailed) }
        } catch (cancel: CancellationException) { installed.cancel(cancel); throw cancel }
        catch (error: Exception) {
            installed.completeExceptionally(error)
            if (currentOwner()) { routeFailed = true; queue.trySend(Work.RouteFailed) }
        }
    }.also { if (invitationOwner == null) it.start() }
    private suspend fun installInvitation(generation: Int, id: String) {
        val existing = invitationListeners
        if (ownsInvitationInstallation(source, generation, id)) return
        val next = InvitationListeners(generation, id)
        invitationListeners = next
        val filter = Filter(kinds = listOf(KIND_INVITATION_REQUEST), tags = mapOf("#d" to listOf(id), "#p" to listOf(source.binding.authority)))
        val registrations = mutableListOf<CompletableDeferred<Unit>>()
        next.jobs = RekeyLane.entries.filter(source.binding::permits).map { lane ->
            val installed = CompletableDeferred<Unit>(owner); registrations += installed
            attachListener(lane, filter, installed, next)
        }
        next.jobs.forEach { it.start() }
        try {
            withTimeout(15_000) { registrations.awaitAll() }
            check(selected() && !routeFailed && invitationListeners === next && next.jobs.all { it.isActive })
            next.installed = true; invitationId = id
        } finally {
            existing?.jobs?.forEach { it.cancel() }; existing?.jobs?.joinAll()
        }
    }
    private suspend fun answer(request: Work.Request) {
        val current = source.snapshot()
        if ((mutableState.value !is State.Ready && !(current.replacement != null && request.event.kind == KIND_EPOCH_REQUEST)) ||
            !endpoints.ready(request.lane)) return
        if (request.event.kind == KIND_INVITATION_REQUEST && request.invitationGeneration != current.invitationGeneration) return
        if (request.event.kind !in setOf(KIND_INVITATION_REQUEST, KIND_EPOCH_REQUEST) ||
            request.event.tagValue("p") != source.binding.authority ||
            request.event.tagValue("d") != (if (request.event.kind == KIND_EPOCH_REQUEST) source.binding.room else invitationId)) return
        source.verifyReceiver(receiver, live)
        val generation = endpoints.generation(request.lane)
        val reserved = when (request.event.kind) {
            KIND_INVITATION_REQUEST -> source.answer(request.event, request.lane, request.invitationGeneration)
            KIND_EPOCH_REQUEST -> source.answerEpoch(request.event, request.lane)
            else -> null
        } ?: run { mutableUnknown.value = source.unknownParticipants(); publishState(mutableState.value, source.snapshot()); return }
        mutableUnknown.value = source.unknownParticipants()
        if (offer(reserved, generation, answer = true)) source.offered(reserved)
        publishState(mutableState.value, source.snapshot())
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
        val beginning = source.snapshot()
        // Admission ends at the durable source commit, even while the exact
        // notice waits for local custody. Never keep an old approval card alive
        // until a transport accepts it.
        if (beginning.phase != KeeperPhase.ACTIVE || beginning.replacement != null) mutableUnknown.value = emptyList()
        if (beginning.replacement != null) {
            if (!recoverReplacement()) return
        }
        val pending = beginning.pending
        if (pending.isNotEmpty()) {
            publishState(State.Pending(pending.map { it.id }), beginning)
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
            if (pending.any { source.pendingNeedsOffer(it.id) }) {
                val remaining = source.snapshot()
                publishState(State.Pending(remaining.pending.map { it.id }), remaining)
                // A retirement notice does not change the approved traffic
                // epoch. Cold foreground chat must not wait for its custody.
                // Rekey/terminal pending states keep their startup hold.
                if (remaining.phase == KeeperPhase.RETIRED && remaining.pending.isNotEmpty() &&
                    remaining.pending.all { it.kind == KIND_INVITATION_RETIREMENT }) {
                    validateReceiverBeforeRecovery()
                    source.binding.let {
                        live?.releaseKeeperStartup(it.room, it.authority, it.participant, it.device, ::selected)
                    }
                }
                return
            }
            source.completePending(receiver, requireNotNull(live))
        }
        val phase = source.verifyReceiver(receiver, live)
        val completed = source.snapshot()
        publishState(if (phase == KeeperPhase.CLOSED) State.Closed(completed.epoch)
            else State.Ready(completed.epoch, phase), completed)
        mutableUnknown.value = source.unknownParticipants()
        if (phase != KeeperPhase.CLOSED) source.binding.let {
            live?.releaseKeeperStartup(it.room, it.authority, it.participant, it.device, ::selected)
        }
    }

    private fun pendingIds(snapshot: NativeKeeperJournal.Snapshot): List<String> = snapshot.replacement?.let {
        listOf(it.retirement, it.welcome)
    } ?: snapshot.pending.map { it.id }

    private suspend fun recoverReplacement(): Boolean {
        val index = requireNotNull(rooms) { "Pending replacement requires the actual saved-room repository" }
        validateReceiverBeforeRecovery()
        index.withNativeIndex(source) { source.requirePendingIndex(it) }
        publishState(State.Pending(pendingIds(source.snapshot())), source.snapshot())
        for (event in source.replacementOriginals()) {
            for (lane in RekeyLane.entries.filter(source.binding::permits)) {
                if (!endpoints.ready(lane)) continue
                val generation = endpoints.generation(lane)
                val reserved = source.reserveReplacement(event.id, lane) ?: continue
                val accepted = if (event.kind == KIND_GROUP_INVITATION) try {
                    endpoints.offerWelcome(reserved.event, generation, { selected() && source.canHandoff(reserved) }, 5_000)
                } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); false }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { if (source.persistenceFailed()) error("Authority persistence failed"); false }
                else offer(reserved, generation, answer = false)
                if (accepted) { source.offered(reserved); break }
            }
        }
        source.archiveReplacementNotice()
        val snapshot = source.snapshot()
        if (!source.replacementReadyForIndex()) {
            source.verifyPendingReplacementStores(receiver, ledger)
            publishState(State.Pending(pendingIds(snapshot)), snapshot)
            val b = source.binding
            live?.releaseKeeperStartup(b.room, b.authority, b.participant, b.device, ::selected)
            return false
        }
        source.reconcileReplacementIndex(index, receiver, ledger)
        val target = requireNotNull(source.snapshot().replacement)
        installInvitation(target.proposedGeneration, target.proposedInvitation)
        source.completeReplacement(index, receiver, requireNotNull(live), this)
        return true
    }

    private suspend fun publishWelcome() {
        if (!source.courierReady() || mutableState.value != State.Ready(source.snapshot().epoch, KeeperPhase.ACTIVE) ||
            !source.binding.route.internet || !endpoints.ready(RekeyLane.INTERNET)) return
        source.verifyReceiver(receiver, live)
        val generation = endpoints.generation(RekeyLane.INTERNET)
        val original = source.reserveWelcome() ?: return
        publishState(mutableState.value, source.snapshot())
        val accepted = try {
            endpoints.offerWelcome(original.event, generation, { selected() && source.canHandoff(original) }, 5_000)
        } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (source.persistenceFailed()) error("Authority persistence failed"); false }
        if (accepted) source.offered(original)
        publishState(mutableState.value, source.snapshot())
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
    override fun close() { closed = true; withdrawPublicState(); if (ownsSource) live?.holdKeeperStartup(); queue.close(); owner.cancel() }

    /** Worker snapshots are taken BEFORE this lock. UI withdrawal never waits
     * for source/receiver/courier IO, and an old snapshot cannot revive Ready. */
    private fun publishState(next: State, snapshot: NativeKeeperJournal.Snapshot? = null) = synchronized(publicationGate) {
        if (closed && next != State.Failed) return@synchronized
        val status = when (next) {
            State.Starting -> NativeHostingStatus.STARTING
            is State.Ready -> NativeHostingStatus.READY
            is State.Pending -> NativeHostingStatus.RECOVERING
            is State.Closed -> NativeHostingStatus.CLOSED
            State.Suspended -> NativeHostingStatus.SUSPENDED
            State.Failed -> NativeHostingStatus.FAILED
        }
        val previous = mutableHosting.value
        val observed = if (snapshot == null) previous.copy(status = status) else previous.copy(
            status = status, lifecycle = NativeHostingLifecycle.valueOf(snapshot.phase.name), epoch = snapshot.epoch,
            revision = snapshot.revision,
            approved = NativeHostingState.frozen(snapshot.members), removed = NativeHostingState.frozen(snapshot.removed),
            pendingOriginals = NativeHostingState.frozen(pendingIds(snapshot)),
            retirementOriginals = NativeHostingState.frozen(snapshot.retirementOriginals),
            missingRetirementSlots = snapshot.missingRetirementSlots,
            invitationGeneration = snapshot.invitationGeneration,
            replacementGeneration = snapshot.replacement?.proposedGeneration,
        )
        mutableState.value = next
        mutableHosting.value = if (next == State.Failed || lifetimeSelected()) observed else observed.paused()
    }

    private fun withdrawPublicState() = synchronized(publicationGate) {
        if (mutableState.value != State.Failed) mutableState.value = State.Suspended
        mutableHosting.value = mutableHosting.value.paused()
    }
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
        private val observationGenerations = java.util.concurrent.atomic.AtomicLong()
        suspend fun start(source: NativeKeeperJournal, receiver: EpochVault, live: RoomSession?, ledger: RoomRekeyLedger,
            endpoints: NativeKeeperEndpoints, parent: CoroutineScope, stillSelected: () -> Boolean,
            dispatcher: CoroutineDispatcher = Dispatchers.IO): NativeKeeperController = withContext(dispatcher) {
            val b = source.binding; val q = ledger.binding
            require(parent.isActive && stillSelected())
            require(q.pin == endpoints.binding.pin && b.room == q.room && b.authority == q.authority && b.device == q.device &&
                b.route == q.route && b.meshScope == q.meshScope && b.relays == q.relays)
            startOwned(source, receiver, live, ledger, endpoints, parent, stillSelected, dispatcher, null)
        }
        suspend fun startForRoom(source: NativeKeeperJournal, receiver: EpochVault, live: RoomSession?, ledger: RoomRekeyLedger,
            endpoints: NativeKeeperEndpoints, parent: CoroutineScope, stillSelected: () -> Boolean, rooms: RoomRepository,
            dispatcher: CoroutineDispatcher = Dispatchers.IO): NativeKeeperController = withContext(dispatcher) {
            startOwned(source, receiver, live, ledger, endpoints, parent, stillSelected, dispatcher, rooms)
        }
        private suspend fun startOwned(source: NativeKeeperJournal, receiver: EpochVault, live: RoomSession?, ledger: RoomRekeyLedger,
            endpoints: NativeKeeperEndpoints, parent: CoroutineScope, stillSelected: () -> Boolean, dispatcher: CoroutineDispatcher,
            rooms: RoomRepository?): NativeKeeperController {
            val b = source.binding; val q = ledger.binding
            require(parent.isActive && stillSelected())
            require(q.pin == endpoints.binding.pin && b.room == q.room && b.authority == q.authority && b.device == q.device &&
                b.route == q.route && b.meshScope == q.meshScope && b.relays == q.relays)
            val controller = NativeKeeperController(source, receiver, live, ledger, endpoints, parent, stillSelected, dispatcher, rooms)
            controller.worker.start()
            return try { controller.started.await(); controller }
            catch (error: Exception) { withContext(NonCancellable) { controller.stop() }; throw error }
        }
    }
}
