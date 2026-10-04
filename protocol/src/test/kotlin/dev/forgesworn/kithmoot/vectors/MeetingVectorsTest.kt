package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.MeetingPolicy
import dev.forgesworn.kithmoot.protocol.RecordingNotice
import dev.forgesworn.kithmoot.protocol.SignedMeetingPolicy
import dev.forgesworn.kithmoot.protocol.SignedRecordingNotice
import dev.forgesworn.kithmoot.protocol.decodeMeetingOp
import dev.forgesworn.kithmoot.protocol.decodeRecordingOp
import dev.forgesworn.kithmoot.protocol.encodeMeetingOp
import dev.forgesworn.kithmoot.protocol.encodeRecordingOp
import dev.forgesworn.kithmoot.protocol.signMeetingPolicy
import dev.forgesworn.kithmoot.protocol.signRecordingNotice
import dev.forgesworn.kithmoot.protocol.verifyMeetingPolicy
import dev.forgesworn.kithmoot.protocol.verifyRecordingNotice
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shared `meeting` vector: the meeting policy and the recording notice
 * the web client's `meeting.ts` signs, and the ways a member refuses to
 * believe one. See `meeting.ts` and `control.ts` in the TypeScript reference
 * implementation.
 */
class MeetingVectorsTest {
    private fun vector(name: String) = Vectors.group("meeting").single { it.text("name") == name }
    private fun policy(o: JsonObject) = MeetingPolicy(o.flag("on"), o.strings("speakers"), o.number("version"))
    private fun notice(o: JsonObject) = RecordingNotice(o.flag("on"), o.text("id"), o.number("version"))

    @Test fun `the meeting policy signs byte for byte, verifies and round trips as a control op`() {
        val value = vector("meeting-policy-signature")
        val input = value.child("input")
        val output = value.child("output")
        val roomId = input.text("roomId")
        val sig = signMeetingPolicy(roomId, policy(input.child("policy")), input.bytes("authoritySkHex"), input.bytes("auxRandHex"))
        assertEquals("signature", output.text("sig"), sig)
        val verify = value.child("expected").child("verify")
        assertTrue(verifyMeetingPolicy(verify.text("roomId"), policy(verify.child("policy")), sig, verify.text("authority")))
        assertTrue(value.child("expected").flag("result"))

        val signed = SignedMeetingPolicy(policy(input.child("policy")), sig)
        assertEquals("encoded control op", output.text("text"), encodeMeetingOp(signed))
        val decoded = requireNotNull(decodeMeetingOp(output.text("text"))) { "the encoded op must decode" }
        val result = output.child("result")
        assertEquals(policy(result), decoded.policy)
        assertEquals(result.text("sig"), decoded.sig)
    }

    @Test fun `the recording notice signs byte for byte, verifies and round trips as a control op`() {
        val value = vector("recording-notice-signature")
        val input = value.child("input")
        val output = value.child("output")
        val sig = signRecordingNotice(input.text("roomId"), notice(input.child("notice")), input.bytes("authoritySkHex"), input.bytes("auxRandHex"))
        assertEquals("signature", output.text("sig"), sig)
        val verify = value.child("expected").child("verify")
        assertTrue(verifyRecordingNotice(verify.text("roomId"), notice(verify.child("notice")), sig, verify.text("authority")))

        val signed = SignedRecordingNotice(notice(input.child("notice")), sig)
        assertEquals("encoded control op", output.text("text"), encodeRecordingOp(signed))
        val decoded = requireNotNull(decodeRecordingOp(output.text("text"))) { "the encoded op must decode" }
        val result = output.child("result")
        assertEquals(notice(result), decoded.notice)
        assertEquals(result.text("sig"), decoded.sig)
    }

    @Test fun `every negative is refused`() {
        val negatives = Vectors.group("meeting").filter { it.text("kind") == "negative" }
        assertEquals(5, negatives.size)
        for (value in negatives) {
            val input = value.child("input")
            val name = value.text("name")
            val accepted = if (input.childOrNull("policy") != null) {
                verifyMeetingPolicy(input.text("roomId"), policy(input.child("policy")), input.text("sig"), input.text("authority"))
            } else {
                verifyRecordingNotice(input.text("roomId"), notice(input.child("notice")), input.text("sig"), input.text("authority"))
            }
            assertFalse(name, accepted)
            assertFalse("$name output.result", value.child("output").flag("result"))
        }
    }
}
