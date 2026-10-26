package dev.forgesworn.kithmoot.vmls

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class AdminInvitationAuthorizationTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/admin-invitation-reference.json")).readText()).jsonObject
    private val vectors = fixture.getValue("vectors").jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun `independent reference exact contexts bind all pins and domains`() {
        for (vector in vectors.filter { it.text("profile") == "Vennel/invitation-admins/2" }) {
            val bytes = vector.text("context_hex").hexToBytes()
            val context = AdminInvitationAuthorization.readContext(bytes)
            assertEquals("Vennel/invitation-admins/2", context.profile)
            assertEquals("designated-leaf-admins/1", context.policy)
            assertEquals(vector.text("commitment"), Digests.sha256("Vennel invitation-admins/2 context\u0000".toByteArray() + bytes).toHex())
            assertEquals("11".repeat(32), context.inviterPersona)
            assertEquals("12".repeat(32), context.inviterDevice)
            assertEquals("13".repeat(32), context.inviterRendezvous)
            assertEquals("14".repeat(32), context.joinerPersona)
            assertEquals("15".repeat(32), context.joinerDevice)
            assertEquals("16".repeat(32), context.joinerRendezvous)
            assertEquals("17".repeat(32), context.group)
            assertEquals("18".repeat(32), context.groupHome)
            assertEquals("55".repeat(32), context.installation)
            assertEquals("1a".repeat(32), context.leafHome)
            assertEquals(64, context.adminSetCommitment.length)
            assertThrows(BindingException::class.java) { InvitationAuthorization.readContext(bytes) }
        }
        val privateContext = AdminInvitationAuthorization.readContext(vectors.first { it.text("name") == "admins-private" }.text("context_hex").hexToBytes())
        assertNull(privateContext.returnNode)
        val mailboxContext = AdminInvitationAuthorization.readContext(vectors.first { it.text("name") == "admins-mailbox" }.text("context_hex").hexToBytes())
        assertEquals("42".repeat(32), mailboxContext.returnNode)
    }

    @Test fun `legacy unknown truncated trailing noncanonical and wrong key count refuse`() {
        val bytes = vectors.first { it.text("name") == "admins-private" }.text("context_hex").hexToBytes()
        val legacy = vectors.first { it.text("name") == "legacy-private" }.text("context_hex").hexToBytes()
        assertThrows(BindingException::class.java) { AdminInvitationAuthorization.readContext(legacy) }
        val malformed = listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(0),
            bytes.copyOf().also { it[0] = 0xb0.toByte() }, bytes.copyOf().also { it[2] = 3 },
            bytes.copyOfRange(0, 1) + byteArrayOf(0x18, 1) + bytes.copyOfRange(2, bytes.size),
            bytes.copyOf().also { it[bytes.size - 35] = 18 })
        malformed.forEach { input -> assertThrows(BindingException::class.java) { AdminInvitationAuthorization.readContext(input) } }
    }

    @Test fun `authorization domain is separate from legacy and leaf binding domains`() {
        val body = byteArrayOf(0xa3.toByte(), 1, 1, 2, 0x40, 3, 0x40)
        val digest = AdminInvitationAuthorization.digest(body)
        assertArrayEquals(Digests.sha256("Vennel invitation-admins/2 authorization\u0000".toByteArray() + body), digest)
        assertFalse(digest.contentEquals(InvitationAuthorization.digest(body)))
        assertFalse(digest.contentEquals(LeafBinding.digest(body)))
    }
}
