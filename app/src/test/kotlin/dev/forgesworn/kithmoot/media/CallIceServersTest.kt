package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallIceServersTest {
    @Test fun `accepts the PWA UDP TCP and TLS response`() {
        val value = CallIceServers.parse("""{"urls":["turn:kithmoot.forgesworn.dev:3478","turn:kithmoot.forgesworn.dev:3478?transport=tcp","turns:kithmoot.forgesworn.dev:5349"],"username":"test-expiry","credential":"synthetic-test-password"}""")
        assertEquals(3, value?.urls?.size)
    }

    @Test fun `does not send credentials to another host or accept empty credentials`() {
        for (url in listOf("turn:other.invalid:3478", "turn:kithmootXforgeswornXdev:3478", "https://kithmoot.forgesworn.dev:3478")) {
            assertNull(CallIceServers.parse("""{"urls":["$url"],"username":"test","credential":"test"}"""))
        }
        assertNull(CallIceServers.parse("""{"urls":["turn:kithmoot.forgesworn.dev:3478"],"username":"","credential":""}"""))
        assertNull(CallIceServers.parse("not json"))
    }
}
