package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.session.*
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

}
