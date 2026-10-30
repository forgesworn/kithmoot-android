package dev.forgesworn.kithmoot.callrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayNodeLineTest {
    private val key = "a".repeat(64)
    private val endpoint = "spry://$key?relay=wss%3A%2F%2Frelay.example.com%2F"
    private val pairing = "A".repeat(43)
    private val urls = """["turn:203.0.113.9:51000?transport=udp","turn:203.0.113.9:51001?transport=tcp"]"""

    private fun ready(endpoint: String = this.endpoint, pairing: String = this.pairing, urls: String = this.urls, map: String = "[[41002,51002]]") =
        """{"v":1,"event":"ready","endpoint":"$endpoint","pairing":"$pairing","urls":$urls,"relayPortMap":$map}"""

    @Test fun `ready carries the endpoint, pairing, URLs and port map`() {
        val event = RelayNodeLine.parse(ready()) as RelayNodeEvent.Ready
        assertEquals(endpoint, event.endpoint)
        assertEquals(pairing, event.pairing)
        assertEquals(2, event.urls.size)
        assertEquals(listOf(41002 to 51002), event.relayPortMap)
    }

    @Test fun `ready and urls never show the address in their text`() {
        val event = RelayNodeLine.parse(ready())!!
        assertFalse(event.toString().contains("203.0.113.9"))
        val urlsLine = RelayNodeLine.parse("""{"v":1,"event":"urls","urls":$urls,"relayPortMap":[]}""")!!
        assertFalse(urlsLine.toString().contains("203.0.113.9"))
    }

    @Test fun `ready with a bad endpoint, pairing, URL or port is ignored`() {
        assertNull(RelayNodeLine.parse(ready(endpoint = "https://relay.example.com/")))
        assertNull(RelayNodeLine.parse(ready(endpoint = "spry://$key?relay=ws%3A%2F%2Frelay.example.com%2F")))
        assertNull(RelayNodeLine.parse(ready(pairing = "short")))
        assertNull(RelayNodeLine.parse(ready(urls = """["turns:203.0.113.9:51000?transport=tcp"]""")))
        assertNull(RelayNodeLine.parse(ready(urls = """["turn:203.0.113.9:80?transport=udp"]""")))
        assertNull(RelayNodeLine.parse(ready(urls = """["turn:203.0.113.9:51000?transport=udp","turn:203.0.113.9:51001?transport=tcp","turn:203.0.113.9:51002?transport=tcp"]""")))
        assertNull(RelayNodeLine.parse(ready(urls = "[]")))
        assertNull(RelayNodeLine.parse(ready(map = "[[80,51002]]")))
        assertNull(RelayNodeLine.parse(ready(map = "[[41002]]")))
    }

    @Test fun `status counts and nothing else`() {
        assertEquals(RelayNodeEvent.Status(2, 412000, 1800000000),
            RelayNodeLine.parse("""{"v":1,"event":"status","allocations":2,"bytesRelayed":412000,"lastAllocation":1800000000}"""))
        assertEquals(RelayNodeEvent.Status(0, 0, null),
            RelayNodeLine.parse("""{"v":1,"event":"status","allocations":0,"bytesRelayed":0,"lastAllocation":null}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"status","allocations":"2","bytesRelayed":0,"lastAllocation":null}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"status","allocations":-1,"bytesRelayed":0,"lastAllocation":null}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"status","allocations":1.5,"bytesRelayed":0,"lastAllocation":null}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"status","allocations":0,"bytesRelayed":0}"""))
        // Beyond 2^53 - 1, as the desktop's safe-integer rule.
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"status","allocations":0,"bytesRelayed":18446744073709551615,"lastAllocation":null}"""))
    }

    @Test fun `control and portmap lines`() {
        assertEquals(RelayNodeEvent.Control(1), RelayNodeLine.parse("""{"v":1,"event":"control","relays":1}"""))
        assertEquals(RelayNodeEvent.PortmapCounts(false, 10, 0), RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"cleaned","removed":10,"remaining":0}"""))
        assertEquals(RelayNodeEvent.PortmapCounts(true, 10, 1), RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"released","removed":10,"remaining":1}"""))
        assertEquals(RelayNodeEvent.PortmapMapped(false, "pcp", 8, 8, false, null),
            RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"mapped","method":"pcp","allocationPorts":8,"requested":8,"permanent":false}"""))
        assertEquals(RelayNodeEvent.PortmapMapped(true, "upnp", 7, 8, true, "ports"),
            RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"changed","method":"upnp","allocationPorts":7,"requested":8,"permanent":true,"reason":"ports"}"""))
        assertEquals(RelayNodeEvent.PortmapRestored(2), RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"restored","restored":2}"""))
        assertEquals(RelayNodeEvent.PortmapEnded(false, "carrier-grade-nat"), RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"failed","reason":"carrier-grade-nat"}"""))
        assertEquals(RelayNodeEvent.PortmapEnded(true, "expired"), RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"lost","reason":"expired"}"""))
    }

    @Test fun `malformed portmap lines are ignored`() {
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"failed","reason":"something-else"}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"mapped","method":"igd","allocationPorts":8,"requested":8,"permanent":false}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"mapped","method":"pcp","allocationPorts":8,"requested":8,"permanent":"false"}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"changed","method":"pcp","allocationPorts":8,"requested":8,"permanent":false}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"portmap","state":"opened"}"""))
    }

    @Test fun `stopped carries the exit code and any refusal`() {
        assertEquals(RelayNodeEvent.Stopped(0, null), RelayNodeLine.parse("""{"v":1,"event":"stopped","code":0}"""))
        assertEquals(RelayNodeEvent.Stopped(3, null), RelayNodeLine.parse("""{"v":1,"event":"stopped","code":3}"""))
        assertEquals(RelayNodeEvent.Stopped(1, "--bind must be the concrete local IPv4 address of the hosting interface"),
            RelayNodeLine.parse("""{"v":1,"event":"stopped","code":1,"refusal":"--bind must be the concrete local IPv4 address of the hosting interface"}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"stopped","code":2}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"stopped","code":1,"refusal":7}"""))
    }

    @Test fun `anything else is ignored`() {
        assertNull(RelayNodeLine.parse("""{"v":2,"event":"control","relays":1}"""))
        assertNull(RelayNodeLine.parse("""{"v":"1","event":"control","relays":1}"""))
        assertNull(RelayNodeLine.parse("""{"v":1,"event":"hello"}"""))
        assertNull(RelayNodeLine.parse("""[1,2]"""))
        assertNull(RelayNodeLine.parse("not json"))
        assertNull(RelayNodeLine.parse(""))
        val long = """{"v":1,"event":"control","relays":1,"pad":"${"x".repeat(RelayNodeLine.LINE_LIMIT)}"}"""
        assertNull(RelayNodeLine.parse(long))
        assertNull(RelayNodeLine.parse(long.toByteArray()))
        assertTrue(RelayNodeLine.parse("""{"v":1,"event":"control","relays":0}""".toByteArray()) is RelayNodeEvent.Control)
    }
}
