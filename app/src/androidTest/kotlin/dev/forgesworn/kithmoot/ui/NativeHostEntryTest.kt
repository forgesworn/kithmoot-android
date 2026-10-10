package dev.forgesworn.kithmoot.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.RoomRekeyBinding
import dev.forgesworn.kithmoot.epoch.NativeHostingStatus
import dev.forgesworn.kithmoot.epoch.NativeKeeperController
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.storage.NativeKeeperVault
import dev.forgesworn.kithmoot.storage.RoomRekeyVault
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Rendered production controls and actual encrypted authority ownership. */
class NativeHostEntryTest {
    @get:Rule val compose = createComposeRule()
    private fun sourceDigest(source: JsonObject): String {
        val bytes = source.toString().toByteArray(Charsets.UTF_8)
        try { return Digests.sha256(bytes).toHex() } finally { bytes.fill(0) }
    }

    @Test fun nearby_creation_approval_lost_grant_withdrawal_and_reopen() = runBlocking { journey(RoomRoute.NEARBY) }
    @Test fun mixed_creation_approval_lost_grant_withdrawal_and_reopen() = runBlocking { journey(RoomRoute.MIXED) }

    private suspend fun journey(route: RoomRoute) = coroutineScope {
        val f = NativeHostFixture(loseGrant = true)
        try {
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText(if (route.internet) "Start nearby + Internet chat" else "Start nearby chat")
                .performScrollTo().performClick()
            val saved = f.opened()
            val original = f.source(saved)
            assertEquals(route, saved.route)
            assertNotNull(saved.nativeAuthority)
            assertNull(saved.host(System.currentTimeMillis() / 1000))
            assertFalse(saved.json.containsKey("host"))
            assertTrue(original.getValue("courierReady").jsonPrimitive.boolean)
            NativeHostFixture.await("selected public hosting projection is ready") {
                f.model.room.value.nativeHosting?.status == NativeHostingStatus.READY
            }
            assertEquals(saved.nativeAuthority!!.pin, f.model.room.value.nativeHosting!!.binding.pin)
            assertEquals(listOf(saved.participant), f.model.room.value.nativeHosting!!.approved)
            compose.onNodeWithText("Hosting · epoch 0").assertIsDisplayed()
            val welcome = NostrEvent.fromJson(original.getValue("welcome"))
            val at = System.currentTimeMillis() / 1000
            val who = f.identity(saved, at)
            val joining = async { f.join(saved, at) }
            NativeHostFixture.await("unknown member is shown after original grant loss") {
                f.model.room.value.letInAsks.any { it.participant == who.participant }
            }
            assertFalse("Unknown member cannot enter without approval", joining.isCompleted)
            compose.onNodeWithText("Let in").performClick()
            val peer = joining.await()
            assertEquals(2, f.grants.size)
            assertEquals(f.grants.first(), f.grants.last())
            NativeHostFixture.await("approval is in the real source") {
                f.source(saved).getValue("members").jsonArray.any { it.jsonPrimitive.content == who.participant }
            }
            NativeHostFixture.await("source approval reaches selected public hosting state") {
                f.model.room.value.nativeHosting?.approved?.contains(who.participant) == true
            }
            compose.onNodeWithContentDescription("Room details").performClick()
            compose.onNodeWithText("Last observed epoch 0 · 2 approved members · 0 removed")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            f.main { f.model.sendChat("host before withdrawal") }
            peer.sendChat("member before withdrawal")
            NativeHostFixture.await("fresh chat crosses both ways") {
                peer.chat.value.count { it.body == "host before withdrawal" } == 1 &&
                    f.model.room.value.chat.count { it.body == "member before withdrawal" } == 1
            }
            if (!route.internet) assertEquals("Nearby-only creates no Internet request", 0, f.server.requestCount)
            val owners = f.radios.size
            f.main {
                f.model.setAppVisible(false)
                // Main.immediate teardown may already clear RoomState before
                // setAppVisible returns. Neither outcome may claim hosting.
                val withdrawn = f.model.room.value.nativeHosting
                assertTrue(withdrawn == null || withdrawn.status == NativeHostingStatus.SUSPENDED)
                assertFalse(withdrawn?.canRetry == true)
                assertFalse(f.model.nativeHostState() is NativeKeeperController.State.Ready)
                f.model.setAppVisible(true)
            }
            NativeHostFixture.await("rapid return retires the old owner before rebinding") {
                f.radios.size > owners && f.radios.take(owners).all { it.closed } &&
                    f.model.stage.value == Stage.ROOM && !f.model.start.value.busy
            }
            assertEquals(saved.participant, f.model.room.value.selfParticipant)
            assertEquals(saved.devicePubkey, f.model.room.value.selfDevice)
            f.main { f.model.sendChat("host after return") }
            f.awaitHost("fresh host traffic resumes after verification") {
                peer.chat.value.count { it.body == "host after return" } == 1
            }
            f.main { f.model.leave(); assertNull(f.model.room.value.nativeHosting) }
            NativeHostFixture.await("leave completes") { f.model.stage.value == Stage.START && !f.model.start.value.busy }
            f.main { f.model.reopenRoom(saved.id) }; f.opened()
            NativeHostFixture.await("reopened public hosting state comes from the same source") {
                f.model.room.value.nativeHosting?.let {
                    it.status == NativeHostingStatus.READY && it.binding.pin == saved.nativeAuthority!!.pin &&
                        it.approved.toSet() == setOf(saved.participant, who.participant)
                } == true
            }
            compose.onNodeWithText("Hosting · epoch 0").assertIsDisplayed()
            assertEquals(saved.participant, f.model.room.value.selfParticipant)
            assertEquals(saved.devicePubkey, f.model.room.value.selfDevice)
            assertEquals(saved.json["nativeAuthority"], f.app.savedRooms.get(saved.id)!!.json["nativeAuthority"])
            assertEquals(welcome, NostrEvent.fromJson(f.source(saved).getValue("welcome")))
            assertTrue(f.model.room.value.letInAsks.isEmpty())
            peer.sendChat("member after reopen")
            NativeHostFixture.await("reopened native host receives fresh chat") {
                f.model.room.value.chat.count { it.body == "member after reopen" } == 1
            }
            if (!route.internet) assertEquals(0, f.server.requestCount)
        } finally { f.close() }
    }

    @Test fun missing_marked_courier_refuses_reopen_before_any_route_or_new_source_credit() = runBlocking {
        val f = NativeHostFixture()
        try {
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText("Start nearby chat").performScrollTo().performClick()
            val saved = f.opened()
            f.main { f.model.leave() }
            NativeHostFixture.await("leave finishes before inactive journal inspection") { !f.model.start.value.busy && f.model.stage.value == Stage.START }
            val b = requireNotNull(saved.nativeAuthority)
            // The active owner must actually have released the source lease.
            NativeHostFixture.await("source owner closes") {
                runCatching { NativeKeeperVault.forSavedRoom(f.app, saved).open().use { it.courierReady() } }.getOrDefault(false)
            }
            val before = f.source(saved)
            RoomRekeyVault(f.app, RoomRekeyBinding(b.room, b.authority, b.device, b.meshScope, b.relays, b.route)).forget()
            val owners = f.radios.size
            f.main { f.model.reopenRoom(saved.id) }
            NativeHostFixture.await("missing marked courier is refused") { !f.model.start.value.busy && f.model.start.value.error != null }
            assertEquals(Stage.START, f.model.stage.value)
            assertEquals(owners, f.radios.size)
            // Failed comparisons must not print the source's private material.
            assertEquals(sourceDigest(before), sourceDigest(f.source(saved)))
            assertNull(f.courierStore(saved).read())
            assertNotNull(f.app.savedRooms.get(saved.id))
        } finally { f.close() }
    }

    @Test fun corrupt_index_cannot_orphan_the_actual_native_signer_and_restored_index_can_reset_it() = runBlocking {
        val f = NativeHostFixture()
        val index = File(f.app.noBackupFilesDir, "kithmoot.rooms.v1.vault")
        val orphanIntent = File(f.app.noBackupFilesDir, "kithmoot.native-creation.v1.vault.new")
        var original: ByteArray? = null
        try {
            f.startModel(); compose.showNativeHost(f)
            compose.onNodeWithText("Start nearby chat").performScrollTo().performClick()
            val saved = f.opened()
            f.main { f.model.leave() }
            NativeHostFixture.await("native owner retires before index corruption") {
                !f.model.start.value.busy && f.model.stage.value == Stage.START &&
                    runCatching { NativeKeeperVault.forSavedRoom(f.app, saved).open().use { it.courierReady() } }.getOrDefault(false)
            }
            original = index.readBytes()
            val sourceBefore = sourceDigest(f.source(saved))
            index.writeText("broken ciphertext")
            // A failed native creation must survive without an AtomicFile read
            // discarding its only bytes. Only this fixture creates this file.
            assertFalse(orphanIntent.exists())
            orphanIntent.writeText("fixture unfinished creation")
            f.main { f.model.refreshSavedRooms() }
            NativeHostFixture.await("corrupt native index is visible") { !f.model.start.value.loadingRooms && f.model.start.value.storageError }
            f.main { f.model.resetSavedRooms() }
            NativeHostFixture.await("native state blocks destructive index reset") { !f.model.start.value.busy && f.model.start.value.error != null }
            assertTrue(f.model.start.value.storageError)
            assertEquals("broken ciphertext", index.readText())
            assertEquals("fixture unfinished creation", orphanIntent.readText())
            assertEquals(sourceBefore, sourceDigest(f.source(saved)))
            assertNotNull(f.courierStore(saved).read())
            assertTrue(orphanIntent.delete())
            index.writeBytes(requireNotNull(original))
            f.main { f.model.refreshSavedRooms() }
            NativeHostFixture.await("restored exact index identifies native aliases") { !f.model.start.value.loadingRooms && !f.model.start.value.storageError }
            f.main { f.model.resetSavedRooms() }
            NativeHostFixture.await("readable native reset deletes source and references") {
                !f.model.start.value.busy && !f.model.start.value.storageError && f.app.savedRooms.list().isEmpty()
            }
            assertNull(f.sourceStore(saved).read()); assertNull(f.courierStore(saved).read())
        } finally {
            orphanIntent.delete()
            // Restore only this fixture's original encrypted index if a failure
            // leaves it unreadable, so actual cleanup can still locate the source.
            if (original != null && runCatching { f.app.savedRooms.list() }.isFailure) index.writeBytes(original!!)
            try { f.close() } finally { original?.fill(0) }
        }
    }
}
