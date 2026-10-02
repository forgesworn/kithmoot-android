package dev.forgesworn.kithmoot.vmls

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.createPersonCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeafBindingTest {
    private val suite: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/vmls/vmls-binding-v1.json"))
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val now get() = suite.getValue("now").jsonPrimitive.long
    private val revoked get() = suite.getValue("revokedCredentialIds").jsonArray.map { it.jsonPrimitive.content }.toSet()

    private data class Case(val name: String, val hex: String, val identity: String?, val revoked: Boolean, val ok: Boolean, val error: String?)

    private val cases: List<Case> by lazy {
        suite.getValue("cases").jsonArray.map { it.jsonObject }.map { c ->
            val expect = c.getValue("expect").jsonObject
            Case(
                c.getValue("name").jsonPrimitive.content,
                c.getValue("bindingHex").jsonPrimitive.content,
                c["expectedIdentityHex"]?.jsonPrimitive?.content,
                c["revoked"]?.jsonPrimitive?.boolean == true,
                expect.getValue("ok").jsonPrimitive.boolean,
                expect["error"]?.jsonPrimitive?.content,
            )
        }
    }

    // The vectors are signed eight-key bindings. The unsigned body is the same
    // map with header 0xa7 and without its last field, key 8 (`08 58 40` and a
    // 64-byte signature). Cases that malform that framing are covered below.
    private val framed get() = cases.filter { it.hex.length > 134 && it.hex.substring(it.hex.length - 134, it.hex.length - 128) == "085840" && it.hex.startsWith("a8") }
    private fun unsignedOf(hex: String): ByteArray = ("a7" + hex.substring(2, hex.length - 134)).hexToBytes()

    /** What a signer, which sees neither the carrying leaf nor the signature, should say. */
    private fun signerSees(c: Case): String = when {
        c.ok -> "ok"
        c.error == "BindingLeafMismatch" || c.error == "BindingSignatureInvalid" -> "ok"
        else -> c.error!!
    }

    private fun code(block: () -> Unit): String = try { block(); "ok" } catch (e: BindingException) { e.code.name }

    @Test fun everyValidFramedBodyIsExactlyWhatItsSignatureCovers() {
        val valid = framed.filter { it.ok }
        assertTrue(valid.size >= 4)
        for (c in valid) {
            val body = unsignedOf(c.hex)
            val binding = LeafBinding.readUnsigned(body)
            val signature = c.hex.substring(c.hex.length - 128).hexToBytes()
            assertTrue(c.name, Schnorr.verify(signature, LeafBinding.digest(body), binding.device))
        }
    }

    @Test fun agreesWithTheRustAndJsReadersOnEveryFramedCase() {
        for (c in framed) {
            val body = unsignedOf(c.hex)
            // A signer always knows the selected person; a case without one
            // expects whoever signed the credential.
            val actual = code {
                val binding = LeafBinding.readUnsigned(body)
                LeafBinding.checkUnsigned(binding, now, c.identity ?: binding.event.pubkey, if (c.revoked) revoked else emptySet())
            }
            assertEquals(c.name, signerSees(c), actual)
        }
    }

    @Test fun theFramedCasesCoverMostOfTheSuite() {
        assertTrue(framed.size >= cases.size - 6)
    }

    @Test fun refusesASignedBindingTrailingByteTruncationIndefiniteMapAndOversize() {
        val valid = cases.first { it.name == "valid" }
        val body = unsignedOf(valid.hex)
        assertEquals("NonCanonical", code { LeafBinding.readUnsigned(valid.hex.hexToBytes()) })
        assertEquals("NonCanonical", code { LeafBinding.readUnsigned(body + 0) })
        assertEquals("Malformed", code { LeafBinding.readUnsigned(body.copyOf(body.size - 1)) })
        assertEquals("NonCanonical", code { LeafBinding.readUnsigned(byteArrayOf(0xbf.toByte()) + body.copyOfRange(1, body.size)) })
        assertEquals("TooLarge", code { LeafBinding.readUnsigned(ByteArray(LeafBinding.MAX_UNSIGNED_BODY_BYTES + 1)) })
        assertEquals("Malformed", code { LeafBinding.readUnsigned(ByteArray(0)) })
    }

    @Test fun anEightByteHeadAboveTwoToTheSixtyThreeIsNotReadAsNegative() {
        val valid = cases.first { it.name == "valid" }
        val body = unsignedOf(valid.hex)
        // Key 1 (the version) as the eight-byte value 2^64 - 1.
        val huge = byteArrayOf(0xa7.toByte(), 0x01, 0x1b) + ByteArray(8) { 0xff.toByte() } + body.copyOfRange(3, body.size)
        assertEquals("UnsupportedVersion", code { LeafBinding.readUnsigned(huge) })
    }

    @Test fun aPersonCredentialFromThisAppMeetsTheEngineRules() {
        val identitySecret = ByteArray(32) { 1 }
        val identity = Schnorr.publicKeyHex(identitySecret)
        val device = Schnorr.publicKeyHex(ByteArray(32) { 2 })
        val issued = 1_793_577_600L
        val event = createPersonCredential(identitySecret, device, issued + 7 * 86_400, createdAt = issued)
        val credential = LeafBinding.verifyPersonCredential(event, issued, identity)
        assertEquals(identity, credential.identity)
        assertEquals(device, credential.device)
    }

    @Test fun aRoomCredentialIsNotAPersonCredential() {
        val identitySecret = ByteArray(32) { 1 }
        val identity = Schnorr.publicKeyHex(identitySecret)
        val device = Schnorr.publicKeyHex(ByteArray(32) { 2 })
        val issued = 1_793_577_600L
        val event = createDeviceCredential(identitySecret, device, "a".repeat(64), issued + 3600, createdAt = issued)
        assertEquals("CredentialNotPersonScoped", code { LeafBinding.verifyPersonCredential(event, issued, identity) })
    }

    @Test fun aCarriedIdIsNeverTrusted() {
        val identitySecret = ByteArray(32) { 1 }
        val identity = Schnorr.publicKeyHex(identitySecret)
        val device = Schnorr.publicKeyHex(ByteArray(32) { 2 })
        val issued = 1_793_577_600L
        val event = createPersonCredential(identitySecret, device, issued + 3600, createdAt = issued)
        val credential = LeafBinding.verifyPersonCredential(event.copy(id = "0".repeat(64)), issued, identity)
        assertEquals(event.id, credential.id)
    }
}
