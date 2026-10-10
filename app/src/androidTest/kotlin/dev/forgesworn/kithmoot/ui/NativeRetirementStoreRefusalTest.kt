package dev.forgesworn.kithmoot.ui

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.RoomRekeyBinding
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.NativeKeeperVault
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore

/** Actual foreground retirement and encrypted stores; only BLE bytes are fake.
 * Design mesh-kit 18024b1 precedes this missing/corrupt retirement matrix. */
class NativeRetirementStoreRefusalTest {
    @get:Rule val compose = createComposeRule()
    private enum class Target { SOURCE, RECEIVER, COURIER }
    private enum class Fault { MISSING, CORRUPT }

    @Test fun missing_retired_source_refuses_before_routes_or_recreation() = runBlocking {
        refuses(Target.SOURCE, Fault.MISSING)
    }
    @Test fun corrupt_retired_source_refuses_before_routes_or_reset() = runBlocking {
        refuses(Target.SOURCE, Fault.CORRUPT)
    }
    @Test fun missing_retired_receiver_refuses_before_routes_or_reinitialisation() = runBlocking {
        refuses(Target.RECEIVER, Fault.MISSING)
    }
    @Test fun corrupt_retired_receiver_refuses_before_routes_or_reset() = runBlocking {
        refuses(Target.RECEIVER, Fault.CORRUPT)
    }
    @Test fun missing_retired_marked_courier_refuses_before_routes_or_fresh_credit() = runBlocking {
        refuses(Target.COURIER, Fault.MISSING)
    }
    @Test fun corrupt_retired_marked_courier_refuses_before_routes_or_reset() = runBlocking {
        refuses(Target.COURIER, Fault.CORRUPT)
    }

    private suspend fun refuses(target: Target, fault: Fault) {
        require(Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Use the guarded disposable emulator runner"
        }
        val f = NativeHostFixture()
        val originals = mutableMapOf<File, ByteArray>()
        val damaged = mutableMapOf<File, ByteArray?>()
        var targetFile: File? = null
        var ownedAliases = emptySet<String>()
        var fixtureRoom: String? = null
        var primary: Throwable? = null
        try {
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText("Start nearby chat").performScrollTo().performClick()
            val saved = f.opened()
            fixtureRoom = saved.id
            f.awaitHost("retirement refusal fixture can retire its actual source") {
                f.model.room.value.nativeHosting?.canRetireInvitation == true
            }
            compose.onNodeWithContentDescription("Room details").performClick()
            compose.onNodeWithText("Retire invitation").performScrollTo().performClick()
            compose.onNodeWithText("Retire link").performClick()
            f.awaitHost("retirement refusal fixture archives its original") {
                f.model.room.value.nativeHosting?.canResendRetirement == true &&
                    !f.model.room.value.nativeHostingBusy &&
                    f.app.savedRooms.get(saved.id)?.retired == true
            }
            assertFalse(f.model.room.value.canShareInvitation)
            assertTrue(f.model.room.value.joinUrl.isEmpty())
            f.main { f.model.leave() }
            NativeHostFixture.await("retired source owner actually releases its lease") {
                f.model.stage.value == Stage.START && !f.model.start.value.busy &&
                    f.radios.all { it.closed } && runCatching {
                        NativeKeeperVault.forSavedRoom(f.app, saved).open().use { it.courierReady() }
                    }.getOrDefault(false)
            }
            val source = f.source(saved)
            assertEquals("RETIRED", source.getValue("phase").jsonPrimitive.content)
            assertEquals(0, source.getValue("epoch").jsonPrimitive.int)
            assertEquals(JsonNull, source.getValue("pending"))
            assertTrue(source.getValue("courierReady").jsonPrimitive.boolean)
            val archive = source.getValue("retirements").jsonArray.single().jsonObject
            val original = NostrEvent.fromJson(archive.getValue("event"))
            assertTrue(Events.verify(original)); assertEquals(KIND_INVITATION_RETIREMENT, original.kind)
            assertEquals(1, archive.getValue("attempts").jsonObject.values.sumOf { it.jsonPrimitive.int })
            assertEquals(1, archive.getValue("offered").jsonObject.values.sumOf { it.jsonPrimitive.int })
            val debt = source.getValue("spends").jsonArray.sumOf {
                if (it.jsonObject.getValue("lane") == JsonPrimitive("NEARBY"))
                    it.jsonObject.getValue("bytes").jsonPrimitive.int else 0
            }
            assertEquals(original.toCompactJson().toByteArray(Charsets.UTF_8).size, debt)
            val retained = requireNotNull(f.app.savedRooms.get(saved.id))
            assertFalse("Native saved state must contain no legacy signer", "host" in retained.json)
            assertNotNull(retained.nativeAuthority)
            val b = requireNotNull(retained.nativeAuthority)
            val q = RoomRekeyBinding(b.room, b.authority, b.device, b.meshScope, b.relays, b.route)
            val directory = f.app.noBackupFilesDir
            val files = mapOf(
                Target.SOURCE to File(directory, "kithmoot.keeper-authority." + Digests.sha256(b.owner.toByteArray(Charsets.UTF_8)).toHex() + ".vault"),
                Target.RECEIVER to File(directory, "kithmoot.epoch.v1.vault"),
                Target.COURIER to File(directory, "kithmoot.keeper-rekeys." + Digests.sha256(q.owner.toByteArray(Charsets.UTF_8)).toHex() + ".vault"),
            )
            val inspect = files.values + File(directory, "kithmoot.rooms.v1.vault")
            ownedAliases = setOf(files.getValue(Target.SOURCE).name.removeSuffix(".vault"),
                files.getValue(Target.COURIER).name.removeSuffix(".vault"))
            for (file in inspect) {
                assertTrue("Retirement fixture store must exist before damage", file.isFile)
                assertFalse("No unfinished writer may overlap damage", File(file.path + ".new").exists())
                assertFalse("No older file may supply recovery", File(file.path + ".bak").exists())
                check(file.length() in 1..(32L * 1024 * 1024))
                originals[file] = file.readBytes()
            }
            val keysBefore = aliases()
            targetFile = files.getValue(target)
            when (fault) {
                Fault.MISSING -> assertTrue("Remove only the fixture's committed file", targetFile.delete())
                Fault.CORRUPT -> {
                    val bytes = originals.getValue(targetFile).copyOf()
                    try {
                        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                        targetFile.writeBytes(bytes)
                    } finally { bytes.fill(0) }
                }
            }
            for (file in inspect) damaged[file] = if (file.exists()) file.readBytes() else null
            val inventory = inventory(directory)
            val radios = f.radios.size
            val offered = f.phoneEvents.size
            val relayWrites = f.relayWrites.size
            val relayRequests = f.server.requestCount
            assertEquals(0, relayRequests)
            f.main { f.model.reopenRoom(saved.id) }
            NativeHostFixture.await("damaged retired native store refuses foreground entry") {
                !f.model.start.value.busy && f.model.start.value.error != null
            }
            assertEquals(Stage.START, f.model.stage.value)
            assertEquals(radios, f.radios.size)
            assertEquals(offered, f.phoneEvents.size)
            assertEquals(relayWrites, f.relayWrites.size)
            assertEquals(relayRequests, f.server.requestCount)
            assertTrue("Refusal must preserve the exact store file inventory", inventory == inventory(directory))
            assertTrue("Refusal must neither create nor delete a wrapping key", keysBefore == aliases())
            for ((file, before) in damaged) {
                if (before == null) assertFalse("Missing store must not be recreated", file.exists())
                else {
                    val after = file.readBytes()
                    try { assertTrue("Refusal must preserve every retained ciphertext", before.contentEquals(after)) }
                    finally { after.fill(0) }
                }
            }
            println("NATIVE_RETIREMENT_STORE_REFUSAL target=${target.name} fault=${fault.name} " +
                "sourceEpoch=0 phase=RETIRED attempts=1 chargedBytes=$debt " +
                "newRadios=0 newOffers=0 relayRequests=0 filesUnchanged=true keysUnchanged=true " +
                "processDeath=false participantReceipt=false")
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                // Restore only this fixture's selected original so explicit
                // forgetting can locate its authority. No recovery is inferred.
                try {
                    targetFile?.let { file -> originals[file]?.let { file.writeBytes(it) } }
                } catch (error: Throwable) { cleanupFailure = error }
                try {
                    f.close()
                    assertTrue("Explicit forgetting must remove fixture authority and courier keys",
                        aliases().none { it in ownedAliases })
                    for (alias in ownedAliases) for (suffix in listOf(".vault", ".vault.new", ".vault.bak"))
                        assertFalse("Explicit forgetting must remove fixture authority and courier files",
                            File(f.app.noBackupFilesDir, alias + suffix).exists())
                    fixtureRoom?.let { room ->
                        assertNull(f.app.savedRooms.get(room))
                        assertNull(f.app.roomEpochs.get(room))
                    }
                }
                catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                } finally {
                    originals.values.forEach { it.fill(0) }
                    damaged.values.filterNotNull().forEach { it.fill(0) }
                }
                cleanupFailure?.let { error ->
                    if (primary == null) throw error else primary!!.addSuppressed(error)
                }
            }
        }
    }

    private fun aliases(): Set<String> = KeyStore.getInstance("AndroidKeyStore").run {
        load(null); aliases().toList().toSet()
    }
    private fun inventory(directory: File): Set<String> = requireNotNull(directory.listFiles()).map { it.name }.toSet()
}
