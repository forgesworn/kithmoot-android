package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RecordingShareDraftStoreTest {
    private class MemoryJournal : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { check(!fail) { "Synthetic journal failure" }; bytes = value.clone() }
        override fun reset() { bytes = null }
    }
    private val origin = RecordingOrigin("ab".repeat(32), "cd".repeat(16), "Synthetic original room")
    private val otherRoom = "ef".repeat(32)

    private fun seal(ticket: RecordingDraftTicket, root: File): SealedFile {
        val source = File(root, "original.m4a").apply { writeText("Synthetic native recording bytes") }
        return sealFile(source, ticket.destination, source.name, "audio/mp4")
    }
    private fun receipt(draft: RecordingShareDraft, storage: String) = ChatAttachment(
        "$storage/${draft.sealed.hash}", draft.sealed.hash, draft.sealed.key,
        draft.sealed.name, draft.sealed.type, draft.sealed.file.length(),
    )

    @Test fun `Add survives restart without choosing a server and leaves the local export independent`() {
        val root = Files.createTempDirectory("recording-draft-").toFile()
        try {
            val journal = MemoryJournal()
            val directory = File(root, "drafts")
            val store = RecordingShareDraftStore(directory, journal, { 100 }).apply { recover() }
            val ticket = store.begin(origin, "original.m4a", 200)
            val draft = store.complete(ticket, seal(ticket, root))
            assertNull(draft.storageOrigin); assertNull(draft.uploaded)
            assertFalse(draft.sealed.file.readText(Charsets.ISO_8859_1).contains("Synthetic native recording bytes"))
            val restored = RecordingShareDraftStore(directory, journal, { 101 }).apply { recover() }
            assertEquals(draft, restored.selected(draft.id, origin.room))
            assertTrue(restored.list(otherRoom).isEmpty())
            restored.remove(draft.id, origin.room)
            assertTrue(File(root, "original.m4a").isFile)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `chosen origin receipt and original room cannot be substituted after Upload`() {
        val root = Files.createTempDirectory("recording-draft-origin-").toFile()
        try {
            val journal = MemoryJournal(); val directory = File(root, "drafts")
            val store = RecordingShareDraftStore(directory, journal, { 100 }).apply { recover() }
            val ticket = store.begin(origin, "original.m4a", null)
            val draft = store.complete(ticket, seal(ticket, root))
            assertFails { store.bindOrigin(draft.id, otherRoom, "https://private.example") }
            assertFails { store.bindOrigin(draft.id, origin.room, "http://private.example") }
            val bound = store.bindOrigin(draft.id, origin.room, "https://PRIVATE.example/")
            assertEquals("https://private.example", bound.storageOrigin)
            assertFails { store.bindOrigin(draft.id, origin.room, "https://different.example") }
            assertFails { store.retainUpload(draft.id, otherRoom, "https://private.example", receipt(draft, "https://private.example")) }
            assertFails { store.retainUpload(draft.id, origin.room, "https://private.example", receipt(draft, "https://different.example")) }
            assertFails { store.retainUpload(draft.id, origin.room, "https://private.example", receipt(draft, "https://private.example").copy(key = "ff".repeat(32))) }
            val incoming = receipt(draft, "https://private.example")
            val retained = store.retainUpload(draft.id, origin.room, "https://private.example", incoming)
            val restored = RecordingShareDraftStore(directory, journal, { 101 }).apply { recover() }
            assertEquals(retained, restored.selected(draft.id, origin.room))
            restored.remove(draft.id, origin.room)
            assertFails { restored.retainUpload(draft.id, origin.room, "https://private.example", incoming) }
            assertTrue(restored.list().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `Forget revokes an Add worker even when it creates encrypted bytes afterwards`() {
        val root = Files.createTempDirectory("recording-draft-late-").toFile()
        try {
            val journal = MemoryJournal(); val directory = File(root, "drafts")
            val store = RecordingShareDraftStore(directory, journal, { 100 }).apply { recover() }
            val ticket = store.begin(origin, "original.m4a", null)
            store.forgetRoom(origin.room)
            val late = seal(ticket, root)
            assertFails { store.complete(ticket, late) }
            store.abandon(ticket)
            assertTrue(directory.listFiles()!!.isEmpty())
            assertTrue(RecordingShareDraftStore(directory, journal, { 101 }).apply { recover() }.list().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun `expiry during Add and process restart discard incomplete ciphertext`() {
        val root = Files.createTempDirectory("recording-draft-expired-").toFile()
        try {
            var at = 100L
            val journal = MemoryJournal(); val directory = File(root, "drafts")
            val store = RecordingShareDraftStore(directory, journal, { at }).apply { recover() }
            val ticket = store.begin(origin, "original.m4a", 101)
            val sealed = seal(ticket, root); at = 101
            assertFails { store.complete(ticket, sealed) }; store.abandon(ticket)
            at = 102
            val next = store.begin(origin, "original.m4a", 200)
            seal(next, root)
            val restarted = RecordingShareDraftStore(directory, journal, { at }).apply { recover() }
            assertTrue(restarted.list().isEmpty()); assertTrue(directory.listFiles()!!.isEmpty())
            val lateAfterRestart = seal(next, root)
            assertFails { restarted.complete(next, lateAfterRestart) }
            store.abandon(next)
        } finally { root.deleteRecursively() }
    }

    @Test fun `failed journal commit cannot present a draft and failed Forget denies current access`() {
        val root = Files.createTempDirectory("recording-draft-journal-").toFile()
        try {
            val journal = MemoryJournal(); val directory = File(root, "drafts")
            val store = RecordingShareDraftStore(directory, journal, { 100 }).apply { recover() }
            val ticket = store.begin(origin, "original.m4a", null); val sealed = seal(ticket, root)
            journal.fail = true
            assertFails { store.complete(ticket, sealed) }
            assertTrue(store.list().isEmpty()); assertTrue(directory.listFiles()!!.isEmpty())
            journal.fail = false
            val retry = store.begin(origin, "original.m4a", null)
            val draft = store.complete(retry, seal(retry, root))
            journal.fail = true
            assertFails { store.forgetRoom(origin.room) }
            assertFails { store.selected(draft.id, origin.room) }
            assertFails { store.begin(origin, "original.m4a", null) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `final Add commit cannot borrow a replacement export from another room`() {
        val root = Files.createTempDirectory("recording-draft-source-").toFile()
        try {
            val exports = LocalRecordingStore(File(root, "exports"), { 100 })
            val original = exports.complete(exports.begin(RecordingFormat.AUDIO, origin, 200).apply { writeText("Synthetic original audio") })
            val store = RecordingShareDraftStore(File(root, "drafts"), MemoryJournal(), { 100 }).apply { recover() }
            val ticket = store.begin(origin, original.name, 200)
            val sealed = sealFile(original, ticket.destination, original.name, "audio/mp4")
            exports.forgetRoom(origin.room)
            val replacement = exports.complete(exports.begin(RecordingFormat.AUDIO,
                RecordingOrigin(otherRoom, "ef".repeat(16), "Other room")).apply { writeText("Other room's private recording") })
            var committed = false
            assertFails {
                exports.withSelectedExport(original.name) { _, _ ->
                    committed = true
                    store.complete(ticket, sealed)
                }
            }
            assertFalse(committed)
            store.abandon(ticket)
            assertTrue(store.list().isEmpty())
            assertEquals("Other room's private recording", replacement.readText())
        } finally { root.deleteRecursively() }
    }
}
