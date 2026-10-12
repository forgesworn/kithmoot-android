package dev.forgesworn.kithmoot.service

import android.content.Context
import android.util.Log
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.epoch.EpochPhase
import dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry
import dev.forgesworn.kithmoot.notifications.CallRingSettings
import dev.forgesworn.kithmoot.notifications.DestructHeadsUp
import dev.forgesworn.kithmoot.notifications.MessageNotices
import dev.forgesworn.kithmoot.relay.Nip09Deletion
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.OrbotTorRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPolicy
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.relay.TorCarrierTimings
import dev.forgesworn.kithmoot.session.CountdownStage
import dev.forgesworn.kithmoot.session.countdownStage
import dev.forgesworn.kithmoot.session.roomLifetime
import dev.forgesworn.kithmoot.storage.AssignmentVault
import dev.forgesworn.kithmoot.storage.BackgroundInboxVault
import dev.forgesworn.kithmoot.storage.DestructTombstones
import dev.forgesworn.kithmoot.storage.PendingChatVault
import dev.forgesworn.kithmoot.storage.RoomWipe
import dev.forgesworn.kithmoot.storage.RoomWipeStep
import dev.forgesworn.kithmoot.storage.RoomWipeTarget
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Tidies a self-destructing room away on this phone: asks the room's relays to
 * delete what this device's key signed (NIP-09), wipes everything the phone
 * keeps for the room ([RoomWipeStep]), and leaves a tombstone row that names
 * no room. One per process (`KithMootApplication.selfDestructor`), used by the
 * app while it is open and by the background service while it is not, so a
 * room whose end came while nobody looked still goes.
 *
 * Leaving an open room, and tombstoning the account's bookmark (which needs
 * the account's signer), are the open app's: a room tidied away here owes its
 * bookmark tombstone, which [owedBookmarks] keeps until the app sends it.
 */
class RoomSelfDestructor internal constructor(
    private val context: Context,
    private val app: KithMootApplication?,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    constructor(app: KithMootApplication) : this(app, app)

    sealed interface Outcome {
        /** Not saved here, not self-destructing, or its end has not come. */
        data object NotDue : Outcome
        /** Another caller is tidying it away now. */
        data object Running : Outcome
        /** No relay could be asked: the saved room, with its device key, stays until one can. */
        data object Postponed : Outcome
        /** Gone from this phone. [left] names stores that could not be cleared. */
        data class Done(val left: List<RoomWipeStep>) : Outcome
    }

    private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val requireApp get() = requireNotNull(app)
    val tombstones: DestructTombstones by lazy { DestructTombstones.of(context) }
    private val owedPrefs by lazy { context.getSharedPreferences(OWED_PREFS, Context.MODE_PRIVATE) }

    /** A self-destructing room whose end has come: by its time, or closed by its authority. */
    fun due(saved: SavedRoom, at: Long = now()): Boolean =
        saved.destruct && (saved.ended(at) ||
            runCatching { requireApp.roomEpochs.get(saved.id)?.phase == EpochPhase.CLOSED }.getOrDefault(false))

    /** Whether a room should wait before it is tried again. */
    fun waiting(roomId: String, at: Long = now()): Boolean = (retryAt[roomId] ?: 0) > at

    /** Claim [roomId] for one run; false while another holds it. */
    fun claim(roomId: String): Boolean = running.add(roomId)
    fun release(roomId: String) { running.remove(roomId) }

    /**
     * Tidy [roomId] away if it is due ([force] for a room another of the
     * person's devices already tidied away). The caller has left it, if it was
     * open. On the background inbox's own queue when [inboxQueue] is given, so
     * nothing a close still had to write there lands after the wipe.
     */
    suspend fun run(
        roomId: String,
        force: Boolean = false,
        claimed: Boolean = false,
        inboxQueue: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
        transport: suspend (SavedRoom, suspend (RoomTransport) -> Nip09Deletion.Report) -> Nip09Deletion.Report = ::withRoomRelays,
    ): Outcome {
        val saved = runCatching { requireApp.savedRooms.get(roomId) }.getOrNull() ?: return Outcome.NotDue
        if (!(saved.destruct && (force || due(saved)))) return Outcome.NotDue
        if (!claimed && !claim(roomId)) return Outcome.Running
        try {
            // Local unsaved recordings expire immediately, including a
            // capture still finalising, even when relay cleanup is postponed.
            requireApp.forgetRecordingsForRoom(roomId)
            // The background service hands the room over now, rather than at its next look.
            ActiveRoomRegistry.mark(roomId)
            val target = RoomWipeTarget(saved.id, saved.participant, saved.devicePubkey)
            val outcome = try {
                val key = saved.deviceSecretKey()
                val report = try {
                    transport(saved) { Nip09Deletion.deleteOwnEvents(it, key, now) }
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { Nip09Deletion.Report(complete = false)
                } finally { key.fill(0) }
                Log.i(LOG_TAG, "room=${roomId.take(8)} found=${report.found} requested=${report.requested} " +
                    "failed=${report.failed} complete=${report.complete} reached=${report.reached}")
                if (!report.reached) {
                    MessageNotices.cancelEverything(context, roomId)
                    retryAt[roomId] = now() + RETRY_SECONDS
                    return Outcome.Postponed
                }
                retryAt.remove(roomId)
                // Owed before the record goes, so a run that stops half way still owes it.
                if (saved.viaAccount) oweBookmark(roomId, saved.participant)
                val left = withContext(inboxQueue) { wipe().run(target) }
                if (left.isNotEmpty()) Log.w(LOG_TAG, "room=${roomId.take(8)} could not clear $left")
                // One row per room: a room still saved is tried again, and its row waits for that.
                if (RoomWipeStep.SAVED_ROOM !in left) tombstones.add(now())
                Outcome.Done(left)
            } finally {
                ActiveRoomRegistry.unmark(roomId)
            }
            // The background service may have written once more before it let go.
            delay(SETTLE_MS)
            withContext(inboxQueue) {
                wipe().run(target, only = setOf(RoomWipeStep.BACKGROUND_INBOX, RoomWipeStep.PARTICIPANT_CACHE, RoomWipeStep.NOTIFICATIONS))
            }
            return outcome
        } finally {
            if (!claimed) release(roomId)
        }
    }

    /** Every due self-destructing room no app screen has open, tidied away one at a time. */
    suspend fun runDue(skip: (String) -> Boolean = { false }): List<String> {
        val at = now()
        val rooms = runCatching { requireApp.savedRooms.list() }.getOrDefault(emptyList())
        return rooms.filter { it.destruct && !waiting(it.id, at) && !skip(it.id) }
            .filter { run(it.id) is Outcome.Done }.map { it.id }
    }

    /**
     * The heads-up at the start of red, at most once per room whoever sends
     * it: the app while it is open, the background service while it is not.
     * The claim is made on the saved room under its repository's lock, so the
     * two cannot both post. [skip] leaves a room to whoever has it on screen.
     */
    fun sendHeadsUps(at: Long = now(), skip: (String) -> Boolean = { false }): List<String> {
        val rooms = runCatching { requireApp.savedRooms.list() }.getOrDefault(emptyList())
        val sent = mutableListOf<String>()
        for (room in destructHeadsUpsDue(rooms, at)) {
            if (skip(room.id)) continue
            if (!claimDestructHeadsUp(requireApp.savedRooms, room.id)) continue
            DestructHeadsUp.post(context, room.id, dev.forgesworn.kithmoot.ui.start.roomLabel(room.name, room.id), room.endsAt!! - at)
            sent += room.id
        }
        return sent
    }

    /** Rooms tidied away whose account bookmark still needs its tombstone, for [account]. */
    fun owedBookmarks(account: String): List<String> {
        val at = now()
        return owedPrefs.all.mapNotNull { (key, value) ->
            val parts = key.split('|')
            val when_ = (value as? Long) ?: return@mapNotNull null
            if (at - when_ > OWED_SECONDS) { owedPrefs.edit().remove(key).apply(); return@mapNotNull null }
            parts.takeIf { it.size == 2 && it[0] == account }?.get(1)
        }
    }

    fun bookmarkTombstoned(account: String, roomId: String) { owedPrefs.edit().remove("$account|$roomId").apply() }

    private fun oweBookmark(roomId: String, account: String) { owedPrefs.edit().putLong("$account|$roomId", now()).apply() }

    private fun wipe(): RoomWipe {
        val app = requireApp
        return RoomWipe(mapOf<RoomWipeStep, suspend (RoomWipeTarget) -> Unit>(
            RoomWipeStep.UPLOADED_FILES to { t -> dev.forgesworn.kithmoot.storage.MediaUploadLedger(app).due(room = t.roomId) },
            RoomWipeStep.PENDING_OUTBOX to { t ->
                dev.forgesworn.kithmoot.storage.RoomSharingVault(app, t.roomId, t.participant, t.devicePubkey).forget()
                PendingChatVault(app, t.roomId, t.participant, t.devicePubkey).outbox.clear()
            },
            RoomWipeStep.ASSIGNMENTS to { t -> AssignmentVault(app, t.roomId, t.participant).reset() },
            RoomWipeStep.BACKGROUND_INBOX to { t -> BackgroundInboxVault(app, t.roomId, t.participant, t.devicePubkey).inbox.clear() },
            RoomWipeStep.PARTICIPANT_CACHE to { t -> BackgroundParticipantCache(app).forget(t.roomId) },
            RoomWipeStep.NOTIFICATIONS to { t -> MessageNotices.cancelEverything(app, t.roomId) },
            RoomWipeStep.NIP77_OFFERS to { t -> app.nip77Offers.forgetRoom(t.roomId) },
            RoomWipeStep.NIP77_INDEX to { t -> app.nip77Events.forgetRoom(t.roomId) },
            RoomWipeStep.CALL_RING_SETTING to { t -> CallRingSettings(app).forget(t.roomId) },
            RoomWipeStep.NATIVE_COURIER to { t -> app.savedRooms.get(t.roomId)?.nativeAuthority?.let { b ->
                dev.forgesworn.kithmoot.storage.RoomRekeyVault(app, dev.forgesworn.kithmoot.epoch.RoomRekeyBinding(
                    b.room, b.authority, b.device, b.meshScope, b.relays, b.route)).forget()
            } },
            RoomWipeStep.NATIVE_AUTHORITY to { t -> app.savedRooms.get(t.roomId)?.let { saved ->
                if (saved.nativeAuthority != null) dev.forgesworn.kithmoot.storage.NativeKeeperVault.forSavedRoom(app, saved).forget()
            } },
            RoomWipeStep.EPOCHS to { t -> app.roomEpochs.forget(t.roomId) },
            RoomWipeStep.MEMBERS to { t -> app.roomMembers.forget(t.roomId) },
            RoomWipeStep.SAVED_ROOM to { t -> app.savedRooms.forget(t.roomId) },
        ))
    }

    companion object {
        private const val LOG_TAG = "KithMootDestruct"
        /** How long a room whose relays could not be reached waits before it is tried again. */
        const val RETRY_SECONDS = 120L
        /** How long the background service is given to let a room go before its stores are cleared again. */
        private const val SETTLE_MS = 1_500L
        private const val OWED_PREFS = "kithmoot.destruct-owed.v1"
        /** An owed bookmark tombstone nobody signed in to send is dropped after this. */
        private const val OWED_SECONDS = 30L * 86_400

        /** Every relay the room is on: its own, and the ones this device used for it. Read and written whatever the relay choices say. */
        fun destructRelays(saved: SavedRoom): List<String> = (saved.sharedRelays + saved.relays).distinct()

        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun withRoomRelays(saved: SavedRoom, action: suspend (RoomTransport) -> Nip09Deletion.Report): Nip09Deletion.Report {
            check(saved.route.internet) { "This room has no authorised Internet cleanup route" }
            val relays = destructRelays(saved)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pool = RelayPool(relays, if (saved.anonymous) OrbotTorRelaySockets() else OkHttpRelaySockets(), scope,
                policy = if (saved.anonymous) TorCarrierTimings.policy else RelayPolicy(),
                readRelays = relays.toSet(), writeRelays = relays.toSet())
            pool.start()
            return try { action(pool) } finally { pool.stop(); scope.cancel() }
        }
    }
}

/** Self-destructing rooms in the red stage of their countdown at [at]: those a heads-up is for. */
internal fun destructHeadsUpsDue(rooms: List<dev.forgesworn.kithmoot.storage.SavedRoomSummary>, at: Long) = rooms.filter { room ->
    val ends = room.endsAt
    ends != null && room.destruct && !room.ended &&
        countdownStage(ends - at, roomLifetime(ends, room.startsAt)) == CountdownStage.RED
}

/**
 * True for the one caller that marks [roomId]'s heads-up as sent, false for
 * every other and for a room no longer saved. Done under the repository's
 * lock, so the app and the background service never both post it.
 */
internal fun claimDestructHeadsUp(repository: dev.forgesworn.kithmoot.storage.RoomRepository, roomId: String): Boolean {
    var claimed = false
    runCatching {
        repository.update(roomId) { room -> if (room.destructHeadsUp) room else { claimed = true; room.withDestructHeadsUp() } }
    }
    return claimed
}
