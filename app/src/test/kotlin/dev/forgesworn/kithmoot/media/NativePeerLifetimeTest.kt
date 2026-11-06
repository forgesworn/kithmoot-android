package dev.forgesworn.kithmoot.media

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativePeerLifetimeTest {
    @Test
    fun `disposal waits for an entered native call and rejects later calls`() {
        val lifetime = NativePeerLifetime(Any())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val threads = Executors.newFixedThreadPool(2)
        try {
            val operation = threads.submit {
                lifetime.use {
                    events += "entered"
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    events += "returned"
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val close = threads.submit {
                closing.countDown()
                lifetime.close { events += "disposed" }
            }
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            assertFalse(close.isDone)
            release.countDown()
            operation.get(3, TimeUnit.SECONDS)
            close.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("entered", "returned", "disposed"), events)
            assertFailsWith<IllegalStateException> { lifetime.use { events += "too late" } }
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun `native close runs without holding its entry monitor and disposes once`() {
        val lifetime = NativePeerLifetime(Any())
        val threads = Executors.newSingleThreadExecutor()
        val disposals = AtomicInteger()
        try {
            lifetime.close {
                disposals.incrementAndGet()
                // A callback on another thread must be able to return while
                // native close waits for it. It sees closed, not a held lock.
                val callback = threads.submit<Boolean> {
                    assertFailsWith<IllegalStateException> { lifetime.use { error("native access after close") } }
                    true
                }
                assertTrue(callback.get(3, TimeUnit.SECONDS))
            }
            lifetime.close { disposals.incrementAndGet() }
            assertEquals(1, disposals.get())
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    fun `a failed native operation still permits cleanup`() {
        val lifetime = NativePeerLifetime(Any())
        assertFailsWith<IllegalArgumentException> { lifetime.use { throw IllegalArgumentException("native refusal") } }
        var disposed = false
        lifetime.close { disposed = true }
        assertTrue(disposed)
    }

    @Test
    fun `signalling callback returns while a JNI caller holds the peer monitor`() {
        val monitor = Any()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val signalling = Executors.newSingleThreadExecutor()
        val refreshed = CountDownLatch(1)
        try {
            val job = synchronized(monitor) {
                // Models a JNI caller awaiting the signalling thread while
                // retaining the monitor that protects transient wrappers.
                signalling.submit<kotlinx.coroutines.Job> {
                    dispatchPeerCallback(scope) {
                        synchronized(monitor) { refreshed.countDown() }
                    }
                }.get(3, TimeUnit.SECONDS)
            }
            assertTrue(refreshed.await(3, TimeUnit.SECONDS))
            runBlocking { withTimeout(3_000) { job.join() } }
        } finally {
            scope.cancel()
            signalling.shutdownNow()
        }
    }
}
