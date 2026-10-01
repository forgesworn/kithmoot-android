package dev.forgesworn.kithmoot.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReachabilityTest {
    private val untitledTest = AtRiskRoom("a".repeat(64), "Untitled test")
    private val standup = AtRiskRoom("b".repeat(64), "Standup")

    @Test
    fun `the notification says what is wrong and what to do`() {
        val one = reachabilityNoticeText(listOf("Untitled test"))
        assertEquals("You can't answer KithMoot calls yet", one.title)
        assertEquals("Tap to confirm with your signer so you can answer calls in Untitled test.", one.text)
        assertEquals(
            "Tap to confirm with your signer so you can answer calls in 2 rooms.",
            reachabilityNoticeText(listOf("Untitled test", "Standup")).text,
        )
    }

    @Test
    fun `a room with no name reads as Untitled room, as it does on the rooms list`() {
        assertEquals("Untitled room", AtRiskRoom.of("c".repeat(64), "").name)
        assertEquals("Untitled room", AtRiskRoom.of("c".repeat(64), "Room cccccccc").name)
        assertEquals("Standup", AtRiskRoom.of("c".repeat(64), "Standup").name)
    }

    @Test
    fun `the banner names the room and the signer app`() {
        val banner = reachabilityBanner(listOf(untitledTest), "My Signet")!!
        assertEquals("You can't answer calls in Untitled test yet.", banner.message)
        assertEquals("My Signet has to confirm this phone again.", banner.detail)
        assertEquals("Confirm with My Signet", banner.action)
    }

    @Test
    fun `the banner counts the rooms when there are several`() {
        val banner = reachabilityBanner(listOf(untitledTest, standup), "My Signet")!!
        assertEquals("You can't answer calls in 2 rooms yet.", banner.message)
    }

    @Test
    fun `without a signer name it says your signer, never a package name`() {
        assertEquals("Confirm with your signer", reachabilityBanner(listOf(untitledTest), null)!!.action)
        assertEquals("Confirm with your signer", reachabilityBanner(listOf(untitledTest), "  ")!!.action)
        assertEquals("Confirm with your signer", reachabilityBanner(listOf(untitledTest), "com.getsignet.app")!!.action)
        assertEquals("Your signer has to confirm this phone again.", reachabilityBanner(listOf(untitledTest), null)!!.detail)
        assertEquals("your signer", signerDisplayName(null))
        assertEquals("Amber", signerDisplayName("Amber"))
    }

    @Test
    fun `no banner while nothing is at risk`() {
        assertNull(reachabilityBanner(emptyList(), "My Signet"))
        assertNull(reachabilityBanner(emptyList(), "My Signet", inRoom = untitledTest.id))
    }

    @Test
    fun `inside a room the banner appears only for a room that cannot ring, and names just that room`() {
        val atRisk = listOf(untitledTest, standup)
        assertEquals("You can't answer calls in Standup yet.", reachabilityBanner(atRisk, "My Signet", inRoom = standup.id)!!.message)
        assertNull(reachabilityBanner(listOf(untitledTest), "My Signet", inRoom = standup.id))
        assertTrue(reachabilityBanner(atRisk, null, inRoom = untitledTest.id) != null)
    }
}
