package dev.forgesworn.kithmoot.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CircleGrantTest {
    private val terms = CircleGrantTerms(
        "ws://${"a".repeat(52)}/events",
        "1".repeat(64),
        "2".repeat(64),
        "3".repeat(64),
        "4".repeat(32),
        1_900_000_000,
    )

    @Test fun `active grant has the frozen exact scope and order`() {
        assertEquals(listOf(
            listOf("t", "event-grant"),
            listOf("server", terms.server),
            listOf("d", terms.room),
            listOf("p", terms.persona),
            listOf("device", terms.device),
            listOf("grant", terms.grantId),
            listOf("read", "1460"),
            listOf("read", "1462"),
            listOf("read", "20461"),
            listOf("write", "1460"),
            listOf("write", "20461"),
            listOf("expiration", "1900000000"),
            listOf("status", "active"),
        ), terms.tags(CircleGrantStatus.ACTIVE))
        assertEquals("revoked", terms.tags(CircleGrantStatus.REVOKED).last()[1])
    }

    @Test fun `malformed authority coordinates fail before signing`() {
        assertThrows(IllegalArgumentException::class.java) { terms.copy(server = "wss://example.com/events") }
        assertThrows(IllegalArgumentException::class.java) { terms.copy(room = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { terms.copy(grantId = "4".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { terms.copy(expiration = 0) }
    }

    private val vmls = VmlsGrantTerms("ws://${"a".repeat(52)}/events", "2".repeat(64), "3".repeat(64), "4".repeat(32), 1_900_000_000)

    @Test fun `a VMLS grant reads one kind, writes none, and carries its ceiling`() {
        assertEquals(listOf(
            listOf("t", "event-grant"),
            listOf("server", vmls.server),
            listOf("d", vmlsGrantRoom(vmls.device)),
            listOf("p", vmls.persona),
            listOf("device", vmls.device),
            listOf("grant", vmls.grantId),
            listOf("read", "1460"),
            listOf("expiration", "1900000000"),
            listOf("status", "active"),
            listOf("vmls", "67108864"),
        ), vmls.tags(CircleGrantStatus.ACTIVE))
        assertEquals(listOf("status", "revoked"), vmls.tags(CircleGrantStatus.REVOKED)[8])
    }

    @Test fun `a VMLS grant's room is derived from its device alone`() {
        // SHA-256("VMLS/1 box grant" || 0x33 * 32), computed independently.
        assertEquals(VMLS_ROOM_VECTOR, vmlsGrantRoom("3".repeat(64)))
        assertEquals(vmls.room, vmls.copy(persona = "5".repeat(64), grantId = "6".repeat(32)).room)
        assert(vmls.room != vmls.copy(device = "7".repeat(64)).room)
    }

    @Test fun `a VMLS grant's ceiling stays within Bothy's bound`() {
        assertThrows(IllegalArgumentException::class.java) { vmls.copy(ceiling = 0) }
        assertThrows(IllegalArgumentException::class.java) { vmls.copy(ceiling = (1L shl 30) + 1) }
        assertThrows(IllegalArgumentException::class.java) { vmls.copy(device = "A".repeat(64)) }
        assertEquals(listOf("vmls", "1073741824"), vmls.copy(ceiling = 1L shl 30).tags(CircleGrantStatus.ACTIVE).last())
    }

    private companion object {
        const val VMLS_ROOM_VECTOR = "7442e4f96f67ba7b3a71f4b371aa66c435973acf40df080b1606f854bf5a9b1d"
    }
}
