package dev.forgesworn.kithmoot.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.account.Nip55Bridge
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SignerException
import dev.forgesworn.kithmoot.account.openAccount
import dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry
import dev.forgesworn.kithmoot.notifications.CallRingSettings
import dev.forgesworn.kithmoot.notifications.ChatNotifications
import dev.forgesworn.kithmoot.notifications.MAX_NOTICE_LINES
import dev.forgesworn.kithmoot.notifications.MessageNotices
import dev.forgesworn.kithmoot.notifications.NoticeLine
import dev.forgesworn.kithmoot.notifications.noticeContent
import dev.forgesworn.kithmoot.notifications.noticeLine
import dev.forgesworn.kithmoot.notifications.restoredNoticeLines
import dev.forgesworn.kithmoot.session.isDmPolicy
import dev.forgesworn.kithmoot.notifications.IncomingCallRingCoordinator
import dev.forgesworn.kithmoot.protocol.CALL_BELL_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.KIND_CALL_BELL
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.epoch.activeEpochFor
import dev.forgesworn.kithmoot.epoch.pastEpochsFor
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.session.PastEpoch
import dev.forgesworn.kithmoot.protocol.decodeCallBellEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.PendingChatOutbox
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.BackgroundInboxVault
import dev.forgesworn.kithmoot.storage.PendingChatVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Optional, opt-in foreground service with two jobs, each behind its own
 * switch: ringing for a call in a Ring me room ([BackgroundRingSettings], on
 * by default) and receiving messages for saved rooms while KithMoot is closed
 * ([BackgroundDeliverySettings], on by default). See the P4-01 delivery
 * ticket and `BackgroundDelivery.kt` for the rules.
 *
 * Rooms that list the same public relays share one pool ([SharedRelayPools]),
 * each with its own subscriptions on it, so the socket count follows the
 * relays rather than the rooms. A room with a Link relay gets its own pool.
 * Every pool is built from the same hybrid socket factory an open room uses:
 * a Link relay address needs that room's active consent and otherwise fails
 * closed, so a box's node id never reaches OkHttp or DNS. For calls it
 * listens only for the room's bell (kind 1464, `protocol/CallBell.kt`), never
 * the roster's heartbeat. For messages it
 * listens for the room's chat under its current epoch, records verified
 * messages in the room's [dev.forgesworn.kithmoot.session.BackgroundInbox]
 * without their text, and shows each new one as a notification. The text goes
 * to Android's notification, only when previews are on, and is otherwise held
 * in this process's memory and nowhere else.
 *
 * For both it follows the room's rekeys (kind 1462 from its authority), opening
 * this device's own copy with the saved device key and moving to the new epoch
 * (`BackgroundRekey.kt`), so a room nobody opens does not go quiet at its next
 * rekey. A rekey that gives this device no copy is left for the open room.
 *
 * The only thing it ever publishes is a room's retained pending message: the
 * exact event the person already signed, on the epoch it was sealed for,
 * cleared only when a relay confirms it. No presence, roster entry, read
 * receipt or credential. A reply typed on a message notification is such a
 * message, and goes out over the room's pool here (see `BackgroundReply.kt`).
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
    private val rooms = java.util.concurrent.ConcurrentHashMap<String, RoomHandle>()
    private val coordinators = mutableMapOf<String, IncomingCallRingCoordinator>()
    private val flushing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var network = true
    @Volatile private var reconciled = false
    /** The notification last shown, so a start while running (the app coming to the front) keeps it. */
    @Volatile private var shown: Notification? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var accountSigner: ParticipantSigner? = null
    private var accountLoaded = false
    private val registryListener: (String) -> Unit = { scope.launch { reconcileNow() } }
    /** The lines each room's notification shows, in memory only. */
    private val noticeLines = java.util.concurrent.ConcurrentHashMap<String, List<NoticeLine>>()
    private val noticeSoundAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** What the service's own notification last said, so a report that changes nothing posts nothing. */
    private var noticeShows: List<Any>? = null
    /** One client for every background socket: one dispatcher and one set of threads. */
    private val publicSockets by lazy { OkHttpRelaySockets(OkHttpRelaySockets.backgroundClient()) }
    private val sharedPoolsLazy = lazy {
        val application = application as KithMootApplication
        SharedRelayPools { relays -> RelayPool(relays, backgroundSockets(publicSockets, application.linkEngine, ActiveLinkRoute { null }), scope) }
    }
    private val sharedPools by sharedPoolsLazy

    /** One watched room: what it listens for, keyed so a rekey or relay change rebuilds it. */
    private class RoomHandle(
        val key: String,
        val watch: BackgroundRoomWatch,
        val epochId: String,
        val epochKey: ByteArray,
        val delivery: Boolean,
        val pool: RelayPool,
        /** [pool] belongs to [sharedPools], and is released there rather than stopped. */
        val shared: Boolean,
        val jobs: List<Job>,
        val needsSigner: java.util.concurrent.atomic.AtomicBoolean,
    )

    override fun onCreate() {
        super.onCreate()
        alive = true
        running = this
        channel()
        watchNetwork()
        ActiveRoomRegistry.listen(registryListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, shown ?: notification(DeliveryState.RECONNECTING, 0, 0), type)
        } catch (e: Exception) {
            // The OS can refuse a foreground start from the background on API 31+.
            // Say so; the next toggle-driven start or launch retries.
            BackgroundDeliverySettings(this).report(DeliveryState.RESTRICTED, running = false)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startLoop()
        startMidnightRefresh()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        if (running === this) running = null
        ActiveRoomRegistry.unlisten(registryListener)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        loopJob?.cancel()
        midnightJob?.cancel()
        stopAll()
        coordinators.values.forEach { it.end() }
        coordinators.clear()
        BackgroundDeliverySettings(this).report(DeliveryState.OFF, running = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                if (!reconcileNow()) break
                CredentialRenewal.renewQuietly(applicationContext, fromBackground = true)
                delay(RECONCILE_INTERVAL_MS)
            }
            stopAll()
            coordinators.values.forEach { it.end() }
            coordinators.clear()
            stopSelf()
        }
    }

    /** Re-subscribes every room with a fresh bell tag set at the next UTC
     *  midnight, forever (while the service runs) - the one thing a mere
     *  relay reconnect, which [RelayPool] already re-sends the live REQ for,
     *  does not cover. */
    private fun startMidnightRefresh() {
        midnightJob?.cancel()
        midnightJob = scope.launch {
            while (isActive) {
                delay(millisUntilNextUtcMidnight())
                reconcileMutex.withLock { rooms.keys.toList().forEach { rebuild(it) } }
            }
        }
    }

    private val reconcileMutex = kotlinx.coroutines.sync.Mutex()

    /** Returns false when the service should stop: nothing has work. */
    private suspend fun reconcileNow(): Boolean = reconcileMutex.withLock {
        val application = application as KithMootApplication
        val ringSettings = CallRingSettings(this)
        val ringToggle = BackgroundRingSettings(this).enabled()
        val deliveryToggle = BackgroundDeliverySettings(this).enabled()
        // However ringing came back on - Settings, the banner, the follow-up's own button - the
        // service is running by now, and the offer to turn it back on has had its answer.
        if (ringToggle) cancelTurnOnOffer(this)
        val savedIds = savedRoomIdsOrNone(application.savedRooms)
        val notificationsPermitted = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!shouldRunBackgroundService(ringToggle, deliveryToggle, savedIds, ringSettings::modeFor, notificationsPermitted)) return false

        val candidates = savedIds.mapNotNull { id -> watchFor(application, id) }
        val ringing = if (ringToggle) roomsToWatch(candidates.map { it.watch }, ringSettings::modeFor, ActiveRoomRegistry::isOpen)
            .map { it.stableRoomId }.toSet() else emptySet()
        val delivering = if (deliveryToggle) candidates.filter { it.exclusion == null }.map { it.watch.stableRoomId }.toSet() else emptySet()
        val wanted = candidates.filter { it.watch.stableRoomId in ringing || it.watch.stableRoomId in delivering }

        coordinators.keys.filterNot { it in ringing }.forEach { id -> coordinators.remove(id)?.end() }
        for (id in ringing) coordinators.getOrPut(id) { IncomingCallRingCoordinator(applicationContext) }

        val wantedIds = wanted.map { it.watch.stableRoomId }.toSet()
        rooms.keys.filterNot { it in wantedIds }.forEach { id -> close(rooms.remove(id)) }
        for (candidate in wanted) {
            val id = candidate.watch.stableRoomId
            val delivery = id in delivering
            val key = listOf(id, candidate.epochId, candidate.past.joinToString(",") { it.keys.id }, delivery, id in ringing,
                candidate.watch.relays.joinToString(","), candidate.shareable).joinToString("|")
            if (rooms[id]?.key != key) {
                // Opened before the old one closes, so a shared pool this room
                // was the last on is not stopped only to be started again.
                val previous = rooms[id]
                rooms[id] = open(candidate, key, bell = id in ringing, delivery = delivery)
                close(previous)
            }
        }
        reconciled = true
        report()
        true
    }

    private suspend fun rebuild(id: String) {
        val handle = rooms[id] ?: return
        val application = application as KithMootApplication
        val candidate = watchFor(application, id)
        if (candidate == null) {
            close(rooms.remove(id))
            return
        }
        val bell = coordinators.containsKey(id)
        rooms[id] = open(candidate, handle.key, bell, handle.delivery)
        close(handle)
    }

    private class Candidate(val watch: BackgroundRoomWatch, val epochId: String, val epochKey: ByteArray,
        val exclusion: DeliveryExclusion?, val saved: dev.forgesworn.kithmoot.storage.SavedRoom,
        /** Epochs the room has left whose chat is still read, newest first (kithmoot-android #128). */
        val past: List<PastEpoch> = emptyList(),
        /** Everybody the room's rekeys removed, lower case: refused on [past]. */
        val removed: Set<String> = emptySet(),
        /** No relay of the room's is a Link relay: it can go on a [SharedRelayPools] pool. */
        val shareable: Boolean = false,
        /** The epoch journal holds this room, so a rekey can be followed here (see [BackgroundRekeyFollower]). */
        val followsRekeys: Boolean = false)

    /** The current keys for a saved room, following any rekey recorded in
     *  [KithMootApplication.roomEpochs] via the shared, tested [activeEpochFor]
     *  - the same derivation `RoomViewModel` uses. The room's own (epoch-0)
     *  id is what a bell's device signature is bound to and what chat
     *  credentials name, and is used as-is regardless of any later rekey.
     *  Returns null (watch nothing) once the room's epoch has been REMOVED
     *  or CLOSED, and for an anonymous (Tor-only) room, which this clearnet
     *  listener must never dial. */
    private fun watchFor(application: KithMootApplication, roomId: String): Candidate? {
        return try {
            val saved = application.savedRooms.get(roomId) ?: return null
            // An ended conference room is never watched: it can neither ring nor deliver.
            if (saved.movedOn || saved.retired || saved.anonymous || saved.ended(now())) return null
            val stored = saved.authority?.let { application.roomEpochs.get(roomId) }
            val epoch = activeEpochFor(saved, stored) ?: return null
            val linkRoute = { url: String -> application.linkConsents.activeRoute(saved.participant, saved.id, url) }
            val usesLink = saved.relays.any { url -> linkRoute(url) != null }
            val exclusion = deliveryExclusion(DeliveryCandidate(
                roomId = saved.id,
                anonymous = saved.anonymous,
                quiet = saved.policy?.quiet == true || saved.quietState != null,
                ended = saved.retired || saved.movedOn,
                epochId = epoch.id,
                needsBunker = usesLink && saved.viaAccount && application.accounts.load()?.method == "bunker",
            ), ActiveRoomRegistry::isOpen)
            val past = pastEpochsFor(saved.secret, stored,
                { application.roomEpochs.secretAt(roomId, it) }, { application.roomEpochs.leftAt(roomId, it) }, now())
            Candidate(BackgroundRoomWatch(saved.id, saved.name, epoch.key, saved.relays, saved.participant, saved.devicePubkey, saved.ends),
                epoch.id, epoch.key, exclusion, saved, past, stored?.removed.orEmpty().map(String::lowercase).toSet(),
                canShareBackgroundPool(saved.relays, linkRoute), followsRekeys = stored != null)
        } catch (_: Exception) {
            null
        }
    }

    private fun open(candidate: Candidate, key: String, bell: Boolean, delivery: Boolean): RoomHandle {
        val application = application as KithMootApplication
        val watch = candidate.watch
        val needsSigner = java.util.concurrent.atomic.AtomicBoolean(false)
        val shared = candidate.shareable
        val pool = if (shared) sharedPools.acquire(watch.relays) else {
            val route = ActiveLinkRoute { url -> application.linkConsents.activeRoute(watch.selfParticipant, watch.stableRoomId, url) }
            val sockets = backgroundSockets(publicSockets, application.linkEngine, route)
            val authenticators = RelayAuthenticatorProvider { url ->
                if (route.routeId(url) == null) null else authenticatorFor(candidate.saved, needsSigner)
            }
            RelayPool(watch.relays, sockets, scope, authenticators = authenticators).also { it.start() }
        }
        val jobs = mutableListOf<Job>()
        if (bell) jobs += scope.launch {
            pool.subscribe({
                listOf(Filter(
                    kinds = listOf(KIND_CALL_BELL),
                    // "#d", not "d": Filter's wire form for a tag filter (see relay/Filter.kt).
                    tags = mapOf("#d" to callBellTagsFor(watch, now()).toList()),
                    since = now() - CALL_BELL_TTL_SECONDS,
                ))
            }).collect { event -> onBell(watch, event) }
        }
        if (delivery) {
            val inbox = BackgroundInboxVault(applicationContext, watch.stableRoomId, watch.selfParticipant, watch.selfDevice).inbox
            val outbox = PendingChatVault(applicationContext, watch.stableRoomId, watch.selfParticipant, watch.selfDevice).outbox
            runCatching {
                // First watched now: count from here, not the room's whole retained history.
                if (inbox.state().cursor == 0L) inbox.markRead(now())
                Log.i(LOG_TAG, "room=${label(watch.stableRoomId)} watching unread=${inbox.state().unread.size}")
                restoreNotice(candidate, inbox)
            }
            jobs += scope.launch {
                // Rebuilt from the inbox at every send, first REQ and every
                // reconnect alike, so a relay that returns resumes from the cursor.
                pool.subscribe({ listOf(backgroundChatFilter(candidate.epochId, inbox.state().cursor, now(), candidate.past.map { it.keys.id })) })
                    .collect { event -> onChat(candidate, inbox, event) }
            }
            jobs += scope.launch {
                pool.connected.collect { up ->
                    report()
                    if (up.isNotEmpty() && flushing.add(watch.stableRoomId)) scope.launch {
                        try {
                            val outcome = flushPending(outbox, candidate.epochId, pool)
                            if (outcome != FlushOutcome.NOTHING) Log.i(LOG_TAG, "room=${label(watch.stableRoomId)} pending=$outcome")
                        } catch (_: Exception) { } finally { flushing.remove(watch.stableRoomId) }
                    }
                }
            }
        } else jobs += scope.launch { pool.connected.collect { report() } }
        // Ringing and delivery alike follow the room's rekeys, so neither goes quiet at the
        // next one for a room nobody opens. A move reconciles, which rebuilds this room's
        // subscriptions under the new epoch (its id is part of the handle's key).
        val authority = candidate.saved.authority
        if (authority != null && candidate.followsRekeys) {
            val follower = BackgroundRekeyFollower(
                application.roomEpochs, watch.stableRoomId, authority, watch.selfParticipant,
                deviceSecretKey = candidate.saved::deviceSecretKey,
                mayFollow = { !ActiveRoomRegistry.isOpen(watch.stableRoomId) && !holdsCadence(application, candidate.saved) },
                now = ::now,
            )
            jobs += scope.launch {
                pool.subscribe({ listOf(backgroundRekeyFilter(watch.stableRoomId, authority)) }).collect { event ->
                    if (follower.offer(event)) {
                        Log.i(LOG_TAG, "room=${label(watch.stableRoomId)} followed a rekey")
                        scope.launch { reconcileNow() }
                    }
                }
            }
        }
        return RoomHandle(key, watch, candidate.epochId, candidate.epochKey, delivery, pool, shared, jobs, needsSigner)
    }

    /**
     * A quiet room, or one with a Bothy lease still running: its rekey must retire the old
     * schedule first, which only the open room does (`RoomViewModel.commitRoomEpoch`), so the
     * background leaves it to that.
     */
    private fun holdsCadence(application: KithMootApplication, saved: dev.forgesworn.kithmoot.storage.SavedRoom): Boolean =
        saved.policy?.quiet == true || saved.quietState != null || runCatching {
            application.cadenceLeases.all(saved.id, saved.devicePubkey).any { it.ownership != dev.forgesworn.kithmoot.cadence.CadenceOwnership.ENDED }
        }.getOrDefault(true)

    private fun close(handle: RoomHandle?) {
        handle ?: return
        handle.jobs.forEach { it.cancel() }
        if (handle.shared) sharedPools.release(handle.pool) else handle.pool.stop()
    }

    private fun stopAll() {
        rooms.values.forEach(::close)
        rooms.clear()
        if (sharedPoolsLazy.isInitialized()) sharedPools.stopAll()
    }

    /**
     * NIP-42 for a consented Link relay, signed as the room's participant. A
     * room joined as an account uses the saved account's signer through a
     * bridge that cannot open a screen: a NIP-55 signer answers through its
     * content provider, or the room waits with [DeliveryState.NEEDS_SIGNER].
     * A paired (secondary) device authenticates nowhere, as in an open room.
     */
    private fun authenticatorFor(saved: dev.forgesworn.kithmoot.storage.SavedRoom,
        needsSigner: java.util.concurrent.atomic.AtomicBoolean): RelayAuthenticator? {
        val signer: ParticipantSigner = if (saved.viaAccount) backgroundAccountSigner() ?: return null
            else (runCatching { saved.identity(now()) }.getOrNull() as? PrimaryIdentity)?.signer ?: return null
        if (signer.pubkey != saved.participant) return null
        return object : RelayAuthenticator {
            override val pubkey = signer.pubkey
            override suspend fun sign(url: String, challenge: String): NostrEvent = try {
                signer.sign(22242, now(), listOf(listOf("relay", url), listOf("challenge", challenge)), "").also {
                    if (needsSigner.getAndSet(false)) report()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                needsSigner.set(true)
                report()
                throw e
            }
        }
    }

    @Synchronized private fun backgroundAccountSigner(): ParticipantSigner? {
        val account = runCatching { (application as KithMootApplication).accounts.load() }.getOrNull()
        // Reopened when the person signs in as someone else while this runs.
        if (accountLoaded && account?.pubkey == accountSigner?.pubkey) return accountSigner
        accountLoaded = true
        accountSigner = null
        if (account == null) return null
        // A bunker would add a hidden relay connection; those rooms are excluded instead.
        if (account.method == "bunker") return null
        accountSigner = runCatching { openAccount(account, applicationContext, NoScreenBridge, scope).signer }.getOrNull()
        return accountSigner
    }

    private object NoScreenBridge : Nip55Bridge {
        override suspend fun request(intent: Intent): Intent? =
            throw SignerException("KithMoot is closed, so the signer cannot ask you.")
    }

    private fun onChat(candidate: Candidate, inbox: dev.forgesworn.kithmoot.session.BackgroundInbox, event: NostrEvent) {
        val watch = candidate.watch
        // Handed over between reconcile ticks: the open room shows it now.
        if (ActiveRoomRegistry.isOpen(watch.stableRoomId)) return
        val message = decodeBackgroundChat(event, EpochKeys(0, candidate.epochId, candidate.epochKey), candidate.past,
            candidate.removed, now(), candidate.saved.policy, credentialRoomId = watch.stableRoomId) ?: return
        try {
            val added = inbox.record(event.id, event.createdAt, message)
            val unread = inbox.state().unread
            Log.i(LOG_TAG, "room=${label(watch.stableRoomId)} ${if (added) "recorded" else "seen"} unread=${unread.size}")
            // The inbox decides what counts: someone else's new message, not
            // an edit, reaction, retraction or one already seen.
            if (added) notify(candidate, unread, message)
        } catch (_: Exception) { }
    }

    private fun notify(candidate: Candidate, unread: List<dev.forgesworn.kithmoot.session.BackgroundInbox.Unread>,
        message: dev.forgesworn.kithmoot.session.ChatMessage) {
        val settings = ChatNotifications.load(this)
        if (!settings.enabled) return
        val id = candidate.watch.stableRoomId
        // Opening the room marks the inbox read, which drops these lines too.
        val ids = unread.map { it.id }.toSet()
        val lines = (noticeLines[id].orEmpty() + noticeLine(message)).filter { it.id in ids }.takeLast(MAX_NOTICE_LINES)
        noticeLines[id] = lines
        val content = noticeContent(candidate.watch.roomName, isDmPolicy(candidate.saved.policy), lines, settings.previews)
            .copy(unread = unread.size)
        val now = android.os.SystemClock.elapsedRealtime()
        val sound = settings.bell && noticeSoundAt[id].let { it == null || now - it >= 5_000 }
        if (MessageNotices.post(this, id, content, sound, noticeReplyAvailable(this, id)) && sound) noticeSoundAt[id] = now
    }

    /**
     * After a restart or reboot the inbox still counts unread messages, but
     * this process has no text for them and a reboot emptied the tray. Picks
     * the lines up from the tray where they are still there; otherwise posts
     * them again, silently, saying "New message". Once per room per process.
     */
    private fun restoreNotice(candidate: Candidate, inbox: dev.forgesworn.kithmoot.session.BackgroundInbox) {
        val id = candidate.watch.stableRoomId
        if (noticeLines.containsKey(id)) return
        val unread = inbox.state().unread
        val settings = ChatNotifications.load(this)
        if (unread.isEmpty() || !settings.enabled) return
        val shown = MessageNotices.shown(this, id)
        val lines = restoredNoticeLines(unread, shown.orEmpty())
        noticeLines[id] = lines
        if (shown != null) return
        val content = noticeContent(candidate.watch.roomName, isDmPolicy(candidate.saved.policy), lines, settings.previews)
            .copy(unread = unread.size)
        MessageNotices.post(this, id, content, sound = false, replyable = noticeReplyAvailable(this, id))
    }

    private fun onBell(watch: BackgroundRoomWatch, event: NostrEvent) {
        val tag = event.tagValue("d") ?: return
        val now = now()
        if (watch.endedAt(now) || tag !in callBellTagsFor(watch, now)) return
        val bell = decodeCallBellEvent(event, watch.stableRoomId, watch.bellKey, now) ?: return
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
    }

    private fun watchNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java)
        network = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                this@BackgroundCallListenerService.network = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                report()
            }
            override fun onLost(network: android.net.Network) {
                this@BackgroundCallListenerService.network = false
                report()
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { networkCallback = callback }
    }

    private fun restricted(): Boolean {
        val activity = getSystemService(ActivityManager::class.java)
        val usage = getSystemService(UsageStatsManager::class.java)
        return activity.isBackgroundRestricted || usage.appStandbyBucket == UsageStatsManager.STANDBY_BUCKET_RESTRICTED ||
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    @Synchronized private fun report() {
        // Pools report as they open; the first reconcile has not finished.
        if (!reconciled) return
        val handles = rooms.values.toList()
        val delivering = handles.filter { it.delivery }
        // Summarise the rooms receiving messages; with none, the ringing ones.
        val summarised = delivering.ifEmpty { handles }
        val relaysUp = summarised.sumOf { it.pool.connected.value.size }
        val state = deriveDeliveryState(summarised.map { RoomLink(it.pool.connected.value.size, it.needsSigner.get()) }, network, restricted())
        val settings = BackgroundDeliverySettings(this)
        if (settings.state() != state || !settings.wasRunning()) {
            settings.report(state, running = true)
            Log.i(LOG_TAG, "state=$state rooms=${handles.size} relaysUp=$relaysUp")
        }
        updateNotification(state, handles.count { coordinators.containsKey(it.watch.stableRoomId) }, delivering.size)
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun channel() {
        val ch = NotificationChannel(CHANNEL_ID, "Background connection", NotificationManager.IMPORTANCE_LOW)
        ch.description = "A quiet notification while KithMoot listens for calls or receives messages without being open."
        ch.setSound(null, null)
        ch.enableVibration(false)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun updateNotification(state: DeliveryState, ringing: Int, delivering: Int) {
        // Rooms on one shared pool each report the same change.
        val shows = listOf(state, ringing, delivering, BackgroundRingSettings(this).enabled())
        if (shows == noticeShows) return
        try {
            val next = notification(state, ringing, delivering)
            shown = next
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, next)
            noticeShows = shows
        } catch (_: SecurityException) {
            // Notification permission withdrawn mid-run: the service still
            // works, it just cannot say so.
        }
    }

    private fun notification(state: DeliveryState, ringing: Int, delivering: Int): Notification {
        val turnOff = PendingIntent.getBroadcast(
            this, 0,
            Intent(this, BackgroundRingActionReceiver::class.java).setAction(BackgroundRingActionReceiver.ACTION_TURN_OFF),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun rooms(n: Int) = "$n room${if (n == 1) "" else "s"}"
        val jobs = listOfNotNull(
            if (ringing > 0) "calls in ${rooms(ringing)}" else null,
            if (delivering > 0) "messages in ${rooms(delivering)}" else null,
        )
        val title = if (delivering > 0) "Messages: ${state.label}" else "Listening for calls"
        val text = if (jobs.isEmpty()) "Waiting for a room to watch." else "Watching " + jobs.joinToString(" and ") + "."
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
        // Only while ringing is on: it stops ringing and nothing else, and says so.
        if (BackgroundRingSettings(this).enabled()) builder.addAction(0, STOP_RINGING_LABEL, turnOff)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "background_call_listen_v1"
        private const val NOTIFICATION_ID = 4604
        private const val RECONCILE_INTERVAL_MS = 60_000L
        const val LOG_TAG = "KithMootDelivery"

        /** True while this process runs the service; a fresh process after force-stop starts false. */
        @Volatile var alive = false
            private set

        @Volatile private var running: BackgroundCallListenerService? = null

        /** Logs name a room by a short digest of its id, never its name or key. */
        internal fun label(roomId: String) = Digests.sha256(roomId.toByteArray(Charsets.UTF_8)).toHex().take(8)

        /**
         * Sends a room's retained message, a notification reply, over this
         * service's own pool for the room, so a reply opens no second
         * connection. Null when the service is not running or does not watch
         * the room on [epochId]. Shares the reconnect flush's guard: while
         * that is already sending the same message, this leaves it to it.
         */
        internal suspend fun flushWatched(roomId: String, epochId: String, outbox: PendingChatOutbox, timeoutMs: Long): FlushOutcome? {
            val service = running ?: return null
            val handle = service.rooms[roomId]?.takeIf { it.epochId == epochId } ?: return null
            if (!service.flushing.add(roomId)) return FlushOutcome.NOT_CONFIRMED
            return try {
                flushPending(outbox, epochId, handle.pool, timeoutMs)
            } catch (_: Exception) {
                FlushOutcome.NOT_CONFIRMED
            } finally { service.flushing.remove(roomId) }
        }

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
