package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.HAND_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.MeetingPolicy
import dev.forgesworn.kithmoot.protocol.RecordingNotice
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.protocol.SignedMeetingPolicy
import dev.forgesworn.kithmoot.protocol.SignedRecordingNotice
import dev.forgesworn.kithmoot.protocol.decodeHandOp
import dev.forgesworn.kithmoot.protocol.decodeMeetingOp
import dev.forgesworn.kithmoot.protocol.decodeRecordingOp
import dev.forgesworn.kithmoot.protocol.meetingAllows
import dev.forgesworn.kithmoot.protocol.recordingView
import dev.forgesworn.kithmoot.protocol.verifyMeetingPolicy
import dev.forgesworn.kithmoot.protocol.verifyRecordingNotice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A verified recording notice, when this device first saw it running and
 *  when it last saw it posted, unix seconds. */
data class HeardRecording(val signed: SignedRecordingNotice, val since: Long, val heardAt: Long) {
    val notice: RecordingNotice get() = signed.notice
}

/** Everything a room's control log has said about meeting mode and recording. */
data class MeetingSnapshot(
    val policy: SignedMeetingPolicy? = null,
    val recording: HeardRecording? = null,
    /** Raised hands: participant -> unix seconds raised. */
    val hands: Map<String, Long> = emptyMap(),
) {
    val meeting: MeetingPolicy? get() = policy?.policy
    /** What the room should be told about recording at [now]. */
    fun recordingView(now: Long): RecordingView =
        recordingView(recording?.notice, recording?.since ?: 0, recording?.heardAt ?: 0, now)
}

/** Something worth a line in the room's chat: said only for a change read
 *  as it happened, not for one found in the log's history. */
enum class MeetingNews { MeetingOn, MeetingOff, RecordingOn, RecordingOff }

/**
 * The room's meeting policy, recording notice and raised hands, read from
 * its control channel. Mirrors `ingestMeeting`, `ingestRecording` and
 * `ingestHand` in the web client's `app/src/main.ts`.
 *
 * The policy and the notice are believed on [authority]'s signature alone,
 * never on who posted them: any member may repost either. A room with no
 * pinned authority has no meeting mode. A hand is the sender's own, and
 * needs no signature.
 */
class RoomMeeting(
    private val roomId: String,
    private val authority: String?,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val onNews: (MeetingNews) -> Unit = {},
) {
    private val mutable = MutableStateFlow(MeetingSnapshot())
    val state: StateFlow<MeetingSnapshot> = mutable.asStateFlow()
    /** The newest hand message per participant, up or down, so an old "up"
     *  read after a newer "down" changes nothing. */
    private val handsAt = HashMap<String, Long>()

    /** Takes one control message body, sent by [participant] at [sentAt].
     *  Returns whether it was a meeting, recording or hand op at all. */
    fun receive(body: String, participant: String, sentAt: Long): Boolean {
        decodeMeetingOp(body)?.let { ingestMeeting(it, sentAt); return true }
        decodeRecordingOp(body)?.let { ingestRecording(it, sentAt); return true }
        decodeHandOp(body)?.let { hand(participant, it, sentAt); return true }
        return false
    }

    private fun fresh(sentAt: Long) = sentAt >= now() - 60

    private fun ingestMeeting(signed: SignedMeetingPolicy, sentAt: Long) {
        val auth = authority ?: return
        if (!verifyMeetingPolicy(roomId, signed.policy, signed.sig, auth)) return
        var news: MeetingNews? = null
        synchronized(this) {
            val before = mutable.value.policy
            if (before != null && before.policy.version >= signed.policy.version) return
            mutable.update { snapshot ->
                // A speaker's own raised hand has been answered.
                snapshot.copy(policy = signed, hands = snapshot.hands.filterKeys { !(signed.policy.on && it in signed.policy.speakers) })
            }
            if (fresh(sentAt) && (before?.policy?.on == true) != signed.policy.on) {
                news = if (signed.policy.on) MeetingNews.MeetingOn else MeetingNews.MeetingOff
            }
        }
        news?.let { runCatching { onNews(it) } }
    }

    private fun ingestRecording(signed: SignedRecordingNotice, sentAt: Long) {
        val auth = authority ?: return
        if (!verifyRecordingNotice(roomId, signed.notice, signed.sig, auth)) return
        var news: MeetingNews? = null
        synchronized(this) {
            val before = mutable.value.recording
            val next = signed.notice
            if (before != null && before.notice.version > next.version) return
            if (before != null && before.notice.version == next.version) {
                if (sentAt > before.heardAt) mutable.update { it.copy(recording = before.copy(heardAt = sentAt)) }
                return
            }
            val since = if (next.on && before?.notice?.on == true && before.notice.id == next.id) before.since else sentAt
            mutable.update { it.copy(recording = HeardRecording(signed, since, sentAt)) }
            if (fresh(sentAt) && (before?.notice?.on == true) != next.on) {
                news = if (next.on) MeetingNews.RecordingOn else MeetingNews.RecordingOff
            }
        }
        news?.let { runCatching { onNews(it) } }
    }

    /** [participant]'s hand went up or down at [at]: read, or raised here. */
    fun hand(participant: String, up: Boolean, at: Long) = synchronized(this) {
        if ((handsAt[participant] ?: 0) > at) return
        handsAt[participant] = at
        val meeting = mutable.value.meeting
        // A hand from somebody already on the stage asks for nothing.
        val speaker = meeting?.on == true && meetingAllows(meeting, participant)
        val keep = up && at > now() - HAND_TTL_SECONDS && !speaker
        mutable.update { snapshot -> snapshot.copy(hands = if (keep) snapshot.hands + (participant to at) else snapshot.hands - participant) }
    }
}
