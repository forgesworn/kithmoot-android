package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PersistentGroupsTest {
    private val host = createRoomInvitation(true)
    private val secret = ByteArray(32) { 12 }
    private val welcome = encodePersistentInvitation(host, secret, 1_800_000_000)

    @Test fun `an empty group admits from stored events without a live request or delegation`() = runTest {
        val admission = requestPersistentAdmission(host.invitation) { filters ->
            assertEquals(listOf(KIND_GROUP_INVITATION, KIND_INVITATION_RETIREMENT), filters.single().kinds)
            assertEquals(listOf(host.invitation.canonicalInviter), filters.single().authors)
            listOf(welcome)
        }
        assertContentEquals(secret, admission.secret)
        assertNull(admission.delegate)
    }

    @Test fun `retirement wins even when welcome arrives first`() = runTest {
        val retirement = encodeInvitationRetirement(host.invitation, host.inviterSecretKey, 1_800_000_060)
        val error = assertFailsWith<GroupInvitationException> {
            requestPersistentAdmission(host.invitation) { listOf(welcome, retirement) }
        }
        assertTrue(error.message!!.contains("retired"))
    }

    @Test fun `missing and conflicting welcomes fail closed`() = runTest {
        assertFailsWith<GroupInvitationException> { requestPersistentAdmission(host.invitation) { emptyList() } }
        val other = encodePersistentInvitation(host, ByteArray(32) { 13 }, 1_800_000_001)
        assertFailsWith<GroupInvitationException> { requestPersistentAdmission(host.invitation) { listOf(welcome, other) } }
    }
}
