package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.session.Roles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeakingLineTest {

    private fun person(id: String, self: Boolean = false, name: String? = null) =
        ParticipantTile(id, self, 1, listOf(TileTrack("$id-device", "cam-$id", Roles.CAMERA)), null, false, name = name)

    private fun profile(name: String) = PublicProfile(name, null, 0, "event-$name")

    // --- speakingNames: the line's formatting, no Compose involved -----------

    @Test
    fun `nobody speaking is an empty list`() {
        assertEquals(emptyList(), speakingNames(emptySet(), listOf(person("me", self = true)), emptyMap()))
    }

    @Test
    fun `self speaking alone gives an empty list, never a name or You`() {
        val tiles = listOf(person("me", self = true, name = "Daz"))
        assertEquals(emptyList(), speakingNames(setOf("me"), tiles, mapOf("me" to profile("Daz"))))
    }

    @Test
    fun `a profile name wins over the tile's own name and the npub fallback`() {
        val tiles = listOf(person("npub1abc", name = "fallback"))
        val names = speakingNames(setOf("npub1abc"), tiles, mapOf("npub1abc" to profile("Donkey")))
        assertEquals(listOf("Donkey"), names)
    }

    @Test
    fun `no profile falls back to the tile's own name`() {
        val tiles = listOf(person("npub1abc", name = "Bright Idea"))
        assertEquals(listOf("Bright Idea"), speakingNames(setOf("npub1abc"), tiles, emptyMap()))
    }

    @Test
    fun `no profile and no tile name falls back to a short key`() {
        // A participant id is hex, not npub - shortNpub does the bech32 encoding.
        val hex = "a".repeat(64)
        val tiles = listOf(person(hex))
        val names = speakingNames(setOf(hex), tiles, emptyMap())
        assertEquals(listOf(shortNpub(hex)), names)
    }

    @Test
    fun `order follows the room's tile order, not the speaking set's, and self is left out`() {
        val tiles = listOf(person("me", self = true), person("p1", name = "Daz"), person("p2", name = "Donkey"))
        val names = speakingNames(setOf("p2", "me", "p1"), tiles, emptyMap())
        assertEquals(listOf("Daz", "Donkey"), names)
    }

    @Test
    fun `a speaker no longer in the room is left out`() {
        val tiles = listOf(person("me", self = true), person("p1", name = "Daz"))
        assertEquals(listOf("Daz"), speakingNames(setOf("me", "p1", "left-the-call"), tiles, emptyMap()))
    }

    // --- speakingLine: the label text ------------------------------------------

    @Test
    fun `empty names give an empty line`() {
        assertEquals("", speakingLine(emptyList()))
    }

    @Test
    fun `one name`() {
        assertEquals("Speaking: Daz", speakingLine(listOf("Daz")))
    }

    @Test
    fun `several names are comma separated`() {
        assertEquals("Speaking: Daz, Donkey", speakingLine(listOf("Daz", "Donkey")))
    }

    // --- HeldSpeakers: the ~1s hold that keeps names from flickering -----------

    @Test
    fun `starting to speak is held from the first moment, no delay on entry`() {
        val held = HeldSpeakers(holdMs = 1_000)
        assertEquals(setOf("p1"), held.update(setOf("p1"), setOf("p1"), now = 0))
    }

    @Test
    fun `a brief drop is still held just under the hold window`() {
        val held = HeldSpeakers(holdMs = 1_000)
        held.update(setOf("p1"), setOf("p1"), now = 0)
        val stillHeld = held.update(emptySet(), setOf("p1"), now = 999)
        assertEquals(setOf("p1"), stillHeld)
        assertTrue(held.pending)
    }

    @Test
    fun `a name drops once the hold window has fully elapsed`() {
        val held = HeldSpeakers(holdMs = 1_000)
        held.update(setOf("p1"), setOf("p1"), now = 0)
        val dropped = held.update(emptySet(), setOf("p1"), now = 1_001)
        assertEquals(emptySet(), dropped)
        assertFalse(held.pending)
    }

    @Test
    fun `leaving the call drops the hold immediately, even mid-hold`() {
        val held = HeldSpeakers(holdMs = 1_000)
        held.update(setOf("p1"), setOf("p1"), now = 0)
        val gone = held.update(emptySet(), present = emptySet(), now = 100)
        assertEquals(emptySet(), gone)
    }

    @Test
    fun `still speaking is never marked pending`() {
        val held = HeldSpeakers(holdMs = 1_000)
        held.update(setOf("p1"), setOf("p1"), now = 0)
        held.update(setOf("p1"), setOf("p1"), now = 500)
        assertFalse(held.pending)
    }
}
