package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.relay.RelayGate
import dev.forgesworn.kithmoot.relay.RelayRefusalKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpryControlRefusalTest {
    private fun frame(text: String) = Json.parseToJsonElement(text).jsonArray

    @Test fun `a rate limit in a control socket is read whatever frame carries it`() {
        assertEquals(RelayRefusalKind.RATE_LIMITED, spryRefusal(frame("""["OK","abc",false,"rate-limited: slow down"]""")))
        assertEquals(RelayRefusalKind.RATE_LIMITED, spryRefusal(frame("""["OK","abc",false,"ERROR: rate-limited: slow down"]""")))
        assertEquals(RelayRefusalKind.RATE_LIMITED, spryRefusal(frame("""["CLOSED","spry-reply","rate-limited: too many REQs"]""")))
        assertEquals(RelayRefusalKind.RATE_LIMITED, spryRefusal(frame("""["NOTICE","too many requests"]""")))
        assertEquals(RelayRefusalKind.STOP, spryRefusal(frame("""["OK","abc",false,"blocked: no"]""")))
    }

    @Test fun `an accepted write, a challenge the key answers and ordinary frames are not refusals`() {
        assertNull(spryRefusal(frame("""["OK","abc",true,""]""")))
        assertNull(spryRefusal(frame("""["CLOSED","spry-reply","auth-required: sign in"]""")))
        assertNull(spryRefusal(frame("""["EOSE","spry-reply"]""")))
        assertNull(spryRefusal(frame("""["NOTICE","welcome"]""")))
    }

    @Test fun `control requests are spaced to one a second per relay`() {
        val gate = RelayGate()
        val waits = List(4) { gate.reserveTurn("wss://relay.example", 1_000_000) }
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L), waits)
    }
}
