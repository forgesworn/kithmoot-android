package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.relay.RoomRoute
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomRouteTransitionsTest {
    @Test fun `route change waits for in flight handoff and queued handoff reads new route`() = runTest {
        var route = RoomRoute.INTERNET
        val handingOff = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val old = launch { RoomRouteTransitions.stable {
            handingOff.complete(Unit); release.await(); actions.add("old handoff completed")
        } }
        handingOff.await()
        val change = launch { RoomRouteTransitions.stable {
            actions.add("queues stopped"); route = RoomRoute.NEARBY; actions.add("route saved")
        } }
        runCurrent()
        val next = launch { RoomRouteTransitions.stable {
            if (route.internet) actions.add("unexpected network") else actions.add("refused")
        } }
        runCurrent(); assertTrue(actions.isEmpty())
        release.complete(Unit); joinAll(old, change, next)
        assertEquals(listOf("old handoff completed", "queues stopped", "route saved", "refused"), actions)
    }
    @Test fun `failed route save releases gate without inventing a new route`() = runTest {
        assertFailsWith<IllegalStateException> { RoomRouteTransitions.stable { error("storage unavailable") } }
        assertEquals(42, RoomRouteTransitions.stable { 42 })
    }
}
