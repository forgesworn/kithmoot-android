package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyTransportRecoveryTest {
    @Test fun `a transient disconnect and cold negotiation do not restart`() {
        val recovery = LegacyTransportRecovery()
        assertNull(recovery.sample("connecting", 0))
        assertNull(recovery.sample("checking", 120_000))
        assertNull(recovery.sample("disconnected", 121_000))
        assertNull(recovery.sample("disconnected", 126_999))
        assertNull(recovery.sample("connected", 127_000))
        assertNull(recovery.sample("connected", 200_000))
    }

    @Test fun `a VPN failure restarts once then rebuilds even while ICE is checking`() {
        val recovery = LegacyTransportRecovery()
        assertNull(recovery.sample("failed", 0))
        assertEquals(LegacyTransportRecovery.Action.RESTART_ICE, recovery.sample("failed", 6_000))
        assertNull(recovery.sample("checking", 7_000))
        assertNull(recovery.sample("checking", 13_999))
        assertEquals(LegacyTransportRecovery.Action.REBUILD, recovery.sample("checking", 14_000))
        recovery.replaced()
        assertNull(recovery.sample("connecting", 14_001))
    }

    @Test fun `successful native recovery cancels the rebuild`() {
        val recovery = LegacyTransportRecovery()
        recovery.sample("disconnected", 0)
        assertEquals(LegacyTransportRecovery.Action.RESTART_ICE, recovery.sample("disconnected", 6_000))
        assertNull(recovery.sample("completed", 7_000))
        assertNull(recovery.sample("connected", 40_000))
    }

    @Test fun `replacement connections retain the rebuild limit and rest before retrying`() {
        val recovery = LegacyTransportRecovery()
        for (start in listOf(0L, 14_000L, 28_000L)) {
            assertNull(recovery.sample("failed", start))
            assertEquals(LegacyTransportRecovery.Action.RESTART_ICE, recovery.sample("failed", start + 6_000))
            assertEquals(LegacyTransportRecovery.Action.REBUILD, recovery.sample("checking", start + 14_000))
            recovery.replaced()
        }
        assertNull(recovery.sample("failed", 42_000))
        assertEquals(LegacyTransportRecovery.Action.RESTART_ICE, recovery.sample("failed", 48_000))
        assertNull(recovery.sample("failed", 56_000))
        assertNull(recovery.sample("failed", 115_999))
        assertEquals(LegacyTransportRecovery.Action.RESTART_ICE, recovery.sample("failed", 116_000))
        assertEquals(LegacyTransportRecovery.Action.REBUILD, recovery.sample("checking", 124_000))
    }
}
