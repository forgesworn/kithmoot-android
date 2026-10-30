package dev.forgesworn.kithmoot.callrelay

import java.time.Instant
import java.time.ZoneId

/**
 * What the phone says about itself, read from platform APIs only
 * (`ConnectivityManager`, `BatteryManager`, `PowerManager`). Null means
 * not known yet, and a gate that needs it does not pass.
 */
internal data class RelayConditions(
    val network: NetworkFacts? = null,
    val battery: BatteryFacts? = null,
    /** `PowerManager.getCurrentThermalStatus()`. */
    val thermalStatus: Int = 0,
)

/** The default network. [bind] and [gateway] are null when it has no usable IPv4 address or router. */
internal data class NetworkFacts(val wifi: Boolean, val notMetered: Boolean, val vpn: Boolean, val bind: String?, val gateway: String?) {
    override fun toString() = "NetworkFacts(wifi=$wifi, notMetered=$notMetered, vpn=$vpn, address=${bind != null}, gateway=${gateway != null})"
}

internal data class BatteryFacts(val charging: Boolean, val percent: Int, val tenthsCelsius: Int)

/** Why the relay is held off while the person wants it on, in plain words. */
internal enum class RelayGate(val text: String) {
    NO_WIFI("It runs only on Wi-Fi."),
    METERED("This Wi-Fi is set as metered, so the relay stays off."),
    VPN(CANNOT_HOST_TEXT.getValue("vpn")),
    NO_ADDRESS("Waiting for this Wi-Fi to finish connecting."),
    NOT_CHARGING("It runs only while this phone is charging."),
    LOW_BATTERY("It waits until the battery is at least 50%."),
    HOT("This phone is warm. It tries again after 10 minutes cool."),
    DAILY_LIMIT("Today's limit of 2 GB is used up."),
}

internal sealed interface GateVerdict {
    data class Pass(val network: RelayNetwork) : GateVerdict
    /** [retryAt] is when the answer may change with nothing else changing (cool-down, midnight). */
    data class Blocked(val gate: RelayGate, val retryAt: Long? = null) : GateVerdict
}

/** Relayed bytes counted against [day] (YYYY-MM-DD, local). */
internal data class RelayDailyUsage(val day: String = "", val bytes: Long = 0) {
    fun on(today: String) = if (day == today) this else RelayDailyUsage(today, 0)
    fun add(today: String, delta: Long) = on(today).let { it.copy(bytes = it.bytes + maxOf(0, delta)) }
}

internal object RelayClockDays {
    fun localDay(nowMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()
    fun nextLocalMidnight(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * The relay's gates (design, 4.3), checked before every start and on every
 * change. All must pass:
 *
 * - the default network is Wi-Fi, not metered (`NET_CAPABILITY_NOT_METERED`)
 *   and not a VPN, with a usable IPv4 address and router;
 * - the phone is charging (always, owner decision of 28 October 2026);
 * - the battery is at least 50%;
 * - the battery is below 40 °C and the thermal status below
 *   `THERMAL_STATUS_MODERATE`, and has been for 10 minutes since it last was not;
 * - less than the daily limit was relayed today, local time.
 *
 * Holds the one piece of memory a gate needs: when the phone was last hot.
 */
internal class RelayGates(private val dailyLimit: Long = RelayCaps.DAILY_LIMIT_BYTES, private val coolMs: Long = COOL_MS) {
    private var lastHotAt: Long? = null

    fun check(conditions: RelayConditions, usage: RelayDailyUsage, nowMs: Long, zone: ZoneId): GateVerdict {
        val battery = conditions.battery
        // Recorded whichever gate speaks first, so the cool-down runs from the last hot reading.
        if (conditions.thermalStatus >= THERMAL_STATUS_MODERATE || battery != null && battery.tenthsCelsius >= MAX_TENTHS_CELSIUS) lastHotAt = nowMs
        val network = conditions.network ?: return GateVerdict.Blocked(RelayGate.NO_WIFI)
        if (network.vpn) return GateVerdict.Blocked(RelayGate.VPN)
        if (!network.wifi) return GateVerdict.Blocked(RelayGate.NO_WIFI)
        if (!network.notMetered) return GateVerdict.Blocked(RelayGate.METERED)
        val bind = network.bind?.takeIf(RelayConfig::usableAddress)
        val gateway = network.gateway?.takeIf(RelayConfig::usableAddress)
        if (bind == null || gateway == null || bind == gateway) return GateVerdict.Blocked(RelayGate.NO_ADDRESS)
        if (battery == null || !battery.charging) return GateVerdict.Blocked(RelayGate.NOT_CHARGING)
        if (battery.percent < MIN_PERCENT) return GateVerdict.Blocked(RelayGate.LOW_BATTERY)
        lastHotAt?.let { hot -> if (nowMs - hot < coolMs) return GateVerdict.Blocked(RelayGate.HOT, hot + coolMs) }
        val today = RelayClockDays.localDay(nowMs, zone)
        if (usage.on(today).bytes >= dailyLimit) return GateVerdict.Blocked(RelayGate.DAILY_LIMIT, RelayClockDays.nextLocalMidnight(nowMs, zone))
        return GateVerdict.Pass(RelayNetwork(bind, gateway))
    }

    companion object {
        /** `PowerManager.THERMAL_STATUS_MODERATE`. */
        const val THERMAL_STATUS_MODERATE = 2
        /** 40 °C, as `BatteryManager.EXTRA_TEMPERATURE` gives it (tenths of a degree). */
        const val MAX_TENTHS_CELSIUS = 400
        const val MIN_PERCENT = 50
        const val COOL_MS = 10 * 60_000L
    }
}
