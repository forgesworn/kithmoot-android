package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertThrows
import org.junit.Test

class BothyPairingTest {
    @Test fun `pairing URI must use exact bothy scheme`() {
        assertThrows(IllegalArgumentException::class.java) { BothyPairing.parse("bothyy:abc", 100) }
    }

    @Test fun `pairing URI rejects padded or non url-safe payloads`() {
        assertThrows(IllegalArgumentException::class.java) { BothyPairing.parse("bothy:abc=", 100) }
        assertThrows(IllegalArgumentException::class.java) { BothyPairing.parse("bothy:abc+", 100) }
    }
}
