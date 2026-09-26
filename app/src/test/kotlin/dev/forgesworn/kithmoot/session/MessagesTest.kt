package dev.forgesworn.kithmoot.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mention reader against the reference implementation's test table
 * (`src/messages.test.ts`, the `mentions` describe block), ported alongside
 * `ROOM_MENTION_PATTERN` itself so Android recognises `@all` / `@everyone`
 * the same way the web client and `kithmoot-agent` do.
 */
class MessagesTest {
    private val ADA = "a".repeat(64)
    private val ROWAN = "b".repeat(64)
    private val TALLY = "c".repeat(64)

    private fun msg(text: String, mentions: List<String>? = null) =
        ChatMessage(id = "m1", participant = ADA, device = ADA, body = text, sentAt = 0, mentions = mentions)

    @Test
    fun `names as a whole word, with or without the at sign`() {
        assertTrue(namesInText("morning @Ada", "Ada"))
        assertTrue(namesInText("Ada, are you there", "Ada"))
        assertFalse(namesInText("Adam is here", "Ada"))
        assertFalse(namesInText("madame", "Ada"))
        assertFalse(namesInText("anything", "  "))
    }

    @Test
    fun `reads the wire field when there is one, and the text when there is not`() {
        val roster = listOf(Named(ADA, "Ada"), Named(ROWAN, "Rowan"))
        assertEquals(listOf(ADA), mentionsOf(msg("@Rowan look", listOf(ADA)), roster))
        assertEquals(listOf(ROWAN), mentionsOf(msg("@Rowan look"), roster))
        assertEquals(emptyList(), mentionsOf(msg("nobody"), roster))
    }

    @Test
    fun `a room call addresses people and agents`() {
        assertTrue(mentionedBy(msg("hi", listOf(EVERYONE)), ADA))
        assertTrue(mentionedBy(msg("hi", listOf(EVERYONE)), TALLY, agent = true))
        assertTrue(mentionedBy(msg("hi", listOf(TALLY)), TALLY, agent = true))
        assertTrue(mentionedBy(msg("@Tally do it"), TALLY, listOf(Named(TALLY, "Tally")), agent = true))
    }

    @Test
    fun `recognises explicit room calls without broadcasting prose, names or email addresses`() {
        for (text in listOf("@all help", "Hello @ALL!", "@everyone, please look", "Thanks @all.")) {
            assertEquals(listOf(EVERYONE), mentionsOf(msg(text)))
            assertTrue(mentionedBy(msg(text), TALLY, agent = true))
        }
        for (text in listOf(
            "all done", "everyone is here", "@allison", "mail@all.example", "mail+@all.example",
            "@all.example", "@all-team", "@everyone_else",
        )) {
            assertEquals(emptyList<String>(), mentionsOf(msg(text)))
        }
        assertEquals(listOf(ADA), mentionsOf(msg("@all", listOf(ADA))))
        assertEquals(
            listOf(EVERYONE, TALLY),
            mentionsOf(msg("@all"), listOf(Named(ADA, "Ada"), Named(TALLY, "Tally", agent = true))),
        )
        assertFalse(mentionedBy(msg("@all", emptyList()), TALLY, agent = true))

        val roster = (0 until 40).map { Named(it.toString(16).padStart(64, '0'), "Person$it") }
        val text = roster.joinToString(" ") { "@${it.name}" } + " @all"
        assertTrue(EVERYONE in mentionsOf(msg(text), roster).take(32))
    }
}
