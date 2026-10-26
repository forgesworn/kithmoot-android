package dev.forgesworn.kithmoot.mls

/** Build one explicit exact-leaf intent from the native applied role view.
 * Native setAdminRolesV1 repeats the commitment comparison atomically. */
internal object AdminRoleIntent {
    fun targetLeafIds(roles: ExperimentalAdminRoles, leaf: String, admin: Boolean, expectedCommitment: String): List<String> {
        require(ROOM_HEX64.matches(leaf) && ROOM_HEX64.matches(expectedCommitment))
        check(roles.ownIsAdmin) { "This device is not a current group admin." }
        check(roles.commitment == expectedCommitment) { "Admin roles changed; review the current roles again." }
        check(roles.devices.any { it.leaf == leaf }) { "Select an exact current device leaf." }
        val current = roles.devices.filter { it.admin }.map { it.leaf }.toSet()
        check((leaf in current) != admin) { "This device already has that role." }
        val next = (if (admin) current + leaf else current - leaf).sorted()
        check(next.isNotEmpty()) { "The last group admin cannot be demoted." }
        check(next.size <= 64) { "The group already has the maximum number of admins." }
        return next
    }
}
