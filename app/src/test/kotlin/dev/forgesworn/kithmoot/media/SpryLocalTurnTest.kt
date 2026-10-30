package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.callrelay.LocalTurn
import dev.forgesworn.kithmoot.callrelay.RelayConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SpryLocalTurnTest {
    @Test fun `only this device's own standard pairing swaps in the local listener`() {
        val public = listOf("turn:198.51.100.7:51000?transport=udp", "turn:198.51.100.7:51001?transport=tcp")
        val local = LocalTurn("192.168.1.20", 23456)
        assertEquals(RelayConfig.localTurnUrls(local), SpryClient.credentialUrls(public, shared = false, standard = true, localTurn = local))
        assertEquals("another member's offer keeps the node's answer", public, SpryClient.credentialUrls(public, shared = true, standard = true, localTurn = local))
        assertEquals("a private node keeps its answer", public, SpryClient.credentialUrls(public, shared = false, standard = false, localTurn = local))
        assertEquals(public, SpryClient.credentialUrls(public, shared = false, standard = true, localTurn = null))
    }
}
