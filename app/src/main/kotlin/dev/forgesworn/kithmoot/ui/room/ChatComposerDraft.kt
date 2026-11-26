package dev.forgesworn.kithmoot.ui.room

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.forgesworn.kithmoot.session.PendingChatOutbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A room owns its composer even when the chat pane leaves the composition. */
class ChatComposerDraft internal constructor(
    private val draft: PendingChatOutbox.Draft,
    private val onSaveFailure: (ChatComposerDraft, Boolean) -> Unit,
) {
    var value by mutableStateOf(TextFieldValue(draft.text, TextRange(draft.text.length)))
        private set
    private val lock = Any()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val writes = Mutex()
    private data class Edit(val text: String, val revision: Long)
    @Volatile private var latest = Edit(draft.text, 0)
    private var savedRevision = 0L
    private var closed = false

    // Finish the last edit even if the room's session is cancelled on departure.
    private val writer = CoroutineScope(Dispatchers.IO).launch {
        for (update in updates) {
            try {
                flush()
                onSaveFailure(this@ChatComposerDraft, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onSaveFailure(this@ChatComposerDraft, true)
            }
        }
    }

    internal fun edit(next: TextFieldValue) = synchronized(lock) {
        if (closed) return@synchronized
        value = next
        if (next.text != latest.text) {
            latest = Edit(next.text, latest.revision + 1)
            updates.trySend(Unit)
        }
    }

    internal suspend fun flush() = writes.withLock {
        val edit = latest
        if (edit.revision != savedRevision) {
            draft.save(edit.text)
            savedRevision = edit.revision
        }
    }

    internal fun close() = synchronized(lock) {
        closed = true
        updates.close()
        writer
    }
}
