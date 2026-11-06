package dev.forgesworn.kithmoot.media

import kotlin.test.*

class NodeGatheringFallbackTest {
    private class Rig {
        var time = 0L
        var active = true
        var advanced = 0
        var recovered = 0
        var available = true
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val fallback = NodeGatheringFallback(
            schedule = { wait, run -> val timer = time + wait to run; timers.add(timer); { timers.remove(timer); Unit } },
            active = { active }, advanceRoute = { advanced++; available }, recover = { recovered++ },
        )
        fun tick(millis: Long) {
            time += millis
            val due = timers.filter { it.first <= time }; timers.removeAll(due.toSet())
            due.forEach { it.second() }
        }
    }
    @Test fun `empty gathering waits four seconds and asks once for fresh replacement`() {
        val r = Rig(); r.fallback.gatheringStarted(); r.fallback.gatheringComplete()
        r.tick(3999); assertEquals(0, r.recovered)
        r.tick(1); assertEquals(1, r.recovered)
        r.fallback.gatheringComplete(); r.tick(8000); assertEquals(1, r.advanced)
    }
    @Test fun `one family error and empty rollback do not preempt a relay candidate`() {
        val r = Rig(); r.fallback.gatheringStarted(); r.fallback.error(701)
        r.fallback.gatheringComplete(); r.tick(2000)
        r.fallback.gatheringStarted(); r.tick(2000); r.fallback.relayCandidate()
        r.tick(8000); assertEquals(0, r.recovered)
    }
    @Test fun `authenticated refusals do not cycle routes or mint more credentials`() {
        for (code in listOf(401, 403, 486, 508)) {
            val r = Rig(); r.fallback.gatheringStarted(); r.fallback.error(code)
            r.fallback.gatheringStarted(); r.fallback.transportFailed(); r.tick(8000)
            assertEquals(0, r.advanced, "code $code")
        }
    }
    @Test fun `healthy continual gathering does not replace a connected peer`() {
        val r = Rig(); r.fallback.connectionState(true); r.fallback.gatheringStarted()
        r.fallback.gatheringComplete(); r.tick(8000); assertEquals(0, r.recovered)
    }
    @Test fun `a fresh failed gathered route can advance but only while current and permitted`() {
        val r = Rig(); r.fallback.gatheringStarted(); r.fallback.relayCandidate()
        r.fallback.transportFailed(); assertEquals(1, r.recovered)
        val retired = Rig(); retired.fallback.gatheringStarted(); retired.active = false
        retired.tick(8000); assertEquals(0, retired.advanced)
    }
    @Test fun `an established failure leaves route repair to its owner`() {
        val r = Rig(); r.fallback.gatheringStarted(); r.fallback.relayCandidate()
        r.fallback.connectionState(true); r.fallback.connectionState(false)
        r.fallback.transportFailed(); r.tick(8000)
        assertEquals(0, r.advanced); assertEquals(0, r.recovered)
    }
    @Test fun `an empty later round cannot spend an established route while repair waits`() {
        val r = Rig(); r.fallback.connectionState(true); r.fallback.connectionState(false)
        r.fallback.gatheringStarted(); r.fallback.gatheringComplete(); r.tick(8000)
        assertEquals(0, r.advanced); assertEquals(0, r.recovered)
        // A replacement owns a fresh watchdog and may discover a blocked route.
        val replacement = Rig(); replacement.fallback.gatheringStarted(); replacement.tick(4000)
        assertEquals(1, replacement.advanced); assertEquals(1, replacement.recovered)
    }
    @Test fun `exhaustion and close cannot request recovery`() {
        val r = Rig(); r.available = false; r.fallback.gatheringStarted(); r.tick(4000)
        assertEquals(0, r.recovered); assertEquals(1, r.advanced)
        val closed = Rig(); closed.fallback.gatheringStarted(); closed.fallback.close()
        closed.tick(8000); closed.fallback.transportFailed(); assertEquals(0, closed.advanced)
    }
}
