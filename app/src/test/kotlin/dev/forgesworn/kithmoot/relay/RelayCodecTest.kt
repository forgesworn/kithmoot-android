package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelayCodecTest {

    private val event = NostrEvent(
        kind = 20461,
        createdAt = 1799995000,
        tags = listOf(listOf("d", "room")),
        content = "opaque",
        pubkey = "aa".repeat(32),
        id = "bb".repeat(32),
        sig = "cc".repeat(64),
    )

    @Test
    fun `a publish frame is an EVENT array`() {
        val frame = RelayCodec.publishFrame(event)
        assertTrue(frame.startsWith("""["EVENT",{"kind":20461"""), frame)
    }

    @Test
    fun `a request frame carries every filter`() {
        val frame = RelayCodec.requestFrame(
            "sub-1",
            listOf(Filter(kinds = listOf(20461), tags = mapOf("#d" to listOf("room")))),
        )
        assertEquals("""["REQ","sub-1",{"kinds":[20461],"#d":["room"]}]""", frame)
    }

    @Test
    fun `a close frame names the subscription`() {
        assertEquals("""["CLOSE","sub-1"]""", RelayCodec.closeFrame("sub-1"))
    }

    @Test
    fun `an EVENT frame round-trips`() {
        val parsed = RelayCodec.parse("""["EVENT","sub-1",${event.toCompactJson()}]""")
        val message = assertIs<RelayMessage.Event>(parsed)
        assertEquals("sub-1", message.subscriptionId)
        assertEquals(event, message.event)
    }

    @Test
    fun `OK EOSE CLOSED and NOTICE all parse`() {
        assertIs<RelayMessage.Ok>(RelayCodec.parse("""["OK","abc",true,""]"""))
        assertIs<RelayMessage.EndOfStoredEvents>(RelayCodec.parse("""["EOSE","sub-1"]"""))
        assertIs<RelayMessage.Closed>(RelayCodec.parse("""["CLOSED","sub-1","rate-limited"]"""))
        assertIs<RelayMessage.Notice>(RelayCodec.parse("""["NOTICE","slow down"]"""))
    }

    @Test
    fun `nothing a relay can send makes the codec throw`() {
        // A relay is an untrusted stranger. One malformed frame must not take
        // down the socket carrying everybody else's presence.
        val hostile = listOf(
            "",
            "not json",
            "{}",
            "[]",
            """["EVENT"]""",
            """["EVENT","sub-1"]""",
            """["EVENT","sub-1",{"kind":"not a number"}]""",
            """["OK","abc","not a bool",""]""",
            """[null,null,null]""",
            """["MYSTERY","what"]""",
        )
        for (frame in hostile) {
            assertIs<RelayMessage>(RelayCodec.parse(frame), "parsing <$frame> should not throw")
        }
    }
}
