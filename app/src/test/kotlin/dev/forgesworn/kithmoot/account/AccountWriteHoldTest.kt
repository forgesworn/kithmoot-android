package dev.forgesworn.kithmoot.account

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountWriteHoldTest {
    private val wait = 300_000L
    private fun TestScope.hold() = AccountWriteHold(backgroundScope) { wait }

    @Test fun holdsWhileATorOnlyRoomIsOpenAndForTheWaitAfterItCloses() = runTest {
        val hold = hold()
        assertFalse(hold.isHeld)
        hold.torOnlyRoomOpened(); assertTrue(hold.isHeld)
        advanceTimeBy(wait * 3); runCurrent(); assertTrue(hold.isHeld)
        hold.torOnlyRoomClosed(); runCurrent(); assertTrue(hold.isHeld)
        advanceTimeBy(wait - 1); runCurrent(); assertTrue(hold.isHeld)
        advanceTimeBy(1); runCurrent(); assertFalse(hold.isHeld)
    }

    @Test fun aPersonsAccountActionEndsTheWaitButNotAnOpenRoom() = runTest {
        val hold = hold()
        hold.torOnlyRoomOpened(); hold.personActed(); assertTrue(hold.isHeld)
        hold.torOnlyRoomClosed(); hold.personActed(); assertFalse(hold.isHeld)
    }

    @Test fun twoTorOnlyRoomsHoldUntilBothHaveClosed() = runTest {
        val hold = hold()
        hold.torOnlyRoomOpened(); hold.torOnlyRoomOpened()
        hold.torOnlyRoomClosed(); advanceTimeBy(wait * 2); runCurrent(); assertTrue(hold.isHeld)
        hold.personActed(); assertTrue(hold.isHeld)
        hold.torOnlyRoomClosed(); advanceTimeBy(wait); runCurrent(); assertFalse(hold.isHeld)
    }

    @Test fun reopeningDuringTheWaitStartsItAgainFromTheNextClose() = runTest {
        val hold = hold()
        hold.torOnlyRoomOpened(); hold.torOnlyRoomClosed()
        advanceTimeBy(wait - 10); hold.torOnlyRoomOpened(); hold.torOnlyRoomClosed()
        advanceTimeBy(20); runCurrent(); assertTrue(hold.isHeld)
        advanceTimeBy(wait); runCurrent(); assertFalse(hold.isHeld)
    }

    @Test fun anExtraCloseIsIgnored() = runTest {
        val hold = hold()
        hold.torOnlyRoomClosed(); assertFalse(hold.isHeld)
        hold.torOnlyRoomOpened(); hold.torOnlyRoomClosed(); hold.torOnlyRoomClosed(); hold.torOnlyRoomOpened()
        advanceTimeBy(wait * 2); runCurrent(); assertTrue(hold.isHeld)
    }

    @Test fun waitersResumeOnRelease() = runTest {
        val hold = hold(); hold.torOnlyRoomOpened()
        var resumed = false
        backgroundScope.launch { hold.awaitReleased(); resumed = true }
        runCurrent(); assertFalse(resumed)
        hold.torOnlyRoomClosed(); advanceTimeBy(wait); runCurrent(); assertTrue(resumed)
    }

    @Test fun theDefaultWaitIsBetweenThreeAndTenMinutes() = runTest {
        assertEquals(3 * 60_000L, AccountWriteHold.MIN_WAIT_MS); assertEquals(10 * 60_000L, AccountWriteHold.MAX_WAIT_MS)
        repeat(20) {
            val hold = AccountWriteHold(backgroundScope); hold.torOnlyRoomOpened(); hold.torOnlyRoomClosed()
            advanceTimeBy(AccountWriteHold.MIN_WAIT_MS - 1); runCurrent(); assertTrue(hold.isHeld)
            advanceTimeBy(AccountWriteHold.MAX_WAIT_MS - AccountWriteHold.MIN_WAIT_MS + 1); runCurrent(); assertFalse(hold.isHeld)
        }
    }
}
