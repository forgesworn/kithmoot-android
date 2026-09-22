package dev.forgesworn.kithmoot.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The lock discipline that keeps the engine and libwebrtc's signalling thread
 * from waiting on each other. Three freezes on 0.6.9 had the same shape: the
 * main thread inside a peer-connection close under the engine lock, and the
 * signalling thread waiting for that lock to publish a state.
 */
class LinkTableTest {
    private class Link(val device: String)

    @Test fun `opens under the lock and hands back what is gone for closing outside it`() {
        val lock = Any()
        val table = LinkTable<Link>()
        var openedUnderLock = true
        table.reconcile(lock, setOf("a", "b")) { device ->
            if (!Thread.holdsLock(lock)) openedUnderLock = false
            Link(device)
        }
        assertTrue(openedUnderLock, "a link is opened under the lock, so a device is never opened twice")
        assertEquals(setOf("a", "b"), table.devices)
        val b = assertNotNull(table["b"])

        val gone = table.reconcile(lock, setOf("b")) { Link(it) }
        assertFalse(Thread.holdsLock(lock), "the lock is released before the caller closes anything")
        assertEquals(listOf("a"), gone.map { it.first })
        assertNull(table["a"])
        assertTrue(table.isCurrent("b", b))
        assertFalse(table.isCurrent("a", gone.single().second))
    }

    @Test fun `a state read never waits for the engine lock`() {
        val lock = Any()
        val table = LinkTable<Link>()
        val a = Link("a")
        table.put("a", a)
        val read = CountDownLatch(1)
        var current = false
        var completed = false
        val engine = thread {
            synchronized(lock) {
                // The worst case, and the shape of every 0.6.9 freeze: the
                // engine holds its lock and waits for the signalling thread.
                val signalling = thread {
                    current = table.isCurrent("a", a)
                    read.countDown()
                }
                completed = read.await(5, TimeUnit.SECONDS)
                signalling.join()
            }
        }
        engine.join()
        assertTrue(completed, "the callback waited for the engine lock")
        assertTrue(current)
    }

    @Test fun `clear hands everything back and leaves nothing current`() {
        val lock = Any()
        val table = LinkTable<Link>()
        val a = Link("a")
        val b = Link("b")
        table.put("a", a)
        table.put("b", b)
        val all = synchronized(lock) { table.clear() }
        assertEquals(setOf(a, b), all.toSet())
        assertTrue(table.devices.isEmpty())
        assertFalse(table.isCurrent("a", a))
        assertEquals(emptyList(), table.snapshot())
    }
}
