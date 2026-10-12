package dev.forgesworn.kithmoot.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SavedRoomObservationTest {
    @Test fun `late readable snapshot cannot hide a newer corrupt index`() {
        val observations = SavedRoomObservation()
        var shown = "loading"
        val readable = observations.begin()
        val corrupt = observations.begin()
        observations.commit(corrupt) { shown = "storage error" }
        observations.commit(readable) { shown = "readable" }
        assertEquals("storage error", shown)
    }

    @Test fun `late corrupt observation cannot replace a restored readable index`() {
        val observations = SavedRoomObservation()
        var shown = "loading"
        val corrupt = observations.begin()
        val restored = observations.begin()
        observations.commit(restored) { shown = "restored" }
        observations.commit(corrupt) { shown = "storage error" }
        assertEquals("restored", shown)
    }

    @Test fun `mutation storage failure invalidates an outstanding readable snapshot`() {
        val observations = SavedRoomObservation()
        var shown = "loading"
        val readable = observations.begin()
        observations.failed { shown = "storage error" }
        observations.commit(readable) { shown = "readable" }
        assertEquals("storage error", shown)
    }

    @Test fun `a later restored read can clear a mutation storage failure`() {
        val observations = SavedRoomObservation()
        var shown = "readable"
        observations.failed { shown = "storage error" }
        val restored = observations.begin { shown = "loading" }
        observations.commit(restored) { shown = "restored" }
        assertEquals("restored", shown)
    }

    @Test fun `cleared view model cannot publish late or newly started observations`() {
        val observations = SavedRoomObservation()
        var shown = "retained"
        val read = observations.begin()
        observations.close()
        observations.commit(read) { shown = "late read" }
        observations.failed { shown = "late failure" }
        val later = observations.begin { shown = "late loading" }
        observations.commit(later) { shown = "late restored" }
        assertEquals("retained", shown)
    }

    @Test fun `new observation cannot overtake an accepted synchronous publication`() {
        val observations = SavedRoomObservation()
        val executor = Executors.newFixedThreadPool(2)
        val publishing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val beginning = CountDownLatch(1)
        var shown = "loading"
        val old = observations.begin()
        try {
            val oldWrite = executor.submit {
                observations.commit(old) {
                    publishing.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    shown = "readable"
                }
            }
            assertTrue(publishing.await(3, TimeUnit.SECONDS))
            val newRead = executor.submit<SavedRoomObservation.Token> {
                beginning.countDown()
                observations.begin { shown = "new loading" }
            }
            assertTrue(beginning.await(3, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { newRead.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            oldWrite.get(3, TimeUnit.SECONDS)
            val corrupt = newRead.get(3, TimeUnit.SECONDS)
            observations.commit(corrupt) { shown = "storage error" }
            assertEquals("storage error", shown)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
