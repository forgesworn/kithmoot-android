package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.relay.RelaySocket
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.relay.RelaySocketListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class AccountNetworkMode(val value: String) {
    PRIVATE_INVITATIONS("private-invitations"), NOSTR("nostr");
    companion object {
        fun parse(value: String): AccountNetworkMode = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unknown account network mode: $value")
    }
}

const val NOSTR_MODE_REQUIRED = "This action needs Nostr account networking. Enable it in Settings › Privacy › Account networking. Your private invitations and local records are kept."

/** Installation-owned, loaded synchronously before account restore or service composition.
 * The generation also invalidates work across private -> Nostr and account replacement.
 * Paired Link and native own-stack routes deliberately do not use ordinarySockets. */
class AccountNetworkPolicy(default: String, read: () -> String?, private val persist: (String) -> Unit) {
    data class Snapshot(val mode: AccountNetworkMode, val generation: Long, val persistenceError: String? = null)
    private val lock = Any()
    val accountGate = Mutex()
    private val mutable = MutableStateFlow(run {
        val readResult = runCatching { read() }
        val stored = readResult.getOrNull()
        val parsed = stored?.let { runCatching { AccountNetworkMode.parse(it) } }
        var mode = if (readResult.isFailure || parsed?.isFailure == true) AccountNetworkMode.PRIVATE_INVITATIONS
            else parsed?.getOrNull() ?: AccountNetworkMode.parse(default)
        var error: String? = if (readResult.isFailure || parsed?.isFailure == true)
            "The saved network choice could not be read. Private invitations is active; save your choice again in Settings."
            else null
        if (stored == null && readResult.isSuccess) {
            if (runCatching { persist(mode.value) }.isFailure) {
                mode = AccountNetworkMode.PRIVATE_INVITATIONS
                error = "The network choice could not be saved. Private invitations is active for this session; save your choice again before restarting."
            }
        }
        Snapshot(mode, 0, error)
    })
    val state = mutable.asStateFlow()
    private val sockets = mutableSetOf<RelaySocket>()
    private val guardedJobs = mutableSetOf<kotlinx.coroutines.Job>()
    private val invalidatedJobs = mutableSetOf<kotlinx.coroutines.Job>()
    val allowsNostr get() = state.value.mode == AccountNetworkMode.NOSTR
    fun token(): Snapshot = state.value
    fun current(token: Snapshot): Boolean = token == state.value && token.mode == AccountNetworkMode.NOSTR
    fun requireNostr() { check(allowsNostr) { NOSTR_MODE_REQUIRED } }
    fun requireCurrent(token: Snapshot) { check(current(token)) { NOSTR_MODE_REQUIRED } }

    /** Caller holds accountGate while stopping/joining and recomposing account services. */
    fun set(mode: AccountNetworkMode) = synchronized(lock) {
        if (mode == AccountNetworkMode.PRIVATE_INVITATIONS && mode != state.value.mode) advance(mode)
        try { persist(mode.value) }
        catch (failure: Exception) {
            if (state.value.mode != AccountNetworkMode.PRIVATE_INVITATIONS) advance(AccountNetworkMode.PRIVATE_INVITATIONS)
            mutable.value = state.value.copy(persistenceError =
                "Private invitations is active for this session, but the choice could not be saved. Try again before restarting.")
            throw failure
        }
        if (mode != state.value.mode) advance(mode)
        else mutable.value = state.value.copy(persistenceError = null)
    }
    fun invalidate() = synchronized(lock) { advance(state.value.mode) }
    private fun advance(mode: AccountNetworkMode) {
        mutable.value = Snapshot(mode, state.value.generation + 1)
        invalidatedJobs.addAll(guardedJobs)
        guardedJobs.clear()
        val closing = sockets.toList()
        sockets.clear()
        closing.forEach { runCatching { it.close() } }
    }

    /** Cancels work synchronously on generation change, even if a StateFlow collector
     * misses an intermediate private state during a rapid private -> Nostr round trip. */
    fun guardJob(token: Snapshot, job: kotlinx.coroutines.Job) = synchronized(lock) {
        if (token != state.value) { job.cancel(); return@synchronized }
        val registered = object : RelaySocket {
            override fun send(text: String) = Unit
            override fun close() { job.cancel() }
        }
        sockets.add(registered)
        guardedJobs.add(job)
        job.invokeOnCompletion { synchronized(lock) {
            sockets.remove(registered); guardedJobs.remove(job); invalidatedJobs.remove(job)
        } }
    }

    /** Called under accountGate after cancellation, before the transition is complete. */
    suspend fun joinInvalidated() {
        val pending = synchronized(lock) { invalidatedJobs.toList() }
        pending.forEach { it.join() }
    }

    /** A cancellable HTTP or other ordinary resource, registered before it can execute. */
    fun <T> resource(token: Snapshot, create: () -> T, cancel: (T) -> Unit): NetworkResource<T> = synchronized(lock) {
        requireCurrent(token)
        val value = create()
        val registered = object : RelaySocket {
            override fun send(text: String) = Unit
            override fun close() { cancel(value) }
        }
        sockets.add(registered)
        NetworkResource(value) { synchronized(lock) { sockets.remove(registered) } }
    }
    class NetworkResource<T>(val value: T, private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }

    /** Captured at factory construction: an old pool may never reconnect under a later grant.
     * Open, send and mode changes share the same lock. No check-then-dial gap exists. */
    fun ordinarySockets(delegate: RelaySocketFactory): RelaySocketFactory {
        val token = token()
        return RelaySocketFactory { url, listener -> synchronized(lock) {
            requireCurrent(token)
            var registered: RelaySocket? = null
            val delegateSocket = delegate.open(url, object : RelaySocketListener {
                override val attemptTrace get() = listener.attemptTrace
                override fun onOpen() { if (current(token)) listener.onOpen() }
                override fun onMessage(text: String) { if (current(token)) listener.onMessage(text) }
                override fun onClosed(reason: String) {
                    synchronized(lock) { registered?.let { sockets.remove(it) } }
                    listener.onClosed(reason)
                }
            })
            val guarded = object : RelaySocket {
                override fun send(text: String) = synchronized(lock) {
                    requireCurrent(token)
                    delegateSocket.send(text)
                }
                override fun close() = synchronized(lock) {
                    sockets.remove(this)
                    delegateSocket.close()
                }
            }
            registered = guarded
            sockets.add(guarded)
            guarded
        } }
    }
}

/** Actual account-service composition used by RoomViewModel. The caller holds the
 * installation's accountGate; stop callbacks cancel and join their owned jobs/pools.
 * Local stores and signer custody are owned separately and never reset here. */
class AccountNetworkServices<A : Any>(private val policy: AccountNetworkPolicy,
    private val stops: List<suspend () -> Unit>, private val starts: List<(A) -> Unit>) {
    private var owner: A? = null
    private var started: AccountNetworkPolicy.Snapshot? = null
    suspend fun stop() {
        stops.forEach { it() }
        owner = null
        started = null
    }
    suspend fun reconcile(account: A?, force: Boolean = false) {
        val token = policy.token()
        if (!force && account === owner && started == token) return
        stop()
        if (account == null || !policy.current(token)) return
        owner = account
        started = token
        starts.forEach { if (policy.current(token)) it(account) }
    }
}

/** Classification uses active paired-route authority, never a hostname/pubkey allowlist. */
fun privatePairedRelayRoom(relays: List<String>, paired: (String) -> Boolean): Boolean =
    relays.isNotEmpty() && relays.all(paired)

/** A conflated final Nostr emission still pauses any ordinary session opened under an older grant. */
fun staleOrdinaryRoomNetwork(owned: AccountNetworkPolicy.Snapshot?, current: AccountNetworkPolicy.Snapshot): Boolean =
    owned != null && (owned != current || current.mode != AccountNetworkMode.NOSTR)
