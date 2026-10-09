package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LivePersistentVectorsTest(private val name: String, private val vector: JsonObject) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): Collection<Array<Any>> {
            val stream = requireNotNull(LivePersistentVectorsTest::class.java.getResourceAsStream("/live-persistent-vectors.json"))
            val doc = Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
            return doc.child("groups").list("livePersistentAdmission").map { arrayOf(it.jsonObject.text("name"), it.jsonObject) }
        }
    }

    @Test fun independentWire() {
        val i = vector.child("input")
        val expected = vector.child("output")
        val context = LivePersistentContext(RoomInvitation(i.bytes("bearerHex"), i.text("inviter"), persistent = true), i.text("roomId"))
        when (vector.text("operation")) {
            "descriptor" -> {
                val got = decodeLivePersistentDescriptor(i.text("encoded"), context.invitation)
                assertEquals(name, expected.flag("accepted"), got != null)
                if (got != null) {
                    assertEquals(expected.child("result").text("roomId"), got.roomId)
                    assertEquals(i.text("encoded"), encodeLivePersistentDescriptor(context))
                }
            }
            "request" -> {
                val event = NostrEvent.fromJson(i.child("event"))
                val got = decodeLivePersistentRequest(event, context, i.number("now"))
                assertEquals(name, expected.flag("accepted"), got != null)
                if (got != null) {
                    val result = expected.child("result")
                    assertEquals(result.text("requestId"), got.requestId)
                    assertEquals(result.text("requester"), got.requester)
                    assertEquals(result.number("createdAt"), got.createdAt)
                    assertEquals(result.number("expiresAt"), got.expiresAt)
                    val random = i.strings("randomHex").map { it.hexToBytes() }
                    val rebuilt = encodeLivePersistentRequest(context, i.bytes("requesterSkHex"), event.createdAt, random[0], random[1])
                    assertEquals(i.child("event"), rebuilt.toJson())
                }
            }
            "answer" -> {
                val event = NostrEvent.fromJson(i.child("event"))
                val request = NostrEvent.fromJson(i.child("request"))
                val got = decodeLivePersistentAnswer(event, context, request, i.bytes("requesterSkHex"), i.number("now"))
                assertEquals(name, expected.flag("accepted"), got != null)
                if (got != null) {
                    val result = expected.child("result")
                    assertEquals(result.text("secretHex"), got.admission.secret.toHex())
                    assertEquals(result.number("epoch"), got.admission.epoch!!.toLong())
                    assertEquals(result.number("epochHint"), got.epochHint)
                    assertEquals(result.number("endsAt"), got.admission.endsAt)
                    assertEquals(result.flag("destruct"), got.admission.destruct)
                    assertEquals(result.strings("relays"), got.admission.relays)
                    assertEquals(result.text("requestId"), got.requestId)
                    assertEquals(result.number("expiresAt"), got.expiresAt)
                    assertNull(got.admission.delegate)
                    val random = i.strings("randomHex").map { it.hexToBytes() }
                    val rebuilt = encodeLivePersistentAnswer(context, request, NostrEvent.fromJson(i.child("invitationEvent")),
                        i.bytes("inviterSkHex"), i.number("epoch"), event.createdAt, random[0], random[1])
                    assertEquals(i.child("event"), rebuilt.toJson())
                }
            }
            else -> fail("unsupported vector operation")
        }
    }
}
