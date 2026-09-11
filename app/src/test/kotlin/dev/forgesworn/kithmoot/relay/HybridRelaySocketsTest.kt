package dev.forgesworn.kithmoot.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HybridRelaySocketsTest {
    private val node = "a".repeat(51) + "q"
    private val url = "ws://$node/events"
    private val listener = object : RelaySocketListener {
        override fun onOpen() = Unit
        override fun onMessage(text: String) = Unit
        override fun onClosed(reason: String) = Unit
    }

    @Test fun `node ids become canonical Link relay URLs`() {
        assertEquals("ws://${"a".repeat(52)}/events", LinkRelayAddress.canonicalForNode("00".repeat(32)))
        assertEquals("ws://${"7".repeat(51)}q/events", LinkRelayAddress.canonicalForNode("ff".repeat(32)))
    }

    @Test
    fun `ordinary relays retain the public socket path`() {
        val calls = mutableListOf<String>()
        val hybrid = hybrid(public = { requested, callback ->
            calls += requested
            TestSocket(callback)
        })

        hybrid.open("wss://relay.example/events", listener)

        assertEquals(listOf("wss://relay.example/events"), calls)
    }

    @Test
    fun `an active exact route uses Link and keeps its opaque id`() {
        val calls = mutableListOf<Pair<String, String>>()
        val hybrid = hybrid(
            route = { requested -> "room-bothy".takeIf { requested == url } },
            link = { requested, routeId, callback ->
                calls += requested to routeId
                TestSocket(callback)
            },
        )

        hybrid.open(url, listener)

        assertEquals(listOf(url to "room-bothy"), calls)
    }

    @Test
    fun `a canonical Link URL without consent never reaches public networking`() {
        var publicCalls = 0
        val hybrid = hybrid(public = { _, callback -> publicCalls += 1; TestSocket(callback) })

        assertFailsWith<IllegalStateException> { hybrid.open(url, listener) }

        assertEquals(0, publicCalls)
    }

    @Test
    fun `noncanonical Link variants never reach public networking`() {
        val variants = listOf(
            "wss://$node/events",
            "ws://${node.uppercase()}/events",
            "ws://$node:443/events",
            "ws://$node/other",
            "ws://${"a".repeat(51)}b/events",
        )
        var publicCalls = 0
        val hybrid = hybrid(public = { _, callback -> publicCalls += 1; TestSocket(callback) })

        variants.forEach { assertFailsWith<IllegalArgumentException> { hybrid.open(it, listener) } }

        assertEquals(0, publicCalls)
    }

    private fun hybrid(
        public: RelaySocketFactory = RelaySocketFactory { _, callback -> TestSocket(callback) },
        route: ActiveLinkRoute = ActiveLinkRoute { null },
        link: LinkRelaySocketFactory = LinkRelaySocketFactory { _, _, callback -> TestSocket(callback) },
    ) = HybridRelaySockets(public, link, route)

    private class TestSocket(@Suppress("unused") listener: RelaySocketListener) : RelaySocket {
        override fun send(text: String) = Unit
        override fun close() = Unit
    }
}
