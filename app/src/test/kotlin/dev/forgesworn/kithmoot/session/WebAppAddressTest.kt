package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.SignetSignIn
import java.net.URI
import java.net.URLDecoder
import kotlin.test.*

class WebAppAddressTest {
    @Test fun `chosen origin covers invitations and the matching Signet return origin`() {
        val address = WebAppAddress.parse(" https://CHAT.example:8443/ ")
        assertEquals("https://chat.example:8443", address.origin)
        assertEquals("https://chat.example:8443/j/", address.joinBase)
        val invitation = SignetSignIn.nostrConnectUri("cd".repeat(32), listOf("wss://relay.example"), "test-secret", webApp = address)
        fun query(url: String) = URI(url).rawQuery.split('&').associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            key to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals(address.origin, query(invitation)["url"])
        val signIn = query(SignetSignIn.url(invitation, address))
        assertEquals(invitation, signIn["nostrconnect"])
        assertEquals("https://chat.example:8443/signet/", signIn["callback"])
        assertFalse(invitation.contains("forgesworn"))
        assertFalse(signIn.getValue("callback").contains("forgesworn"))
    }

    @Test fun `saved invitations keep their exact payload when shared from a chosen site`() {
        val address = WebAppAddress.parse("https://circle.example")
        assertEquals("https://circle.example/j/#synthetic_opaque-Payload%2F", address.roomLink("https://previous.example/j/#synthetic_opaque-Payload%2F"))
        assertFails { address.roomLink("https://previous.example/j/") }
    }

    @Test fun `normalises only safe HTTPS origins`() {
        assertEquals("https://chat.example", WebAppAddress.parse("https://chat.example:443/").origin)
        assertEquals("https://[::1]:8443", WebAppAddress.parse("https://[::1]:8443").origin)
        for (bad in listOf("", "chat.example", "http://chat.example", "javascript:alert(1)",
            "https://user@chat.example", "https://chat.example/j/", "https://chat.example?token=x",
            "https://chat.example#key", "https://chat.example:0", "https://chat.example:65536",
            "https://chat.example\\@evil.example", "https://chat.example/%2f", "https://chat.example?", "https://chat.example#")) {
            assertFails("Accepted $bad") { WebAppAddress.parse(bad) }
        }
    }
}
