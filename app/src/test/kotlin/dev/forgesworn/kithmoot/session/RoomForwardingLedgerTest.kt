package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RoomForwardingLedgerTest {
    @Test fun dispatchDoesNotWaitForAnObservationHoldingTheLedger() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pause = AtomicBoolean(false)
        val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "ledger-lock-regression").apply { isDaemon = true } }
        RoomForwardingLedger(Store(), binding(), { 1_000_000 }, true).use { box ->
            try {
                box.current { _, _ ->
                    if (pause.compareAndSet(true, false)) {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    }
                    ForwardingVerdict.CURRENT
                }
                val original = event()
                box.observe(original, ForwardingLane.NEARBY)
                val reservation = assertNotNull(box.reserve(original.id, ForwardingLane.INTERNET))
                val before = box.status()
                pause.set(true)
                val observe = workers.submit<ForwardingObservation> { box.observe(original, ForwardingLane.NEARBY) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val guard = workers.submit<Boolean> { box.canHandoff(reservation) }
                assertFalse(guard.get(2, TimeUnit.SECONDS), "Dispatch cannot wait for an observation's authority check")
                release.countDown()
                assertEquals(ForwardingObservation.DUPLICATE, observe.get(5, TimeUnit.SECONDS))
                assertTrue(box.canHandoff(reservation))
                assertEquals(before, box.status(), "Contention creates no receipt, expiry change or retry refund")
            } finally {
                release.countDown(); workers.shutdown()
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        var failAfterCommit = false
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) {
            if (fail) error("disk full")
            bytes = value.clone()
            if (failAfterCommit) error("commit could not be verified")
        }
        override fun reset() { error("Sharing must never silently reset its store") }
    }
    private fun binding(relays: List<String> = listOf("wss://fixture.invalid/"), senders: Set<String> = setOf("44".repeat(32))) =
        RoomForwardingBinding("11".repeat(32), "22".repeat(32), "33".repeat(32), "55".repeat(32), relays, senders)
    private fun event(i: Int = 0, content: String = "ciphertext", at: Long = 1000,
        tags: List<List<String>> = listOf(listOf("d", "66".repeat(32))), kind: Int = KIND_CHAT) =
        Events.sign(ByteArray(32) { 4 }, kind, at, tags + listOf(listOf("n", "$i")), content, ByteArray(32))
    private fun RoomForwardingLedger.current(check: (NostrEvent, Long) -> ForwardingVerdict = { _, _ -> ForwardingVerdict.CURRENT }) =
        bind(binding.room, binding.participant, binding.device, check)

    @Test fun originalCiphertextAndSourceSurviveReopenWithoutRestoringAuthority() {
        val store = Store(); val b = binding(); val e = event()
        RoomForwardingLedger(store, b, { 1_000_000 }, true).use { box ->
            box.current(); assertEquals(ForwardingObservation.RETAINED, box.observe(e, ForwardingLane.NEARBY))
            assertNull(box.reserve(e.id, ForwardingLane.NEARBY))
        }
        RoomForwardingLedger(store, b, { 1_000_000 }).use { box ->
            assertTrue(box.status().suspended); assertNull(box.reserve(e.id, ForwardingLane.INTERNET))
            assertEquals(e, box.status().entries.single().event)
            box.current(); val attempt = assertNotNull(box.reserve(e.id, ForwardingLane.INTERNET))
            assertEquals(e, attempt.event); assertTrue(box.canHandoff(attempt))
            assertEquals(ForwardingLaneState.UNKNOWN, box.status().entries.single().internet.state)
            box.relayAccepted(attempt)
            assertNull(box.reserve(e.id, ForwardingLane.INTERNET))
            assertEquals(ForwardingLaneState.ACCEPTED, box.status().entries.single().internet.state)
        }
    }

    @Test fun sourceObservationAndEventCommitTogetherOrNoExportIsPossible() {
        val store = Store(); val b = binding(); val e = event()
        RoomForwardingLedger(store, b, { 1_000_000 }, true).use { box ->
            box.current(); store.fail = true
            assertFails { box.observe(e, ForwardingLane.NEARBY) }
            assertTrue(box.persistenceFailed()); assertFails { box.reserve(e.id, ForwardingLane.INTERNET) }
        }
        store.fail = false
        RoomForwardingLedger(store, b, { 1_000_000 }).use { assertTrue(it.status().entries.isEmpty()) }
    }

    @Test fun ambiguousCommitReservesDebtAndUnknownAcrossRestart() {
        val store = Store(); val b = binding(); val e = event()
        RoomForwardingLedger(store, b, { 1_000_000 }, true).use { box ->
            box.current(); box.observe(e, ForwardingLane.NEARBY); store.failAfterCommit = true
            assertFails { box.reserve(e.id, ForwardingLane.INTERNET) }
            assertTrue(box.persistenceFailed())
        }
        store.failAfterCommit = false
        RoomForwardingLedger(store, b, { 1_000_000 }).use { box ->
            val restored = box.status()
            assertEquals(1, restored.entries.single().internet.attempts)
            assertEquals(ForwardingLaneState.UNKNOWN, restored.entries.single().internet.state)
            assertTrue(restored.internetBytes > 0); box.current()
            assertNull(box.reserve(e.id, ForwardingLane.INTERNET), "A crash cannot reset the retry delay")
        }
    }

    @Test fun anObservedDestinationStopsAReservedHandoffAndLateCompletionCannotEraseIt() {
        val store = Store(); val e = event()
        RoomForwardingLedger(store, binding(), { 1_000_000 }, true).use { box ->
            box.current(); box.observe(e, ForwardingLane.INTERNET)
            val reservation = assertNotNull(box.reserve(e.id, ForwardingLane.NEARBY))
            assertTrue(box.canHandoff(reservation))
            assertEquals(ForwardingObservation.RETAINED, box.observe(e, ForwardingLane.NEARBY))
            assertFalse(box.canHandoff(reservation)); box.offered(reservation)
            assertEquals(ForwardingLaneState.SEEN, box.status().entries.single().nearby.state)
            assertEquals(ForwardingObservation.DUPLICATE, box.observe(e, ForwardingLane.NEARBY))
        }
    }

    @Test fun nearbyOfferStaysUnconfirmedAndRetrievableAfterRestart() {
        val store = Store(); var at = 1_000_000L; val e = event(); val b = binding()
        RoomForwardingLedger(store, b, { at }, true).use { box ->
            box.current(); box.observe(e, ForwardingLane.INTERNET)
            val reservation = assertNotNull(box.reserve(e.id, ForwardingLane.NEARBY))
            assertFailsWith<IllegalArgumentException> { box.relayAccepted(reservation) }
            box.offered(reservation); assertEquals(ForwardingLaneState.OFFERED, box.status().entries.single().nearby.state)
        }
        at += 5000
        RoomForwardingLedger(store, b, { at }).use { box ->
            box.current(); val retry = assertNotNull(box.reserve(e.id, ForwardingLane.NEARBY))
            assertEquals(2, retry.attempt); assertEquals(e, retry.event)
        }
    }

    @Test fun suspensionRevocationAndCredentialExpiryStopTheDispatchBarrier() {
        val store = Store(); var at = 1_000_000L; var verdict = ForwardingVerdict.CURRENT
        RoomForwardingLedger(store, binding(), { at }, true).use { box ->
            val e = event(); box.current { _, _ -> verdict }; box.observe(e, ForwardingLane.NEARBY)
            val reservation = assertNotNull(box.reserve(e.id, ForwardingLane.INTERNET))
            verdict = ForwardingVerdict.WAITING; assertFalse(box.canHandoff(reservation))
            box.suspendExports(); assertFalse(box.canHandoff(reservation))
            box.current { _, seconds -> if (seconds >= 1001) ForwardingVerdict.MOVED else ForwardingVerdict.CURRENT }
            at += 1000; assertFalse(box.canHandoff(reservation)); at += 5000
            assertNull(box.reserve(e.id, ForwardingLane.INTERNET)); assertTrue(box.status().entries.single().moved)
            box.current(); assertNull(box.reserve(e.id, ForwardingLane.INTERNET), "Restoring a callback cannot revive stranded ciphertext")
        }
    }

    @Test fun retainedExpiryAndHighClockCannotBeExtendedByReplaysReopenOrRollback() {
        val store = Store(); val b = binding(); var at = 1_000_000L
        val e = event(tags = listOf(listOf("d", "66".repeat(32)), listOf("expiration", "1100")))
        RoomForwardingLedger(store, b, { at }, true).use { box ->
            box.current(); box.observe(e, ForwardingLane.NEARBY); assertEquals(1_100_000L, box.status().entries.single().expires)
            at = 1_099_000; box.observe(e, ForwardingLane.INTERNET)
            assertEquals(1_100_000L, box.status().entries.single().expires)
        }
        at = 1_000_000
        RoomForwardingLedger(store, b, { at }).use { box ->
            box.current(); assertEquals(1_099_000L, box.status().high)
            at = 1_100_000; assertTrue(box.status().entries.isEmpty()); at = 1_000_000
            assertEquals(ForwardingObservation.REFUSED, box.observe(e, ForwardingLane.NEARBY))
            assertEquals(1L, box.status().expired)
        }
    }

    @Test fun retryDebtPersistsAcrossReopenRollbackAndTheFullReservationWindow() {
        val store = Store(); val b = binding(); var at = 1_000_000L; val e = event(content = "x".repeat(6000))
        RoomForwardingLedger(store, b, { at }, true).use { box ->
            box.current(); box.observe(e, ForwardingLane.NEARBY)
            repeat(10) { assertNotNull(box.reserve(e.id, ForwardingLane.INTERNET)); at += 300_000 }
            assertNull(box.reserve(e.id, ForwardingLane.INTERNET)); assertTrue(box.status().internetBytes > 60_000)
        }
        at = 1_000_000
        RoomForwardingLedger(store, b, { at }).use { box ->
            box.current(); assertEquals(4_000_000L, box.status().high)
            assertNull(box.reserve(e.id, ForwardingLane.INTERNET))
            at = 1_000_000 + RoomForwardingLedger.WINDOW_MS
            assertNull(box.reserve(e.id, ForwardingLane.INTERNET), "The exact window boundary still owes its first reservation")
            at++; assertNotNull(box.reserve(e.id, ForwardingLane.INTERNET))
        }
    }

    @Test fun attemptCapCannotBeResetByReopenOrFreshCredit() {
        val store = Store(); val b = binding(); var at = 1_000_000L; val e = event()
        RoomForwardingLedger(store, b, { at }, true).use { box ->
            box.current(); box.observe(e, ForwardingLane.NEARBY)
            repeat(64) { assertNotNull(box.reserve(e.id, ForwardingLane.INTERNET)); at += 300_000 }
            assertNull(box.reserve(e.id, ForwardingLane.INTERNET))
        }
        RoomForwardingLedger(store, b, { at }).use { box ->
            box.current(); assertNull(box.reserve(e.id, ForwardingLane.INTERNET))
            assertEquals(64, box.status().entries.single().internet.attempts)
        }
    }

    @Test fun capacityRefusesNewCiphertextWithoutEvictingUnacknowledgedMessages() {
        val store = Store()
        RoomForwardingLedger(store, binding(), { 1_000_000 }, true).use { box ->
            box.current(); val original = event()
            repeat(100) { assertEquals(ForwardingObservation.RETAINED, box.observe(event(it), ForwardingLane.NEARBY)) }
            assertEquals(ForwardingObservation.REFUSED, box.observe(event(101), ForwardingLane.NEARBY))
            assertEquals(100, box.status().entries.size); assertEquals(original, box.status().entries.first().event)
            assertFails { box.observe(event(102, "x".repeat(16384)), ForwardingLane.NEARBY) }
        }
    }

    @Test fun missingCorruptReboundAndDuplicateOwnersFailWithoutReplacingState() {
        val store = Store(); val b = binding()
        assertFails { RoomForwardingLedger(store, b, { 1_000_000 }) }
        RoomForwardingLedger(store, b, { 1_000_000 }, true).use { box ->
            assertFails { RoomForwardingLedger(store, b, { 1_000_000 }, true) }
            assertFails { box.bind(b.room, "77".repeat(32), b.device) { _, _ -> ForwardingVerdict.CURRENT } }
        }
        val original = store.bytes!!.clone()
        assertFails { RoomForwardingLedger(store, binding(relays = listOf("wss://another.invalid/")), { 1_000_000 }, true) }
        assertContentEquals(original, store.bytes)
        assertFails { RoomForwardingLedger(store, binding(senders = setOf("88".repeat(32))), { 1_000_000 }, true) }
        store.bytes = "broken ciphertext state".toByteArray()
        assertFails { RoomForwardingLedger(store, b, { 1_000_000 }, true) }
        assertEquals("broken ciphertext state", store.bytes!!.toString(Charsets.UTF_8))
    }

    @Test fun byteCapacityRefusesWithoutEvictionAndOldCompletionsCannotConfirmNewAttempts() {
        val store = Store(); var at = 1_000_000L
        RoomForwardingLedger(store, binding(), { at }, true).use { box ->
            box.current(); val accepted = mutableListOf<NostrEvent>()
            for (i in 0..99) {
                val e = event(i, "x".repeat(12000))
                if (box.observe(e, ForwardingLane.NEARBY) == ForwardingObservation.RETAINED) accepted += e
            }
            assertTrue(accepted.size in 80..99, "The byte bound must apply before the record bound")
            assertEquals(accepted, box.status().entries.map { it.event })
            val first = assertNotNull(box.reserve(accepted.first().id, ForwardingLane.INTERNET))
            at += 5000
            val next = assertNotNull(box.reserve(accepted.first().id, ForwardingLane.INTERNET))
            assertFalse(box.canHandoff(first)); box.relayAccepted(first)
            assertEquals(ForwardingLaneState.UNKNOWN, box.status().entries.first().internet.state)
            assertFalse(box.canHandoff(next.copy(event = next.event.copy(content = "changed"))))
            box.relayAccepted(next); assertEquals(ForwardingLaneState.ACCEPTED, box.status().entries.first().internet.state)
        }
    }

    @Test fun persistedRecordsWithoutAnObservedSourceFailClosed() {
        val store = Store(); val b = binding()
        RoomForwardingLedger(store, b, { 1_000_000 }, true).use { box -> box.current(); box.observe(event(), ForwardingLane.NEARBY) }
        val root = Json.parseToJsonElement(store.bytes!!.toString(Charsets.UTF_8)).jsonObject
        val row = root.getValue("entries").jsonArray.single().jsonObject
        val source = row.getValue("NEARBY").jsonObject
        val altered = JsonObject(row + ("NEARBY" to JsonObject(source + ("state" to JsonPrimitive("WAITING")))))
        store.bytes = JsonObject(root + ("entries" to JsonArray(listOf(altered)))).toString().toByteArray()
        assertFails { RoomForwardingLedger(store, b, { 1_000_000 }) }
    }

    @Test fun malformedEventsAndUnauthorisedSendersNeverAcquireAQueue() {
        val store = Store()
        RoomForwardingLedger(store, binding(), { 1_000_000 }, true).use { box ->
            box.current { _, _ -> ForwardingVerdict.MOVED }
            assertEquals(ForwardingObservation.REFUSED, box.observe(event(), ForwardingLane.NEARBY))
            box.current()
            for (invalid in listOf(event().copy(content = "tampered"), event(kind = 1462), event(tags = emptyList()),
                event(tags = listOf(listOf("d", "66".repeat(32)), listOf("d", "66".repeat(32)))),
                event(tags = listOf(listOf("d", "66".repeat(32)), listOf("expiration", "9223372036854775807"))))) {
                assertFails { box.observe(invalid, ForwardingLane.NEARBY) }
            }
            assertEquals(ForwardingObservation.REFUSED, box.observe(event(at = 1301), ForwardingLane.NEARBY))
            assertTrue(box.status().entries.isEmpty())
        }
    }
}
