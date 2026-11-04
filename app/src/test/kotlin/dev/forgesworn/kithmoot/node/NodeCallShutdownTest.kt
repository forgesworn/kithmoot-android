package dev.forgesworn.kithmoot.node

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeCallShutdownTest {
    @Test fun `concurrent refusal and disposal wait for the original media teardown`() {
        val started = CountDownLatch(1); val finish = CountDownLatch(1); val callers = CountDownLatch(2)
        val mediaStopped = AtomicBoolean(false); val stops = AtomicInteger(); val releases = AtomicInteger()
        val shutdown = NodeCallShutdown(stop = {
            stops.incrementAndGet(); started.countDown()
            assertTrue(finish.await(3, TimeUnit.SECONDS))
            mediaStopped.set(true)
        }, release = {
            assertTrue(mediaStopped.get(), "Factory disposal cannot precede media teardown")
            releases.incrementAndGet()
        })
        val workers = Executors.newFixedThreadPool(3)
        try {
            val first = workers.submit { shutdown.close() }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val refusal = workers.submit { callers.countDown(); shutdown.close(); assertTrue(mediaStopped.get()) }
            val disposal = workers.submit { callers.countDown(); shutdown.dispose() }
            assertTrue(callers.await(3, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { refusal.get(100, TimeUnit.MILLISECONDS) }
            assertFailsWith<TimeoutException> { disposal.get(100, TimeUnit.MILLISECONDS) }
            finish.countDown()
            for (work in listOf(first, refusal, disposal)) work.get(3, TimeUnit.SECONDS)
            assertEquals(1, stops.get()); assertEquals(1, releases.get())
        } finally {
            finish.countDown(); workers.shutdownNow()
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `repeated close and dispose stop media and release the factory once`() {
        val order = mutableListOf<String>()
        val shutdown = NodeCallShutdown({ order += "stop" }, { order += "release" })
        shutdown.close(); shutdown.dispose(); shutdown.dispose(); shutdown.close()
        assertEquals(listOf("stop", "release"), order)
    }
}
