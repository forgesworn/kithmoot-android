package dev.forgesworn.kithmoot.callrelay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RelayGatesTest {
    private val zone = ZoneId.of("Europe/London")
    private val noon = ZonedDateTime.of(2026, 10, 30, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val wifi = NetworkFacts(wifi = true, notMetered = true, vpn = false, bind = "192.168.1.20", gateway = "192.168.1.1")
    private val charging = BatteryFacts(charging = true, percent = 80, tenthsCelsius = 300)
    private val good = RelayConditions(wifi, charging, thermalStatus = 0)
    private val none = RelayDailyUsage()

    private fun blocked(gate: RelayGate, retryAt: Long? = null) = GateVerdict.Blocked(gate, retryAt)

    @Test fun `all gates pass on unmetered Wi-Fi while charging, cool and under the limit`() {
        assertEquals(GateVerdict.Pass(RelayNetwork("192.168.1.20", "192.168.1.1")), RelayGates().check(good, none, noon, zone))
    }

    @Test fun `the network must be unmetered Wi-Fi with an address and a router, and no VPN`() {
        val gates = RelayGates()
        assertEquals(blocked(RelayGate.NO_WIFI), gates.check(good.copy(network = null), none, noon, zone))
        assertEquals(blocked(RelayGate.NO_WIFI), gates.check(good.copy(network = wifi.copy(wifi = false)), none, noon, zone))
        assertEquals(blocked(RelayGate.METERED), gates.check(good.copy(network = wifi.copy(notMetered = false)), none, noon, zone))
        assertEquals(blocked(RelayGate.VPN), gates.check(good.copy(network = wifi.copy(vpn = true)), none, noon, zone))
        assertEquals(blocked(RelayGate.NO_ADDRESS), gates.check(good.copy(network = wifi.copy(bind = null)), none, noon, zone))
        assertEquals(blocked(RelayGate.NO_ADDRESS), gates.check(good.copy(network = wifi.copy(gateway = null)), none, noon, zone))
        assertEquals(blocked(RelayGate.NO_ADDRESS), gates.check(good.copy(network = wifi.copy(bind = "169.254.3.4")), none, noon, zone))
    }

    @Test fun `charging is always required, and half a battery`() {
        val gates = RelayGates()
        assertEquals(blocked(RelayGate.NOT_CHARGING), gates.check(good.copy(battery = charging.copy(charging = false)), none, noon, zone))
        assertEquals(blocked(RelayGate.NOT_CHARGING), gates.check(good.copy(battery = null), none, noon, zone))
        assertEquals(blocked(RelayGate.LOW_BATTERY), gates.check(good.copy(battery = charging.copy(percent = 49)), none, noon, zone))
        assertEquals(GateVerdict.Pass(RelayNetwork("192.168.1.20", "192.168.1.1")), gates.check(good.copy(battery = charging.copy(percent = 50)), none, noon, zone))
    }

    @Test fun `a warm phone rests until it has been cool for ten minutes`() {
        val gates = RelayGates()
        val hot = good.copy(battery = charging.copy(tenthsCelsius = 400))
        assertEquals(blocked(RelayGate.HOT, noon + 600_000), gates.check(hot, none, noon, zone))
        // Cool again, but not for long enough.
        assertEquals(blocked(RelayGate.HOT, noon + 600_000), gates.check(good, none, noon + 599_999, zone))
        assertEquals(GateVerdict.Pass(RelayNetwork("192.168.1.20", "192.168.1.1")), gates.check(good, none, noon + 600_000, zone))
        // The platform's own thermal status counts from MODERATE.
        assertEquals(GateVerdict.Pass(RelayNetwork("192.168.1.20", "192.168.1.1")), gates.check(good.copy(thermalStatus = 1), none, noon + 600_001, zone))
        assertEquals(blocked(RelayGate.HOT, noon + 1_300_000), gates.check(good.copy(thermalStatus = 2), none, noon + 700_000, zone))
    }

    @Test fun `heat is remembered even while another gate speaks first`() {
        val gates = RelayGates()
        gates.check(RelayConditions(null, charging.copy(tenthsCelsius = 450)), none, noon, zone)
        assertEquals(blocked(RelayGate.HOT, noon + 600_000), gates.check(good, none, noon + 60_000, zone))
    }

    @Test fun `the daily limit stops the relay until local midnight`() {
        val gates = RelayGates(dailyLimit = 1000)
        val midnight = ZonedDateTime.of(2026, 10, 31, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val used = RelayDailyUsage("2026-10-30", 1000)
        assertEquals(blocked(RelayGate.DAILY_LIMIT, midnight), gates.check(good, used, noon, zone))
        assertEquals(GateVerdict.Pass(RelayNetwork("192.168.1.20", "192.168.1.1")), gates.check(good, RelayDailyUsage("2026-10-30", 999), noon, zone))
        // A new day starts the count again.
        assertEquals(GateVerdict.Pass(RelayNetwork("192.168.1.20", "192.168.1.1")), gates.check(good, used, midnight, zone))
    }

    @Test fun `usage counts against the local day`() {
        val usage = RelayDailyUsage("2026-10-29", 500).add("2026-10-30", 20).add("2026-10-30", -5).add("2026-10-30", 30)
        assertEquals(RelayDailyUsage("2026-10-30", 50), usage)
        assertEquals("2026-10-30", RelayClockDays.localDay(noon, zone))
    }
}
