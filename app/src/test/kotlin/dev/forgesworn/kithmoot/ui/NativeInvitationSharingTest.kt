package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.epoch.*
import dev.forgesworn.kithmoot.relay.RoomRoute
import org.junit.Test
import kotlin.test.*

class NativeInvitationSharingTest {
    private val active = NativeHostingState(
        NativeHostingBinding("1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64),
            RoomRoute.NEARBY, emptyList(), "5".repeat(64)),
        NativeHostingStatus.READY, NativeHostingLifecycle.ACTIVE, 0, 1, 1)

    @Test fun everyUnavailableNativeObservationWithdrawsSharingEvenWithAnOldUrl() {
        val room = RoomState(joinUrl = "https://fixture.invalid/join", nativeHosting = active)
        assertTrue(room.canShareInvitation)
        for (status in NativeHostingStatus.entries.filter { it != NativeHostingStatus.READY })
            assertFalse(room.copy(nativeHosting = active.copy(status = status)).canShareInvitation)
        for (lifecycle in listOf(null, NativeHostingLifecycle.RETIRED, NativeHostingLifecycle.CLOSED))
            assertFalse(room.copy(nativeHosting = active.copy(lifecycle = lifecycle)).canShareInvitation)
        assertFalse(room.copy(nativeHosting = active.copy(revision = null)).canShareInvitation)
        assertFalse(room.copy(nativeHosting = active.copy(ownerGeneration = null)).canShareInvitation)
        assertFalse(room.copy(nativeHosting = active.copy(pendingOriginals = listOf("6".repeat(64)))).canShareInvitation)
        assertFalse(room.copy(nativeHostingBusy = true).canShareInvitation)
        assertFalse(room.copy(joinUrl = "").canShareInvitation)
        assertTrue(active.copy(lifecycle = NativeHostingLifecycle.RETIRED,
            retirementOriginals = listOf("6".repeat(64))).canResendRetirement)
    }

    @Test fun aLateUnknownParticipantListCannotReviveCardsAfterAnUnavailableObservation() {
        val asks = listOf(LetInAsk("6".repeat(64), "Unknown member"))
        val room = RoomState(joinUrl = "https://fixture.invalid/join", nativeHosting = active)
        assertEquals(asks, room.withNativeUnknownApprovals(asks).letInAsks)
        val retired = active.copy(lifecycle = NativeHostingLifecycle.RETIRED)
        assertTrue(room.copy(nativeHosting = retired, letInAsks = asks).withNativeUnknownApprovals(asks).letInAsks.isEmpty())
        for (status in NativeHostingStatus.entries.filter { it != NativeHostingStatus.READY })
            assertTrue(room.copy(nativeHosting = active.copy(status = status), letInAsks = asks)
                .withNativeUnknownApprovals(asks).letInAsks.isEmpty())
        val pending = room.copy(nativeHosting = active.copy(pendingOriginals = listOf("7".repeat(64))))
        assertTrue(pending.withNativeUnknownApprovals(asks).letInAsks.isEmpty())
        assertEquals(asks, room.withNativeUnknownApprovals(asks).letInAsks)
    }

    @Test fun ordinarySharingStillHonoursPrivateMovedEndedAndBusyRoomBoundaries() {
        val room = RoomState(joinUrl = "https://fixture.invalid/join")
        assertTrue(room.canShareInvitation)
        assertFalse(room.copy(privateConversation = true).canShareInvitation)
        assertFalse(room.copy(conferenceEnded = true).canShareInvitation)
        assertFalse(room.copy(privateConversationBusy = true).canShareInvitation)
        assertFalse(room.copy(movedOn = 1).canShareInvitation)
    }

    @Test fun explicitRecoveryRequiresACompleteNonterminalPendingObservation() {
        val pending = active.copy(status = NativeHostingStatus.RECOVERING, pendingOriginals = listOf("6".repeat(64)))
        assertTrue(pending.canRetry)
        assertTrue(pending.copy(lifecycle = NativeHostingLifecycle.RETIRED).canRetry)
        assertFalse(pending.copy(lifecycle = NativeHostingLifecycle.CLOSED).canRetry)
        assertFalse(pending.copy(lifecycle = null).canRetry)
        assertFalse(pending.copy(epoch = null).canRetry)
        assertFalse(pending.copy(revision = null).canRetry)
        assertFalse(pending.copy(ownerGeneration = null).canRetry)
        assertFalse(pending.copy(pendingOriginals = emptyList()).canRetry)
        for (status in NativeHostingStatus.entries.filter { it != NativeHostingStatus.RECOVERING })
            assertFalse(pending.copy(status = status).canRetry)
    }
}
