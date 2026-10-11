package dev.forgesworn.kithmoot.media.recording

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual Keystore and AtomicFile; restored owners in the same emulator
 * process, with a synthetic clock and no HTTP. A process-kill test is separate. */
class RecordingUploadJournalAndroidTest {
    @Test fun wrapped_storage_identity_restores_and_cleans_without_room_references() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        InstrumentationRegistry.getArguments().getString("recordingUploadRestartStage")?.let {
            processRestart(it)
            return
        }
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val alias = "kithmoot.lab.recording-uploads.${UUID.randomUUID()}"
        val storage = EncryptedRoomStorage(app, alias)
        val room = "ab".repeat(32); val hash = "cd".repeat(32); val origin = "https://private.example"
        var time = 100L
        try {
            val owner = RecordingUploadJournal(storage) { time }.apply { recover() }
            val key = owner.identity(origin)
            val ticket = owner.begin(room, origin, hash, null)
            assertEquals(key, owner.uploadAuthorisation(ticket).pubkey)
            assertTrue(owner.finish(ticket, true))
            val vault = File(app.noBackupFilesDir, "$alias.vault")
            val ciphertext = vault.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(ciphertext.contains(room)); assertFalse(ciphertext.contains(hash)); assertFalse(ciphertext.contains(origin))
            val bytes = requireNotNull(storage.read())
            try {
                val secret = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                    .getValue("identities").jsonObject.getValue(origin).jsonPrimitive.content
                assertFalse("Storage secret must remain wrapped", ciphertext.contains(secret))
            } finally { bytes.fill(0) }
            val reopened = RecordingUploadJournal(EncryptedRoomStorage(app, alias)) { time }.apply { recover() }
            assertEquals(key, reopened.identity(origin))
            assertTrue(reopened.readyToSend(room, origin, hash))
            reopened.forgetRoom(room)
            val afterForget = requireNotNull(storage.read())
            try { assertFalse(afterForget.toString(Charsets.UTF_8).contains(room)) } finally { afterForget.fill(0) }
            time += 100
            val cleanup = RecordingUploadJournal(EncryptedRoomStorage(app, alias)) { time }.apply { recover() }
            assertFalse(cleanup.readyToSend(room, origin, hash))
            var requests = 0
            cleanup.retry { server, target, auth ->
                requests++
                assertEquals(origin, server); assertEquals(hash, target); assertEquals(key, auth.pubkey)
                assertTrue(Events.verify(auth)); assertEquals(time, auth.createdAt)
                assertEquals("delete", auth.tagValue("t")); assertEquals(hash, auth.tagValue("x"))
                assertEquals((time + 300).toString(), auth.tagValue("expiration"))
                true
            }
            assertEquals(1, requests)
            val finalOwner = RecordingUploadJournal(EncryptedRoomStorage(app, alias)) { time }.apply { recover() }
            finalOwner.retry { _, _, _ -> error("Confirmed cleanup cannot repeat") }
        } finally { storage.reset() }
    }

    /** The guarded external driver force-stops the app between these stages.
     * This fixed alias belongs only to this disposable fixture. */
    private fun processRestart(stage: String) {
        require(stage in setOf("prepare", "verify"))
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val storage = EncryptedRoomStorage(app, "kithmoot.lab.recording-uploads.restart")
        val marker = File(app.noBackupFilesDir, "recording-upload-restart-fixture.json")
        val origin = "https://private.example"; val room = "ab".repeat(32); val hash = "cd".repeat(32)
        if (stage == "prepare") {
            storage.reset()
            val owner = RecordingUploadJournal(storage) { 100 }.apply { recover() }
            val key = owner.identity(origin)
            val ticket = owner.begin(room, origin, hash, null)
            assertTrue(owner.finish(ticket, true))
            owner.forgetRoom(room)
            marker.writeText(buildJsonObject { put("publicKey", key); put("pid", android.os.Process.myPid()) }.toString())
            return
        }
        try {
            val fixture = Json.parseToJsonElement(marker.readText()).jsonObject
            assertNotEquals("The verification must use a new process", fixture.getValue("pid").jsonPrimitive.int, android.os.Process.myPid())
            val owner = RecordingUploadJournal(storage) { 200 }.apply { recover() }
            assertEquals(fixture.getValue("publicKey").jsonPrimitive.content, owner.identity(origin))
            val bytes = requireNotNull(storage.read())
            try { assertFalse(bytes.toString(Charsets.UTF_8).contains(room)) } finally { bytes.fill(0) }
            assertFalse(owner.readyToSend(room, origin, hash))
            var requests = 0
            owner.retry { server, target, auth ->
                requests++
                assertEquals(origin, server); assertEquals(hash, target)
                assertEquals(fixture.getValue("publicKey").jsonPrimitive.content, auth.pubkey)
                assertTrue(Events.verify(auth)); assertEquals(200L, auth.createdAt)
                assertEquals("delete", auth.tagValue("t")); assertEquals("500", auth.tagValue("expiration"))
                true
            }
            assertEquals(1, requests)
        } finally { storage.reset(); check(!marker.exists() || marker.delete()) }
    }
}
