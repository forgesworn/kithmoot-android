package dev.forgesworn.kithmoot.ui.room

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DraftSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(draft: TextFieldValue): TextFieldValue {
        val saved = with(boundedDraftSaver) { scope.save(draft) }
        return boundedDraftSaver.restore(checkNotNull(saved))!!
    }

    @Test fun anOrdinaryDraftSurvivesARestartWithItsCursor() {
        val restored = roundTrip(TextFieldValue("hello there", TextRange(5)))
        assertEquals("hello there", restored.text)
        assertEquals(TextRange(5), restored.selection)
    }

    @Test fun aDraftAtTheLimitIsStillKept() {
        val draft = "x".repeat(MAX_SAVED_DRAFT_CHARS)
        assertEquals(draft, roundTrip(TextFieldValue(draft)).text)
    }

    @Test fun aPasteTooLongToSaveIsLeftOutRatherThanBreakingTheSavedState() {
        assertEquals("", roundTrip(TextFieldValue("x".repeat(MAX_SAVED_DRAFT_CHARS + 1))).text)
    }
}
