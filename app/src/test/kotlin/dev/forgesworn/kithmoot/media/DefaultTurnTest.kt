package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultTurnTest {
    private fun answer(urls: String = """"turn:kithmoot.forgesworn.dev:3478","turn:kithmoot.forgesworn.dev:3478?transport=tcp","turns:kithmoot.forgesworn.dev:5349"""",
                       ttl: String = "3600", username: String = "1762000000:abc", credential: String = "synthetic-test-password") =
        """{"urls":[$urls],"username":"$username","credential":"$credential","ttl":$ttl}"""

    @Test fun `accepts the UDP, TCP and TLS answer and counts the lease from when it was asked for`() {
        val login = assertNotNull(DefaultTurn.parse(answer(), issuedAtMs = 10_000))
        assertEquals(3, login.relay.urls.size)
        assertEquals("1762000000:abc", login.relay.username)
        assertEquals(10_000 + 3_600_000, login.expiresAtMs)
    }

    @Test fun `never sends a login to another host, or accepts an empty or oddly leased one`() {
        for (url in listOf("turn:other.invalid:3478", "turn:kithmootXforgeswornXdev:3478", "https://kithmoot.forgesworn.dev:3478", "turn:kithmoot.forgesworn.dev:9999")) {
            assertNull(DefaultTurn.parse(answer(urls = "\"$url\""), 0), url)
        }
        assertNull(DefaultTurn.parse(answer(urls = "\"turn:kithmoot.forgesworn.dev:3478\",\"turn:other.invalid:3478\""), 0))
        assertNull(DefaultTurn.parse(answer(username = "", credential = ""), 0))
        assertNull(DefaultTurn.parse(answer(ttl = "60"), 0))
        assertNull(DefaultTurn.parse(answer(ttl = "999999"), 0))
        assertNull(DefaultTurn.parse(answer().replace(""","ttl":3600""", ""), 0))
        assertNull(DefaultTurn.parse("not json", 0))
    }

    @Test fun `a login is reused until a quarter of an hour before it lapses`() {
        val login = assertNotNull(DefaultTurn.parse(answer(), issuedAtMs = 0))
        DefaultTurn.forget()
        assertNull(DefaultTurn.keptLogin(nowMs = 0))
        DefaultTurn.kept = login
        assertNotNull(DefaultTurn.keptLogin(nowMs = 0))
        assertNotNull(DefaultTurn.keptLogin(nowMs = 3_600_000 - DefaultTurn.RENEW_BEFORE_MS - 1))
        assertNull(DefaultTurn.keptLogin(nowMs = 3_600_000 - DefaultTurn.RENEW_BEFORE_MS))
        DefaultTurn.forget()
    }

    @Test fun `the endpoint is the one the web app uses`() {
        assertEquals("https://kithmoot.forgesworn.dev/turn", DefaultTurn.ENDPOINT)
        assertTrue(DefaultTurn.FETCH_LIMIT_MS in 3_000..4_000)
    }
}
