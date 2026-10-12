package dev.forgesworn.kithmoot.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.storage.NativeKeeperVault
import dev.forgesworn.kithmoot.storage.ReceiverKeyCreationProbe
import dev.forgesworn.kithmoot.storage.RoomStorageException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Actual foreground creation, leave, refresh and restored saved index. */
class MissingIndexMaintenanceTest {
    @get:Rule val compose = createComposeRule()
    private fun aliases() = KeyStore.getInstance("AndroidKeyStore").run {
        load(null); aliases().toList().toSet()
    }

    @Test fun missing_index_refresh_preserves_native_authority_without_receiver_creation_or_route_entry() = runBlocking {
        val f = NativeHostFixture()
        val index = File(f.app.noBackupFilesDir, "kithmoot.rooms.v1.vault")
        var original: ByteArray? = null
        var observation: ReceiverKeyCreationProbe.Session? = null
        var retained: Map<String, ByteArray> = emptyMap()
        try {
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText("Start nearby chat").performScrollTo().performClick()
            val saved = f.opened()
            f.main { f.model.leave() }
            NativeHostFixture.await("native owner closes before missing-index refresh") {
                !f.model.start.value.busy && !f.model.start.value.loadingRooms &&
                    f.model.stage.value == Stage.START && f.radios.all { it.closed } &&
                    runCatching { NativeKeeperVault.forSavedRoom(f.app, saved).open().use { it.courierReady() } }.getOrDefault(false)
            }
            val keys = aliases()
            val factories = f.nearbyLinkCreations
            val offers = f.phoneEvents.size
            val relayWrites = f.relayWrites.size
            synchronized(f.app.savedRooms) {
                original = index.readBytes()
                retained = f.app.noBackupFilesDir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".vault") && it != index }
                    .associate { it.name to it.readBytes() }
                assertTrue(index.delete())
            }
            observation = ReceiverKeyCreationProbe.open(ReceiverKeyCreationProbe.Context(
                "reference-installed-before-subscription-switch", "INDEX", "MISSING",
                ReceiverKeyCreationProbe.Phase.OWNER_BARRIERS))
            f.main { f.model.refreshSavedRooms() }
            NativeHostFixture.await("missing index exposes the storage error") {
                !f.model.start.value.loadingRooms && f.model.start.value.storageError
            }
            // Both real retention callbacks must refuse, including the late-list
            // callback under the receiver lock that previously erased every room.
            assertThrows(RoomStorageException::class.java) {
                f.app.roomEpochs.retainOnly { f.app.savedRooms.list().map { it.id }.toSet() }
            }
            assertThrows(RoomStorageException::class.java) {
                f.app.roomMembers.retainOnly { f.app.savedRooms.list().map { it.id }.toSet() }
            }
            val captured = requireNotNull(observation).closeAndSnapshot()
            observation = null
            assertTrue(captured.complete)
            assertTrue(captured.events.isEmpty())
            assertEquals(keys, aliases())
            assertFalse(index.exists())
            val actual = f.app.noBackupFilesDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".vault") && it != index }
            assertEquals(retained.keys, actual.map { it.name }.toSet())
            actual.forEach { assertArrayEquals(retained.getValue(it.name), it.readBytes()) }
            assertEquals(factories, f.nearbyLinkCreations)
            assertEquals(offers, f.phoneEvents.size)
            assertEquals(relayWrites, f.relayWrites.size)
            assertTrue(f.radios.all { it.closed })
            assertEquals(Stage.START, f.model.stage.value)
            synchronized(f.app.savedRooms) { index.writeBytes(requireNotNull(original)) }
            f.main { f.model.refreshSavedRooms() }
            NativeHostFixture.await("exact index restoration reopens saved authority") {
                !f.model.start.value.loadingRooms && !f.model.start.value.storageError
            }
            assertNotNull(f.app.savedRooms.get(saved.id))
            assertEquals(keys, aliases())
            assertArrayEquals(original, index.readBytes())
        } finally {
            observation?.closeAndSnapshot()
            synchronized(f.app.savedRooms) { original?.let { index.writeBytes(it) } }
            original?.fill(0)
            retained.values.forEach { it.fill(0) }
            f.close()
        }
    }
}
