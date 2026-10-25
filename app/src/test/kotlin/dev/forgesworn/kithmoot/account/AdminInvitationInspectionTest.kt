package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.vmls.ffi.VmlsAdminInvitationContextV2
import dev.forgesworn.vmls.ffi.VmlsAdminInvitationInspectionV2
import dev.forgesworn.vmls.ffi.VmlsReturnRoute
import kotlin.test.*

class AdminInvitationInspectionTest {
    private fun bytes(value: Int) = ByteArray(32) { value.toByte() }
    private val contextBytes = InvitationTestEncode.adminContext(bytes(1), bytes(2), bytes(3), 1_900_000_000L)
    private val context = VmlsAdminInvitationContextV2(2U, "Vennel/invitation-admins/2", "designated-leaf-admins/1", bytes(17),
        bytes(4), bytes(1), bytes(2), bytes(3), bytes(8), bytes(9), bytes(10), bytes(11), bytes(12), bytes(13), bytes(14), VmlsReturnRoute.PrivateOob, 1_900_000_000UL)
    private fun inspection(c: VmlsAdminInvitationContextV2 = context) = VmlsAdminInvitationInspectionV2(c, contextBytes,
        Digests.sha256("Vennel invitation-admins/2 context\u0000".toByteArray() + contextBytes), byteArrayOf())

    @Test fun `independent context accepts matching native pins and rejects each substituted pin`() {
        checkAdminInvitationInspection(inspection())
        val changes: List<(VmlsAdminInvitationContextV2) -> VmlsAdminInvitationContextV2> = listOf(
            { it.copy(version = 1U) }, { it.copy(profile = "Vennel/invitation/1") }, { it.copy(policy = "all-current-members/1") },
            { it.copy(adminSetCommitment = bytes(99)) }, { it.copy(invitationId = bytes(99)) }, { it.copy(inviterPersona = bytes(99)) },
            { it.copy(inviterDevice = bytes(99)) }, { it.copy(inviterRz = bytes(99)) }, { it.copy(joinerPersona = bytes(99)) },
            { it.copy(joinerDevice = bytes(99)) }, { it.copy(joinerRz = bytes(99)) }, { it.copy(groupId = bytes(99)) },
            { it.copy(groupHome = bytes(99)) }, { it.copy(groupInstallation = bytes(99)) }, { it.copy(joinerHome = bytes(99)) },
            { it.copy(returnRoute = VmlsReturnRoute.LinkMailbox(bytes(99))) }, { it.copy(expiresAt = 1_900_000_001UL) })
        changes.forEach { change -> assertFailsWith<IllegalArgumentException> { checkAdminInvitationInspection(inspection(change(context))) } }
    }
    @Test fun `legacy context commitment and altered native commitment never pass agreement check`() {
        assertFailsWith<IllegalArgumentException> { checkAdminInvitationInspection(inspection().copy(contextCommitment = bytes(99))) }
        val old = InvitationTestEncode.context(bytes(1), bytes(2), bytes(3), 1_900_000_000L)
        assertFails { checkAdminInvitationInspection(inspection().copy(contextBytes = old,
            contextCommitment = Digests.sha256("Vennel invitation-admins/2 context\u0000".toByteArray() + old))) }
    }
}
