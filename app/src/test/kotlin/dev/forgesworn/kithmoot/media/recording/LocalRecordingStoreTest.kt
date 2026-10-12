package dev.forgesworn.kithmoot.media.recording

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class LocalRecordingStoreTest {
    private val origin = RecordingOrigin("1".repeat(64), "2".repeat(32), "Original room")

    @Test fun `picker consent survives recovery but cannot save a replacement from another room`() {
        val directory = Files.createTempDirectory("recording-save-owner-").toFile()
        try {
            val store = LocalRecordingStore(directory)
            val original = store.complete(store.begin(RecordingFormat.VIDEO, origin).apply { writeText("first private movie") })
            val selectedName = original.name
            val recovered = LocalRecordingStore(directory)
            recovered.recover()
            assertEquals(original, recovered.selectedExport(selectedName))
            assertEquals(RecordingFormat.VIDEO, recovered.details(original).format)
            recovered.forgetRoom(origin.room)
            val other = RecordingOrigin("3".repeat(64), "4".repeat(32), "Other room")
            val replacement = recovered.complete(recovered.begin(RecordingFormat.AUDIO, other).apply { writeText("other private call") })
            assertFailsWith<IllegalStateException> { recovered.selectedExport(selectedName) }
            assertEquals("other private call", replacement.readText())
            assertEquals(other, recovered.details(replacement).origin)
            assertEquals(replacement, recovered.export.value)
            assertEquals(replacement, recovered.selectedExport(replacement.name))
            assertFalse(original.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `video format and exact originating call survive process recovery`() {
        val directory = Files.createTempDirectory("recording-video-").toFile()
        try {
            val store = LocalRecordingStore(directory)
            val source = store.begin(RecordingFormat.VIDEO, origin).apply { writeText("synthetic mp4") }
            val file = store.complete(source)
            assertEquals("mp4", file.extension)
            val reopened = LocalRecordingStore(directory)
            assertEquals(file, reopened.recover())
            assertEquals(RecordingExportDetails(RecordingFormat.VIDEO, origin), reopened.details(file))
            assertEquals("video/mp4", reopened.details(file).format.mime)
            reopened.forgetRoom("3".repeat(64))
            assertEquals(file, reopened.export.value)
            reopened.discard(file)
            assertNull(reopened.export.value)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `destruction revokes a finalising capture before a worker can retain it`() {
        val directory = Files.createTempDirectory("recording-revoked-").toFile()
        try {
            val store = LocalRecordingStore(directory)
            val source = store.begin(RecordingFormat.VIDEO, origin)
            store.forgetRoom(origin.room)
            // Simulate remux completing after the room's destruction.
            source.writeText("late completed mp4")
            assertFails { store.complete(source) }
            assertFalse(source.exists())
            assertNull(store.export.value)
            assertTrue(directory.listFiles()!!.isEmpty())
            store.abandon(store.begin())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `destroyed completed exports disappear while unrelated rooms remain available`() {
        val directory = Files.createTempDirectory("recording-forget-").toFile()
        try {
            val store = LocalRecordingStore(directory)
            val file = store.complete(store.begin(origin = origin).apply { writeText("synthetic audio") })
            store.forgetRoom("3".repeat(64))
            assertTrue(file.exists())
            store.forgetRoom(origin.room)
            assertFalse(file.exists())
            assertNull(store.export.value)
            assertFails { store.details(file) }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `expiry prevents late completion and removes completed exports before recovery presents them`() {
        val directory = Files.createTempDirectory("recording-expiry-").toFile()
        var at = 99L
        try {
            val store = LocalRecordingStore(directory) { at }
            val source = store.begin(origin = origin, discardAt = 100).apply { writeText("synthetic audio") }
            at = 100
            assertFails { store.complete(source) }
            assertFalse(source.exists())
            assertFails { store.begin(origin = origin, discardAt = 100) }
            at = 99
            val file = store.complete(store.begin(origin = origin, discardAt = 100).apply { writeText("synthetic audio") })
            at = 100
            val reopened = LocalRecordingStore(directory) { at }
            assertNull(reopened.recover())
            assertFalse(file.exists())
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `corrupt metadata never presents a video export or infers an originating room`() {
        val directory = Files.createTempDirectory("recording-corrupt-").toFile()
        try {
            val store = LocalRecordingStore(directory)
            val file = store.complete(store.begin(RecordingFormat.VIDEO, origin).apply { writeText("synthetic video") })
            File(directory, file.nameWithoutExtension + ".metadata").writeText("not JSON")
            assertNull(LocalRecordingStore(directory).recover())
            assertFalse(file.exists())
            val legacy = File(directory, "legacy.m4a").apply { writeText("old audio") }
            val reopened = LocalRecordingStore(directory)
            assertEquals(legacy, reopened.recover())
            assertNull(reopened.details(legacy).origin)
        } finally { directory.deleteRecursively() }
    }
    @Test fun `opening the store does not create storage or fail the app when recording storage is unavailable`() {
        val directory = Files.createTempDirectory("recording-lazy-").toFile()
        try {
            val absent = File(directory, "absent")
            val store = LocalRecordingStore(absent)
            assertNull(store.recover())
            assertFalse(absent.exists())
            store.abandon(store.begin())
            assertTrue(absent.isDirectory)
            val blocked = File(directory, "blocked").apply { writeText("keep") }
            val unavailable = LocalRecordingStore(blocked)
            assertNull(unavailable.recover())
            assertFails { unavailable.begin() }
            assertEquals("keep", blocked.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `completed exports survive recovery while interrupted containers are removed`() {
        val directory = Files.createTempDirectory("recording-store-").toFile()
        try {
            val first = LocalRecordingStore(directory)
            assertNull(first.recover())
            val source = first.begin().apply { writeText("finalised-container") }
            val export = first.complete(source)
            File(directory, "interrupted.capture").writeText("unfinished")
            val parts = File(directory, "interrupted.capture.parts").apply { mkdir() }
            File(parts, "video.mp4").writeText("unfinished private video")
            val reopened = LocalRecordingStore(directory)
            assertEquals(export, reopened.recover())
            assertEquals("finalised-container", export.readText())
            assertEquals(export, reopened.export.value)
            assertFalse(File(directory, "interrupted.capture").exists())
            assertFalse(parts.exists())
            assertFails { reopened.begin() }
            reopened.discard(export)
            assertNull(reopened.export.value)
            val next = reopened.begin()
            reopened.abandon(next)
            assertFalse(next.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `one active export and only its owned source can be completed or abandoned`() {
        val directory = Files.createTempDirectory("recording-owner-").toFile()
        try {
            val store = LocalRecordingStore(directory)
            store.recover()
            val source = store.begin()
            assertFails { store.begin() }
            assertFails { store.complete(source) }
            val unrelated = File(directory, "other.capture").apply { writeText("keep") }
            assertFails { store.complete(unrelated) }
            assertFails { store.abandon(unrelated) }
            assertEquals("keep", unrelated.readText())
            source.writeBytes(byteArrayOf())
            assertFails { store.complete(source) }
            store.abandon(source)
            assertFalse(source.exists())
            assertFails { store.begin() } // Unresolved containers keep the disk bound intact.
            assertTrue(unrelated.delete()) // Remove only this test's unrelated fixture.
            assertNotEquals(source, store.begin())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `failed retention preserves the source and cannot overwrite a completed file`() {
        val directory = Files.createTempDirectory("recording-conflict-").toFile()
        try {
            val store = LocalRecordingStore(directory)
            store.recover()
            val source = store.begin().apply { writeText("new") }
            val conflict = File(directory, source.name.removeSuffix(".capture") + ".m4a").apply { writeText("keep") }
            assertFails { store.complete(source) }
            assertEquals("new", source.readText())
            assertEquals("keep", conflict.readText())
            assertFails { store.discard(source) }
            assertTrue(source.exists())
        } finally { directory.deleteRecursively() }
    }
}
