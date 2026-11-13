package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.NodeRoomLinkState
import dev.forgesworn.kithmoot.protocol.NodeRooms
import dev.forgesworn.kithmoot.protocol.deriveRoom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeRoomLinkWordsTest {
    private val time: (Long) -> String = { "at-$it" }

    @Test fun `an ended room says so instead of asking to try again`() {
        val words = requireNotNull(nodeRoomLinkWords(NodeRoomLinkState.Ended(1_900_000_000), time))
        assertTrue(words.startsWith("This room has ended."))
        assertTrue("at-1900000000" in words)
        assertTrue("nobody can join it any more" in words)
        assertTrue("Try again" !in words && "try again" !in words)
    }

    @Test fun `a link made ahead of the phone's clock asks for automatic time`() {
        val words = requireNotNull(nodeRoomLinkWords(NodeRoomLinkState.Early(1_900_000_000), time))
        assertTrue(words.startsWith("This phone’s clock is behind."))
        assertTrue("at-1900000000" in words)
        assertTrue("Set the date and time to update automatically" in words)
    }

    @Test fun `an open or broken link keeps its usual handling`() {
        assertNull(nodeRoomLinkWords(null, time))
        val now = 1_900_000_000L
        val event = NodeRooms.sign(Entropy.bytes(32), deriveRoom(Entropy.bytes(32)).roomId, "https://node.example",
            Schnorr.publicKeyHex(Entropy.bytes(32)), now, now + 3600).toJson()
        val short = NodeRooms.encodeShortLink("https://kithmoot.app/j/", event, now)
        assertNull(nodeRoomLinkWords(NodeRooms.inspectLink(short, now), time))
        assertEquals("This room has ended.", nodeRoomLinkWords(NodeRooms.inspectLink(short, now + 3600), time)?.substringBefore(" It"))
    }
}
