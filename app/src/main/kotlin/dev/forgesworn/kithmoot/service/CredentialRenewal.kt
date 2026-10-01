package dev.forgesworn.kithmoot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import dev.forgesworn.kithmoot.account.Nip55Bridge
import dev.forgesworn.kithmoot.account.NostrAccount
import dev.forgesworn.kithmoot.account.SignerException
import dev.forgesworn.kithmoot.account.openAccount
import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.notifications.CallRingSettings
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.session.RoomIdentity
import dev.forgesworn.kithmoot.storage.RING_CREDENTIAL_RENEW_BELOW
import dev.forgesworn.kithmoot.storage.RING_CREDENTIAL_TTL
import dev.forgesworn.kithmoot.storage.SAVED_CREDENTIAL_TTL
import dev.forgesworn.kithmoot.storage.RoomStorageException
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One saved room, as far as keeping its credential fresh is concerned. */
data class RenewalCandidate(
    val roomId: String,
    val viaAccount: Boolean,
    val participant: String,
    /** Anonymous, retired or moved on: never renewed. */
    val excluded: Boolean,
    val ringMode: CallRingMode,
    /** When the credential kept with the room expires; null when none is valid. */
    val expiresAt: Long?,
    /** What the person chose for the room, whether or not ringing in the background is on. */
    val chosenMode: CallRingMode = ringMode,
)

/**
 * The Ring me rooms joined as [account] whose kept credential is due: none,
 * or less than [RING_CREDENTIAL_RENEW_BELOW] left, half the life of the
 * credential minted for a Ring me room, so there are days of quiet tries
 * before it lapses. Pure, so it is unit-tested.
 */
fun roomsDueForRenewal(candidates: List<RenewalCandidate>, account: String, now: Long): List<String> =
    candidates.filter {
        it.viaAccount && !it.excluded && it.participant == account && it.ringMode == CallRingMode.RING &&
            (it.expiresAt == null || it.expiresAt - now < RING_CREDENTIAL_RENEW_BELOW)
    }.map { it.roomId }

/**
 * The rooms where answering a call would soon have to wait on the signer.
 * [account] null means the signed-in account could not be read, so any room
 * joined as an account counts: better to say something than to stay silent.
 */
fun roomsAtRisk(candidates: List<RenewalCandidate>, account: String?, now: Long): List<String> =
    candidates.filter {
        it.viaAccount && !it.excluded && (account == null || it.participant == account) && it.ringMode == CallRingMode.RING &&
            (it.expiresAt == null || it.expiresAt - now < AT_RISK_SECONDS)
    }.map { it.roomId }

/** Whether any room is at risk: what the notice and the in-app banner both answer to. */
fun reachabilityAtRisk(candidates: List<RenewalCandidate>, account: String?, now: Long): Boolean =
    roomsAtRisk(candidates, account, now).isNotEmpty()

/**
 * Ringing in the background is switched off while a saved room is set to
 * Ring me: calls will not ring this phone when KithMoot is closed. Off from
 * the notification's action, off in Settings, or off by an old install, it
 * reads the same, and it is said until it is put right. Mirrors
 * [shouldRunBackgroundListener].
 */
fun ringingSwitchedOff(ringOn: Boolean, candidates: List<RenewalCandidate>): Boolean =
    !ringOn && candidates.any { !it.excluded && it.chosenMode == CallRingMode.RING }

/**
 * How long to make a credential for a saved room joined as an account:
 * [RING_CREDENTIAL_TTL] when it is a Ring me room and ringing in the
 * background is on, the ordinary day otherwise.
 */
fun credentialLifetimeFor(backgroundRing: Boolean, mode: CallRingMode): Long =
    if (backgroundRing && mode == CallRingMode.RING) RING_CREDENTIAL_TTL else SAVED_CREDENTIAL_TTL

/** Below this much credential left, the person is told rather than left to find out on a call. */
const val AT_RISK_SECONDS: Long = 2L * 60 * 60

/**
 * Keeps each Ring me room's account credential fresh, so answering a call
 * never waits on the signer app - which on a locked phone it cannot ask.
 *
 * A room joined as a signed-in account carries a credential the account's
 * signer made: "this device speaks for me in this room". A Ring me room's
 * lasts seven days ([RING_CREDENTIAL_TTL]) and is renewed once half of that is
 * gone; opening a room reuses what is kept while it has 12 hours left (see
 * `SavedRoom.identity`).
 * Ringing needs none of it; answering does. So this renews every due room
 * while the signer will answer without showing anything: through its content
 * provider only, which Signet answers while it is unlocked - for a few
 * minutes after it last signed for an app - and refuses otherwise, quietly,
 * without prompting. Asking it any other time costs nothing.
 *
 * It is tried when the room screen has just used the signer (a window is
 * most likely open then), when KithMoot comes to the front, and on the
 * background listener's tick. When none of that has worked and a room is
 * within [AT_RISK_SECONDS] of needing the signer, a quiet notice asks the
 * person to open KithMoot; tapping it renews with the signer shown, once,
 * and the rest follow through the window that opens. The same condition
 * keeps a banner on the rooms list and in the room until it clears: a
 * notification is easy to dismiss, and then nothing else says calls cannot
 * ring this phone ([atRisk]).
 *
 * Bunker accounts are left alone: reaching one means a relay connection the
 * background listener does not otherwise make.
 */
object CredentialRenewal {
    private const val TAG = "KithMootRenew"
    private const val CHANNEL = "reachability_v1"
    private const val NOTICE_ID = 4620
    const val ACTION_RENEW = "dev.forgesworn.kithmoot.RENEW_CALLS"
    /** The background tick's own pace: a locked signer refuses at once, but there is no need to ask it every minute. */
    private const val BACKGROUND_INTERVAL_MS = 30L * 60 * 1000

    private val turn = Mutex()
    @Volatile private var lastBackgroundTry = 0L

    private val _atRisk = MutableStateFlow<List<AtRiskRoom>>(emptyList())
    /**
     * The Ring me rooms that cannot ring this phone for want of a credential,
     * for the signed-in account: what the banner shows, for as long as it is
     * true. Process-wide, so a renewal made by the background listener clears
     * it too. Worked out from the saved rooms and their credentials alone,
     * never from whether a quiet signer session could be had. Kept current by
     * [refresh] and by every renewal.
     */
    val atRisk: StateFlow<List<AtRiskRoom>> = _atRisk.asStateFlow()

    private val _ringingOff = MutableStateFlow(false)
    /** Ringing in the background is off while a room is set to Ring me; see [ringingSwitchedOff]. */
    val ringingOff: StateFlow<Boolean> = _ringingOff.asStateFlow()

    /**
     * Looks at the rooms again, without signing, and updates [atRisk] and
     * [ringingOff]. With [post], the notification follows the condition too:
     * posted again while it holds, cleared once it does not. The foreground
     * screen passes false, so a notification the person has just dismissed
     * does not come back while they are looking at the banner.
     */
    fun refresh(context: Context, post: Boolean = true) {
        val app = context.applicationContext as KithMootApplication
        settle(AppHost(app), System.currentTimeMillis() / 1000, post)
    }

    /** Renews what is due without ever showing the signer. */
    suspend fun renewQuietly(context: Context, fromBackground: Boolean = false) {
        if (fromBackground) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBackgroundTry < BACKGROUND_INTERVAL_MS) return
            lastBackgroundTry = now
        }
        val app = context.applicationContext as KithMootApplication
        // The same turn as renewWith: a tick and a tapped button never mint for one room at once.
        turn.withLock { renewQuietlyVia(AppHost(app), System.currentTimeMillis() / 1000) }
    }

    /**
     * Renews what is due with [signer], which may show itself: the room
     * screen's own signer, after the person tapped the notice. Returns how
     * many rooms were renewed.
     */
    suspend fun renewWith(context: Context, signer: ParticipantSigner): Int = turn.withLock {
        val app = context.applicationContext as KithMootApplication
        renewRooms(AppHost(app), signer, System.currentTimeMillis() / 1000)
    }

    private class AppHost(private val app: KithMootApplication) : RenewalHost {
        override fun account(): Result<NostrAccount?> = runCatching { app.accounts.load() }

        override suspend fun runWithQuietSigner(account: NostrAccount, block: suspend (ParticipantSigner) -> Unit): Boolean {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val session = runCatching { openAccount(account, app, NoScreen, scope) }.getOrNull() ?: return false
                try { block(session.signer) } finally { session.close() }
                return true
            } finally {
                scope.cancel()
            }
        }

        override fun rooms(now: Long): RoomsReading? {
            val ringOn = BackgroundRingSettings(app).enabled()
            val modes = CallRingSettings(app)
            val saved = try { app.savedRooms.list().mapNotNull { app.savedRooms.get(it.id) } } catch (_: RoomStorageException) { return null }
            return RoomsReading(ringOn, saved.associate { room ->
                val chosen = modes.modeFor(room.id)
                room.id to (room to RenewalCandidate(
                    roomId = room.id,
                    viaAccount = room.viaAccount,
                    participant = room.participant,
                    excluded = room.anonymous || room.retired || room.movedOn || room.ended(now),
                    ringMode = if (ringOn) chosen else CallRingMode.NOTHING,
                    expiresAt = room.keptCredentialExpiry(now),
                    chosenMode = chosen,
                ))
            })
        }

        override fun keep(roomId: String, identity: RoomIdentity) { app.savedRooms.update(roomId) { it.keepingCredential(identity) } }

        override fun publish(atRisk: List<AtRiskRoom>, ringingOff: Boolean) {
            _atRisk.value = atRisk
            _ringingOff.value = ringingOff
        }

        override fun notify(names: List<String>) = notice(app, names)
        override fun cancelNotice() = cancelNotice(app)
    }

    private fun notice(context: Context, names: List<String>) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(reachabilityChannel())
        val open = PendingIntent.getActivity(
            context, NOTICE_ID,
            Intent(context, MainActivity::class.java).setAction(ACTION_RENEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val words = reachabilityNoticeText(names)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle(words.title)
            .setContentText(words.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(words.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try { manager.notify(NOTICE_ID, notification) } catch (_: SecurityException) { }
    }

    private fun cancelNotice(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTICE_ID)
    }

    /** The channel both "calls can't ring" notifications use; the id is kept so existing settings survive. */
    internal fun reachabilityChannel() =
        NotificationChannel(CHANNEL, "Calls that can't ring", NotificationManager.IMPORTANCE_LOW).apply {
            description = "When your signer has to confirm this phone again, or ringing is off, so KithMoot cannot ring you for calls."
        }

    internal const val CHANNEL_ID = CHANNEL

    private object NoScreen : Nip55Bridge {
        override suspend fun request(intent: Intent): Intent? =
            throw SignerException("The signer would have to ask, so this waits for a better moment.")
    }
}

/** The saved rooms as renewal sees them, with whether ringing in the background is on. */
internal class RoomsReading(val ringOn: Boolean, val rooms: Map<String, Pair<SavedRoom, RenewalCandidate>>)

/** What renewal needs from the phone: the account, a quiet signer, the saved rooms and the notice. A fake in tests. */
internal interface RenewalHost {
    /** The saved account: a success of null when signed out, a failure when it cannot be read. */
    fun account(): Result<NostrAccount?>
    /** Runs [block] with a signer that never shows a screen; false, without running it, when none can be had. */
    suspend fun runWithQuietSigner(account: NostrAccount, block: suspend (ParticipantSigner) -> Unit): Boolean
    /** The saved rooms, or null when they cannot be read. */
    fun rooms(now: Long): RoomsReading?
    fun keep(roomId: String, identity: RoomIdentity)
    fun publish(atRisk: List<AtRiskRoom>, ringingOff: Boolean)
    fun notify(names: List<String>)
    fun cancelNotice()
}

/**
 * Works out what is wrong from the saved rooms and their credentials alone,
 * says so ([RenewalHost.publish]), and makes the notification follow:
 * posted while a room is at risk and [post] is set, cleared once none is.
 * [signer] is the account that just signed, when one did. Otherwise the saved
 * account is read: nobody signed in means no room is waiting on a signer, and
 * an account that cannot be read means any room joined as one is. Returns the
 * rooms at risk.
 */
internal fun settle(host: RenewalHost, now: Long, post: Boolean, signer: String? = null): List<AtRiskRoom> {
    val reading = host.rooms(now) ?: return emptyList()
    val candidates = reading.rooms.values.map { it.second }
    val who = if (signer != null) Result.success(signer) else host.account().map { it?.pubkey }
    val signedOut = who.isSuccess && who.getOrNull() == null
    val atRisk = if (signedOut) emptyList() else roomsAtRisk(candidates, who.getOrNull(), now).mapNotNull { id ->
        reading.rooms[id]?.first?.let { AtRiskRoom.of(it.id, it.name) }
    }
    host.publish(atRisk, ringingSwitchedOff(reading.ringOn, candidates))
    if (atRisk.isEmpty()) host.cancelNotice() else if (post) host.notify(atRisk.map { it.name })
    return atRisk
}

/**
 * Renews what is due without showing the signer, and in every case where that
 * cannot be done - nobody signed in, a bunker, an account that cannot be read,
 * a signer that has to be asked - still settles the notice, so a person who
 * dismissed it is told again. The banner never depended on this: it follows
 * the saved rooms.
 */
internal suspend fun renewQuietlyVia(host: RenewalHost, now: Long) {
    val read = host.account()
    val account = read.getOrNull()
    val ran = account != null && account.method != "bunker" &&
        host.runWithQuietSigner(account) { signer -> renewRooms(host, signer, now) }
    if (!ran) settle(host, now, post = true)
}

/** Renews every due room with [signer]; then settles. Returns how many were renewed. */
internal suspend fun renewRooms(host: RenewalHost, signer: ParticipantSigner, now: Long): Int {
    val reading = host.rooms(now) ?: return 0
    var renewed = 0
    for (id in roomsDueForRenewal(reading.rooms.values.map { it.second }, signer.pubkey, now)) {
        val saved = reading.rooms[id]?.first ?: continue
        try {
            val identity = saved.identity(now, signer, RING_CREDENTIAL_TTL, RING_CREDENTIAL_RENEW_BELOW)
            host.keep(id, identity)
            renewed++
        } catch (e: SignerException) {
            // Locked, or no standing permission: the next chance will do.
            Log.i("KithMootRenew", "room=${id.take(8)} not renewed: ${e.message}")
            break
        } catch (e: Exception) {
            Log.i("KithMootRenew", "room=${id.take(8)} not renewed: ${e.javaClass.simpleName}")
        }
    }
    if (renewed > 0) Log.i("KithMootRenew", "renewed=$renewed")
    settle(host, now, post = true, signer = signer.pubkey)
    return renewed
}
