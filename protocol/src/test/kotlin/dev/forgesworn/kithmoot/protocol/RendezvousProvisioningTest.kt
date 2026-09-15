package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousProvisioningTest {
    private val now = 1_793_577_600L
    private val identity = "3e0b147852ddd35607a06b4bb56ffc102e4d7b3ece162042c3f32f96a49c4613"
    private val device = "d6bd3b313d2cbc4f0b2355179911bd45747e6bd1024b9fead9a72e914a28e827"
    private val record = """{"v":1,"p":"$identity","d":"$device","rz":"5f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486","u":"rendezvous","i":7,"n":"AAECAwQFBgcICQoLDA0ODw","e":1793577900,"k":"Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA"}"""
    private val expect = RendezvousProvisionExpect(identity, device, "000102030405060708090a0b0c0d0e0f".hexToBytes(), now)

    @Test fun `reads Vennel canonical provision vector`() {
        val result = readRendezvousProvision(record, expect)
        assertTrue(result is RendezvousProvisionResult.Accepted)
        val provision = (result as RendezvousProvisionResult.Accepted).provision
        assertEquals(7L, provision.index)
        assertEquals(now + 300, provision.expiresAt)
        assertEquals("1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100", provision.scalar.joinToString("") { "%02x".format(it) })
        provision.wipe()
        assertTrue(provision.scalar.all { it == 0.toByte() })
    }

    @Test fun `refuses altered binding expiry scalar and shape`() {
        fun reason(value: String) = (readRendezvousProvision(value, expect) as RendezvousProvisionResult.Refused).reason
        assertEquals("identity", reason(record.replace(identity, "aa".repeat(32))))
        assertEquals("device", reason(record.replace(device, "bb".repeat(32))))
        assertEquals("purpose", reason(record.replace("\"u\":\"rendezvous\"", "\"u\":\"persona\"")))
        assertEquals("nonce", reason(record.replace("AAECAwQFBgcICQoLDA0ODw", "AQEBAQEBAQEBAQEBAQEBAQ")))
        assertEquals("expired", reason(record.replace("1793577900", now.toString())))
        assertEquals("rendezvous key", reason(record.replace("5f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486", identity)))
        assertEquals("fields", reason(record.replace("{\"v\":1,\"p\":\"$identity\",\"d\":", "{\"p\":\"$identity\",\"v\":1,\"d\":")))
        assertEquals("scalar", reason(record.replace("\"k\":\"Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA\"", "\"k\":\"Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=\"")))
    }

    @Test fun `checks public response wrapper before opening its ciphertext`() {
        // serde_json may serialise Heartwood's outer response map in key
        // order; the public envelope is deliberately shape-checked, not
        // transport-order-sensitive.  The encrypted inner record is canonical.
        val envelope = """{"c":"test-ciphertext","d":"$device","e":1793577900,"i":7,"n":"AAECAwQFBgcICQoLDA0ODw","p":"$identity","rz":"5f7117a78150fe2ef97db7cfc83bd57b2e2c0d0dd25eaf467a4a1c2a45ce1486","u":"rendezvous","v":1}"""
        val result = readRendezvousProvisionEnvelope(envelope, expect)

        assertTrue(result is RendezvousProvisionEnvelopeResult.Accepted)
        assertEquals("test-ciphertext", (result as RendezvousProvisionEnvelopeResult.Accepted).envelope.ciphertext)
        assertEquals(
            "device",
            (readRendezvousProvisionEnvelope(envelope.replace(device, "bb".repeat(32)), expect) as RendezvousProvisionEnvelopeResult.Refused).reason,
        )
        assertEquals(
            "fields",
            (readRendezvousProvisionEnvelope(envelope.replace("\"c\":\"test-ciphertext\"", "\"x\":\"test-ciphertext\""), expect) as RendezvousProvisionEnvelopeResult.Refused).reason,
        )
    }
}
