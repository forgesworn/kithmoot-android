package dev.forgesworn.kithmoot.account

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Carries NIP-55 intents between whoever asks (the view model, the signer) and
 * whichever activity is on screen to start them. It lives in the application,
 * not in the activity, because the activity does not last: a language or font
 * size change recreates it while the signer app is still up, and what was
 * pending in the old one was lost, so the wait never ended.
 *
 * - **Recreation.** A request is pending here. The activity attaches its
 *   launcher whenever it is created and detaches it when it is destroyed; the
 *   result Android redelivers to the new activity lands in [deliver] and
 *   completes the same request. A request made while no activity is attached
 *   waits a few seconds for one, then fails cleanly.
 * - **Time.** Each request waits [timeoutMs] after the signer is opened, no
 *   longer; the wait for an earlier request's turn does not count. Past it the
 *   request fails with a [SignerTimeoutException] and the way is clear for a retry.
 * - **A late result.** Android still delivers one result per launch, however
 *   long the signer takes. A launch that timed out or was cancelled is kept as
 *   abandoned until its result arrives and is then dropped, so it can neither
 *   complete nor spoil the next request. Results are matched by the `id` the
 *   request carried where the signer echoes it, and in launch order otherwise.
 * - **Process death.** Nothing outlives the process, so after one a redelivered
 *   result finds nothing waiting and is dropped; nobody is left hanging.
 *
 * One request at a time, as a signer is one screen.
 */
class SignerRelay(
    private val timeoutMs: Long = SIGNER_INTENT_TIMEOUT_MS,
    private val attachWaitMs: Long = 5_000,
    private val idOf: (Intent) -> String? = { it.getStringExtra("id") },
) : Nip55Bridge {
    private class Launch(val id: String?, val answer: CompletableDeferred<Intent?>) {
        @Volatile var abandonedAt = 0L
        val abandoned: Boolean get() = abandonedAt != 0L
        fun abandon() { abandonedAt = System.nanoTime().coerceAtLeast(1) }
    }

    private class Answered(val intent: Intent?)

    private val turn = Mutex()
    private val lock = Any()
    private val launches = ArrayDeque<Launch>()
    private val launcher = MutableStateFlow<((Intent) -> Unit)?>(null)

    /** The activity on screen can start signer intents. Replaces any earlier one. */
    fun attach(launch: (Intent) -> Unit) { launcher.value = launch }

    /** The activity is going away. Only its own launcher is removed: a newer one stays. */
    fun detach(launch: (Intent) -> Unit) { launcher.compareAndSet(launch, null) }

    /**
     * The signer's result: [ok] says the signer finished with RESULT_OK, [data]
     * is what it sent back, [id] the request id it echoed, if it did. A result
     * with nothing waiting for it is dropped.
     */
    fun deliver(ok: Boolean, data: Intent?, id: String? = data?.let(idOf)) {
        val matched = synchronized(lock) {
            val index = if (id != null) launches.indexOfFirst { it.id == id }.takeIf { it >= 0 } else null
            val at = index ?: if (launches.isNotEmpty()) 0 else return
            // Anything launched before the one answered will not be answered now: forget it.
            repeat(at) { launches.removeFirst() }
            launches.removeFirst()
        }
        if (matched.abandoned) return
        matched.answer.complete(if (ok) data ?: Intent() else null)
    }

    override suspend fun request(intent: Intent): Intent? = turn.withLock {
        val launch = withTimeoutOrNull(attachWaitMs) { launcher.filterNotNull().first() }
            ?: throw SignerException("The screen is not ready to open the signer.")
        val pending = Launch(idOf(intent), CompletableDeferred())
        synchronized(lock) {
            // A result that has not come in ten minutes is not coming; it must not be mistaken for this one's.
            launches.removeAll { it.abandoned && System.nanoTime() - it.abandonedAt > STALE_AFTER_NANOS }
            launches.addLast(pending)
        }
        try {
            launch(intent)
        } catch (e: Exception) {
            synchronized(lock) { launches.remove(pending) }
            throw SignerException("The signer app could not be opened: ${e.message}")
        }
        try {
            val answered = withTimeoutOrNull(timeoutMs) { Answered(pending.answer.await()) }
            if (answered == null) {
                pending.abandon()
                throw SignerTimeoutException(signerDidNotAnswer(null))
            }
            answered.intent
        } catch (e: CancellationException) {
            pending.abandon()
            throw e
        }
    }

    private companion object {
        const val STALE_AFTER_NANOS = 10L * 60 * 1_000_000_000
    }
}
