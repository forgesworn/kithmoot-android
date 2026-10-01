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
import dev.forgesworn.kithmoot.account.SignerException
import dev.forgesworn.kithmoot.account.openAccount
import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.notifications.CallRingSettings
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.storage.KEPT_CREDENTIAL_MIN_REMAINING
import dev.forgesworn.kithmoot.storage.RoomStorageException
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
)

/**
 * The Ring me rooms joined as [account] whose kept credential is due: none,
 * or less than [KEPT_CREDENTIAL_MIN_REMAINING] left - the point at which
 * opening the room would ask the signer anyway. Pure, so it is unit-tested.
 */
fun roomsDueForRenewal(candidates: List<RenewalCandidate>, account: String, now: Long): List<String> =
    candidates.filter {
        it.viaAccount && !it.excluded && it.participant == account && it.ringMode == CallRingMode.RING &&
            (it.expiresAt == null || it.expiresAt - now < KEPT_CREDENTIAL_MIN_REMAINING)
    }.map { it.roomId }

/** Whether answering a call in one of these rooms would soon have to wait on the signer. */
fun reachabilityAtRisk(candidates: List<RenewalCandidate>, account: String, now: Long): Boolean =
    candidates.any {
        it.viaAccount && !it.excluded && it.participant == account && it.ringMode == CallRingMode.RING &&
            (it.expiresAt == null || it.expiresAt - now < AT_RISK_SECONDS)
    }

/** Below this much credential left, the person is told rather than left to find out on a call. */
const val AT_RISK_SECONDS: Long = 2L * 60 * 60

/**
 * Keeps each Ring me room's account credential fresh, so answering a call
 * never waits on the signer app - which on a locked phone it cannot ask.
 *
 * A room joined as a signed-in account carries a credential the account's
 * signer made: "this device speaks for me in this room", for 24 hours, kept
 * and reused while it has at least 12 hours left (see `SavedRoom.identity`).
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
 * and the rest follow through the window that opens.
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

    /** Renews what is due without ever showing the signer. */
    suspend fun renewQuietly(context: Context, fromBackground: Boolean = false) {
        if (fromBackground) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastBackgroundTry < BACKGROUND_INTERVAL_MS) return
            lastBackgroundTry = now
        }
        val app = context.applicationContext as KithMootApplication
        val account = runCatching { app.accounts.load() }.getOrNull() ?: return
        if (account.method == "bunker") return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val session = runCatching { openAccount(account, app, NoScreen, scope) }.getOrNull() ?: return
            try { renewWith(app, session.signer) } finally { session.close() }
        } finally {
            scope.cancel()
        }
    }

    /**
     * Renews what is due with [signer], which may show itself: the room
     * screen's own signer, after the person tapped the notice. Returns how
     * many rooms were renewed.
     */
    suspend fun renewWith(context: Context, signer: ParticipantSigner): Int = turn.withLock {
        val app = context.applicationContext as KithMootApplication
        val now = System.currentTimeMillis() / 1000
        val candidates = candidates(app, now) ?: return 0
        var renewed = 0
        for (id in roomsDueForRenewal(candidates.values.map { it.second }, signer.pubkey, now)) {
            val saved = candidates[id]?.first ?: continue
            try {
                val identity = saved.identity(now, signer)
                app.savedRooms.update(id) { it.keepingCredential(identity) }
                renewed++
            } catch (e: SignerException) {
                // Locked, or no standing permission: the next chance will do.
                Log.i(TAG, "room=${id.take(8)} not renewed: ${e.message}")
                break
            } catch (e: Exception) {
                Log.i(TAG, "room=${id.take(8)} not renewed: ${e.javaClass.simpleName}")
            }
        }
        if (renewed > 0) Log.i(TAG, "renewed=$renewed")
        val after = candidates(app, now)?.values?.map { it.second }.orEmpty()
        if (reachabilityAtRisk(after, signer.pubkey, now)) notice(app) else cancelNotice(app)
        renewed
    }

    private fun candidates(app: KithMootApplication, now: Long): Map<String, Pair<SavedRoom, RenewalCandidate>>? {
        val ringOn = BackgroundRingSettings(app).enabled()
        val modes = CallRingSettings(app)
        val rooms = try { app.savedRooms.list().mapNotNull { app.savedRooms.get(it.id) } } catch (_: RoomStorageException) { return null }
        return rooms.associate { saved ->
            saved.id to (saved to RenewalCandidate(
                roomId = saved.id,
                viaAccount = saved.viaAccount,
                participant = saved.participant,
                excluded = saved.anonymous || saved.retired || saved.movedOn,
                ringMode = if (ringOn) modes.modeFor(saved.id) else CallRingMode.NOTHING,
                expiresAt = saved.keptCredentialExpiry(now),
            ))
        }
    }

    private fun notice(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Staying reachable", NotificationManager.IMPORTANCE_LOW).apply {
                description = "When KithMoot needs your signer before it can answer calls."
            },
        )
        val open = PendingIntent.getActivity(
            context, NOTICE_ID,
            Intent(context, MainActivity::class.java).setAction(ACTION_RENEW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_chat_notice)
            .setContentTitle("Open KithMoot to stay reachable")
            .setContentText("Your signer needs to confirm this phone before you can answer calls.")
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

    private object NoScreen : Nip55Bridge {
        override suspend fun request(intent: Intent): Intent? =
            throw SignerException("The signer would have to ask, so this waits for a better moment.")
    }
}
