package dev.forgesworn.kithmoot.media

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReceivedTracksTest {
    private class Resource(var references: Int = 1)
    private class Track(val resource: Resource = Resource()) {
        var disposed = false
        fun retain(): Track {
            check(!disposed)
            resource.references++
            return Track(resource)
        }
        fun dispose() {
            check(!disposed)
            disposed = true
            resource.references--
        }
    }
    private fun store() = ReceivedTracks<Track>(
        retain = { _, source -> source.retain() },
        release = Track::dispose,
        sameSource = { held, source -> held.resource === source.resource },
    )

    @Test fun `callback owner can dispose its wrapper before the view detaches`() {
        val source = Track()
        val tracks = store()
        tracks["camera"] = source
        val held = tracks.values.single()
        assertNotSame(source, held)
        source.dispose()
        assertFalse(held.disposed)
        assertEquals(1, held.resource.references)
        tracks.close()
        assertTrue(held.disposed)
        assertEquals(0, held.resource.references)
    }

    @Test fun `duplicate track callbacks retain the existing view binding`() {
        val source = Track()
        val tracks = store()
        tracks["camera"] = source
        val held = tracks.values.single()
        tracks["camera"] = source
        assertSame(held, tracks.values.single())
        assertEquals(2, source.resource.references)
        tracks.close()
        source.dispose()
        assertEquals(0, source.resource.references)
    }

    @Test fun `replacement and removal release only the stores references`() {
        val first = Track()
        val second = Track()
        val tracks = store()
        tracks["camera"] = first
        val old = tracks.values.single()
        tracks["camera"] = second
        assertTrue(old.disposed)
        assertEquals(1, first.resource.references)
        assertEquals(2, second.resource.references)
        tracks.remove("camera")
        tracks.remove("camera")
        tracks.close()
        assertEquals(1, second.resource.references)
        first.dispose(); second.dispose()
    }

    @Test fun `late callbacks after close never acquire another reference`() {
        val source = Track()
        val tracks = store()
        tracks.close()
        tracks["late"] = source
        tracks.close()
        assertTrue(tracks.values.isEmpty())
        assertEquals(1, source.resource.references)
        source.dispose()
    }

    @Test fun `close waits for capture and releases the captured reference once`() {
        val source = Track()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        val tracks = ReceivedTracks<Track>(retain = { _, track ->
            entered.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
            track.retain()
        }, release = Track::dispose)
        try {
            val capture = threads.submit { tracks["camera"] = source }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val close = threads.submit { closing.countDown(); tracks.close() }
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            assertFalse(close.isDone)
            finish.countDown()
            capture.get(3, TimeUnit.SECONDS); close.get(3, TimeUnit.SECONDS)
            assertTrue(tracks.values.isEmpty())
            assertEquals(1, source.resource.references)
            tracks.close()
            source.dispose()
        } finally { finish.countDown(); threads.shutdownNow() }
    }
}
