package dev.forgesworn.kithmoot.callrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.RemoteCallbackList
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.media.SpryRelayLibrary
import java.io.File
import java.time.ZoneId

/**
 * The built-in call relay (design, 4.1 to 4.3): a `specialUse` foreground
 * service in its own process (`:relay`), so a native fault ends the relay and
 * not the app. The node runs in this process through the JNI library
 * ([SpryRelayNodeRunner]); [RelayController] holds the rules and
 * [RelayConditionsWatcher] feeds it the gates.
 *
 * The app's main process starts it with [ACTION_HOLD], binds and hands over
 * the start request ([ICallRelay.start]). Nothing secret is written to disk
 * here, so a service the system restarts on its own (a null intent) stops at
 * once: it has nothing to run with.
 *
 * Its notification, on a low-importance channel, says what it is doing and
 * carries Stop, which stops it directly. A partial wake lock and a Wi-Fi
 * low-latency lock are held only while at least one allocation is live.
 */
class CallRelayService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private val main = Handler(Looper.getMainLooper())
    private lateinit var controller: RelayController
    private lateinit var watcher: RelayConditionsWatcher
    private val callbacks = RemoteCallbackList<ICallRelayCallback>()
    @Volatile private var latest: String = RelayState().toJson()
    @Volatile private var foreground = false
    @Volatile private var lastStartId = 0
    @Volatile private var finishing = false
    private var shown: List<Any?>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val holdCheck = Runnable { if (controller.idle()) finish() }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("call-relay-control").apply { start() }
        handler = Handler(thread.looper)
        val directory = RelayIdentityStore.stateDirectory(this)
        val clock = object : RelayClock {
            override fun now() = System.currentTimeMillis()
            override val zone: ZoneId get() = ZoneId.systemDefault()
            override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
                val runnable = Runnable(task)
                handler.postDelayed(runnable, delayMs)
                return { handler.removeCallbacks(runnable) }
            }
        }
        controller = RelayController(SpryRelayNodeRunner(handler::post), clock, RelayUsageFile(directory),
            File(directory, RelayIdentityStore.STATE_FILE).absolutePath, ::onState,
            libraryReady = { SpryRelayLibrary.load() == SpryRelayLibrary.Status.Ready })
        watcher = RelayConditionsWatcher(this, handler) { conditions -> controller.conditions(conditions) }
        channel()
        handler.post { watcher.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_HOLD -> {
                goForeground()
                handler.removeCallbacks(holdCheck)
                handler.postDelayed(holdCheck, HOLD_MS)
            }
            ACTION_STOP -> handler.post {
                controller.turnOff()
                if (controller.idle()) finish()
            }
            // Restarted by the system with nothing to run with.
            else -> handler.post { if (controller.idle()) finish() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        handler.post { watcher.stop() }
        releaseLocks()
        callbacks.kill()
        thread.quitSafely()
        super.onDestroy()
        // A fresh process for the next run: no native handle, thread or lock survives.
        Process.killProcess(Process.myPid())
    }

    private val binder = object : ICallRelay.Stub() {
        private fun caller() { if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Not this app") }

        override fun start(turnSecret: ByteArray?, pairingPublicKey: String?, controlRelays: Array<String>?, listenPort: Int): Int {
            caller()
            val request = try {
                require(turnSecret != null && turnSecret.size == 32 && pairingPublicKey != null && controlRelays != null)
                require(Regex("^[0-9a-f]{64}$").matches(pairingPublicKey) && listenPort in RelayCaps.LISTEN_PORTS)
                RelayRequest(turnSecret.toHex(), pairingPublicKey, RelayConfig.controlRelayList(controlRelays.toList()), listenPort)
            } catch (_: Exception) { return ICallRelay.ERROR_INVALID } finally { turnSecret?.fill(0) }
            main.post { goForeground() }
            handler.post {
                handler.removeCallbacks(holdCheck)
                finishing = false
                controller.turnOn(request)
            }
            return ICallRelay.OK
        }

        override fun stop() {
            caller()
            handler.post {
                controller.turnOff()
                if (controller.idle()) finish()
            }
        }

        override fun state(): String { caller(); return latest }

        override fun watch(callback: ICallRelayCallback?) {
            caller()
            if (callback == null) return
            callbacks.register(callback)
            try { callback.onState(latest) } catch (_: Exception) { /* Gone already. */ }
        }

        override fun unwatch(callback: ICallRelayCallback?) { caller(); callback?.let(callbacks::unregister) }
    }

    /** On the control thread. */
    private fun onState(state: RelayState) {
        latest = state.toJson()
        broadcast(latest)
        if (controller.allocationsLive()) holdLocks() else releaseLocks()
        if (foreground) show(state, ongoing = true)
        if (controller.idle() && state.phase != RelayPhase.STOPPING) finish()
    }

    private fun broadcast(json: String) = synchronized(callbacks) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                try { callbacks.getBroadcastItem(index).onState(json) } catch (_: Exception) { /* That watcher has gone. */ }
            }
        } finally { callbacks.finishBroadcast() }
    }

    private fun goForeground() {
        if (foreground) return
        try {
            // Android 13 knows no specialUse type and refuses one it does not know.
            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(RelayState(phase = RelayPhase.STARTING), ongoing = true), type)
            foreground = true
        } catch (_: Exception) {
            // Android refused a foreground start (the app was in the background).
            handler.post {
                controller.turnOff()
                finish()
            }
        }
    }

    /** On the control thread: nothing runs and nothing is wanted, so end the service. */
    private fun finish() {
        if (finishing) return
        finishing = true
        handler.removeCallbacks(holdCheck)
        releaseLocks()
        val state = controller.state()
        val startId = lastStartId
        main.post {
            // What stopped it by itself stays on show; the person's own Stop leaves nothing.
            val keep = state.phase == RelayPhase.FAILED || state.phase == RelayPhase.CANNOT_HOST
            if (foreground) ServiceCompat.stopForeground(this, if (keep) ServiceCompat.STOP_FOREGROUND_DETACH else ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
            if (keep) show(state, ongoing = false)
            // A start that arrived meanwhile keeps the service.
            if (!stopSelfResult(startId)) finishing = false
        }
    }

    @Suppress("DEPRECATION")
    private fun holdLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (wakeLock ?: getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KithMoot:call-relay")
                ?.apply { setReferenceCounted(false) })?.also { runCatching { it.acquire() } }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock = (wifiLock ?: applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "KithMoot:call-relay")
                ?.apply { setReferenceCounted(false) })?.also { runCatching { it.acquire() } }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wifiLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
    }

    private fun channel() {
        val channel = NotificationChannel(CHANNEL_ID, "Call relay", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows while this phone relays calls for people in your rooms."
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Synchronized private fun show(state: RelayState, ongoing: Boolean) {
        val key = listOf(state.phase, state.gate, state.reason, state.failure, state.allocations, ongoing)
        if (key == shown) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state, ongoing))
            shown = key
        } catch (_: SecurityException) { /* Notifications are off: the relay still runs. */ }
    }

    private fun notification(state: RelayState, ongoing: Boolean): Notification {
        val (title, text) = RelayNotice.words(state)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(open)
        if (ongoing) builder.addAction(0, "Stop", PendingIntent.getService(this, 1,
            Intent(this, CallRelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return builder.build()
    }

    companion object {
        const val ACTION_HOLD = "dev.forgesworn.kithmoot.callrelay.HOLD"
        const val ACTION_STOP = "dev.forgesworn.kithmoot.callrelay.STOP"
        internal const val CHANNEL_ID = "call_relay_v1"
        private const val NOTIFICATION_ID = 4606
        /** A hold with no start request after this long ends the service. */
        private const val HOLD_MS = 30_000L

        internal fun intent(context: Context, action: String) = Intent(context, CallRelayService::class.java).setAction(action)
    }
}

/** The notification's words for a state: never an address, port or key. */
internal object RelayNotice {
    fun words(state: RelayState): Pair<String, String> = when (state.phase) {
        RelayPhase.STARTING -> "Call relay starting" to "Getting ready. This can take up to a minute."
        RelayPhase.RUNNING -> "Call relay on" to when (state.allocations) {
            0L -> "Nobody is using it right now."
            1L -> "Relaying 1 connection for people in your rooms."
            else -> "Relaying ${state.allocations} connections for people in your rooms."
        }
        RelayPhase.STOPPING -> "Call relay stopping" to (state.gate?.text ?: "Stopping.")
        RelayPhase.PAUSED -> "Call relay paused" to (state.message ?: "")
        RelayPhase.CANNOT_HOST -> "Call relay can't run on this network" to
            (state.message.orEmpty() + if (state.wanted) " It tries again on another network." else "")
        RelayPhase.FAILED -> (if (state.failure == RelayFailure.CRASHED) "Call relay restarting" else "Call relay stopped") to (state.message ?: "")
        RelayPhase.OFF -> "Call relay" to "Off."
    }
}
