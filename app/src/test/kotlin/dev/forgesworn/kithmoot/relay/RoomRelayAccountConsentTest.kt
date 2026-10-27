package dev.forgesworn.kithmoot.relay

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RoomRelayAccountConsentTest {
    private val relay = "wss://relay.example/private"

    @Test fun `only the approved exact relay and account session may authenticate`() {
        val consent = RoomRelayAccountConsent()
        val account = Any()
        consent.accountChanged(account)
        assertFalse(consent.permits(account, relay))
        consent.change(consent.epoch, relay, true)
        assertTrue(consent.permits(account, "$relay/"))
        assertFalse(consent.permits(Any(), relay))
        assertFalse(consent.permits(null, relay))
        assertFalse(consent.permits(account, "wss://relay.example/other"))
        assertFalse(consent.permits(account, "wss://other.example/private"))
        assertFalse(consent.permits(account, "ws://relay.example/private"))
    }

    @Test fun `sign-out account replacement and a late confirmation revoke permission`() {
        val consent = RoomRelayAccountConsent()
        val first = Any()
        consent.accountChanged(first)
        val oldEpoch = consent.epoch
        consent.change(oldEpoch, relay, true)
        consent.accountChanged(null)
        assertFalse(consent.permits(first, relay))
        assertFailsWith<IllegalStateException> { consent.change(oldEpoch, relay, true) }
        val replacement = Any()
        consent.accountChanged(replacement)
        assertFailsWith<IllegalStateException> { consent.change(oldEpoch, relay, true) }
        assertFalse(consent.permits(replacement, relay))
        consent.change(consent.epoch, relay, true)
        assertTrue(consent.permits(replacement, relay))
    }

    @Test fun `revocation and removing or disabling a relay clear the grant`() {
        val consent = RoomRelayAccountConsent()
        val account = Any()
        consent.accountChanged(account)
        consent.change(consent.epoch, relay, true)
        consent.change(consent.epoch, relay, false)
        assertFalse(consent.permits(account, relay))
        consent.change(consent.epoch, relay, true)
        consent.retain(setOf("wss://other.example"))
        assertFalse(consent.permits(account, relay))
        assertTrue(consent.urls().isEmpty())
        assertTrue(RoomRelayAccountConsent().urls().isEmpty(), "a new app instance restores no grants")
    }
}
