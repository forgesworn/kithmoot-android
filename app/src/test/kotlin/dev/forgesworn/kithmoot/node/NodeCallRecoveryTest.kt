package dev.forgesworn.kithmoot.node

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NodeCallRecoveryTest {
    private var clock = 0L
    private var expiry = 300_000L
    private var allowed = true
    private var failures = 0
    private var calls = 0
    private var stopped = 0
    private val replacements = mutableListOf<Pair<Set<String>?, Long>>()
    private fun recovery(fetch: suspend () -> Unit = {
        calls++
        if (failures-- > 0) throw NodeRequestException(503)
        expiry = clock + 300_000
    }) = NodeCallRecovery({ allowed }, { expiry - clock }, fetch,
        { peers, grace -> replacements.add(peers to grace) }, { stopped++ }, { clock })

    @Test fun `pair recovery replaces only failed peers and retains the original global renewal deadline`() = runTest {
        val recovery = recovery()
        clock = 100_000; recovery.request("failed"); recovery.tick()
        assertEquals(1, replacements.size); assertEquals(setOf("failed"), replacements.single().first)
        assertEquals(20_000L, replacements.single().second)
        clock = 239_999; recovery.tick(); assertEquals(1, calls)
        clock = 240_000; recovery.tick()
        assertNull(replacements.last().first); assertEquals(20_000L, replacements.last().second); assertEquals(2, calls)
    }
    @Test fun `temporary issuer failure retries without closing a valid call and expiry is terminal`() = runTest {
        val recovery = recovery(); failures = 2; recovery.request()
        recovery.tick(); assertEquals(0, stopped); assertTrue(replacements.isEmpty())
        clock = 1_999; recovery.tick(); assertEquals(1, calls)
        clock = 2_000; recovery.tick(); assertEquals(0, stopped)
        clock = 4_000; recovery.tick(); assertEquals(1, replacements.size)
        expiry = clock; recovery.tick(); recovery.tick(); assertEquals(1, stopped)
    }
    @Test fun `refusal and malformed responses terminate immediately and never replace a peer`() = runTest {
        for (status in listOf(401, 403, -1)) {
            val recovery = recovery { throw NodeRequestException(status) }
            recovery.request(); recovery.tick()
        }
        assertEquals(3, stopped); assertTrue(replacements.isEmpty())
    }
    @Test fun `new route failures during a request survive completion and concurrent ticks share that request`() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val recovery = recovery { calls++; started.complete(Unit); release.await(); expiry = clock + 300_000 }
        recovery.request("first")
        val first = async { recovery.tick() }; started.await()
        recovery.request("second"); val second = async { recovery.tick() }; runCurrent()
        assertEquals(1, calls); release.complete(Unit); first.await(); second.await()
        assertEquals(1, replacements.size); assertEquals(setOf("first"), replacements.single().first)
        clock = 5_000; recovery.tick()
        assertEquals(setOf("second"), replacements.last().first)
    }
    @Test fun `cached credentials cannot defer global renewal and repairs cannot retain media past the old expiry`() = runTest {
        val recovery = recovery { calls++ }
        clock = 299_000; recovery.request("peer"); recovery.tick()
        assertNull(replacements.single().first); assertEquals(1_000L, replacements.single().second)
        clock = 300_000; recovery.tick(); assertEquals(1, stopped)
    }
    @Test fun `withdrawal while credentials are in flight cannot install a replacement`() = runTest {
        val recovery = recovery { allowed = false }
        recovery.request(); recovery.tick(); assertEquals(1, stopped); assertTrue(replacements.isEmpty())
    }
}
