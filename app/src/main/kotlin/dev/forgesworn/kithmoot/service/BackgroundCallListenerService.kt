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
import dev.forgesworn.kithmoot.protocol.CALL_BELL_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.KIND_CALL_BELL
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.epoch.activeEpochFor
import dev.forgesworn.kithmoot.protocol.decodeCallBellEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

/**
 * Optional, opt-in foreground service: [BackgroundRingSettings]. While it
 * runs, it keeps one shared read-only subscription open across every
 * relay any saved room set to Ring me uses (see [roomsToWatch] /
 * [sharedRelayUrls]), listening only for that room's call bell (kind
 * 1464, `protocol/CallBell.kt`) - never for the roster's 20-second
 * heartbeat, which a phone with the app closed cannot afford to wake the
 * radio for.
 *
 * It never publishes anything: no presence, no roster entry, no read
 * receipt, no credential. The shared [RelayPool] it opens here is only
 * ever `subscribe`d on, never `publish`ed to.
 *
 * Modelled on Cambium's `HeartwoodKeepAliveService` (`specialUse` foreground
 * type with a declared subtype, `START_STICKY`, a boot receiver gated on a
 * toggle, jittered relay reconnection already built into [RelayPool]) - the
 * pattern only; this holds no dependency on Cambium.
 */
class BackgroundCallListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private var midnightJob: Job? = null
    private var subscriptionJob: Job? = null
    private var pool: RelayPool? = null
    private var watches: List<BackgroundRoomWatch> = emptyList()
    private val coordinators = mutableMapOf<String, IncomingCallRingCoordinator>()

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
        startMidnightRefresh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        midnightJob?.cancel()
        stopSubscription()
        coordinators.values.forEach { it.end() }
        coordinators.clear()
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
            stopSubscription()
            coordinators.values.forEach { it.end() }
            coordinators.clear()
            stopSelf()
        }
    }

    /** Re-subscribes with a fresh tag set at the next UTC midnight, forever
     *  (while the service runs) - the one thing a mere relay reconnect,
     *  which [RelayPool] already re-sends the live REQ for, does not cover. */
    private fun startMidnightRefresh() {
        midnightJob?.cancel()
        midnightJob = scope.launch {
            while (isActive) {
                delay(millisUntilNextUtcMidnight())
                if (watches.isNotEmpty()) resubscribe(watches)
            }
        }
    }

    /** Returns false when the service should stop: the toggle is off, or no
     *  saved room wants Ring me any more. */
    private fun reconcile(): Boolean {
        val application = application as KithMootApplication
        val ringSettings = CallRingSettings(this)
        val toggle = BackgroundRingSettings(this).enabled()
        val savedIds = savedRoomIdsOrNone(application.savedRooms)
        val notificationsPermitted = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!shouldRunBackgroundListener(toggle, savedIds, ringSettings::modeFor, notificationsPermitted)) return false

        val candidates = savedIds.mapNotNull { id -> watchFor(application, id) }
        val wanted = roomsToWatch(candidates, ringSettings::modeFor, ActiveRoomRegistry::isOpen)
        val wantedIds = wanted.map { it.stableRoomId }.toSet()

        coordinators.keys.filterNot { it in wantedIds }.forEach { id -> coordinators.remove(id)?.end() }
        for (watch in wanted) coordinators.getOrPut(watch.stableRoomId) { IncomingCallRingCoordinator(applicationContext) }

        if (wanted.map { it.stableRoomId }.toSet() != watches.map { it.stableRoomId }.toSet()) resubscribe(wanted)
        updateNotification(wanted.size)
        return true
    }

    /** The current traffic key for a saved room, following any rekey
     *  recorded in [KithMootApplication.roomEpochs] via the shared, tested
     *  [activeEpochFor] - the same derivation `RoomViewModel` uses, so this
     *  never drifts onto a stale (pre-rekey) key the way deriving directly
     *  from the room secret used to. The room's own (epoch-0) id -
     *  [dev.forgesworn.kithmoot.storage.SavedRoom.id] - is what the bell's
     *  device signature is bound to, and is used as-is regardless of any
     *  later rekey; see `protocol/CallBell.kt`. Returns null (watch
     *  nothing) once the room's epoch has been REMOVED or CLOSED, and for
     *  an anonymous (Tor-only) room, which this clearnet listener must
     *  never dial. */
    private fun watchFor(application: KithMootApplication, roomId: String): BackgroundRoomWatch? {
        return try {
            val saved = application.savedRooms.get(roomId) ?: return null
            if (saved.movedOn || saved.retired || saved.anonymous) return null
            val stored = saved.authority?.let { application.roomEpochs.get(roomId) }
            val epoch = activeEpochFor(saved, stored) ?: return null
            BackgroundRoomWatch(saved.id, saved.name, epoch.key, saved.relays, saved.participant, saved.devicePubkey)
        } catch (_: Exception) {
            null
        }
    }

    /** Tears down the current subscription and, if the relay set changed,
     *  the shared pool too, then opens a fresh one for [wanted]. */
    private fun resubscribe(wanted: List<BackgroundRoomWatch>) {
        subscriptionJob?.cancel()
        subscriptionJob = null
        val newRelays = sharedRelayUrls(wanted)
        if (pool?.relayUrls != newRelays) {
            pool?.stop()
            pool = if (newRelays.isEmpty()) null else RelayPool(newRelays, OkHttpRelaySockets(OkHttpRelaySockets.backgroundClient()), scope).also { it.start() }
        }
        watches = wanted
        val activePool = pool ?: return
        if (wanted.isEmpty()) return
        // One subscription per relay, each targeted with `only` at exactly
        // the relays that room actually lists: a relay used by room X must
        // never learn room Y's day tags just because both share this one
        // pool. The filter is rebuilt from the room set at every send -
        // first REQ and every reconnect alike - so a relay that drops and
        // returns gets today's tags, not whatever they were at start-up.
        subscriptionJob = scope.launch {
            for (url in newRelays) {
                val watchesForUrl = wanted.filter { url in it.relays }
                if (watchesForUrl.isEmpty()) continue
                launch {
                    activePool.subscribe(
                        filters = {
                            listOf(Filter(
                                kinds = listOf(KIND_CALL_BELL),
                                // "#d", not "d": Filter's wire form for a tag filter (see relay/Filter.kt).
                                tags = mapOf("#d" to callBellFilterTags(watchesForUrl, now())),
                                since = now() - CALL_BELL_TTL_SECONDS,
                            ))
                        },
                        only = setOf(url),
                    ).collect { event -> onBell(event) }
                }
            }
        }
    }

    private fun stopSubscription() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        pool?.stop()
        pool = null
        watches = emptyList()
    }

    private fun onBell(event: NostrEvent) {
        val tag = event.tagValue("d") ?: return
        val now = now()
        val candidates = watches.filter { tag in callBellTagsFor(it, now) }
        for (watch in candidates) {
            val bell = decodeCallBellEvent(event, watch.stableRoomId, watch.bellKey, now) ?: continue
            val participant = BackgroundParticipantCache(applicationContext).participantFor(watch.stableRoomId, bell.device)
            // Handed over between reconcile ticks: the open room rings now.
            val coordinator = coordinators[watch.stableRoomId] ?: return
            if (ActiveRoomRegistry.isOpen(watch.stableRoomId)) {
                coordinator.end()
                return
            }
            when (val outcome = outcomeFor(bell, watch, participant)) {
                is BellOutcome.Ignore -> Unit
                is BellOutcome.Ring ->
                    coordinator.update(watch.stableRoomId, watch.roomName, outcome.callId, outcome.caller, watch.selfParticipant, joined = false)
                is BellOutcome.Stop ->
                    coordinator.update(watch.stableRoomId, watch.roomName, null, null, watch.selfParticipant, joined = false)
            }
            return
        }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

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
        val text = if (watching == 0) "Waiting for a room set to Ring me." else "Listening for calls in $watching room${if (watching == 1) "" else "s"}."
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
        private const val RECONCILE_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BackgroundCallListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundCallListenerService::class.java))
        }
    }
}

/** Milliseconds from now until the next UTC midnight, at least one second. */
internal fun millisUntilNextUtcMidnight(now: Instant = Instant.now()): Long {
    val today = now.atZone(ZoneOffset.UTC).toLocalDate()
    val nextMidnight = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    return maxOf(1_000L, nextMidnight.toEpochMilli() - now.toEpochMilli())
}
