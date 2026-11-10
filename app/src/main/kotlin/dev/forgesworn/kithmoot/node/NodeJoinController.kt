package dev.forgesworn.kithmoot.node

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An ask refused before anything was sent, with the words the person sees. */
internal class NodeJoinRefusal(message: String) : Exception(message)

/** Owns one reviewed admission request, independently of any open room session.
 * A request made beside a room may save admission, but never opens that room.
 * Account/network changes and cancelled or replaced requests retire callbacks. */
internal class NodeJoinController(private val scope: CoroutineScope, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val state = MutableStateFlow<NodeJoinView?>(null)
    val view = state.asStateFlow()
    private val lock = Any()
    @Volatile private var token: Any? = null
    @Volatile private var job: Job? = null

    fun offer(offer: NodeJoinView) {
        cancel()
        synchronized(lock) { state.value = offer }
    }

    fun cancel() {
        val previous = synchronized(lock) {
            token = null
            state.value = null
            job.also { job = null }
        }
        previous?.cancel()
    }

    fun <T> request(openAfterAdmission: Boolean, allowed: () -> Boolean,
        enter: (suspend () -> Unit) -> Unit,
        guard: (Job) -> Unit = {},
        ask: suspend (NodeJoinView, () -> Boolean, (Long) -> Unit) -> T,
        admitted: suspend (T, Boolean) -> Unit): Boolean {
        val ticket = Any()
        val offer = synchronized(lock) {
            val held = state.value ?: return false
            if (held.busy) return false
            if (!allowed()) {
                state.value = held.copy(message = "Your account or connection changed. Check it before asking again.")
                return false
            }
            token = ticket
            state.value = held.copy(busy = true, message = null)
            held
        }
        fun current() = token === ticket && allowed() && job?.isActive != false
        val run: suspend () -> Unit = work@ {
            val running = currentCoroutineContext()[Job]
            synchronized(lock) {
                if (token !== ticket) return@work
                job = running
            }
            try {
                if (!openAfterAdmission) running?.let(guard)
                currentCoroutineContext().ensureActive()
                check(current())
                val result = ask(offer, ::current) { deadline ->
                    synchronized(lock) {
                        if (current()) state.value = offer.copy(busy = true, waitingUntil = deadline, message = null)
                    }
                }
                currentCoroutineContext().ensureActive()
                check(current())
                admitted(result, openAfterAdmission)
                synchronized(lock) { if (token === ticket) { token = null; state.value = null } }
            } catch (cancelled: CancellationException) {
                synchronized(lock) { if (token === ticket) {
                    token = null
                    state.value = offer.copy(message = "The request was cancelled. Check your account and connection, then ask again.")
                } }
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    if (current()) {
                        token = null
                        state.value = offer.copy(message = (error as? NodeJoinRefusal)?.message
                            ?: "The room could not be opened. Ask again when the keeper is available.")
                    }
                    else if (token === ticket) { token = null; state.value = null }
                }
                // Start-screen entry owns any partially opened session's cleanup.
                if (openAfterAdmission) throw error
            } finally { synchronized(lock) { if (job === running) job = null } }
        }
        if (openAfterAdmission) enter(run)
        else scope.launch(dispatcher, start = CoroutineStart.LAZY) { run() }.also { pending ->
            synchronized(lock) { if (token === ticket) job = pending else pending.cancel() }
            pending.start()
        }
        return true
    }
}
