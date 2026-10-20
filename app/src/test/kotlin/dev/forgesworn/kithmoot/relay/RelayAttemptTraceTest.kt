package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RelayAttemptTraceTest {
    @Test fun runtime_observer_is_off_without_explicit_debug_startup() {
        assertEquals(null, OrbotTrace.session())
    }

    @Test fun concurrent_producers_are_complete_or_explicitly_accounted() {
        val clock = AtomicLong()
        val buffer = RelayTraceBuffer(1024, clock::incrementAndGet)
        val session = buffer.session()
        val writers = (1..8).map { link -> thread {
            repeat(50) { session.attempt(link.toLong(), it.toLong() + 1).record(RelayTraceEvent.ATTEMPT_START) }
        } }
        writers.forEach { it.join() }
        val snapshot = buffer.snapshot(1)
        assertTrue(snapshot.complete)
        assertEquals(402, snapshot.entries.size)
        assertEquals((1L..402L).toList(), snapshot.entries.map { it.sequence })
        assertEquals(8, snapshot.entries.filter { it.event == RelayTraceEvent.ATTEMPT_START }.map { it.link }.toSet().size)
        Json.parseToJsonElement(snapshot.json()).jsonObject
    }

    @Test fun overflow_and_clock_or_export_failure_forbid_absence_claims() {
        val wrapped = RelayTraceBuffer(4)
        repeat(8) { wrapped.session() }
        val snapshot = wrapped.snapshot(2)
        assertFalse(snapshot.complete)
        assertEquals(5L, snapshot.overwritten)
        assertEquals(4, snapshot.entries.size)
        val broken = RelayTraceBuffer(elapsedNanos = { error("private message") })
        broken.session().record(RelayTraceEvent.ADAPTER_OPEN)
        broken.exportFailed()
        assertFalse(broken.snapshot(1).complete)
        assertTrue(broken.snapshot(1).errors >= 4)
        assertFalse(broken.snapshot(1).json().contains("private message"))
    }

    @Test fun failure_classification_does_not_serialize_throwable_text() {
        val secret = "private-target-and-room"
        assertEquals(RelayTraceFailure.TLS, relayTraceFailure(SSLException(secret)))
        assertEquals(RelayTraceFailure.IO, relayTraceFailure(IOException(secret)))
        val buffer = RelayTraceBuffer()
        buffer.session().attempt(1, 1).record(RelayTraceEvent.ADAPTER_FAILURE,
            relayTraceFailure(IOException(secret)).ordinal.toLong())
        assertFalse(buffer.snapshot(1).json().contains(secret))
    }

    @Test fun abandoned_late_open_keeps_its_attempt_identity_and_cannot_publish_connected() = runTest {
        val sockets = FakeSocketFactory()
        val buffer = RelayTraceBuffer()
        val pool = RelayPool(listOf("wss://private.example"), sockets, backgroundScope,
            policy = RelayPolicy(openTimeoutMs = 100), now = { currentTime }, random = Random(1), trace = buffer.session())
        pool.start()
        runCurrent()
        val late = sockets.opened.single()
        advanceTimeBy(101)
        runCurrent()
        late.open()
        late.drop()
        runCurrent()
        assertTrue(late.closedByPool)
        assertTrue(pool.connected.value.isEmpty())
        val entries = buffer.snapshot(1).entries
        assertTrue(entries.any { it.attempt == 1L && it.event == RelayTraceEvent.OPEN_DECISION && it.value == 0L })
        assertFalse(entries.any { it.attempt == 1L && it.event == RelayTraceEvent.LINK_OPEN_COMPLETE })
        pool.stop()
        val replacement = RelayPool(listOf("wss://private.example"), sockets, backgroundScope,
            now = { currentTime }, random = Random(1), trace = buffer.session())
        replacement.start()
        runCurrent()
        sockets.opened.last().open()
        late.open() // Callback from the closed generation must keep the old ID.
        runCurrent()
        assertEquals(setOf("wss://private.example"), replacement.connected.value)
        val generations = buffer.snapshot(2).entries.filter { it.event == RelayTraceEvent.OPEN_DECISION }
        assertTrue(generations.any { it.session == 1L && it.value == 0L })
        assertTrue(generations.any { it.session == 2L && it.value == 1L })
        replacement.stop()
    }
}
