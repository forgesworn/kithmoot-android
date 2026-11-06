package dev.forgesworn.kithmoot.media

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeSinkLifetimeTest {
    @Test fun `sink removal finishes before disposal mutates the native sink map`() {
        val lifetime = NativeSinkLifetime()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val threads = Executors.newFixedThreadPool(2)
        try {
            val remove = threads.submit { lifetime.use(Unit) {
                events += "removing"; entered.countDown()
                check(finish.await(3, TimeUnit.SECONDS))
                events += "removed"
            } }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val close = threads.submit { closing.countDown(); lifetime.close { events += "disposed" } }
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            assertFalse(close.isDone)
            finish.countDown()
            remove.get(3, TimeUnit.SECONDS); close.get(3, TimeUnit.SECONDS)
            lifetime.use(Unit) { events += "late native call" }
            assertEquals(listOf("removing", "removed", "disposed"), events)
        } finally { finish.countDown(); threads.shutdownNow() }
    }

    @Test fun `close rejects reentrant and late sink operations and disposes once`() {
        val lifetime = NativeSinkLifetime()
        var disposed = 0
        lifetime.close {
            assertFalse(lifetime.use(false) { error("native access while disposing") })
            disposed++
        }
        lifetime.close { disposed++ }
        assertFalse(lifetime.use(false) { error("late native access") })
        assertEquals(1, disposed)
    }

    @Test fun `failed sink operation still permits one cleanup`() {
        val lifetime = NativeSinkLifetime()
        assertFailsWith<IllegalArgumentException> { lifetime.use(Unit) { throw IllegalArgumentException("sink") } }
        var disposed = 0
        lifetime.close { disposed++ }
        lifetime.close { disposed++ }
        assertEquals(1, disposed)
    }
}
