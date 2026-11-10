package dev.forgesworn.kithmoot.notifications

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatNoticeRefreshTest {
    @Test fun `slow notification processing cannot block visibility callbacks or queue every change`() {
        val worker = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor()
        val dispatcher = worker.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CountDownLatch(1); val release = CountDownLatch(1); val caughtUp = CountDownLatch(1)
        val calls = AtomicInteger(); val latest = AtomicBoolean(); val observed = AtomicBoolean()
        val failures = AtomicInteger()
        val refresh = ChatNoticeRefresh(scope, dispatcher, onFailure = { failures.incrementAndGet() }) {
            if (calls.incrementAndGet() == 1) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } else { observed.set(latest.get()); caughtUp.countDown() }
        }
        try {
            refresh.request()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            // A history resolver or Binder call remains blocked on the worker.
            // The UI still applies all visibility changes and handles its next task.
            ui.submit {
                repeat(10_000) { latest.set(it % 2 == 0); refresh.request() }
                latest.set(true)
            }.get(3, TimeUnit.SECONDS)
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(caughtUp.await(3, TimeUnit.SECONDS))
            ui.submit { }.get(3, TimeUnit.SECONDS)
            worker.submit { }.get(3, TimeUnit.SECONDS)
            assertEquals(2, calls.get(), "Only one pending refresh may follow the blocked one")
            assertTrue(observed.get()); assertEquals(0, failures.get())
        } finally {
            release.countDown(); scope.cancel(); dispatcher.close(); ui.shutdownNow()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(ui.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `a failed post permits a later refresh and cancellation drops queued work`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var calls = 0; var failures = 0
        val refresh = ChatNoticeRefresh(scope, StandardTestDispatcher(testScheduler), { failures++ }) {
            if (++calls == 1) error("Synthetic notification failure")
        }
        refresh.request(); runCurrent()
        assertEquals(1, failures)
        refresh.request(); runCurrent()
        assertEquals(2, calls)
        refresh.request(); scope.cancel(); runCurrent()
        refresh.request(); runCurrent()
        assertEquals(2, calls)
    }
}
