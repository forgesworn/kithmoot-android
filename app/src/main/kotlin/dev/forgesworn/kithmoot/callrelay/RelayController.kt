package dev.forgesworn.kithmoot.callrelay

import java.time.ZoneId

/** What the app hands the relay process to start: never written to disk there. */
internal class RelayRequest(val turnSecret: String, val pairingPublicKey: String, val controlRelays: List<String>, val listenPort: Int) {
    override fun toString() = "RelayRequest(redacted)"
}

/** One running node. [stop] asks for an orderly stop and returns at once. */
internal fun interface RelayNode { fun stop() }

/** How a node reports back: lines in order, then exactly one [ended]. */
internal interface RelayNodeEvents {
    fun line(event: RelayNodeEvent)
    /**
     * The node finished. [code] is the exit code (0, 1 or 3), or -1 when it
     * could not be read. [crashed] means the reader thread itself died, and
     * [detached] that the node had not ended when its handle was freed.
     */
    fun ended(code: Int, crashed: Boolean, detached: Boolean)
}

/** Starts a node; null when it could not start at all. Events arrive on the controller's thread. */
internal fun interface RelayNodeStarter {
    fun start(args: List<String>, environment: List<String>, events: RelayNodeEvents): RelayNode?
}

/** Time and delayed work on the controller's one thread. */
internal interface RelayClock {
    fun now(): Long
    val zone: ZoneId
    /** Runs [task] on the controller's thread after [delayMs]; returns a cancel. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** Today's relayed bytes, kept by the relay process (no secrets). */
internal interface RelayUsageStore {
    fun load(): RelayDailyUsage
    fun save(usage: RelayDailyUsage)
}

/**
 * The built-in call relay as the relay process drives it: whether the person
 * wants it, the gates, one node run at a time, and what to say. The desktop's
 * desktop/relay-host.mjs and relay-supervisor.mjs rules:
 *
 * - The person's Turn on marks it wanted and resets the one automatic restart;
 *   only their own Stop clears it. Gates, network changes and the daily limit
 *   stop the run without changing it, so it resumes when they pass.
 * - Exit code 3 (`portmap failed` or `lost`) means this network cannot host:
 *   the node's reason is shown in plain words and it is never restarted as a
 *   crash. It is tried again only on another network (a new address or router).
 * - A node that ends by itself after `ready` (exit 1, or its reader dying)
 *   restarts once after 10 seconds, then stays off and says so.
 * - A run that never became ready (a refusal, a start timeout) is a failed
 *   start, not a crash: it is tried again only on another network.
 * - Relayed bytes count as deltas of `bytesRelayed` across runs, against the
 *   local day.
 *
 * Every method must be called on one thread, the one [clock] schedules on.
 */
internal class RelayController(
    private val starter: RelayNodeStarter,
    private val clock: RelayClock,
    private val usageStore: RelayUsageStore,
    private val stateFile: String,
    private val publish: (RelayState) -> Unit,
    private val dailyLimit: Long = RelayCaps.DAILY_LIMIT_BYTES,
    private val crashRestartMs: Long = 10_000,
    private val startupTimeoutMs: Long = 120_000,
    private val stopTimeoutMs: Long = 20_000,
    /** Whether the relay library loaded and speaks this app's ABI. */
    private val libraryReady: () -> Boolean = { true },
) {
    private class Run(val network: RelayNetwork, var node: RelayNode? = null) {
        var ready = false
        var stopping = false
        var failure: RelayFailure? = null
        var reason: String? = null
        var refusal: String? = null
        var runBytes = 0L
    }

    private val gates = RelayGates(dailyLimit)
    private var conditions = RelayConditions()
    private var usage = runCatching { usageStore.load() }.getOrDefault(RelayDailyUsage())
    private var usageSavedAt = 0L
    private var request: RelayRequest? = null
    private var wanted = false
    private var crashRestarted = false
    private var run: Run? = null
    /** The network on which the node could not host or start; tried again only on another. */
    private var refusedNetwork: RelayNetwork? = null
    /** What to show while waiting on [refusedNetwork]. */
    private var refusedState: RelayState? = null
    private var cancelCrash: (() -> Unit)? = null
    private var cancelRetry: (() -> Unit)? = null
    private var cancelStartup: (() -> Unit)? = null
    private var cancelStopWait: (() -> Unit)? = null
    /** A node did not end when asked: the process should end once idle. */
    var abandoned = false
        private set

    private var state = RelayState(todayBytes = today().bytes, dailyLimit = dailyLimit)

    fun state(): RelayState = state

    /** No node runs and the person does not want one: the service may finish. */
    fun idle(): Boolean = !wanted && run == null

    /** Whether at least one allocation is live: the wake and Wi-Fi locks follow this. */
    fun allocationsLive(): Boolean = run?.let { it.ready && !it.stopping } == true && state.allocations > 0

    /** The person's Turn on. */
    fun turnOn(next: RelayRequest) {
        request = next
        wanted = true
        crashRestarted = false
        refusedNetwork = null
        refusedState = null
        cancelCrash?.invoke(); cancelCrash = null
        if (run == null) update(state.copy(phase = RelayPhase.OFF, wanted = true, gate = null, reason = null, failure = null, refusal = null))
        evaluate()
    }

    /** The person's own Stop: the relay stays off until they turn it on again. */
    fun turnOff() {
        wanted = false
        request = null
        cancelCrash?.invoke(); cancelCrash = null
        cancelRetry?.invoke(); cancelRetry = null
        saveUsage()
        val active = run
        if (active != null) halt(active)
        else update(RelayState(todayBytes = today().bytes, dailyLimit = dailyLimit))
    }

    /** A change in network, battery or temperature. */
    fun conditions(next: RelayConditions) {
        conditions = next
        evaluate()
    }

    private fun today() = usage.on(RelayClockDays.localDay(clock.now(), clock.zone))

    private fun update(next: RelayState) {
        state = next.copy(wanted = wanted, todayBytes = today().bytes, dailyLimit = dailyLimit)
        publish(state)
    }

    private fun evaluate() {
        if (!wanted) return
        val verdict = gates.check(conditions, usage, clock.now(), clock.zone)
        cancelRetry?.invoke(); cancelRetry = null
        val active = run
        when (verdict) {
            is GateVerdict.Blocked -> {
                verdict.retryAt?.let { at -> cancelRetry = clock.schedule(maxOf(0, at - clock.now()) + 1000) { cancelRetry = null; evaluate() } }
                if (active != null) { if (!active.stopping) halt(active, gate = verdict.gate) else update(state.copy(gate = verdict.gate)) }
                else update(state.copy(phase = RelayPhase.PAUSED, gate = verdict.gate, reason = null, failure = null, refusal = null, allocations = 0))
            }
            is GateVerdict.Pass -> {
                if (active != null) {
                    // A new network: stop forwarding on the old one; the end of that run tries this one.
                    if (active.network != verdict.network && !active.stopping) halt(active)
                    return
                }
                if (cancelCrash != null) {
                    if (state.phase != RelayPhase.FAILED) update(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED))
                    return
                }
                if (refusedNetwork != null && refusedNetwork == verdict.network) {
                    refusedState?.let { if (state.phase != it.phase) update(it) }
                    return
                }
                refusedNetwork = null
                refusedState = null
                launch(verdict.network)
            }
        }
    }

    private fun launch(network: RelayNetwork) {
        val wish = request ?: return
        val active = Run(network)
        if (!libraryReady()) { refuse(active, RelayFailure.LIBRARY); return }
        val args = try {
            RelayConfig.args(wish.pairingPublicKey, stateFile, wish.controlRelays, network, wish.listenPort) to RelayConfig.environment(wish.turnSecret)
        } catch (_: Exception) { refuse(active, RelayFailure.START_FAILED); return }
        run = active
        update(RelayState(phase = RelayPhase.STARTING, bind = network.bind, listenPort = wish.listenPort))
        val node = try {
            starter.start(args.first, args.second, object : RelayNodeEvents {
                override fun line(event: RelayNodeEvent) = onLine(active, event)
                override fun ended(code: Int, crashed: Boolean, detached: Boolean) = onEnded(active, code, crashed, detached)
            })
        } catch (_: Exception) { null }
        if (node == null) {
            if (run === active) run = null
            refuse(active, RelayFailure.START_FAILED)
            return
        }
        active.node = node
        cancelStartup = clock.schedule(startupTimeoutMs) {
            cancelStartup = null
            if (run === active && !active.ready && !active.stopping) { active.failure = RelayFailure.START_TIMEOUT; halt(active) }
        }
    }

    private fun refuse(active: Run, failure: RelayFailure, refusal: String? = null) = refuseWith(active, RelayState(phase = RelayPhase.FAILED, failure = failure, refusal = refusal))

    private fun refuseWith(active: Run, shown: RelayState) {
        refusedNetwork = active.network
        refusedState = shown
        update(shown)
    }

    private fun halt(active: Run, gate: RelayGate? = null) {
        if (active.stopping) return
        active.stopping = true
        cancelStartup?.invoke(); cancelStartup = null
        update(state.copy(phase = RelayPhase.STOPPING, gate = gate))
        try { active.node?.stop() } catch (_: Exception) { /* The stop wait below decides. */ }
        cancelStopWait = clock.schedule(stopTimeoutMs) {
            cancelStopWait = null
            if (run !== active) return@schedule
            // The node did not end: give up on it, and let the process end once idle.
            run = null
            abandoned = true
            wanted = false
            update(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.STOP_TIMEOUT))
        }
    }

    private fun onLine(active: Run, event: RelayNodeEvent) {
        if (run !== active) return
        when (event) {
            is RelayNodeEvent.Ready -> {
                if (active.ready || active.stopping) return
                active.ready = true
                cancelStartup?.invoke(); cancelStartup = null
                update(state.copy(phase = RelayPhase.RUNNING, endpoint = event.endpoint, pairing = event.pairing, gate = null, failure = null))
            }
            is RelayNodeEvent.Status -> {
                val delta = maxOf(0, event.bytesRelayed - active.runBytes)
                active.runBytes = event.bytesRelayed
                if (delta > 0) {
                    usage = usage.add(RelayClockDays.localDay(clock.now(), clock.zone), delta)
                    if (usage.bytes >= dailyLimit || clock.now() - usageSavedAt > USAGE_SAVE_MS) saveUsage()
                }
                update(state.copy(allocations = event.allocations, bytesRelayed = event.bytesRelayed))
                if (usage.bytes >= dailyLimit) evaluate()
            }
            is RelayNodeEvent.Control -> update(state.copy(controlConnected = event.relays > 0))
            is RelayNodeEvent.PortmapMapped -> update(state.copy(mapping = RelayMapping(event.method, event.allocationPorts, event.requested,
                event.permanent, state.mapping?.restored ?: 0)))
            is RelayNodeEvent.PortmapRestored -> state.mapping?.let { update(state.copy(mapping = it.copy(restored = it.restored + event.restored))) }
            is RelayNodeEvent.PortmapEnded -> active.reason = event.reason
            is RelayNodeEvent.Stopped -> active.refusal = event.refusal
            is RelayNodeEvent.Urls, is RelayNodeEvent.PortmapCounts -> Unit
        }
    }

    private fun onEnded(active: Run, code: Int, crashed: Boolean, detached: Boolean) {
        if (detached) abandoned = true
        if (run !== active) return
        run = null
        cancelStartup?.invoke(); cancelStartup = null
        cancelStopWait?.invoke(); cancelStopWait = null
        saveUsage()
        val failure = active.failure
        when {
            failure != null -> refuse(active, failure)
            active.stopping -> {
                if (!wanted) update(RelayState())
                else { update(state.copy(phase = RelayPhase.OFF, allocations = 0, endpoint = null, pairing = null)); evaluate() }
            }
            code == 3 -> refuseWith(active, RelayState(phase = RelayPhase.CANNOT_HOST, reason = active.reason ?: "failed"))
            // Ended by itself after `ready`: exit 1 (or 0), or its reader thread died.
            active.ready -> crash()
            else -> refuse(active, RelayFailure.START_FAILED, active.refusal)
        }
    }

    private fun crash() {
        if (wanted && !crashRestarted) {
            crashRestarted = true
            update(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED))
            cancelCrash = clock.schedule(crashRestartMs) { cancelCrash = null; evaluate() }
            return
        }
        wanted = false
        request = null
        cancelRetry?.invoke(); cancelRetry = null
        update(RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED_TWICE))
    }

    private fun saveUsage() {
        usageSavedAt = clock.now()
        try { usageStore.save(usage) } catch (_: Exception) { /* Counted again from the next status line. */ }
    }

    private companion object { const val USAGE_SAVE_MS = 30_000L }
}
