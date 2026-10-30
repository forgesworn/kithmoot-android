package dev.forgesworn.kithmoot.callrelay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.media.SpryPairing
import dev.forgesworn.kithmoot.media.SpryPairingStore
import dev.forgesworn.kithmoot.protocol.SpryControlEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * This device's own built-in relay, while it runs, as its own calls use it:
 * the pairing the node printed in `ready` with the app's pairing secret, plus
 * the node's listener on this Wi-Fi. Held in memory only: never stored,
 * synced with the account, published or put in a link.
 */
internal class BuiltInRelayPairing(val pairing: SpryPairing, val local: LocalTurn) {
    override fun toString() = "BuiltInRelayPairing(redacted)"
    override fun equals(other: Any?) = other is BuiltInRelayPairing && other.pairing == pairing && other.local == local
    override fun hashCode() = pairing.hashCode() * 31 + local.hashCode()
}

/**
 * The app's main-process side of the built-in call relay (design, 4.1): the
 * saved identity, the checks before a start, and the binding to
 * [CallRelayService] in the relay process. It watches the relay without
 * starting it (the binding never creates the service), so a relay that is
 * running when the app opens is picked up.
 *
 * Its page is Settings, Connections, "Call relay on this phone"
 * (`ui/settings/CallRelayPage.kt`).
 */
internal class BuiltInRelay private constructor(private val app: Context) {
    private val identities = RelayIdentityStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services = MutableStateFlow<ICallRelay?>(null)
    private val _state = MutableStateFlow<RelayState?>(null)
    /** What the relay process last said; null before it ever ran in this app's life. */
    val state: StateFlow<RelayState?> = _state.asStateFlow()
    private val _own = MutableStateFlow<BuiltInRelayPairing?>(null)
    /** This device's own pairing while the relay runs. */
    val ownPairing: StateFlow<BuiltInRelayPairing?> = _own.asStateFlow()
    private val _lastUsed = MutableStateFlow<Long?>(null)
    /** When a connection through the relay last ended in this run, epoch milliseconds. */
    val lastUsedAt: StateFlow<Long?> = _lastUsed.asStateFlow()
    @Volatile private var busy = false
    @Volatile private var identity: RelayIdentity? = null
    /** The account a start was last asked for here, for the one restart after the relay process died. */
    @Volatile private var startedFor: Pair<String, List<String>>? = null
    @Volatile private var processRestarted = false

    private val callback = object : ICallRelayCallback.Stub() {
        override fun onState(state: String?) { RelayState.parse(state)?.let(::apply) }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = ICallRelay.Stub.asInterface(binder ?: return)
            services.value = service
            try { service.watch(callback) } catch (_: Exception) { /* Disconnected again below. */ }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            services.value = null
            died()
        }
    }

    init {
        // No BIND_AUTO_CREATE: this only watches; a start creates the service.
        try { app.bindService(CallRelayService.intent(app, CallRelayService.ACTION_HOLD), connection, 0) } catch (_: Exception) { /* No relay to watch. */ }
    }

    /** This device's own pairing for [account], while its relay runs. */
    fun pairingFor(account: String?): BuiltInRelayPairing? = ownPairing.value?.takeIf { account != null && it.pairing.account == account }

    /**
     * The person's Turn on for [account], with control relays picked from
     * [accountRelays] the first time. Returns why it was refused, or null once
     * the relay process has the request.
     */
    suspend fun start(account: String, accountRelays: List<String>): RelayRefusal? = withContext(Dispatchers.IO) {
        processRestarted = false
        launchFor(account, accountRelays)
    }

    private suspend fun launchFor(account: String, accountRelays: List<String>): RelayRefusal? {
        val handPaired = try { SpryPairingStore(app).read(account) != null } catch (_: Exception) { true }
        if (handPaired) return RelayRefusal.HAND_PAIRED
        val saved = try { identities.read() } catch (_: Exception) { return RelayRefusal.KEYS_UNREADABLE }
        if (saved != null && saved.participant != account) return RelayRefusal.OTHER_ACCOUNT
        val chosen = saved ?: run {
            val relays = RelayConfig.pickControlRelays(accountRelays)
            if (relays.isEmpty()) return RelayRefusal.NO_CONTROL_RELAYS
            RelayIdentity.create(account, relays).also { made ->
                try { identities.save(made) } catch (_: Exception) { return RelayRefusal.START_FAILED }
            }
        }
        identity = chosen
        startedFor = account to accountRelays
        try { ContextCompat.startForegroundService(app, CallRelayService.intent(app, CallRelayService.ACTION_HOLD)) }
        catch (_: Exception) { return RelayRefusal.START_FAILED }
        val service = withTimeoutOrNull(10_000) { services.filterNotNull().first() } ?: return RelayRefusal.START_FAILED
        val secret = chosen.turnSecret.hexToBytes()
        return try {
            if (service.start(secret, chosen.pairingPublicKey, chosen.controlRelays.toTypedArray(), chosen.listenPort) == ICallRelay.OK) null
            else RelayRefusal.START_FAILED
        } catch (_: Exception) { RelayRefusal.START_FAILED } finally { secret.fill(0) }
    }

    /** The person's own Stop. */
    fun stop() {
        startedFor = null
        try { services.value?.stop() } catch (_: Exception) { /* The relay process has gone already. */ }
    }

    /**
     * Remove the relay: stop it, then forget its identity and the node's state
     * (its grants and pairing). A later start makes a new relay, with a new
     * address rooms learn as they open. Returns what went wrong, or null.
     */
    suspend fun remove(): String? = withContext(Dispatchers.IO) {
        stop()
        val stopped = withTimeoutOrNull(25_000) {
            while (services.value != null && _state.value?.let { it.wanted || it.phase == RelayPhase.STOPPING || it.phase == RelayPhase.RUNNING || it.phase == RelayPhase.STARTING } == true) delay(250)
            true
        }
        if (stopped != true) return@withContext RelayFailure.STOP_TIMEOUT.text
        try { identities.remove() } catch (_: Exception) { return@withContext "The relay's saved keys couldn't be removed." }
        identity = null
        _own.value = null
        null
    }

    /** Track what is only known across states: when the relay was last used in this run. */
    private fun track(next: RelayState) {
        if (next.phase != RelayPhase.RUNNING) { busy = false; if (next.phase != RelayPhase.STOPPING) _lastUsed.value = null; return }
        if (next.allocations > 0) busy = true
        else if (busy) { busy = false; _lastUsed.value = System.currentTimeMillis() }
    }

    private fun apply(next: RelayState) {
        track(next)
        _state.value = next
        val id = identity
        if (next.phase == RelayPhase.RUNNING && id == null) {
            // The app opened while the relay ran: read the pairing secret once.
            scope.launch {
                identity = try { identities.read() } catch (_: Exception) { null }
                _state.value?.let(::own)
            }
        }
        own(next)
    }

    private fun own(next: RelayState) {
        val id = identity
        val endpoint = next.endpoint
        val pairing = next.pairing
        val bind = next.bind
        val port = next.listenPort
        _own.value = if (next.phase != RelayPhase.RUNNING || id == null || endpoint == null || pairing == null || bind == null || port == null) null
        else try {
            // Only the relay this identity started: same listener, same control relays.
            require(port == id.listenPort && SpryControlEndpoint.parse(endpoint).relays == id.controlRelays.sorted())
            BuiltInRelayPairing(SpryPairing(endpoint, pairing, id.pairingSecret, id.participant), LocalTurn(bind, port))
        } catch (_: Exception) { null }
    }

    /** The relay process ended. Expected after a stop; otherwise a crash, restarted once after 10 seconds. */
    private fun died() {
        _own.value = null
        busy = false
        val last = _state.value ?: return
        if (!last.wanted) return
        val wish = startedFor
        if (wish == null || processRestarted) {
            startedFor = null
            _state.value = RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED_TWICE)
            return
        }
        processRestarted = true
        _state.value = RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED, wanted = true)
        scope.launch {
            delay(10_000)
            if (startedFor != wish) return@launch
            val failed = launchFor(wish.first, wish.second)
            if (failed != null) {
                startedFor = null
                _state.value = RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED_TWICE)
            }
        }
    }

    companion object {
        @Volatile private var instance: BuiltInRelay? = null

        /** The one client in the app's main process. Never call it from the relay process. */
        fun get(context: Context): BuiltInRelay = instance ?: synchronized(this) {
            instance ?: BuiltInRelay(context.applicationContext).also { instance = it }
        }
    }
}
