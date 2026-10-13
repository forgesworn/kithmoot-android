package dev.forgesworn.kithmoot.relay

import kotlinx.serialization.json.*
import kotlin.test.*

class QuietLinkRelaySocketTest {
    private class Child(val listener: RelaySocketListener) : RelaySocket {
        val sent = mutableListOf<JsonArray>()
        var closes = 0
        override fun send(text: String) { sent += Json.parseToJsonElement(text).jsonArray }
        override fun close() { closes++; listener.onClosed("closed") }
        fun receive(text: String) = listener.onMessage(text)
        fun wire() = sent.last { it[0].jsonPrimitive.content == "REQ" }[1].jsonPrimitive.content
    }
    private class Fixture {
        val url = LinkRelayAddress.canonicalForNode("00".repeat(32))
        val children = linkedMapOf<String, Child>()
        val messages = mutableListOf<JsonArray>()
        var active: String? = "original-route"
        var opens = 0
        var closes = 0
        val socket = QuietLinkRelaySocket(url, "original-route", LinkRelaySocketFactory { endpoint, route, listener ->
            assertEquals("original-route", route)
            Child(listener).also { children[endpoint] = it; listener.onOpen() }
        }, ActiveLinkRoute { requested -> assertEquals(url, requested); active }, object : RelaySocketListener {
            override fun onOpen() { opens++ }
            override fun onMessage(text: String) { messages += Json.parseToJsonElement(text).jsonArray }
            override fun onClosed(reason: String) { closes++ }
        }).start()
        val events get() = children.getValue(url)
        val drops get() = children.getValue(url.removeSuffix("/events") + "/drops")
        fun send(text: String) = socket.send(text)
        fun event(wire: String, recipient: String, author: String = "author", timestamp: Long = 10): String =
            """["EVENT","$wire",{"id":"abc","pubkey":"$author","created_at":$timestamp,"kind":1059,"tags":[["p","$recipient"]],"content":"encrypted","sig":"signature"}]"""
    }

    @Test fun `broadcast drop requests retain recipient author and time filtering locally`() {
        val f = Fixture()
        assertEquals(1, f.opens)
        assertEquals(2, f.children.size)
        f.send("""["REQ","chat",{"kinds":[1059],"#p":["recipient"],"authors":["author"],"since":5,"until":15,"limit":50}]""")
        val outward = f.drops.sent.single()[2].jsonObject
        assertFalse("#p" in outward)
        assertFalse("authors" in outward)
        assertEquals(50, outward.getValue("limit").jsonPrimitive.int)
        assertTrue(f.events.sent.isEmpty())
        val wire = f.drops.wire()
        f.drops.receive(f.event(wire, "other"))
        f.drops.receive(f.event(wire, "recipient", "other"))
        f.drops.receive(f.event(wire, "recipient", timestamp = 16))
        assertTrue(f.messages.isEmpty())
        f.drops.receive(f.event(wire, "recipient"))
        assertEquals("chat", f.messages.single()[1].jsonPrimitive.content)
    }

    @Test fun `mixed subscriptions finish once after both endpoints and reject stale wire ids`() {
        val f = Fixture()
        val req = """["REQ","mixed",{"kinds":[1059,1464]}]"""
        f.send(req)
        val old = f.drops.wire()
        f.events.receive("""["EOSE","${f.events.wire()}"]""")
        assertTrue(f.messages.isEmpty())
        f.drops.receive("""["EOSE","$old"]""")
        f.drops.receive("""["EOSE","$old"]""")
        assertEquals(1, f.messages.size)
        f.send(req)
        f.drops.receive(f.event(old, "anyone"))
        assertEquals(1, f.messages.size)
        f.send("""["CLOSE","mixed"]""")
        f.drops.receive(f.event(f.drops.wire(), "anyone"))
        assertEquals(1, f.messages.size)
        assertEquals("CLOSE", f.events.sent.last()[0].jsonPrimitive.content)
        assertEquals("CLOSE", f.drops.sent.last()[0].jsonPrimitive.content)
    }

    @Test fun `publishes select the endpoint and a changed consent closes both`() {
        val f = Fixture()
        f.send("""["EVENT",{"kind":1059}]""")
        f.send("""["EVENT",{"kind":1464}]""")
        assertEquals(1059, f.drops.sent.single()[1].jsonObject.getValue("kind").jsonPrimitive.int)
        assertEquals(1464, f.events.sent.single()[1].jsonObject.getValue("kind").jsonPrimitive.int)
        f.active = "replacement-route"
        assertFailsWith<IllegalStateException> { f.send("""["AUTH",{}]""") }
        f.socket.close()
        assertEquals(1, f.closes)
        assertTrue(f.children.values.all { it.closes == 1 })
    }

    @Test fun `a failed child closes its sibling without reporting a second closure`() {
        val f = Fixture()
        f.drops.listener.onClosed("connection failed")
        f.events.listener.onClosed("connection failed")
        assertEquals(1, f.closes)
        assertTrue(f.children.values.all { it.closes == 1 })
    }

    @Test fun `revoked consent prevents incoming delivery`() {
        val f = Fixture()
        f.send("""["REQ","chat",{"kinds":[1059]}]""")
        val wire = f.drops.wire()
        f.active = null
        f.drops.receive(f.event(wire, "recipient"))
        assertTrue(f.messages.isEmpty())
        assertEquals(1, f.closes)
    }

    @Test fun `kindless requests also select gift wraps and malformed frames are ignored`() {
        val f = Fixture()
        f.send("""["REQ","all",{"since":5}]""")
        assertEquals(1059, f.drops.sent.single()[2].jsonObject.getValue("kinds").jsonArray.single().jsonPrimitive.int)
        assertEquals(1, f.events.sent.size)
        f.drops.receive("""[{},"broken"]""")
        f.drops.receive("""["EVENT",{},{}]""")
        f.drops.receive(f.event(f.drops.wire(), "recipient"))
        assertEquals(1, f.messages.size)
        assertEquals("all", f.messages.single()[1].jsonPrimitive.content)
    }

    @Test fun `invalid replacement filters leave the original subscription active`() {
        val f = Fixture()
        f.send("""["REQ","chat",{"kinds":[1059]}]""")
        val original = f.drops.wire()
        assertFails { f.send("""["REQ","chat",{"kinds":[1059],"#p":3}]""") }
        f.drops.receive(f.event(original, "recipient"))
        assertEquals(1, f.messages.size)
        assertEquals(1, f.drops.sent.size)
    }

    @Test fun `opening failure never reports a logical open and closes the first child`() {
        val url = LinkRelayAddress.canonicalForNode("00".repeat(32))
        var child: Child? = null
        var opens = 0
        var closes = 0
        assertFailsWith<IllegalStateException> {
            QuietLinkRelaySocket(url, "original", LinkRelaySocketFactory { endpoint, _, listener ->
                if (endpoint.endsWith("/drops")) error("connection failed")
                Child(listener).also { child = it; listener.onOpen() }
            }, ActiveLinkRoute { "original" }, object : RelaySocketListener {
                override fun onOpen() { opens++ }
                override fun onMessage(text: String) = Unit
                override fun onClosed(reason: String) { closes++ }
            }).start()
        }
        assertEquals(0, opens)
        assertEquals(1, closes)
        assertEquals(1, child!!.closes)
    }

    @Test fun `authentication arriving during opening is delivered after both children open`() {
        val url = LinkRelayAddress.canonicalForNode("00".repeat(32))
        val order = mutableListOf<String>()
        val socket = QuietLinkRelaySocket(url, "original", LinkRelaySocketFactory { endpoint, _, listener ->
            Child(listener).also {
                if (endpoint.endsWith("/events")) listener.onMessage("""["AUTH","challenge"]""")
                listener.onOpen()
            }
        }, ActiveLinkRoute { "original" }, object : RelaySocketListener {
            override fun onOpen() { order += "open" }
            override fun onMessage(text: String) { order += text }
            override fun onClosed(reason: String) = Unit
        }).start()
        assertEquals(listOf("open", """["AUTH","challenge"]"""), order)
        socket.close()
    }
}
