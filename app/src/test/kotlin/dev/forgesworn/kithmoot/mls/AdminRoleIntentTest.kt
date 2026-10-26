package dev.forgesworn.kithmoot.mls

import kotlin.test.*

class AdminRoleIntentTest {
    private val first = "01".repeat(32)
    private val second = "02".repeat(32)
    private val third = "03".repeat(32)
    private val commitment = "aa".repeat(32)
    private fun roles(ownAdmin: Boolean = true, secondAdmin: Boolean = false) = ExperimentalAdminRoles("7", commitment, first, ownAdmin,
        listOf(ExperimentalAdminDevice(first, "11".repeat(32), "21".repeat(32), true),
            ExperimentalAdminDevice(second, "11".repeat(32), "22".repeat(32), secondAdmin),
            ExperimentalAdminDevice(third, "12".repeat(32), "23".repeat(32), false)))

    @Test fun `promotion targets one exact device leaf even for same persona`() {
        assertEquals(listOf(first, second), AdminRoleIntent.targetLeafIds(roles(), second, true, commitment))
        assertEquals(listOf(first, third), AdminRoleIntent.targetLeafIds(roles(), third, true, commitment))
    }
    @Test fun `demotion keeps surviving admin and never elevates another device`() {
        assertEquals(listOf(second), AdminRoleIntent.targetLeafIds(roles(secondAdmin = true), first, false, commitment))
    }
    @Test fun `last admin stale intent ordinary member absent leaf and unchanged roles refuse`() {
        assertFailsWith<IllegalStateException> { AdminRoleIntent.targetLeafIds(roles(), first, false, commitment) }
        assertFailsWith<IllegalStateException> { AdminRoleIntent.targetLeafIds(roles(), second, true, "bb".repeat(32)) }
        assertFailsWith<IllegalStateException> { AdminRoleIntent.targetLeafIds(roles(ownAdmin = false), second, true, commitment) }
        assertFailsWith<IllegalStateException> { AdminRoleIntent.targetLeafIds(roles(), "04".repeat(32), true, commitment) }
        assertFailsWith<IllegalStateException> { AdminRoleIntent.targetLeafIds(roles(), first, true, commitment) }
    }
}
