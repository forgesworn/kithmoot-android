package dev.forgesworn.kithmoot.storage

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceiverKeyCreationProbeTest {
    private fun context(phase: ReceiverKeyCreationProbe.Phase = ReceiverKeyCreationProbe.Phase.DIRECT_ENTRY) =
        ReceiverKeyCreationProbe.Context("committed-source-before-offer", "RECEIVER", "MISSING", phase)

    @Test fun inactive_probe_records_nothing() {
        assertNull(ReceiverKeyCreationProbe.requested())
        ReceiverKeyCreationProbe.completed(null, ReceiverKeyCreationProbe.Outcome.CREATED)
    }

    @Test fun single_owner_and_stale_close_preserve_the_new_owner() {
        val first = ReceiverKeyCreationProbe.open(context())
        try { assertThrows(IllegalStateException::class.java) { ReceiverKeyCreationProbe.open(context()) } }
        finally { first.close() }
        val next = ReceiverKeyCreationProbe.open(context())
        try {
            first.close()
            val token = requireNotNull(ReceiverKeyCreationProbe.requested())
            ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            assertEquals(2, next.closeAndSnapshot().events.size)
        } finally { next.close() }
        assertNull(ReceiverKeyCreationProbe.requested())
    }

    @Test fun completion_keeps_the_creation_context_and_static_caller() {
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            val token = requireNotNull(ReceiverKeyCreationProbe.requested())
            session.phase(context(ReceiverKeyCreationProbe.Phase.CASE_CLEANUP))
            ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            val measured = session.closeAndSnapshot()
            assertTrue(measured.complete)
            assertEquals(listOf(ReceiverKeyCreationProbe.Outcome.REQUESTED, ReceiverKeyCreationProbe.Outcome.CREATED), measured.events.map { it.outcome })
            assertEquals(1L, measured.events.map { it.request }.distinct().single())
            assertTrue(measured.events.all { it.context == context() && it.thread > 0 })
            assertTrue(measured.events.first().callers.any { it.contains("ReceiverKeyCreationProbeTest#completion_keeps_the_creation_context_and_static_caller:") })
            assertTrue(measured.events.flatMap { it.callers }.all { it.matches(Regex("[A-Za-z0-9_.$]+#[A-Za-z0-9_$<>-]+:-?[0-9]+")) })
        } finally { session.close() }
    }

    @Test fun failed_creation_and_duplicate_completion_do_not_double_charge_observation() {
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            val token = requireNotNull(ReceiverKeyCreationProbe.requested())
            ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.FAILED)
            ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            val measured = session.closeAndSnapshot()
            assertTrue(measured.complete)
            assertEquals(2, measured.events.size)
            assertEquals(ReceiverKeyCreationProbe.Outcome.FAILED, measured.events.last().outcome)
            assertEquals(0, measured.inFlight)
        } finally { session.close() }
    }

    @Test fun unfinished_creation_cannot_supply_complete_diagnostics() {
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            val token = requireNotNull(ReceiverKeyCreationProbe.requested())
            val measured = session.closeAndSnapshot()
            assertFalse(measured.complete); assertEquals(1, measured.inFlight)
            ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            assertEquals(1, measured.events.size)
            assertNull(ReceiverKeyCreationProbe.requested())
        } finally { session.close() }
    }

    @Test fun bounded_queue_reports_lost_events_without_losing_completion_accounting() {
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            repeat(70) {
                val token = requireNotNull(ReceiverKeyCreationProbe.requested())
                ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            }
            val measured = session.closeAndSnapshot()
            assertEquals(128, measured.events.size); assertEquals(12L, measured.overflow)
            assertEquals(0, measured.inFlight); assertEquals(0L, measured.missingRecords)
            assertFalse(measured.complete)
        } finally { session.close() }
    }

    @Test fun concurrent_callers_keep_distinct_requests_and_completion_pairs() {
        val session = ReceiverKeyCreationProbe.open(context())
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8); val go = CountDownLatch(1)
        try {
            val futures = (1..8).map { executor.submit {
                ready.countDown(); check(go.await(5, TimeUnit.SECONDS))
                val token = requireNotNull(ReceiverKeyCreationProbe.requested())
                ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            val measured = session.closeAndSnapshot()
            assertTrue(measured.complete); assertEquals(16, measured.events.size)
            assertEquals(16, measured.events.map { it.sequence }.distinct().size)
            assertEquals(8, measured.events.groupBy { it.request }.size)
            assertTrue(measured.events.groupBy { it.request }.values.all { pair ->
                pair.size == 2 && pair[0].outcome == ReceiverKeyCreationProbe.Outcome.REQUESTED &&
                    pair[1].outcome == ReceiverKeyCreationProbe.Outcome.CREATED && pair[0].thread == pair[1].thread
            })
        } finally { go.countDown(); executor.shutdownNow(); session.close() }
    }

    @Test fun context_accepts_only_static_window_target_and_fault_labels() {
        assertThrows(IllegalArgumentException::class.java) { ReceiverKeyCreationProbe.Context("secret", "RECEIVER", "MISSING", ReceiverKeyCreationProbe.Phase.BASELINE) }
        assertThrows(IllegalArgumentException::class.java) { ReceiverKeyCreationProbe.Context(context().window, "secret", "MISSING", ReceiverKeyCreationProbe.Phase.BASELINE) }
        assertThrows(IllegalArgumentException::class.java) { ReceiverKeyCreationProbe.Context(context().window, "RECEIVER", "secret", ReceiverKeyCreationProbe.Phase.BASELINE) }
    }

    @Test fun observation_failure_is_explicit_and_not_complete() {
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            session.observationFailed()
            val measured = session.closeAndSnapshot()
            assertEquals(1L, measured.observationErrors); assertFalse(measured.complete)
        } finally { session.close() }
    }

    @Test fun actual_seal_boundary_preserves_missing_provider_failure_and_records_no_key_material() {
        assertNull(java.security.Security.getProvider("AndroidKeyStore"))
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            assertThrows(java.security.NoSuchProviderException::class.java) {
                AndroidKeyStoreSealKeys.create("kithmoot.epoch.v1.entry." + "0".repeat(32))
            }
            val measured = session.closeAndSnapshot()
            assertTrue(measured.complete)
            assertEquals(listOf(ReceiverKeyCreationProbe.Outcome.REQUESTED, ReceiverKeyCreationProbe.Outcome.FAILED), measured.events.map { it.outcome })
            assertTrue(measured.events.first().callers.any { it.contains("AndroidKeyStoreSealKeys#create:") })
            assertFalse(measured.toString().contains("0".repeat(32)))
            assertFalse(measured.toString().contains("NoSuchProviderException"))
        } finally { session.close() }
    }

    @Test fun actual_seal_boundary_excludes_other_store_categories() {
        assertNull(java.security.Security.getProvider("AndroidKeyStore"))
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            assertThrows(java.security.NoSuchProviderException::class.java) { AndroidKeyStoreSealKeys.create("another.store.entry." + "0".repeat(32)) }
            val measured = session.closeAndSnapshot()
            assertTrue(measured.complete); assertTrue(measured.events.isEmpty())
        } finally { session.close() }
    }
}
