package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BothyPairingTest {
    @Test fun `pairing URI must use exact bothy scheme`() {
        assertThrows(IllegalArgumentException::class.java) { BothyPairing.parse("bothyy:abc", 100) }
    }

    @Test fun `pairing URI rejects padded or non url-safe payloads`() {
        assertThrows(IllegalArgumentException::class.java) { BothyPairing.parse("bothy:abc=", 100) }
        assertThrows(IllegalArgumentException::class.java) { BothyPairing.parse("bothy:abc+", 100) }
    }

    @Test fun `Bothy identity and Link transport identity are distinct`() {
        val now = 1_795_305_600L
        val card = Base64.getUrlDecoder().decode(
            "RlNMMQE7QxbPz6sql5G7uJvBXiGyBTGqAHYfK7YUH2oj1INlFwAAAABrAjBEAAAAAGsKGYAAAAAAAAAACQEBABd3c3M6Ly9yZWxheS5leGFtcGxlLm9yZxVcqlwx4xd2X9Xx3ZAT0uArQvPdhySJcVf9dgdBpG98V-80Zx2UVa5AU_NqyOizaBZx9jFaO_5pZ6Mi1iLFeAY"
        )
        val bothy = "ab".repeat(32)
        val body = buildJsonObject {
            put("v", 2); put("card", Base64.getEncoder().encodeToString(card)); put("bothy", bothy)
            put("secret", "cd".repeat(16)); put("exp", now + 600); put("role", "box"); put("name", "fixture")
        }.toString().encodeToByteArray()
        val parsed = BothyPairing.parse("bothy:" + Base64.getUrlEncoder().withoutPadding().encodeToString(body), now)

        assertEquals(bothy, parsed.bothyPubkey)
        assertEquals("3b4316cfcfab2a9791bbb89bc15e21b20531aa00761f2bb6141f6a23d4836517", parsed.linkNodeId)
    }
}
