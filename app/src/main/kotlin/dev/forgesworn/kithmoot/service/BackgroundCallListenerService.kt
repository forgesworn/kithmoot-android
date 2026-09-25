package dev.forgesworn.kithmoot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry
import dev.forgesworn.kithmoot.notifications.CallRingSettings
import dev.forgesworn.kithmoot.notifications.IncomingCallRingCoordinator
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.callsOf
import dev.forgesworn.kithmoot.session.groupByParticipant
import dev.forgesworn.kithmoot.session.starter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Optional, opt-in foreground service: [BackgroundRingSettings]. While it
 * runs, it keeps a read-only subscription open for every saved room set to
 * Ring me that no open `RoomViewModel` already covers (see
 * `roomsToWatch` / `ActiveRoomRegistry`), decodes just enough of each
 * roster event to know whether a call has started and who is on it, and
 * feeds that into the same [IncomingCallRingCoordinator] /
 * `IncomingCallRinger` an open room uses - so the ringing rule is identical
 * whether KithMoot is open or not.
 *
 * It never publishes anything: no presence, no roster entry, no read
 * receipt, no credential. Each [RelayPool] it opens here is only ever
 * `subscribe`d on, never `publish`ed to.
 *
 * Modelled on Cambium's `HeartwoodKeepAliveService` (`specialUse` foreground
 * type with a declared subtype, `START_STICKY`, a boot receiver gated on a
 * toggle, jittered relay reconnection already built into [RelayPool]) - the
 * pattern only; this holds no dependency on Cambium.
 */
class BackgroundCallListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val sessions = mutableMapOf<String, WatchSession>()

    private class WatchSession(
        val pool: RelayPool,
        val job: Job,
        val roster: BackgroundRoster,
        val coordinator: IncomingCallRingCoordinator,
    )

    override fun onCreate() {
        super.onCreate()
        channel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(0), type)
        } catch (e: Exception) {
            // The OS can refuse a foreground start from the background on API 31+.
            // Give up quietly; the next toggle-driven start retries.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startLoop()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        sessions.values.forEach { stopWatch(it) }
        sessions.clear()
        scope.cancel()
        super.onDestroy()
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                if (!reconcile()) break
                delay(RECONCILE_INTERVAL_MS)
            }
            sessions.values.forEach { stopWatch(it) }
            sessions.clear()
            stopSelf()
        }
    }

    /** Returns false when the service should stop: the toggle is off, or no
     *  saved room wants Ring me any more. */
    private fun reconcile(): Boolean {
        val application = application as KithMootApplication
        val ringSettings = CallRingSettings(this)
        val toggle = BackgroundRingSettings(this).enabled()
        val savedIds = application.savedRooms.list().map { it.id }
        if (!shouldRunBackgroundListener(toggle, savedIds, ringSettings::modeFor)) return false

        val candidates = savedIds.mapNotNull { id -> watchFor(application, id) }
        val wanted = roomsToWatch(candidates, ringSettings::modeFor, ActiveRoomRegistry::isOpen)
        val wantedIds = wanted.map { it.stableRoomId }.toSet()

        sessions.keys.filterNot { it in wantedIds }.forEach { id -> sessions.remove(id)?.let { stopWatch(it) } }
        for (watch in wanted) {
            if (!sessions.containsKey(watch.stableRoomId)) sessions[watch.stableRoomId] = startWatch(watch)
        }
        updateNotification(sessions.size)
        return true
    }

    /** The current traffic id and key for a saved room, following any rekey
     *  recorded in [KithMootApplication.roomEpochs] - the same durable
     *  journal `RoomViewModel` reads, so this never derives its own key. */
    private fun watchFor(application: KithMootApplication, roomId: String): BackgroundRoomWatch? {
        return try {
            val saved = application.savedRooms.get(roomId) ?: return null
            if (saved.movedOn || saved.retired) return null
            val secret = application.roomEpochs.get(roomId)?.currentSecret ?: saved.secret
            val room = deriveRoom(secret)
            BackgroundRoomWatch(saved.id, saved.name, room.roomId, room.roomKey, saved.relays, saved.participant)
        } catch (_: Exception) {
            null
        }
    }

    private fun startWatch(watch: BackgroundRoomWatch): WatchSession {
        val pool = RelayPool(watch.relays, OkHttpRelaySockets(), scope)
        pool.start()
        val roster = BackgroundRoster()
        val coordinator = IncomingCallRingCoordinator(applicationContext)
        // "#d", not "d": Filter's wire form for a tag filter (see relay/Filter.kt).
        // A plain "d" is not a NIP-01 filter field at all, so a relay would have
        // ignored it and sent every room's roster traffic on that connection -
        // exactly what "minimal relay subscriptions" rules out.
        val filter = Filter(kinds = listOf(KIND_ROSTER), tags = mapOf("#d" to listOf(watch.trafficRoomId)))
        val job = scope.launch {
            pool.subscribe(listOf(filter)).collect { event ->
                // Handed over between reconcile ticks: the open room rings now.
                if (ActiveRoomRegistry.isOpen(watch.stableRoomId)) {
                    coordinator.end()
                    return@collect
                }
                val now = System.currentTimeMillis() / 1000
                val entry = decodeRosterEvent(event, watch.trafficRoomId, watch.roomKey, now) ?: return@collect
                roster.accept(entry, now)
                val people = groupByParticipant(roster.current(now))
                val current = callsOf(people).firstOrNull()
                // Never on the call from here: this device only listens.
                coordinator.update(watch.stableRoomId, watch.roomName, current?.id, current?.starter(people), watch.self, joined = false)
            }
        }
        return WatchSession(pool, job, roster, coordinator)
    }

    private fun stopWatch(session: WatchSession) {
        session.job.cancel()
        session.pool.stop()
        // Clears any ring this session had going, so a hand-over to an
        // opened RoomViewModel never leaves a stray notification behind.
        session.coordinator.end()
    }

    private fun channel() {
        val ch = NotificationChannel(CHANNEL_ID, "Listening for calls", NotificationManager.IMPORTANCE_LOW)
        ch.description = "A quiet notification while KithMoot listens for calls in your Ring me rooms without being open."
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun updateNotification(watching: Int) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(watching))
        } catch (_: SecurityException) {
            // Notification permission withdrawn mid-run: the service still
            // works, it just cannot say so.
        }
    }

    private fun notification(watching: Int): Notification {
        val turnOff = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, BackgroundRingActionReceiver::class.java).setAction(BackgroundRingActionReceiver.ACTION_TURN_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (watching == 0) "Waiting for a room set to Ring me." else "Watching $watching room${if (watching == 1) "" else "s"} set to Ring me."
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("Listening for calls")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Turn off", turnOff)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "background_call_listen_v1"
        private const val NOTIFICATION_ID = 4604
        private const val RECONCILE_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundCallListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundCallListenerService::class.java))
        }
    }
}
