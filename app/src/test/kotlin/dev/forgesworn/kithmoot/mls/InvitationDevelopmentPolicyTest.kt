package dev.forgesworn.kithmoot.mls

import kotlin.test.Test
import kotlin.test.assertFailsWith

class InvitationDevelopmentPolicyTest {
    @Test fun `release and default debug both refuse before invitation operations`() {
        assertFailsWith<IllegalStateException> { InvitationDevelopmentPolicy.requireEnabled(false, false) }
        assertFailsWith<IllegalStateException> { InvitationDevelopmentPolicy.requireEnabled(false, true) }
        assertFailsWith<IllegalStateException> { InvitationDevelopmentPolicy.requireEnabled(true, false) }
        InvitationDevelopmentPolicy.requireEnabled(true, true)
    }
    @Test fun `app refuses unsupported delivery topology before leaf preparation`() {
        val home = ByteArray(32) { 1 }
        InvitationDevelopmentPolicy.requireSingleHome(home, home.copyOf())
        assertFailsWith<IllegalArgumentException> { InvitationDevelopmentPolicy.requireSingleHome(home, ByteArray(32) { 2 }) }
        assertFailsWith<IllegalArgumentException> { InvitationDevelopmentPolicy.requireSingleHome(home, byteArrayOf(1)) }
    }
}
