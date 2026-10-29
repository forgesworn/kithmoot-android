package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RosterSpryKeyTest {
    private val credential = NostrEvent(20460, 0, emptyList(), "", "00".repeat(32), "00".repeat(32), "00".repeat(64))
    private val entry = RosterEntry("aa".repeat(32), "bb".repeat(32), credential, updatedAt = 1_800_000_000)
    private val deviceSk = "36051fb0a299f2c819f2d3c112b99b8e8bd3d213c0e6f0d209edec4f932b6757".hexToBytes()

    @Test fun `the key is the x-only public key of the room device key's derivation`() {
        val key = SpryKey.publicKeyHex(deviceSk)
        assertEquals(Schnorr.publicKeyHex(SpryKey.secret(deviceSk)), key)
        assertTrue(Regex("[0-9a-f]{64}").matches(key))
    }

    @Test fun `an announced key travels and an entry without one is unchanged`() {
        val key = SpryKey.publicKeyHex(deviceSk)
        val announced = entry.copy(spryKey = key)
        assertEquals(key, RosterEntry.fromJson(announced.toJson()).spryKey)
        assertEquals(announced.toJson(), RosterEntry.fromJson(announced.toJson()).toJson())
        assertNull(entry.toJson()["spryKey"])
    }

    @Test fun `a malformed claim costs the claim and never the entry`() {
        val baseline = entry.toJson()
        for (text in listOf("null", "7", "true", "{}", "[]", "\"${"f".repeat(64)}\"", "\"${"ab".repeat(31)}\"", "\"${"zz".repeat(32)}\"", "\" ${"ab".repeat(32)}\"")) {
            val fields = baseline.toMutableMap()
            fields["spryKey"] = Json.parseToJsonElement(text)
            val decoded = RosterEntry.fromJson(JsonObject(fields))
            assertNull(text, decoded.spryKey)
            assertEquals(entry.participant, decoded.participant)
            assertEquals(baseline, decoded.toJson())
        }
    }

    @Test fun `a claim in capitals is kept in lower case and an invalid one is never published`() {
        val key = SpryKey.publicKeyHex(deviceSk)
        val fields = entry.toJson().toMutableMap()
        fields["spryKey"] = Json.parseToJsonElement("\"${key.uppercase()}\"")
        assertEquals(key, RosterEntry.fromJson(JsonObject(fields)).spryKey)
        assertNull(entry.copy(spryKey = "f".repeat(64)).toJson()["spryKey"])
    }
}
