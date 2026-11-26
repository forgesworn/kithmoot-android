package dev.forgesworn.kithmoot.ui.room

import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.text.input.TextFieldValue
import dev.forgesworn.kithmoot.session.PendingChatOutbox

/** Longest draft kept across a restart or a recreated screen. */
internal const val MAX_SAVED_DRAFT_CHARS: Int = PendingChatOutbox.MAX_DRAFT_CHARS

/**
 * The composer takes a paste of any length, but the saved state of a screen is
 * limited to about a megabyte for the whole app, and past it saving fails
 * rather than trims. A draft too long to keep is left out, not allowed to take
 * the screen down with it.
 */
internal val boundedDraftSaver: Saver<TextFieldValue, Any> = Saver(
    save = { draft -> with(TextFieldValue.Saver) { save(if (draft.text.length <= MAX_SAVED_DRAFT_CHARS) draft else TextFieldValue("")) } },
    restore = { saved -> TextFieldValue.Saver.restore(saved) },
)
