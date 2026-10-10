package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.HAND_TTL_SECONDS
import dev.forgesworn.kithmoot.protocol.MeetingPolicy
import dev.forgesworn.kithmoot.protocol.RECORDING_STALE_SECONDS
import dev.forgesworn.kithmoot.protocol.RecordingNotice
import dev.forgesworn.kithmoot.protocol.RecordingView
import dev.forgesworn.kithmoot.protocol.SignedMeetingPolicy
import dev.forgesworn.kithmoot.protocol.SignedRecordingNotice
import dev.forgesworn.kithmoot.protocol.RecordingCaptureNotice
import dev.forgesworn.kithmoot.protocol.SignedRecordingCaptureNotice
import dev.forgesworn.kithmoot.protocol.signRecordingCaptureNotice
import dev.forgesworn.kithmoot.protocol.encodeRecordingCaptureOp
import dev.forgesworn.kithmoot.protocol.decodeHandOp
import dev.forgesworn.kithmoot.protocol.decodeMeetingOp
import dev.forgesworn.kithmoot.protocol.decodeRecordingOp
import dev.forgesworn.kithmoot.protocol.encodeHandOp
import dev.forgesworn.kithmoot.protocol.encodeMeetingOp
import dev.forgesworn.kithmoot.protocol.encodeRecordingOp
import dev.forgesworn.kithmoot.protocol.meetingGated
import dev.forgesworn.kithmoot.protocol.signMeetingPolicy
import dev.forgesworn.kithmoot.protocol.signRecordingNotice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomMeetingTest {
    private val roomId = "ab".repeat(32)
    private val authoritySk = ByteArray(32) { 7 }
    private val authority = Schnorr.publicKeyHex(authoritySk)
    private val strangerSk = ByteArray(32) { 8 }
    private val alice = "aa".repeat(32)
    private val bob = "bb".repeat(32)
    private var clock = 10_000L

    private fun meetingOp(on: Boolean, speakers: List<String>, version: Long, sk: ByteArray = authoritySk): String {
        val policy = MeetingPolicy(on, speakers, version)
        return encodeMeetingOp(SignedMeetingPolicy(policy, signMeetingPolicy(roomId, policy, sk)))
    }

    private fun recordingOp(on: Boolean, id: String, version: Long, sk: ByteArray = authoritySk): String {
        val notice = RecordingNotice(on, id, version)
        return encodeRecordingOp(SignedRecordingNotice(notice, signRecordingNotice(roomId, notice, sk)))
    }

    @Test fun `capture details need the authority and exactly the live recording id and version`() {
        val meeting = RoomMeeting(roomId, authority, { clock })
        val capture = RecordingCaptureNotice("0f".repeat(16), 1, "gallery", alice, bob)
        fun encoded(value: RecordingCaptureNotice, sk: ByteArray = authoritySk) =
            encodeRecordingCaptureOp(SignedRecordingCaptureNotice(value, signRecordingCaptureNotice(roomId, value, sk)))
        meeting.receive(encoded(capture, strangerSk), alice, clock)
        assertNull(meeting.state.value.capture)
        meeting.receive(encoded(capture), bob, clock)
        assertNull(meeting.state.value.recordingCapture(), "details alone cannot start a notice")
        meeting.receive(recordingOp(true, capture.id, 1), bob, clock)
        assertEquals(capture, meeting.state.value.recordingCapture())
        val next = capture.copy(id = "12".repeat(16), version = 2, capture = "speaker")
        meeting.receive(recordingOp(true, next.id, 2), alice, clock)
        assertNull(meeting.state.value.recordingCapture(), "old details cannot describe a new recording")
        meeting.receive(encoded(next), bob, clock)
        assertEquals(next, meeting.state.value.recordingCapture())
        meeting.receive(encoded(capture), alice, clock)
        assertEquals(next, meeting.state.value.recordingCapture(), "a stale replay cannot replace current details")
        meeting.receive(recordingOp(false, next.id, 3), bob, clock)
        assertNull(meeting.state.value.recordingCapture(), "details cannot keep a stopped recording live")
    }

    @Test fun `believes the authority's policy from anybody, and nobody else's`() {
        val news = mutableListOf<MeetingNews>()
        val meeting = RoomMeeting(roomId, authority, { clock }, { news += it })
        assertTrue(meeting.receive(meetingOp(true, listOf(alice), 5, strangerSk), alice, clock))
        assertNull(meeting.state.value.meeting, "a policy signed by another key is not believed")
        // Reposted by Bob: the signature counts, not the sender.
        meeting.receive(meetingOp(true, listOf(alice), 5), bob, clock)
        assertEquals(MeetingPolicy(true, listOf(alice), 5), meeting.state.value.meeting)
        assertEquals(listOf(MeetingNews.MeetingOn), news)
        meeting.receive(meetingOp(false, listOf(alice), 4), bob, clock)
        assertTrue(meeting.state.value.meeting!!.on, "an older version changes nothing")
    }

    @Test fun `a room with no authority has no meeting mode`() {
        val meeting = RoomMeeting(roomId, null, { clock })
        meeting.receive(meetingOp(true, listOf(alice), 5), alice, clock)
        assertNull(meeting.state.value.meeting)
    }

    @Test fun `history is adopted without announcing it`() {
        val news = mutableListOf<MeetingNews>()
        val meeting = RoomMeeting(roomId, authority, { clock }, { news += it })
        meeting.receive(meetingOp(true, listOf(alice), 5), alice, clock - 3_600)
        assertTrue(meeting.state.value.meeting!!.on)
        assertTrue(news.isEmpty())
    }

    @Test fun `a recording notice is kept while reposted, then unconfirmed, and a stop needs its own signature`() {
        val news = mutableListOf<MeetingNews>()
        val meeting = RoomMeeting(roomId, authority, { clock }, { news += it })
        val id = "0f".repeat(16)
        meeting.receive(recordingOp(true, id, 1), alice, clock)
        assertEquals(RecordingView.On(id, clock), meeting.state.value.recordingView(clock))
        // A repost of the same version only refreshes when it was heard.
        meeting.receive(recordingOp(true, id, 1), bob, clock + 600)
        assertEquals(RecordingView.On(id, clock), meeting.state.value.recordingView(clock + 700))
        assertTrue(meeting.state.value.recordingView(clock + 601 + RECORDING_STALE_SECONDS) is RecordingView.Unconfirmed)
        meeting.receive(recordingOp(false, id, 2, strangerSk), bob, clock + 700)
        assertTrue(meeting.state.value.recording!!.notice.on, "nobody else can take the notice down")
        meeting.receive(recordingOp(false, id, 2), alice, clock)
        assertEquals(RecordingView.Off, meeting.state.value.recordingView(clock))
        assertEquals(listOf(MeetingNews.RecordingOn, MeetingNews.RecordingOff), news)
    }

    @Test fun `hands are the sender's, newest wins, and expire`() {
        val meeting = RoomMeeting(roomId, authority, { clock })
        meeting.receive(meetingOp(true, listOf(alice), 5), alice, clock)
        assertTrue(meeting.receive(encodeHandOp(true), bob, clock))
        assertEquals(setOf(bob), meeting.state.value.hands.keys)
        meeting.receive(encodeHandOp(false), bob, clock + 2)
        meeting.receive(encodeHandOp(true), bob, clock + 1)
        assertTrue(meeting.state.value.hands.isEmpty(), "an older up after a newer down changes nothing")
        meeting.receive(encodeHandOp(true), bob, clock - HAND_TTL_SECONDS - 1)
        assertTrue(meeting.state.value.hands.isEmpty(), "a stale hand is forgotten")
        meeting.receive(encodeHandOp(true), alice, clock)
        assertTrue(meeting.state.value.hands.isEmpty(), "a speaker has nothing to ask")
        meeting.receive(encodeHandOp(true), bob, clock + 3)
        meeting.receive(meetingOp(true, listOf(alice, bob), 6), alice, clock + 3)
        assertTrue(meeting.state.value.hands.isEmpty(), "made a speaker, the hand comes down")
    }

    @Test fun `the gate fails closed on a device nobody owns`() {
        val on = MeetingPolicy(true, listOf(alice), 1)
        assertFalse(meetingGated(null, null))
        assertFalse(meetingGated(MeetingPolicy(false, listOf(alice), 1), null))
        assertFalse(meetingGated(on, alice))
        assertTrue(meetingGated(on, bob))
        assertTrue(meetingGated(on, null))
    }

    @Test fun `decoding follows the web client's rules`() {
        val sig = "a".repeat(128)
        assertNull(decodeRecordingOp("{\"op\":\"recording\",\"on\":true,\"id\":\"${"0F".repeat(16)}\",\"version\":1,\"sig\":\"$sig\"}"), "an upper-case id")
        assertNull(decodeMeetingOp("{\"op\":\"meeting\",\"on\":\"yes\",\"speakers\":[],\"version\":1,\"sig\":\"$sig\"}"))
        assertNull(decodeMeetingOp("{\"op\":\"meeting\",\"on\":true,\"speakers\":[\"nobody\"],\"version\":1,\"sig\":\"$sig\"}"))
        assertNull(decodeMeetingOp("{\"op\":\"meeting\",\"on\":true,\"speakers\":[],\"version\":-1,\"sig\":\"$sig\"}"))
        assertNull(decodeMeetingOp("{\"op\":\"meeting\",\"on\":true,\"speakers\":[],\"version\":1,\"sig\":\"x\"}"))
        assertEquals(sig, decodeMeetingOp("{\"op\":\"meeting\",\"on\":true,\"speakers\":[],\"version\":1,\"sig\":\"${sig.uppercase()}\"}")!!.sig)
        assertNull(decodeHandOp("{\"op\":\"hand\",\"up\":1}"))
        assertEquals(true, decodeHandOp(encodeHandOp(true)))
    }
}
