package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.Digests
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LivePersistentAdmissionTest {
    private val now = 1_800_000_000L
    private val root = ByteArray(32) { 1 }
    private val reply = ByteArray(32) { 2 }
    private val secret = ByteArray(32) { 4 }
    private val invitation = RoomInvitation(ByteArray(32) { 5 }, Schnorr.publicKeyHex(root), true)
    private val context = LivePersistentContext(invitation, deriveRoom(secret).roomId)

    @Test fun rawReaderRejectsCoercionDuplicatesAndUnknownFields() {
        val event = encodeLivePersistentRequest(context, reply, now)
        val raw = event.toCompactJson()
        assertEquals(event, parseLivePersistentEvent(raw, request = true))
        for (bad in listOf(raw.replaceFirst("{", "{\"kind\":20466,"),
            raw.replaceFirst("{", "{\"extra\":true,"), raw.replaceFirst("{", "{ "),
            raw.replace("\"kind\":20466", "\"kind\":\"20466\""),
            raw.replace("\"kind\":20466", "\"kind\":20466.0"))) {
            assertNull(parseLivePersistentEvent(bad, request = true))
        }
        assertNull(parseLivePersistentEvent(" ".repeat(4097), request = true))
        assertNull(decodeLivePersistentRequest(event.copy(content = "a".repeat(2049)), context, now))
    }

    @Test fun descriptorRejectsDuplicateFieldsAndNoncanonicalIntegers() {
        val good = encodeLivePersistentDescriptor(context)
        val json = base64UrlDecode(good).toString(Charsets.UTF_8)
        for (bad in listOf(json.replaceFirst("{", "{\"v\":1,"), json.replace("\"v\":1", "\"v\":1.0"),
            json.replaceFirst("{", "{ "), json.replace("\"v\":1", "\"v\":1e0"))) {
            assertNull(decodeLivePersistentDescriptor(base64UrlEncode(bad.toByteArray(Charsets.UTF_8)), invitation))
        }
        assertNull(decodeLivePersistentDescriptor(good + "=", invitation))
        assertNull(decodeLivePersistentDescriptor("A".repeat(513), invitation))
    }

    @Test fun typedAndEncryptedBoundariesRefuseMalformedInputs() {
        val request = encodeLivePersistentRequest(context, reply, now)
        val key = Digests.hkdfSha256(invitation.bearer, null, "kithmoot/v1/persistent-live/request-key".toByteArray(), 32)
        val body = Nip44.decrypt(request.content, key)
        for (bad in listOf(body.replaceFirst("{", "{\"v\":1,"), body.replace("\"v\":1", "\"v\":1.0"))) {
            val forged = Events.sign(reply, request.kind, now, request.tags, Nip44.encrypt(bad, key))
            assertNull(decodeLivePersistentRequest(forged, context, now))
        }
        assertNull(decodeLivePersistentRequest(request.copy(tags = request.tags + listOf(listOf("d", "x"))), context, now))
        assertNull(decodeLivePersistentRequest(request, context, -1))
        assertNull(decodeLivePersistentRequest(request, context, Long.MAX_VALUE))
        assertNotNull(decodeLivePersistentRequest(request, context, now + 89))
        assertNull(decodeLivePersistentRequest(request, context, now + 90))
    }

    @Test fun answerDeadlineIsNeverExtendedAndDoesNotDelegate() {
        val request = encodeLivePersistentRequest(context, reply, now)
        val welcome = encodePersistentInvitation(RoomInvitationHost(invitation, root), secret, now - 1)
        val answer = encodeLivePersistentAnswer(context, request, welcome, root, 0, now + 89)
        val got = requireNotNull(decodeLivePersistentAnswer(answer, context, request, reply, now + 89))
        assertEquals(now + 90, got.expiresAt)
        assertEquals(0L, got.epochHint)
        assertNull(got.admission.delegate)
        assertNull(decodeLivePersistentAnswer(answer, context, request, reply, now + 90))
        val another = encodeLivePersistentRequest(context, reply, now)
        assertNull(decodeLivePersistentAnswer(answer, context, another, reply, now + 89))
    }
}
