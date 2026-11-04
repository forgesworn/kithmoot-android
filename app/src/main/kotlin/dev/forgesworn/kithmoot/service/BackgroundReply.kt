package dev.forgesworn.kithmoot.service

import android.content.Context
import android.util.Log
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.epoch.activeEpochFor
import dev.forgesworn.kithmoot.notifications.ReplyAccount
import dev.forgesworn.kithmoot.notifications.ReplyOutcome
import dev.forgesworn.kithmoot.notifications.ReplyRoom
import dev.forgesworn.kithmoot.notifications.canReplyFromNotice
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.decodeChatEvent
import dev.forgesworn.kithmoot.session.encodeChatEvent
import dev.forgesworn.kithmoot.storage.BackgroundInboxVault
import dev.forgesworn.kithmoot.storage.HeadlessSigning
import dev.forgesworn.kithmoot.storage.PendingChatVault
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A reply from a message notification for a room that is not open in this
 * process. The room's protocol is unchanged: the same chat event an open room
 * sends, signed by this device under a credential that already exists (see
 * [SavedRoom.headlessSigning]), sealed to the room's current epoch, kept in
 * the room's [dev.forgesworn.kithmoot.session.PendingChatOutbox] before it is
 * first published, and cleared from there only when a relay confirms it.
 * Nothing the open room would refuse is ever kept or sent.
 *
 * It goes out over the background service's own pool for the room when the
 * service watches it, and otherwise over a short-lived pool on the room's
 * own relays and no others. That pool has no NIP-42 signer, so a Link relay
 * that asks for authentication does not confirm it; the message stays kept.
 */

/** Long enough for a relay to answer, short enough for a receiver Android allows ten seconds. */
private const val REPLY_CONFIRM_MS = 8_000L

/** What [canReplyFromNotice] needs, read from this device's storage. */
private fun replyRoomOf(application: KithMootApplication, saved: SavedRoom, epoch: EpochKeys?, now: Long): ReplyRoom {
    val account = when {
        !saved.viaAccount -> ReplyAccount.NONE
        else -> runCatching { application.accounts.load() }.getOrNull().let {
            when {
                it == null || it.pubkey != saved.participant -> ReplyAccount.SIGNED_OUT
                it.method == "bunker" -> ReplyAccount.BUNKER
                else -> ReplyAccount.SIGNED_IN
            }
        }
    }
    return ReplyRoom(
        allowsInternet = saved.route.internet,
        anonymous = saved.anonymous,
        quiet = saved.policy?.quiet == true || saved.quietState != null,
        ended = saved.retired || saved.movedOn || saved.ended(now),
        hasEpoch = epoch != null,
        needsProof = saved.policy?.let { it.tier != KindredTier.OPEN } == true,
        account = account,
        credentialExpiresAt = runCatching { saved.headlessSigning(now) }.getOrNull()?.credentialExpiresAt,
    )
}

private fun epochOf(application: KithMootApplication, saved: SavedRoom): EpochKeys? =
    runCatching { activeEpochFor(saved, saved.authority?.let { application.roomEpochs.get(saved.id) }) }.getOrNull()

/** What [canReplyFromNotice] needs about a saved room, or null when it is not saved. Reads encrypted storage: not on the main thread. */
fun noticeReplyRoom(context: Context, roomId: String, now: Long): ReplyRoom? = try {
    val application = context.applicationContext as KithMootApplication
    application.savedRooms.get(roomId)?.let { replyRoomOf(application, it, epochOf(application, it), now) }
} catch (_: Exception) { null }

/** Whether a saved room's notification offers Reply now. Reads encrypted storage: not on the main thread. */
fun noticeReplyAvailable(context: Context, roomId: String): Boolean {
    val now = System.currentTimeMillis() / 1000
    return noticeReplyRoom(context, roomId, now)?.let { canReplyFromNotice(it, now) } == true
}

suspend fun sendReplyInBackground(context: Context, roomId: String, text: String): ReplyOutcome =
    RoomRouteTransitions.stable { sendReplyOnStableRoute(context, roomId, text) }

private suspend fun sendReplyOnStableRoute(context: Context, roomId: String, text: String): ReplyOutcome {
    val application = context.applicationContext as KithMootApplication
    val now = System.currentTimeMillis() / 1000
    val saved = application.savedRooms.get(roomId) ?: return ReplyOutcome.FAILED
    if (!application.accountNetwork.allowsNostr && !dev.forgesworn.kithmoot.account.privatePairedRelayRoom(saved.relays) {
        application.linkConsents.activeRoute(saved.participant, saved.id, it) != null
    }) return ReplyOutcome.FAILED
    val epoch = epochOf(application, saved) ?: return ReplyOutcome.FAILED
    // Asked again: the room may have ended, or the account changed, since the notification was posted.
    if (!canReplyFromNotice(replyRoomOf(application, saved, epoch, now), now)) return ReplyOutcome.FAILED
    if (saved.nodeRoom != null) return sendNodeReply(application, saved, text)
    val signing: HeadlessSigning = saved.headlessSigning(now) ?: return ReplyOutcome.FAILED
    val event = encodeChatEvent(text, signing.participant, signing.credential, epoch.id, epoch.key,
        signing.deviceSecretKey, now, credentialRoomId = saved.id, roomEnds = saved.ends,
        sentAtMs = System.currentTimeMillis().takeIf { Math.floorDiv(it, 1000L) == now })
    // The room's own check, as an open room's send makes it: never keep what the room would refuse.
    decodeChatEvent(event, epoch.id, epoch.key, now, saved.policy, credentialRoomId = saved.id) ?: return ReplyOutcome.FAILED
    val outbox = PendingChatVault(application, saved.id, saved.participant, saved.devicePubkey).outbox
    // Refused when another message already waits for a relay: one at a time, as in the room.
    // A reply from a notification does not queue behind others; the open room's composer does.
    try {
        if (outbox.items().isNotEmpty()) return ReplyOutcome.FAILED
        outbox.retain(epoch.id, event, editable = true, text = text)
    } catch (_: Exception) { return ReplyOutcome.FAILED }
    // Replying means the room was read.
    runCatching { BackgroundInboxVault(application, saved.id, saved.participant, saved.devicePubkey).inbox.markRead(now) }
    val outcome = BackgroundCallListenerService.flushWatched(saved.id, epoch.id, outbox, REPLY_CONFIRM_MS)
        ?: flushOnce(application, saved, epoch.id, outbox)
    Log.i(BackgroundCallListenerService.LOG_TAG, "room=${BackgroundCallListenerService.label(saved.id)} reply=$outcome")
    return if (outcome == FlushOutcome.SENT) ReplyOutcome.SENT else ReplyOutcome.KEPT
}

/** A notification reply obtains fresh device permission and a fresh root epoch.
 * Its only socket is the signed node relay; failure never enters flushOnce. */
private suspend fun sendNodeReply(application: KithMootApplication, saved: SavedRoom, text: String): ReplyOutcome {
    val node = saved.nodeRoom ?: return ReplyOutcome.FAILED
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var background: dev.forgesworn.kithmoot.node.NodeBackgroundRoom? = null
    val network = application.accountNetwork.token()
    fun allowed() = scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true && application.accountNetwork.current(network) &&
        !dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry.isOpen(saved.id)
    val at = { System.currentTimeMillis() / 1000 }
    val signing = saved.headlessSigning(at()) ?: return ReplyOutcome.FAILED.also { scope.cancel() }
    val room = dev.forgesworn.kithmoot.protocol.deriveRoom(saved.secret)
    try {
        val store = dev.forgesworn.kithmoot.node.NodeStore.android(application)
        val held = requireNotNull(store.custody(saved.participant, saved.id))
        require(held.device == saved.devicePubkey && held.node.event.id == node.event.id)
        val erase: suspend () -> Unit = { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            application.selfDestructor.run(saved.id, force = true); Unit
        } }
        val live = dev.forgesworn.kithmoot.node.NodeBackgroundRoom.open(held, store, application.roomEpochs,
            application.accountNetwork.ordinarySockets(OkHttpRelaySockets(OkHttpRelaySockets.backgroundClient())),
            scope, ::allowed, {}, erase, erase, at)
        background = live; live.start()
        val answer = dev.forgesworn.kithmoot.session.requestFreshRootEpoch(room, node.authority,
            signing.deviceSecretKey, signing.credential, live.transport,
            { application.roomEpochs.get(saved.id)?.currentEpoch ?: 0 }, at, 8000,
            ends = minOf(at() + dev.forgesworn.kithmoot.protocol.NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE))
        val grant = answer.grant
        try {
            val current = requireNotNull(application.roomEpochs.get(saved.id))
            try {
                require(current.phase == dev.forgesworn.kithmoot.epoch.EpochPhase.ACTIVE && current.pending == null && grant.epoch >= current.currentEpoch)
                if (grant.epoch > current.currentEpoch) {
                    val notice = dev.forgesworn.kithmoot.protocol.RekeyNotice(grant.epoch, grant.removed, null, false,
                        requireNotNull(grant.secret), answer.event.createdAt, catchUp = true, members = grant.members)
                    application.roomEpochs.beginCatchUp(saved.id, current.currentEpoch, notice, answer.event.id, null, at())
                    application.roomEpochs.activate(saved.id, grant.epoch, at())
                    notice.secret?.fill(0)
                } else if (grant.epoch > 0) require(grant.secret?.contentEquals(current.currentSecret) == true)
            } finally { current.currentSecret.fill(0); current.pending?.secret?.fill(0) }
        } finally { grant.secret?.fill(0); grant.passed.forEach { it.secret.fill(0) } }
        if (!allowed() || !live.permitted()) return ReplyOutcome.FAILED
        val epoch = epochOf(application, saved) ?: return ReplyOutcome.FAILED
        try {
            val sentAt = at()
            val event = encodeChatEvent(text, signing.participant, signing.credential, epoch.id, epoch.key,
                signing.deviceSecretKey, sentAt, credentialRoomId = saved.id,
                roomEnds = minOf(sentAt + dev.forgesworn.kithmoot.protocol.NODE_HORIZON_SECONDS, node.endsAt ?: Long.MAX_VALUE))
            decodeChatEvent(event, epoch.id, epoch.key, sentAt, credentialRoomId = saved.id) ?: return ReplyOutcome.FAILED
            val outbox = PendingChatVault(application, saved.id, saved.participant, saved.devicePubkey).outbox
            if (outbox.items().isNotEmpty() || !allowed() || !live.permitted()) return ReplyOutcome.FAILED
            outbox.retain(epoch.id, event, editable = true, text = text)
            runCatching { BackgroundInboxVault(application, saved.id, saved.participant, saved.devicePubkey).inbox.markRead(sentAt) }
            return if (flushPending(outbox, epoch.id, live.transport, REPLY_CONFIRM_MS) == FlushOutcome.SENT) ReplyOutcome.SENT else ReplyOutcome.KEPT
        } finally { epoch.key.fill(0) }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { return ReplyOutcome.FAILED }
    finally { background?.close(); scope.cancel(); signing.deviceSecretKey.fill(0); room.roomKey.fill(0) }
}

/** The service does not watch this room: one short-lived pool on the room's own relays. */
private suspend fun flushOnce(application: KithMootApplication, saved: SavedRoom, epochId: String,
    outbox: dev.forgesworn.kithmoot.session.PendingChatOutbox): FlushOutcome {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val route = ActiveLinkRoute { url -> application.linkConsents.activeRoute(saved.participant, saved.id, url) }
    val pool = RelayPool(saved.relays, backgroundSockets(application.accountNetwork.ordinarySockets(OkHttpRelaySockets(OkHttpRelaySockets.backgroundClient())),
        application.linkEngine, route), scope)
    return try {
        pool.start()
        flushPending(outbox, epochId, pool, REPLY_CONFIRM_MS)
    } catch (_: Exception) {
        FlushOutcome.NOT_CONFIRMED
    } finally {
        pool.stop()
        scope.cancel()
    }
}
