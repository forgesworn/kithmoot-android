package dev.forgesworn.kithmoot.callrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager
import java.net.Inet4Address
import java.util.concurrent.Executor

/**
 * Reads what the gates need from platform APIs only, on [handler]'s thread:
 * the default network from `ConnectivityManager` (transport, metering, VPN,
 * its IPv4 address and router from `LinkProperties`), the battery from the
 * sticky `ACTION_BATTERY_CHANGED`, and the thermal status from
 * `PowerManager`. No Google Play services or any other library.
 */
internal class RelayConditionsWatcher(private val context: Context, private val handler: Handler, private val changed: (RelayConditions) -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var capabilities: NetworkCapabilities? = null
    private var link: LinkProperties? = null
    private var battery: BatteryFacts? = null
    private var thermal = 0
    private var started = false

    private var current: Network? = null

    /** A new default network: forget what the old one said, so the two are never mixed. */
    private fun follow(network: Network) {
        if (network == current) return
        current = network
        capabilities = null
        link = null
    }

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { follow(network); report() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { follow(network); capabilities = caps; report() }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { follow(network); link = properties; report() }
        override fun onLost(network: Network) {
            if (network != current) return
            current = null; capabilities = null; link = null
            report()
        }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { battery = batteryFacts(intent); report() }
    }
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status -> thermal = status; report() }

    fun start() {
        if (started) return
        started = true
        thermal = power?.currentThermalStatus ?: 0
        power?.addThermalStatusListener(Executor { handler.post(it) }, thermalListener)
        val sticky = context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler, Context.RECEIVER_NOT_EXPORTED)
        battery = sticky?.let(::batteryFacts)
        connectivity?.registerDefaultNetworkCallback(network, handler)
        report()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivity?.unregisterNetworkCallback(network) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { power?.removeThermalStatusListener(thermalListener) }
    }

    private fun report() {
        if (started) changed(RelayConditions(networkFacts(capabilities, link), battery, thermal))
    }

    companion object {
        fun networkFacts(caps: NetworkCapabilities?, link: LinkProperties?): NetworkFacts? {
            caps ?: return null
            val bind = link?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.mapNotNull { it.hostAddress }?.firstOrNull(RelayConfig::usableAddress)
            val gateway = link?.routes?.filter { it.isDefaultRoute }?.mapNotNull { it.gateway as? Inet4Address }
                ?.mapNotNull { it.hostAddress }?.firstOrNull(RelayConfig::usableAddress)
            return NetworkFacts(
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                bind = bind,
                gateway = gateway,
            )
        }

        /** Null when the level is not reported; an unreported temperature counts as hot. */
        fun batteryFacts(intent: Intent): BatteryFacts? {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: Int.MAX_VALUE
            return BatteryFacts(charging = plugged, percent = level * 100 / scale, tenthsCelsius = temperature)
        }
    }
}
