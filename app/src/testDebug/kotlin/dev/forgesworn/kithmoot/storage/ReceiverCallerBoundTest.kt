package dev.forgesworn.kithmoot.storage

import org.junit.Assert.*
import org.junit.Test

/** Measures the real JVM stack recorder; never supplies invented caller frames. */
class ReceiverCallerBoundTest {
    private fun context() = ReceiverKeyCreationProbe.Context(
        "committed-source-before-offer", "RECEIVER", "MISSING", ReceiverKeyCreationProbe.Phase.CASE_CLEANUP)

    // Not tailrec: each invocation contributes one actual application frame.
    private fun requestAtDepth(depth: Int): ReceiverKeyCreationProbe.Token =
        if (depth == 0) requireNotNull(ReceiverKeyCreationProbe.requested()) else requestAtDepth(depth - 1)

    @Test fun sixty_four_real_application_frames_are_complete_and_paired() {
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            val token = requestAtDepth(62)
            ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            val measured = session.closeAndSnapshot()
            assertEquals(2, measured.events.size)
            assertTrue(measured.complete)
            assertTrue(measured.events.all { it.callers.size == 64 && !it.callersTruncated })
            assertEquals(measured.events.first().callers, measured.events.last().callers)
            assertEquals(1L, measured.events.map { it.request }.distinct().single())
            assertEquals(listOf(ReceiverKeyCreationProbe.Outcome.REQUESTED, ReceiverKeyCreationProbe.Outcome.CREATED), measured.events.map { it.outcome })
            assertTrue(measured.events.all { it.context == context() })
            assertTrue(measured.events.first().callers.last().contains("#sixty_four_real_application_frames_are_complete_and_paired:"))
            assertTrue(measured.events.flatMap { it.callers }.all { it.matches(Regex("[A-Za-z0-9_.$]+#[A-Za-z0-9_$<>-]+:-?[0-9]+")) })
            assertEquals(0L, measured.overflow); assertEquals(0, measured.inFlight)
            assertEquals(0, measured.activeRecorders); assertEquals(0L, measured.missingRecords)
            assertEquals(0L, measured.observationErrors)
            assertNull(ReceiverKeyCreationProbe.requested())
            println("NATIVE_RECEIVER_CALLER_BOUND requestedFrames=64 capturedFrames=64 truncated=false complete=true paired=true overflow=0 inFlight=0 activeRecorders=0 missingRecords=0 observationErrors=0 ownerClosed=true jvmDiagnosticOnly=true")
        } finally { session.close() }
    }

    @Test fun sixty_five_real_application_frames_remain_truncated_and_incomplete() {
        val session = ReceiverKeyCreationProbe.open(context())
        try {
            val token = requestAtDepth(63)
            ReceiverKeyCreationProbe.completed(token, ReceiverKeyCreationProbe.Outcome.CREATED)
            val measured = session.closeAndSnapshot()
            assertEquals(2, measured.events.size)
            assertFalse(measured.complete)
            assertTrue(measured.events.all { it.callers.size == 64 && it.callersTruncated })
            assertEquals(measured.events.first().callers, measured.events.last().callers)
            assertEquals(1L, measured.events.map { it.request }.distinct().single())
            assertEquals(listOf(ReceiverKeyCreationProbe.Outcome.REQUESTED, ReceiverKeyCreationProbe.Outcome.CREATED), measured.events.map { it.outcome })
            assertTrue(measured.events.all { it.context == context() })
            assertEquals(0L, measured.overflow); assertEquals(0, measured.inFlight)
            assertEquals(0, measured.activeRecorders); assertEquals(0L, measured.missingRecords)
            assertEquals(0L, measured.observationErrors)
            assertNull(ReceiverKeyCreationProbe.requested())
            println("NATIVE_RECEIVER_CALLER_BOUND requestedFrames=65 capturedFrames=64 truncated=true complete=false paired=true overflow=0 inFlight=0 activeRecorders=0 missingRecords=0 observationErrors=0 ownerClosed=true jvmDiagnosticOnly=true")
        } finally { session.close() }
    }
}
