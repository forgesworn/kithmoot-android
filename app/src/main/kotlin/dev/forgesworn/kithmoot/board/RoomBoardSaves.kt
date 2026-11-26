package dev.forgesworn.kithmoot.board

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Export board's file, waiting for the person to say where it goes. [id] tells one wait from the next. */
class BoardSave internal constructor(val id: String, val name: String, internal val bytes: ByteArray) {
    internal val answer = CompletableDeferred<String?>()
    internal val placed = AtomicBoolean(false)
}

/**
 * The board's exports on their way to wherever the person keeps them. The board's page has no downloads, so the
 * screen asks with the system's own save screen, which gives back a [Place] (a document's address) or nothing. One
 * export waits at a time, and every one is answered: the board's Export dialog waits for it.
 */
class RoomBoardSaves<Place : Any>(
    private val scope: CoroutineScope,
    /** Writes the bytes to the place the person chose. False when it could not. */
    private val write: suspend (Place, ByteArray) -> Boolean,
    /** Takes away a place made for an export that was then not written there. */
    private val discard: suspend (Place) -> Unit,
) {
    private val _pending = MutableStateFlow<BoardSave?>(null)
    /** The export waiting for the person, for the screen to ask about. */
    val pending: StateFlow<BoardSave?> = _pending

    /** Hand [bytes] to the person to keep, suggested as [name]: null once kept, else the words the board shows. */
    suspend fun keep(name: String, bytes: ByteArray): String? {
        val save = BoardSave(UUID.randomUUID().toString(), name, bytes)
        if (!_pending.compareAndSet(null, save)) return "Another export is waiting to be saved."
        return try { save.answer.await() } finally { _pending.compareAndSet(save, null) }
    }

    /** Where the person chose to keep the export that is waiting, or null when they chose nowhere. */
    fun chosen(place: Place?) {
        val save = _pending.value
        if (save == null || save.answer.isCompleted || !save.placed.compareAndSet(false, true)) {
            // Chosen for an export that has gone meanwhile: the empty file the save screen made is not left behind.
            if (place != null) scope.launch { runCatching { discard(place) } }
            return
        }
        if (place == null) { save.answer.complete(NOT_CHOSEN); return }
        scope.launch {
            val written = try { write(place, save.bytes) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (!written) runCatching { discard(place) }
            save.answer.complete(if (written) null else NOT_WRITTEN)
        }
    }

    /** This phone has no save screen to ask the person with. */
    fun unavailable() { _pending.value?.answer?.complete(NO_SCREEN) }

    /** The board has gone: an export still waiting is not kept. */
    fun drop() { _pending.value?.answer?.complete(DROPPED) }

    companion object {
        const val NOT_CHOSEN = "Not saved: no place was chosen."
        const val NOT_WRITTEN = "This export could not be written there. Nothing was saved."
        const val NO_SCREEN = "This phone has no app to save files with."
        const val DROPPED = "The board was closed. Nothing was saved."
    }
}
