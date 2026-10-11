package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlin.test.*

class RecordingUploadJournalTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { check(!fail); bytes = value.clone() }
        override fun reset() { error("Cleanup records must not be reset") }
    }
    private val room = "ab".repeat(32)
    private val hash = "cd".repeat(32)
    private val origin = "https://private.example"

    @Test fun `storage identities survive restart and stay separate between origins`() {
        val storage = Store()
        val journal = RecordingUploadJournal(storage) { 100 }.apply { recover() }
        val key = journal.identity(origin)
        assertEquals(key, journal.identity("https://PRIVATE.example/"))
        assertNotEquals(key, journal.identity("https://other.example"))
        assertEquals(key, RecordingUploadJournal(storage) { 101 }.apply { recover() }.identity(origin))
        assertFails { journal.identity("http://private.example") }
    }

    @Test fun `Forget strips room references and fresh scoped cleanup survives expiry and restart`() {
        var time = 100L
        val storage = Store()
        val journal = RecordingUploadJournal(storage) { time }.apply { recover() }
        val key = journal.identity(origin)
        val ticket = journal.begin(room, origin, hash, 200)
        val upload = journal.uploadAuthorisation(ticket)
        assertEquals(key, upload.pubkey)
        assertEquals("upload", upload.tagValue("t"))
        assertEquals(hash, upload.tagValue("x"))
        assertEquals("200", upload.tagValue("expiration"), "Upload cannot outlive the original room")
        journal.finish(ticket, true)
        journal.forgetRoom(room)
        assertFalse(storage.bytes!!.toString(Charsets.UTF_8).contains(room))
        time = 10_000
        val restored = RecordingUploadJournal(storage) { time }.apply { recover() }
        var calls = 0
        restored.retry { server, target, auth ->
            calls++
            assertEquals(origin, server); assertEquals(hash, target)
            assertEquals(key, auth.pubkey); assertEquals(time, auth.createdAt)
            assertEquals((time + 300).toString(), auth.tagValue("expiration"))
            assertEquals("private.example", auth.tagValue("server"))
            assertEquals("delete", auth.tagValue("t")); assertEquals(hash, auth.tagValue("x"))
            true
        }
        restored.retry { _, _, _ -> error("Confirmed cleanup must not repeat") }
        assertEquals(1, calls)
        assertFalse(storage.bytes!!.toString(Charsets.UTF_8).contains(hash))
    }

    @Test fun `Forget during PUT keeps cleanup held until the late upload finishes`() {
        var time = 100L
        val journal = RecordingUploadJournal(Store()) { time }.apply { recover(); identity(origin) }
        val ticket = journal.begin(room, origin, hash, null)
        journal.forgetRoom(room)
        assertFails { journal.uploadAuthorisation(ticket) }
        time = 1000
        journal.retry { _, _, _ -> error("A PUT still owns the file") }
        journal.finish(ticket, true)
        journal.retry { _, _, _ -> error("Late receipt requires cleanup grace") }
        time += 91
        var deleted = false
        journal.retry { _, _, _ -> deleted = true; true }
        assertTrue(deleted)
        assertFails { journal.begin(room, origin, hash, null) }
    }

    @Test fun `crashed PUT is cleaned after restart without assuming it failed remotely`() {
        var time = 100L
        val storage = Store()
        val journal = RecordingUploadJournal(storage) { time }.apply { recover(); identity(origin) }
        val ticket = journal.begin(room, origin, hash, null)
        val restored = RecordingUploadJournal(storage) { time }.apply { recover() }
        assertFails { restored.finish(ticket, true) }
        restored.retry { _, _, _ -> error("Restart grace has not elapsed") }
        time += 91
        var calls = 0
        restored.retry { _, _, _ -> calls++; false }
        time += 10_000
        restored.retry { _, _, auth -> calls++; assertEquals(time, auth.createdAt); true }
        assertEquals(2, calls)
    }

    @Test fun `failed journal commit authorises neither a new identity nor upload`() {
        val storage = Store()
        val journal = RecordingUploadJournal(storage) { 100 }.apply { recover() }
        storage.fail = true
        assertFails { journal.identity(origin) }
        assertNull(storage.bytes)
        storage.fail = false
        journal.identity(origin)
        val previous = storage.bytes!!.clone()
        storage.fail = true
        assertFails { journal.begin(room, origin, hash, null) }
        assertContentEquals(previous, storage.bytes)
        journal.retry { _, _, _ -> error("Failed registration must not produce a request") }
    }

    @Test fun `cleanup excludes a concurrent retry PUT and room expiry schedules deletion`() {
        var time = 100L
        val journal = RecordingUploadJournal(Store()) { time }.apply { recover(); identity(origin) }
        val ticket = journal.begin(room, origin, hash, 200)
        journal.finish(ticket, false)
        time = 191
        var calls = 0
        journal.retry { _, _, _ ->
            calls++
            assertFails { journal.begin(room, origin, hash, 200) }
            false
        }
        val retry = journal.begin(room, origin, hash, 200)
        journal.finish(retry, true)
        time = 201
        journal.retry { _, _, _ -> error("Expiry grace") }
        time += 91
        journal.retry { _, _, _ -> calls++; true }
        assertEquals(2, calls)
    }
}
