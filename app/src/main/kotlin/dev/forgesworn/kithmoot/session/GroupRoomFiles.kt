package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.GrantReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/** How often a device that has no working grant asks the room's keeper for them, milliseconds. */
const val STORAGE_GRANT_ASK_MS = 10 * 60_000L

/**
 * One open room's use of its group's own storage (S5b): this device's grants,
 * the storage client, and asking the keeper. Mirrors `startRoomGroup`,
 * `ingestStorageGrant`, `askKeeperForGrants` and `checkGrantsSoon` in the web
 * client. [wake] ticks whenever something that could let a waiting file go
 * has changed: a grant arrived, or the room's nodes changed.
 */
class GroupRoomFiles(
    val roomId: String,
    deviceSecretKey: ByteArray,
    private val grants: GrantStore,
    private val scope: CoroutineScope,
    /** The room's listed nodes, as this device holds them now. */
    val nodes: () -> List<String>,
    /** Posts `storage-grant?` on the control channel. */
    private val askKeeper: suspend () -> Boolean,
    /** Tells the person something, once. */
    private val say: (String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    client: OkHttpClient = groupStorageHttp,
    /** Where asking the keeper and saving grants run. Tests pass their own. */
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {
    /** Where a room's grants are kept between visits. */
    interface GrantStore {
        fun load(): GrantReceiver
        fun save(receiver: GrantReceiver): Boolean
    }

    val receiver: GrantReceiver = grants.load()
    private val lockedOutSaid = AtomicBoolean(false)
    private val endingWarned = AtomicBoolean(false)
    private val saidOnce = HashSet<GroupStorageReason>()
    @Volatile private var askedAt = Long.MIN_VALUE / 2
    @Volatile private var saveJob: Job? = null
    @Volatile private var checkJob: Job? = null
    private val _wake = MutableStateFlow(0)
    val wake: StateFlow<Int> = _wake.asStateFlow()

    val storage = GroupStorage(
        nodes = nodes, deviceSecretKey = deviceSecretKey, receiver = receiver, client = client, now = now,
        onAcknowledged = ::saveSoon,
        onNodeOpen = { sayOnce(GroupStorageReason.NODE_OPEN) },
        onClockWrong = { _, _ -> sayOnce(GroupStorageReason.CLOCK) },
        onNoGrant = ::ask,
        onLockedOut = { if (lockedOutSaid.compareAndSet(false, true)) say(GroupStorageReason.LOCKED_OUT.words) },
    )

    private fun sayOnce(reason: GroupStorageReason) {
        if (synchronized(saidOnce) { saidOnce.add(reason) }) say(reason.words)
    }

    /** A `storage-grant` op read off the control channel. True when it concerned this device and was kept. */
    fun offerGrant(event: JsonElement): Boolean {
        val kept = synchronized(receiver) { receiver.offer(event, now()) }
        if (!kept) return false
        // What it reported has changed: a later lock-out is told afresh.
        lockedOutSaid.set(false)
        saveSoon()
        _wake.value++
        checkGrantsSoon()
        return true
    }

    /** The room's storage list changed: waiting files try the new nodes, and grants are checked. */
    fun nodesChanged() { _wake.value++; checkGrantsSoon() }

    /** Waiting files try again (the caller's timer). */
    fun poke() { _wake.value++ }

    /** Ask the room's keeper to send this device's grants again. One ask in ten minutes. */
    fun ask() {
        val at = nowMs()
        synchronized(this) {
            if (at - askedAt < STORAGE_GRANT_ASK_MS) return
            askedAt = at
        }
        scope.launch(io) { runCatching { askKeeper() } }
    }

    /**
     * A little after the control log has loaded: ask the keeper if this device
     * holds no grant at any node it can reach, and say once if the ones it
     * holds run out within a week with no renewal on the way.
     */
    fun checkGrantsSoon(delayMs: Long = 30_000L + (0 until 30_000L).random()) {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(delayMs)
            val reachable = reachableGroupNodes(nodes())
            if (reachable.isEmpty()) return@launch
            val at = now()
            if (!holdsAnyGrant(receiver, reachable, at)) { ask(); return@launch }
            val end = grantsEndingSoon(receiver, reachable, at) ?: return@launch
            if (endingWarned.compareAndSet(false, true)) say(grantsEndingWords(end))
        }
    }

    /** Kept a moment after the last change, not once for each event of a replayed history. */
    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch(io) { delay(250); grants.save(receiver) }
    }

    /** Nothing more is asked of the room's nodes. The grants are kept for the next visit when [keep]. */
    fun close(keep: Boolean) {
        saveJob?.cancel(); checkJob?.cancel()
        if (keep) grants.save(receiver)
        storage.close()
    }
}
