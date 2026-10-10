package dev.forgesworn.kithmoot.epoch

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RoomRekeyLedgerTest {
    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        var ambiguous = false
        var beforeWrite: (() -> Unit)? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) {
            beforeWrite?.invoke()
            if (fail) error("disk full")
            bytes = value.clone()
            if (ambiguous) error("commit could not be verified")
        }
        override fun reset() = error("Policy changes cannot reset a keeper journal")
    }
    private val key = ByteArray(32) { 41 }
    private fun binding(device: String = "33".repeat(32), relays: List<String> = listOf("wss://fixture.invalid/")) =
        RoomRekeyBinding("11".repeat(32), Schnorr.publicKeyHex(key), device, "55".repeat(32), relays)
    private fun event(epoch: Int = 1, at: Long = 1000, content: String = "sealed notice",
        tags: List<List<String>> = listOf(listOf("d", binding().room), listOf("epoch", "$epoch")),
        signer: ByteArray = key, aux: ByteArray = ByteArray(32)) =
        Events.sign(signer, KIND_ROOM_REKEY, at, tags, content, aux)
    private fun RoomRekeyLedger.select() = bind { true }

    @Test fun emptyCourierMaintenancePersistsOnlyTheMonotoneClockWithoutCreatingCredit() {
        val store = Store(); val b = binding(); var at = 1_000_000L
        RoomRekeyLedger(store, b, { at }, true).use { box ->
            box.select()
            val before = Json.parseToJsonElement(store.bytes!!.decodeToString()).jsonObject
            var writes = 0; store.beforeWrite = { writes++ }
            at++
            val current = box.status()
            val after = Json.parseToJsonElement(store.bytes!!.decodeToString()).jsonObject
            assertEquals(1, writes)
            assertNotEquals(before, after)
            assertEquals(JsonObject(before - "high"), JsonObject(after - "high"))
            assertEquals(at, current.high)
            assertTrue(current.entries.isEmpty())
            assertEquals(0, current.nearbyBytes); assertEquals(0, current.internetBytes)
        }
        at--
        RoomRekeyLedger(store, b, { at }).use { reopened ->
            assertEquals(at + 1, reopened.status().high)
            assertTrue(reopened.status().entries.isEmpty())
            assertEquals(0, reopened.status().nearbyBytes); assertEquals(0, reopened.status().internetBytes)
        }
    }

    @Test fun missingCorruptPolicyMismatchAndCompetingOwnersCannotCreateFreshCredit() {
        val store = Store(); val b = binding()
        assertFails { RoomRekeyLedger(store, b, { 1_000_000 }) }
        RoomRekeyLedger(store, b, { 1_000_000 }, true).use { box ->
            assertTrue(box.status().suspended)
            assertFails { RoomRekeyLedger(store, b, { 1_000_000 }, true) }
            assertFails { RoomRekeyLedger.withInactiveOwner(b.owner) { error("must not enter") } }
            box.select(); box.admit(event())
        }
        val original = store.bytes!!.clone()
        for (changed in listOf(binding(device = "44".repeat(32)), binding(relays = listOf("wss://other.invalid/")))) {
            assertEquals(b.owner, changed.owner)
            assertFails { RoomRekeyLedger(store, changed, { 1_000_000 }, true) }
            assertContentEquals(original, store.bytes)
        }
        store.bytes = "corrupt".toByteArray()
        assertFails { RoomRekeyLedger(store, b, { 1_000_000 }, true) }
        assertEquals("corrupt", store.bytes!!.toString(Charsets.UTF_8))
        assertEquals("released", RoomRekeyLedger.withInactiveOwner(b.owner) { "released" })
    }

    @Test fun originalCiphertextExpiryAndDebtSurviveSuspendedReopen() {
        val store = Store(); var at = 1_000_000L; val e = event(); val b = binding()
        lateinit var before: RoomRekeyLedger.Status
        RoomRekeyLedger(store, b, { at }, true).use { box ->
            assertFails { box.admit(e) }; box.select(); box.admit(e)
            val reserved = assertNotNull(box.reserve(e.id, RekeyLane.INTERNET))
            assertTrue(box.canHandoff(reserved)); before = box.status()
        }
        at -= 10_000
        RoomRekeyLedger(store, b, { at }).use { box ->
            assertEquals(before.copy(suspended = true), box.status())
            assertNull(box.reserve(e.id, RekeyLane.INTERNET))
            box.select(); box.admit(e)
            assertEquals(before, box.status())
            at = 1_005_000
            val retry = assertNotNull(box.reserve(e.id, RekeyLane.INTERNET))
            assertEquals(e, retry.event); assertEquals(2, retry.attempt)
            assertEquals(before.internetBytes * 2, box.status().internetBytes)
        }
    }

    @Test fun anAmbiguousReservationPoisonsTheOwnerButRetainsTheOriginalChargeOnReopen() {
        val store = Store(); val b = binding(); val e = event()
        RoomRekeyLedger(store, b, { 1_000_000 }, true).use { box ->
            box.select(); box.admit(e); store.ambiguous = true
            assertFails { box.reserve(e.id, RekeyLane.NEARBY) }
            assertTrue(box.persistenceFailed()); assertFails { box.admit(e) }
            assertFalse(box.canHandoff(RoomRekeyLedger.Reservation(e, RekeyLane.NEARBY, 1)))
        }
        store.ambiguous = false
        RoomRekeyLedger(store, b, { 1_000_000 }).use { box ->
            val row = box.status().entries.single()
            assertEquals(e, row.event); assertEquals(RekeyLaneState.UNKNOWN, row.nearby.state)
            assertEquals(1, row.nearby.attempts); assertTrue(box.status().nearbyBytes > 0)
            box.select(); assertNull(box.reserve(e.id, RekeyLane.NEARBY))
        }
    }

    @Test fun failureBeforeCommitCannotReturnAReservationOrResetExistingDebt() {
        val store = Store(); val b = binding(); val e = event()
        RoomRekeyLedger(store, b, { 1_000_000 }, true).use { box ->
            box.select(); box.admit(e); box.reserve(e.id, RekeyLane.INTERNET)
            store.fail = true; assertFails { box.reserve(e.id, RekeyLane.NEARBY) }
            assertTrue(box.persistenceFailed())
        }
        store.fail = false
        RoomRekeyLedger(store, b, { 1_000_000 }).use { box ->
            val status = box.status()
            assertEquals(RekeyLaneState.WAITING, status.entries.single().nearby.state)
            assertEquals(RekeyLaneState.UNKNOWN, status.entries.single().internet.state)
            assertEquals(0, status.nearbyBytes); assertTrue(status.internetBytes > 0)
        }
    }

    @Test fun nearbyOffersAndLateCompletionsCannotInventAcceptanceOrEraseNewReservations() {
        val store = Store(); var at = 1_000_000L; val e = event()
        RoomRekeyLedger(store, binding(), { at }, true).use { box ->
            box.select(); box.admit(e)
            val first = assertNotNull(box.reserve(e.id, RekeyLane.NEARBY))
            assertFailsWith<IllegalArgumentException> { box.relayAccepted(first) }
            box.offered(first); assertEquals(RekeyLaneState.OFFERED, box.status().entries.single().nearby.state)
            at += 5000
            val second = assertNotNull(box.reserve(e.id, RekeyLane.NEARBY))
            box.offered(first)
            assertEquals(RekeyLaneState.UNKNOWN, box.status().entries.single().nearby.state)
            assertTrue(box.canHandoff(second)); assertFalse(box.canHandoff(first))
            val internet = assertNotNull(box.reserve(e.id, RekeyLane.INTERNET))
            box.relayAccepted(internet); at += 300_000
            assertNull(box.reserve(e.id, RekeyLane.INTERNET))
        }
    }

    @Test fun withdrawalAndExpiryAfterReservationDenyDispatchWithoutRefundingBytes() {
        val store = Store(); var selected = true; var at = 1_000_000L
        val e = event(tags = listOf(listOf("d", binding().room), listOf("epoch", "1"), listOf("expiration", "1002")))
        RoomRekeyLedger(store, binding(), { at }, true).use { box ->
            box.bind { selected }; box.admit(e)
            val reservation = assertNotNull(box.reserve(e.id, RekeyLane.INTERNET))
            val debt = box.status().internetBytes
            selected = false; assertFalse(box.canHandoff(reservation))
            selected = true; assertTrue(box.canHandoff(reservation))
            at = 1_002_000; assertFalse(box.canHandoff(reservation))
            assertTrue(box.status().entries.isEmpty()); assertEquals(debt, box.status().internetBytes)
            at = 1_000_000; assertFails { box.admit(e) }; assertEquals(1L, box.status().expired)
        }
    }

    @Test fun quotaSurvivesReopenAndIncludesTheExactRollingWindowBoundary() {
        val store = Store(); val b = binding(); var at = 1_000_000L
        val e = event(content = "x".repeat(6000))
        RoomRekeyLedger(store, b, { at }, true).use { box ->
            box.select(); box.admit(e)
            repeat(2) { assertNotNull(box.reserve(e.id, RekeyLane.INTERNET)); at += 300_000 }
            assertNull(box.reserve(e.id, RekeyLane.INTERNET))
        }
        at = 1_000_000
        RoomRekeyLedger(store, b, { at }).use { box ->
            box.select(); assertEquals(1_600_000L, box.status().high)
            assertNull(box.reserve(e.id, RekeyLane.INTERNET))
            at = 1_000_000 + RoomRekeyLedger.WINDOW_MS
            assertNull(box.reserve(e.id, RekeyLane.INTERNET))
            at++; assertNotNull(box.reserve(e.id, RekeyLane.INTERNET))
        }
    }

    @Test fun eightAttemptLimitCannotBeResetByAReopenOrNewWindow() {
        val store = Store(); val b = binding(); var at = 1_000_000L; val e = event()
        RoomRekeyLedger(store, b, { at }, true).use { box ->
            box.select(); box.admit(e)
            repeat(RoomRekeyLedger.MAX_ATTEMPTS) { assertNotNull(box.reserve(e.id, RekeyLane.NEARBY)); at += 300_000 }
            assertNull(box.reserve(e.id, RekeyLane.NEARBY))
        }
        at += RoomRekeyLedger.WINDOW_MS
        RoomRekeyLedger(store, b, { at }).use { box ->
            box.select(); assertNull(box.reserve(e.id, RekeyLane.NEARBY))
            assertEquals(8, box.status().entries.single().nearby.attempts)
        }
    }

    @Test fun noticeCapacityRefusesNewEpochsWithoutEvictingUnconfirmedOriginals() {
        RoomRekeyLedger(Store(), binding(), { 1_000_000 }, true).use { box ->
            box.select(); repeat(16) { box.admit(event(epoch = it + 1)) }
            assertFails { box.admit(event(epoch = 17)) }
            assertEquals((1..16).toList(), box.status().entries.map { it.epoch })
            assertFails { box.admit(event(epoch = 17, content = "x".repeat(16 * 1024))) }
        }
    }

    @Test fun duplicateEpochCannotReplaceCiphertextSignatureExpiryOrReservationDebt() {
        val store = Store(); val original = event()
        RoomRekeyLedger(store, binding(), { 1_000_000 }, true).use { box ->
            box.select(); box.admit(original); box.reserve(original.id, RekeyLane.INTERNET)
            val before = box.status()
            box.admit(original); assertEquals(before, box.status())
            assertFails { box.admit(event(content = "conflicting successor")) }
            val resigned = event(aux = ByteArray(32) { 9 })
            assertEquals(original.id, resigned.id); assertNotEquals(original.sig, resigned.sig)
            assertFails { box.admit(resigned) }; assertEquals(before, box.status())
        }
    }

    @Test fun invalidAuthorityRoomEpochSignatureAndAmbiguousTagsNeverAcquireEntries() {
        RoomRekeyLedger(Store(), binding(), { 1_000_000 }, true).use { box ->
            box.select()
            val tags = event().tags
            for (bad in listOf(event(signer = ByteArray(32) { 42 }), event(epoch = 0), event(epoch = 1_000_001),
                event(tags = tags + listOf(listOf("d", binding().room))), event(tags = tags + listOf(listOf("epoch", "1"))),
                event(tags = listOf(listOf("d", "22".repeat(32)), listOf("epoch", "1"))),
                event(tags = tags + listOf(listOf("expiration", "1100"), listOf("expiration", "1101"))),
                event(tags = tags + listOf(listOf("expiration", "-1"))),
                event().copy(content = "tampered"), event(at = 1301))) assertFails { box.admit(bad) }
            assertTrue(box.status().entries.isEmpty())
        }
    }

    @Test fun callerMutationsCannotChangeTheDurableNoticeOrReservedCopy() {
        val mutable = event().tags.map { it.toMutableList() }.toMutableList()
        val e = event().copy(tags = mutable)
        RoomRekeyLedger(Store(), binding(), { 1_000_000 }, true).use { box ->
            box.select(); box.admit(e); mutable.first()[1] = "bad"
            val snapshot = box.status().entries.single().event
            assertEquals(binding().room, snapshot.tagValue("d"))
            val reserved = assertNotNull(box.reserve(snapshot.id, RekeyLane.INTERNET))
            (reserved.event.tags.first() as MutableList<String>)[1] = "bad"
            assertFalse(box.canHandoff(reserved)); assertEquals(binding().room, box.status().entries.single().event.tagValue("d"))
        }
    }

    @Test fun corruptLaneAndIncompleteSchemasCannotBeSilentlyNormalised() {
        val store = Store(); val b = binding()
        RoomRekeyLedger(store, b, { 1_000_000 }, true).use { box -> box.select(); box.admit(event()) }
        val original = store.bytes!!.clone()
        val root = Json.parseToJsonElement(original.toString(Charsets.UTF_8)).jsonObject
        val row = root.getValue("entries").jsonArray.single().jsonObject
        val badLane = JsonObject(row.getValue("NEARBY").jsonObject + mapOf("state" to JsonPrimitive("ACCEPTED"),
            "attempts" to JsonPrimitive(1), "nextAt" to JsonPrimitive(1_005_000)))
        val corruptions = listOf(JsonObject(root - "spends"),
            JsonObject(root + ("high" to JsonPrimitive("1000000"))),
            JsonObject(root + ("entries" to JsonArray(listOf(JsonObject(row +
                ("event" to JsonObject(row.getValue("event").jsonObject + ("unmodelled" to JsonPrimitive(true))))))))),
            JsonObject(root + ("entries" to JsonArray(listOf(JsonObject(row + ("NEARBY" to badLane)))))))
        for (corrupt in corruptions) {
            store.bytes = corrupt.toString().toByteArray()
            assertFails { RoomRekeyLedger(store, b, { 1_000_000 }, true) }
            assertContentEquals(corrupt.toString().toByteArray(), store.bytes)
        }
    }

    @Test fun dispatchDefersInsteadOfWaitingForTheLedgerPersistenceLock() {
        val store = Store(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2) { task -> Thread(task, "rekey-dispatch-regression").apply { isDaemon = true } }
        RoomRekeyLedger(store, binding(), { 1_000_000 }, true).use { box ->
            try {
                box.select(); box.admit(event())
                val reservation = assertNotNull(box.reserve(event().id, RekeyLane.INTERNET))
                store.beforeWrite = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                val persistence = workers.submit { box.status() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val guard = workers.submit<Boolean> { box.canHandoff(reservation) }
                assertFalse(guard.get(2, TimeUnit.SECONDS))
                release.countDown(); persistence.get(5, TimeUnit.SECONDS)
                assertTrue(box.canHandoff(reservation))
            } finally {
                release.countDown(); workers.shutdown(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }
}
