package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.*
import kotlinx.serialization.json.*

class RecordingStopJournalTest {
    private class Store: RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { check(!fail); bytes = value.clone() }
        override fun reset() { bytes = null }
    }
    private val room = "ab".repeat(32)
    private val device = "cd".repeat(32)
    private val secret = ByteArray(32) { 31 }
    private val authority = Schnorr.publicKeyHex(secret)
    private fun off(id: String = "ef".repeat(16)): SignedRecordingNotice {
        val notice = RecordingNotice(false, id, 101)
        return SignedRecordingNotice(notice, signRecordingNotice(room, notice, secret))
    }

    @Test fun `restoration verifies original authority and keeps only signed off metadata`() {
        val storage = Store(); val journal = RecordingStopJournal(storage); val signed = off()
        journal.arm(room, device, signed, authority)
        val restored = RecordingStopJournal(storage)
        assertEquals(signed, restored.pending(room, device, authority))
        assertNull(restored.pending(room, "aa".repeat(32), authority))
        assertFails { restored.pending(room, device, "aa".repeat(32)) }
        assertFails { restored.arm(room, device, off("aa".repeat(16)), authority) }
        restored.confirm(room, device, off("aa".repeat(16)))
        assertEquals(signed, restored.pending(room, device, authority))
        restored.confirm(room, device, signed)
        assertNull(restored.pending(room, device, authority))
    }

    @Test fun `failed writes keep retry ownership and Forget fences an old recorder`() {
        val storage = Store(); val journal = RecordingStopJournal(storage); val signed = off()
        val oldGeneration = journal.generation(room)
        storage.fail = true
        assertFails { journal.arm(room, device, signed, authority, oldGeneration) }
        assertNull(journal.pending(room, device, authority))
        storage.fail = false
        journal.arm(room, device, signed, authority, oldGeneration)
        storage.fail = true
        assertFails { journal.confirm(room, device, signed) }
        assertEquals(signed, RecordingStopJournal(storage).pending(room, device, authority))
        storage.fail = false
        journal.forgetRoom(room)
        assertNull(journal.pending(room, device, authority))
        assertFails { journal.arm(room, device, signed, authority, oldGeneration) }
        // A newly opened owner may record after an explicit rejoin.
        journal.arm(room, device, signed, authority, journal.generation(room))
        assertEquals(signed, journal.pending(room, device, authority))
    }

    @Test fun `restoration retains original call and person and refuses rewritten context`() {
        val storage = Store(); val journal = RecordingStopJournal(storage); val signed = off()
        val call = "12".repeat(16); val person = "34".repeat(32)
        journal.arm(room, device, signed, authority, call = call, participant = person)
        val restored = RecordingStopJournal(storage)
        assertEquals(signed, restored.pending(room, device, authority))
        assertEquals(call, restored.pendingCall(room, device))
        assertEquals(person, restored.pendingParticipant(room, device))
        restored.arm(room, device, signed, authority, call = call, participant = person)
        assertFails { restored.arm(room, device, signed, authority, call = "56".repeat(16), participant = person) }
        assertFails { restored.arm(room, device, signed, authority, call = call, participant = "78".repeat(32)) }
        assertFails { restored.arm(room, device, signed, authority, call = call) }
        assertEquals(person, RecordingStopJournal(storage).pendingParticipant(room, device))
    }

    @Test fun `legacy participant stop with call only remains readable`() {
        val storage = Store(); val journal = RecordingStopJournal(storage); val signed = off()
        val call = "12".repeat(16)
        journal.arm(room, device, signed, authority, call = call)
        val restored = RecordingStopJournal(storage)
        assertEquals(signed, restored.pending(room, device, authority))
        assertEquals(call, restored.pendingCall(room, device))
        assertNull(restored.pendingParticipant(room, device))
    }

    @Test fun `malformed retained context cannot fall back to legacy authority`() {
        val storage = Store(); val signed = off(); val person = "34".repeat(32)
        fun invalid(call: JsonElement?, participant: JsonElement?) {
            storage.bytes = buildJsonArray { add(buildJsonObject {
                put("room", room); put("device", device); put("body", encodeRecordingOp(signed))
                call?.let { put("call", it) }; participant?.let { put("participant", it) }
            }) }.toString().toByteArray()
            assertFails { RecordingStopJournal(storage).pending(room, device, authority) }
        }
        invalid(JsonPrimitive(123), null)
        invalid(JsonNull, null)
        invalid(JsonPrimitive("12".repeat(16)), JsonPrimitive(123))
        invalid(JsonPrimitive("12".repeat(16)), JsonNull)
        invalid(null, JsonPrimitive(person))
        assertFails { RecordingStopJournal(Store()).arm(room, device, signed, authority, participant = person) }
    }
}
