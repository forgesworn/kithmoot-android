package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.*

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
}
