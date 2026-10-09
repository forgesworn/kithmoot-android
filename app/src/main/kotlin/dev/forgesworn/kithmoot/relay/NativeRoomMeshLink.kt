package dev.forgesworn.kithmoot.relay

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Explicit discovery configuration. Neither value is an authorisation secret. */
data class RoomBleConfig(val scope: String, val selfId: String, val serviceUuid: UUID) {
    init {
        require(Regex("[0-9a-f]{64}").matches(scope))
        require(Regex("[0-9a-f]{64}").matches(selfId))
    }
}

enum class RoomBlePhase { IDLE, STARTING, READY, RESETTING, FAILED, CLOSED }
data class RoomBleState(
    val phase: RoomBlePhase = RoomBlePhase.IDLE,
    val writablePeers: Int = 0,
    val lastQueuedPeers: Int = 0,
    val error: String? = null,
)

/** Synchronous engine calls, made only by the link's single main-thread owner.
 * Events may be emitted inline: the link always defers their delivery. */
internal interface RoomBleRadio : AutoCloseable {
    fun start(config: RoomBleConfig)
    fun offer(bytes: ByteArray, to: String?): Int
}
internal sealed interface RoomBleEvent {
    data class Frame(val bytes: ByteArray, val from: String) : RoomBleEvent
    data class Status(val running: Boolean, val peers: Int, val error: String? = null) : RoomBleEvent
}

/** Owns one radio, a bounded command queue and terminal teardown. Constructing
 * it does not start discovery. The host must request permissions and explicitly
 * call start. No failure starts a different transport or requests permissions. */
class NativeRoomMeshLink internal constructor(
    private val factory: ((RoomBleEvent) -> Unit) -> RoomBleRadio,
    dispatcher: CoroutineDispatcher,
) : RoomMeshLink {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<Command>(64)
    private val mutableState = MutableStateFlow(RoomBleState())
    val state = mutableState.asStateFlow()
    private var closed = false
    private var teardownFailure: Exception? = null
    private var generation = 0L
    private var config: RoomBleConfig? = null
    private var offeredBytes = 0
    private var offeredFrames = 0
    private val receivers = linkedSetOf<(ByteArray, String) -> Unit>()

    private sealed interface Command {
        val generation: Long
        data class Open(override val generation: Long, val config: RoomBleConfig,
            val completion: CompletableDeferred<Unit>) : Command
        data class Offer(override val generation: Long, val bytes: ByteArray, val to: String?) : Command
        data class Event(override val generation: Long, val event: RoomBleEvent) : Command
    }

    private val worker = scope.launch {
        var radio: RoomBleRadio? = null
        try {
            for (command in commands) {
                if (command is Command.Offer) synchronized(lock) {
                    offeredBytes -= command.bytes.size; offeredFrames--
                }
                val current = synchronized(lock) {
                    !closed && command.generation == generation &&
                        (command !is Command.Offer || mutableState.value.phase == RoomBlePhase.READY)
                }
                if (!current) {
                    if (command is Command.Open) command.completion.completeExceptionally(
                        IllegalStateException("BLE owner superseded or closed"))
                    continue
                }
                when (command) {
                    is Command.Open -> {
                        try {
                            // Closing the old engine invalidates its callbacks and
                            // drains its queues before the new owner can be ready.
                            try { radio?.close() } catch (error: Exception) {
                                synchronized(lock) { teardownFailure = error }
                                throw error
                            }
                            radio = null
                            val next = factory { event -> enqueueEvent(command.generation, event) }
                            radio = next
                            next.start(command.config)
                            synchronized(lock) {
                                check(!closed && generation == command.generation) { "BLE owner superseded or closed" }
                                mutableState.value = RoomBleState(RoomBlePhase.READY)
                            }
                            command.completion.complete(Unit)
                        } catch (error: Exception) {
                            try { radio?.close() } catch (closeError: Exception) {
                                synchronized(lock) { teardownFailure = closeError }
                            }
                            radio = null
                            fail(command.generation, error.message ?: "Bluetooth start failed")
                            command.completion.completeExceptionally(error)
                        }
                    }
                    is Command.Offer -> {
                        try {
                            val peers = requireNotNull(radio).offer(command.bytes, command.to)
                            synchronized(lock) {
                                if (!closed && generation == command.generation)
                                    mutableState.value = mutableState.value.copy(lastQueuedPeers = peers)
                            }
                        } catch (error: Exception) {
                            fail(command.generation, error.message ?: "Bluetooth offer failed")
                        }
                    }
                    is Command.Event -> when (val event = command.event) {
                        is RoomBleEvent.Frame -> {
                            val listeners = synchronized(lock) {
                                if (mutableState.value.phase == RoomBlePhase.READY) receivers.toList() else emptyList()
                            }
                            // No room callback while holding the host lock.
                            listeners.forEach {
                                try { it(event.bytes.copyOf(), event.from) }
                                catch (_: Exception) { fail(command.generation, "Bluetooth receiver failed") }
                            }
                        }
                        is RoomBleEvent.Status -> synchronized(lock) {
                            if (!closed && generation == command.generation &&
                                mutableState.value.phase == RoomBlePhase.READY) {
                                mutableState.value = mutableState.value.copy(
                                    phase = if (event.running) RoomBlePhase.READY else RoomBlePhase.FAILED,
                                    writablePeers = event.peers.coerceIn(0, 32), error = event.error,
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            try { radio?.close() } catch (error: Exception) {
                synchronized(lock) { teardownFailure = error }
            } finally {
                synchronized(lock) {
                    closed = true; receivers.clear()
                    mutableState.value = RoomBleState(RoomBlePhase.CLOSED)
                }
                commands.close()
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    if (command is Command.Open) command.completion.completeExceptionally(
                        IllegalStateException("BLE owner closed"))
                }
            }
        }
    }

    suspend fun start(config: RoomBleConfig) {
        val epoch = synchronized(lock) {
            check(!closed && teardownFailure == null &&
                mutableState.value.phase in setOf(RoomBlePhase.IDLE, RoomBlePhase.FAILED))
            this.config = config
            generation++
            mutableState.value = RoomBleState(RoomBlePhase.STARTING)
            generation
        }
        open(epoch, config)
    }

    private suspend fun open(epoch: Long, config: RoomBleConfig) {
        val completion = CompletableDeferred<Unit>()
        try {
            commands.send(Command.Open(epoch, config, completion))
            completion.await()
        } catch (cancel: CancellationException) {
            close()
            throw cancel
        }
    }

    override suspend fun resetQueued() {
        val (epoch, current) = synchronized(lock) {
            check(!closed && teardownFailure == null)
            val current = checkNotNull(config) { "Bluetooth has not been started" }
            generation++
            mutableState.value = RoomBleState(RoomBlePhase.RESETTING)
            generation to current
        }
        open(epoch, current)
    }

    override fun offer(bytes: ByteArray, to: String?) = synchronized(lock) {
        check(!closed && mutableState.value.phase == RoomBlePhase.READY) { "Bluetooth is not ready" }
        require(bytes.size in 1..RoomMeshWire.MAX_BYTES)
        require(to == null || (to.isNotBlank() && to.length <= 256))
        check(offeredFrames < 32 && offeredBytes + bytes.size <= 128 * 1024) { "Bluetooth host queue full" }
        val frozen = bytes.copyOf()
        check(commands.trySend(Command.Offer(generation, frozen, to)).isSuccess) { "Bluetooth host queue full" }
        offeredFrames++; offeredBytes += frozen.size
    }

    override fun reachable(): Boolean = synchronized(lock) {
        !closed && mutableState.value.phase == RoomBlePhase.READY && mutableState.value.writablePeers > 0
    }

    override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable = synchronized(lock) {
        check(!closed && receivers.size < 32)
        receivers.add(receive)
        AutoCloseable { synchronized(lock) { receivers.remove(receive) } }
    }

    private fun enqueueEvent(epoch: Long, event: RoomBleEvent) = synchronized(lock) {
        if (closed || epoch != generation) return@synchronized
        val frozen = when (event) {
            is RoomBleEvent.Frame -> {
                if (event.bytes.size !in 1..RoomMeshWire.MAX_BYTES || event.from.isBlank() || event.from.length > 256)
                    return@synchronized
                event.copy(bytes = event.bytes.copyOf())
            }
            is RoomBleEvent.Status -> event
        }
        // Overflow is visible and disables new offers; it cannot grow memory or
        // pretend the Bluetooth path remains usable after dropped control state.
        if (!commands.trySend(Command.Event(epoch, frozen)).isSuccess)
            fail(epoch, "Bluetooth event queue full")
    }

    private fun fail(epoch: Long, reason: String) = synchronized(lock) {
        if (!closed && generation == epoch)
            mutableState.value = RoomBleState(RoomBlePhase.FAILED, error = reason.take(256))
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true; generation++; receivers.clear()
            mutableState.value = RoomBleState(RoomBlePhase.CLOSED)
            commands.close()
        }
    }

    /** Await before another room takes radio ownership. close itself never waits
     * on Android's main thread while the room transport holds its lock. */
    suspend fun awaitClosed() {
        worker.join(); scope.cancel()
        synchronized(lock) { teardownFailure }?.let { throw it }
    }
}
