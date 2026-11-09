package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.protocol.NodeRooms
import dev.forgesworn.kithmoot.protocol.deriveRoom
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeJoinControllerTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private fun offer(seed: Int = 6): NodeJoinView {
        val room = deriveRoom(ByteArray(32) { seed.toByte() })
        val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId,
            "https://node.example", service.pubkey, now).toJson(), now))
        return NodeJoinView(node, "https://app.example/j/#synthetic-node-$seed")
    }

    @Test fun `admission beside an existing room saves access without entering or replacing that room`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler))
        val offered = offer(); controller.offer(offered)
        var enters = 0; var asks = 0; var saved: String? = null
        assertTrue(controller.request(false, { true }, { enters++; launch { it() } },
            ask = { held, current, waiting ->
                assertEquals(offered.node.room, held.node.room); assertTrue(current()); asks++
                waiting(now + 600); "admitted-room"
            }, admitted = { room, open -> assertFalse(open); saved = room }))
        runCurrent()
        assertEquals(0, enters, "The current room's entry/teardown lifecycle must remain untouched")
        assertEquals(1, asks); assertEquals("admitted-room", saved); assertNull(controller.view.value)
    }

    @Test fun `start screen admission enters through the room gate only after approval`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        val answer = CompletableDeferred<String>(); var enters = 0; var opened: String? = null
        assertTrue(controller.request(true, { true }, { enters++; launch { it() } },
            ask = { _, _, waiting -> waiting(now + 600); answer.await() },
            admitted = { value, open -> assertTrue(open); opened = value }))
        runCurrent(); assertEquals(1, enters); assertNull(opened)
        assertTrue(controller.view.value!!.busy); assertEquals(now + 600, controller.view.value!!.waitingUntil)
        answer.complete("approved"); runCurrent()
        assertEquals("approved", opened); assertNull(controller.view.value)
    }

    @Test fun `repeated ask taps share one request and a refusal remains visible above the existing room`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        val answer = CompletableDeferred<Unit>(); var asks = 0; var admits = 0
        fun request() = controller.request(false, { true }, { fail("Must not enter the open room") },
            ask = { _, _, _ -> asks++; answer.await(); error("Synthetic unavailable keeper") },
            admitted = { _: Unit, _ -> admits++ })
        assertTrue(request()); assertFalse(request()); runCurrent()
        assertEquals(1, asks); answer.complete(Unit); runCurrent()
        assertEquals(0, admits); assertFalse(controller.view.value!!.busy)
        assertContains(controller.view.value!!.message!!, "Ask again")
    }

    @Test fun `cancelled or replaced requests cannot accept a late signer or keeper result`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        val answer = CompletableDeferred<String>(); var admitted = 0
        controller.request(false, { true }, { fail("Must not enter") },
            ask = { _, _, _ -> withContext(NonCancellable) { answer.await() } },
            admitted = { _, _ -> admitted++ })
        runCurrent(); val next = offer(7); controller.offer(next)
        answer.complete("late"); runCurrent()
        assertEquals(0, admitted); assertEquals(next, controller.view.value)
        controller.cancel(); assertNull(controller.view.value)
    }

    @Test fun `account or network change retires a pending request before access is committed`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        val answer = CompletableDeferred<String>(); var allowed = true; var admitted = false
        controller.request(false, { allowed }, { fail("Must not enter") },
            ask = { _, current, _ -> answer.await().also { assertFalse(current()) } },
            admitted = { _, _ -> admitted = true })
        runCurrent(); allowed = false; answer.complete("stale"); runCurrent()
        assertFalse(admitted); assertNull(controller.view.value)
    }

    @Test fun `an unavailable request can retry once while preserving the reviewed invitation`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); val offered = offer(); controller.offer(offered)
        controller.request(false, { true }, { fail("Must not enter") },
            ask = { _, _, _ -> error("Unavailable") }, admitted = { _: Unit, _ -> fail("Not admitted") })
        runCurrent(); assertEquals(offered.node.room, controller.view.value!!.node.room)
        var admitted = false
        assertTrue(controller.request(false, { true }, { fail("Must not enter") },
            ask = { held, _, _ -> assertEquals(offered.node.room, held.node.room); Unit },
            admitted = { _, _ -> admitted = true }))
        runCurrent(); assertTrue(admitted); assertNull(controller.view.value)
    }

    @Test fun `navigating from a start screen request to another room cancels delayed automatic entry`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        val answer = CompletableDeferred<String>(); var displayedRoom = "rooms-list"
        controller.request(true, { true }, { launch { it() } },
            ask = { _, _, _ -> withContext(NonCancellable) { answer.await() } },
            admitted = { value, _ -> displayedRoom = value })
        runCurrent()
        controller.cancel() // The production enter() and incoming-link paths retire node admission first.
        displayedRoom = "another-active-room"
        answer.complete("old-node-room"); runCurrent()
        assertEquals("another-active-room", displayedRoom); assertNull(controller.view.value)
    }

    @Test fun `network job cancellation releases waiting state without a hidden retry or room change`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        var owned: Job? = null; var admitted = false
        controller.request(false, { true }, { fail("Must not enter") }, guard = { owned = it },
            ask = { _, _, waiting -> waiting(now + 600); awaitCancellation() },
            admitted = { _: Unit, _ -> admitted = true })
        runCurrent(); assertTrue(controller.view.value!!.busy)
        requireNotNull(owned).cancel(); runCurrent()
        assertFalse(admitted); assertFalse(controller.view.value!!.busy)
        assertContains(controller.view.value!!.message!!, "cancelled")
    }

    @Test fun `start screen failures propagate to entry cleanup while admission errors remain visible`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        val failure = IllegalStateException("Synthetic partial entry failure")
        val result = CompletableDeferred<Result<Unit>>()
        controller.request(true, { true }, { launch { result.complete(runCatching { it() }) } },
            ask = { _, _, _ -> Unit }, admitted = { _, _ -> throw failure })
        runCurrent()
        assertSame(failure, result.await().exceptionOrNull(), "The normal entry gate must still clean up partial sessions")
        assertFalse(controller.view.value!!.busy); assertContains(controller.view.value!!.message!!, "Ask again")
    }

    @Test fun `a retired request finishing cannot detach the next request from cancellation`(): Unit = runTest {
        val controller = NodeJoinController(backgroundScope, StandardTestDispatcher(testScheduler)); controller.offer(offer())
        val oldAnswer = CompletableDeferred<Unit>(); var newClosed = false; var admitted = 0
        controller.request(false, { true }, { fail("Must not enter") },
            ask = { _, _, _ -> withContext(NonCancellable) { oldAnswer.await() } }, admitted = { _, _ -> admitted++ })
        runCurrent(); val next = offer(7); controller.offer(next)
        controller.request(false, { true }, { fail("Must not enter") },
            ask = { _, _, _ -> try { awaitCancellation() } finally { newClosed = true } },
            admitted = { _: Unit, _ -> admitted++ })
        runCurrent(); oldAnswer.complete(Unit); runCurrent()
        assertEquals(next.node.room, controller.view.value!!.node.room); assertTrue(controller.view.value!!.busy)
        controller.cancel(); runCurrent()
        assertTrue(newClosed); assertEquals(0, admitted); assertNull(controller.view.value)
    }
}
