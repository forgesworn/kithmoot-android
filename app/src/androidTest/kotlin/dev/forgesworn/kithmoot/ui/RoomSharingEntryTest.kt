package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.relay.RoomNearbyDiscovery
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.RoomSharingVault
import dev.forgesworn.kithmoot.ui.room.RoomSharingSheet
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual rendered consent, ViewModel and disjoint RoomSessions; injected radio bytes. */
class RoomSharingEntryTest {
    @get:Rule val compose = createComposeRule()
    private suspend fun await(label: String, predicate: () -> Boolean) {
        try { withTimeout(90_000) { while (!predicate()) delay(25) } }
        catch (error: TimeoutCancellationException) { throw AssertionError(label, error) }
    }

    @Test fun explicit_sharing_carries_disjoint_members_both_ways_and_rapid_return_requires_resume() = runBlocking {
        val f = FreshNearbyEntryTest.Fixture(mixed = true, rootInternetOnly = true)
        var meshMember: RoomSession? = null
        try {
            f.start(); f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor, RoomRoute.MIXED) }
            await("mixed room opens for sharing") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            val saved = f.app.savedRooms.get(f.room.roomId)!!
            val now = System.currentTimeMillis() / 1000
            val peer = RoomSession(f.room, PrimaryIdentity.create(f.room.roomId, now + 3600, now),
                f.transport, f.scope, authority = f.host.invitation.inviter, timing = SessionTiming(announceJitterMs = 0))
            meshMember = peer; peer.join()
            peer.sendChat("nearby without sharing")
            await("phone sees isolated nearby message") { f.model.room.value.chat.any { it.body == "nearby without sharing" } }
            delay(5_250)
            assertFalse(f.root.chat.value.any { it.body == "nearby without sharing" })
            assertFalse(f.relayWrites.any { it.kind == KIND_CHAT })
            f.root.sendChat("Internet without sharing")
            await("phone sees isolated Internet message") { f.model.room.value.chat.any { it.body == "Internet without sharing" } }
            delay(500)
            assertFalse(peer.chat.value.any { it.body == "Internet without sharing" })
            val approved = setOf(peer.identity.participant, f.root.identity.participant)
            await("people can be chosen") { f.model.room.value.sharing?.candidates?.containsAll(approved) == true }
            assertTrue(f.model.room.value.sharing!!.selected.isEmpty())
            compose.setContent { KithMootTheme {
                val room by f.model.room.collectAsState()
                room.sharing?.let { RoomSharingSheet(it, f.model::selectSharingParticipant,
                    f.model::startRoomSharing, f.model::stopRoomSharing, {}) }
            } }
            compose.waitUntil(10_000) { compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size == 2 }
            compose.onAllNodes(isToggleable())[0].performScrollTo().performClick()
            await("first choice is saved") { f.model.room.value.sharing?.busy == false }
            compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
            assertFalse(f.model.room.value.sharing!!.enabled)
            await("author edits are committed with sharing off") { f.model.room.value.sharing?.busy == false }
            assertNull(f.model.room.value.sharing!!.error)
            compose.onNodeWithText("Start sharing").performClick()
            await("explicit owner starts") { f.model.room.value.sharing?.enabled == true || f.model.room.value.sharing?.error != null }
            assertNull(f.model.room.value.sharing!!.error)
            peer.sendChat("nearby carried to Internet")
            await("Internet-only member receives original nearby chat") { f.root.chat.value.count { it.body == "nearby carried to Internet" } == 1 }
            val original = f.relayWrites.last { it.kind == KIND_CHAT && it.pubkey == peer.identity.devicePubkey }
            assertTrue(Events.verify(original))
            f.root.sendChat("Internet carried to nearby")
            await("mesh-only member receives reply") { peer.chat.value.count { it.body == "Internet carried to nearby" } == 1 }
            assertEquals(1, f.model.room.value.chat.count { it.body == "Internet carried to nearby" })
            f.main { f.model.setAppVisible(false); f.model.setAppVisible(true) }
            assertFalse(f.model.room.value.sharing?.enabled == true)
            await("rapid return completes teardown and reopens") {
                f.model.stage.value == Stage.ROOM && f.model.room.value.sharing != null && f.linkOwners.get() == 2
            }
            assertEquals(saved.participant, f.model.room.value.selfParticipant)
            assertEquals(approved, f.model.room.value.sharing!!.selected)
            assertFalse(f.model.room.value.sharing!!.enabled)
            peer.sendChat("after return still off")
            await("reopened phone sees nearby") { f.model.room.value.chat.any { it.body == "after return still off" } }
            delay(5_250)
            assertFalse(f.root.chat.value.any { it.body == "after return still off" })
            compose.onNodeWithText("Resume sharing").performClick()
            await("explicit resume starts new owner") { f.model.room.value.sharing?.enabled == true }
            peer.sendChat("after explicit resume")
            await("resumed owner carries chat") { f.root.chat.value.count { it.body == "after explicit resume" } == 1 }
            val peerChoice = f.model.room.value.sharing!!.candidates.indexOf(peer.identity.participant)
            compose.onAllNodes(isToggleable())[peerChoice].performScrollTo().performClick()
            assertFalse(f.model.room.value.sharing!!.enabled)
            peer.sendChat("after approval withdrawn")
            await("phone still sees unapproved nearby chat") { f.model.room.value.chat.any { it.body == "after approval withdrawn" } }
            delay(5_250)
            assertFalse(f.root.chat.value.any { it.body == "after approval withdrawn" })
            f.main { f.model.leave() }
            await("sharing leave closes its selected paths") { !f.model.start.value.busy && f.radios.all { it.closed } && f.relaySockets.size == 1 }
            f.main { f.model.reopenRoom(saved.id) }
            await("withdrawal remains saved on reopen") { f.model.stage.value == Stage.ROOM && f.model.room.value.sharing != null }
            assertEquals(setOf(f.root.identity.participant), f.model.room.value.sharing!!.selected)
            assertFalse(f.model.room.value.sharing!!.enabled)
        } finally { meshMember?.leave(); f.close() }
    }

    @Test fun uncertain_original_queue_and_debt_survive_live_room_reopen_until_explicit_resume() = runBlocking {
        val f = FreshNearbyEntryTest.Fixture(mixed = true, rootInternetOnly = true)
        var member: RoomSession? = null
        try {
            f.start(); f.main { f.model.joinNearbyFromUrl(f.url, f.descriptor, RoomRoute.MIXED) }
            await("room opens") { f.model.stage.value == Stage.ROOM || f.model.start.value.error != null }
            assertNull(f.model.start.value.error)
            val saved = f.app.savedRooms.get(f.room.roomId)!!
            val at = System.currentTimeMillis() / 1000
            val peer = RoomSession(f.room, PrimaryIdentity.create(f.room.roomId, at + 3600, at), f.transport,
                f.scope, authority = f.host.invitation.inviter, timing = SessionTiming(announceJitterMs = 0))
            member = peer; peer.join()
            await("nearby member is available for approval") { f.model.room.value.sharing?.candidates?.contains(peer.identity.participant) == true }
            compose.setContent { KithMootTheme {
                val room by f.model.room.collectAsState()
                room.sharing?.let { RoomSharingSheet(it, f.model::selectSharingParticipant,
                    f.model::startRoomSharing, f.model::stopRoomSharing, {}) }
            } }
            compose.onNodeWithContentDescription("Approve messages from ${peer.identity.participant}").performScrollTo().performClick()
            await("approval commits") { f.model.room.value.sharing?.busy == false }
            assertNull(f.model.room.value.sharing!!.error)
            compose.onNodeWithText("Start sharing").performClick()
            await("sharing starts") { f.model.room.value.sharing?.enabled == true }
            f.relayEnabled = false // The selected relay keeps its socket but drops EVENT and OK.
            peer.sendChat("original retained during outage")
            await("unknown original handoff reaches relay") { f.relayWrites.any { it.kind == KIND_CHAT && it.pubkey == peer.identity.devicePubkey } }
            val original = f.relayWrites.first { it.kind == KIND_CHAT && it.pubkey == peer.identity.devicePubkey }
            f.main { f.model.stopRoomSharing() }
            val binding = RoomForwardingBinding(saved.id, saved.participant, saved.devicePubkey,
                RoomNearbyDiscovery.scope(saved.id), f.relays, setOf(peer.identity.participant))
            val vault = RoomSharingVault(f.app, saved.id, saved.participant, saved.devicePubkey)
            val before = vault.prepare(binding).use { it.status() }
            assertTrue(before.suspended); assertTrue(before.internetBytes > 0)
            val queued = before.entries.single()
            assertEquals(original, queued.event); assertEquals(ForwardingLaneState.UNKNOWN, queued.internet.state)
            assertEquals(1, queued.internet.attempts); assertTrue(f.root.chat.value.isEmpty())
            f.main { f.model.leave() }
            await("old room and pool finish closing") { f.model.stage.value == Stage.START && !f.model.start.value.busy && f.relaySockets.size == 1 }
            f.relayEnabled = true
            f.main { f.model.reopenRoom(saved.id) }
            await("same identity reopens with fresh admission") { f.model.stage.value == Stage.ROOM && f.model.room.value.sharing != null }
            assertEquals(saved.participant, f.model.room.value.selfParticipant)
            assertEquals(saved.devicePubkey, f.model.room.value.selfDevice)
            assertFalse(f.model.room.value.sharing!!.enabled)
            delay(5_250)
            assertTrue(f.root.chat.value.isEmpty())
            assertEquals(1, f.relayWrites.count { it.id == original.id })
            val held = vault.prepare(binding).use { it.status() }
            assertEquals(queued.event, held.entries.single().event)
            assertEquals(queued.expires, held.entries.single().expires)
            assertEquals(before.internetBytes, held.internetBytes)
            compose.onNodeWithText("Resume sharing").performClick()
            await("explicit resume exports original queue") { f.root.chat.value.count { it.body == "original retained during outage" } == 1 }
            f.main { f.model.stopRoomSharing() }
            val after = vault.prepare(binding).use { it.status() }
            assertEquals(listOf(original, original), f.relayWrites.filter { it.id == original.id })
            assertEquals(original, after.entries.single().event)
            assertEquals(queued.expires, after.entries.single().expires)
            assertEquals(2, after.entries.single().internet.attempts)
            assertTrue(after.internetBytes > before.internetBytes)
            assertTrue(after.high >= before.high)
        } finally { member?.leave(); f.close() }
    }
}
