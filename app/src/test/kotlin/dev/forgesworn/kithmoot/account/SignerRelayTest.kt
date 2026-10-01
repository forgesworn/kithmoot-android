package dev.forgesworn.kithmoot.account

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The request that waits on a signer app: time limit, late answers, and an activity recreated meanwhile. */
class SignerRelayTest {
    private val ids = IdentityHashMap<Intent, String?>()
    private fun intent(id: String?) = Intent().also { ids[it] = id }
    private fun relay(timeoutMs: Long = 60_000, attachWaitMs: Long = 5_000) =
        SignerRelay(timeoutMs, attachWaitMs, idOf = { ids[it] })

    private class Screen { val launched = mutableListOf<Intent>(); val launch: (Intent) -> Unit = { launched += it } }

    @Test fun `an answer completes the request it was for`() = runTest {
        val relay = relay(); val screen = Screen().also { relay.attach(it.launch) }
        val reply = intent("a")
        val asking = async { relay.request(intent("a")) }
        runCurrent()
        assertEquals(1, screen.launched.size)
        relay.deliver(true, reply)
        assertSame(reply, asking.await())
    }

    @Test fun `backing out of the signer is a null answer, as before`() = runTest {
        val relay = relay(); val screen = Screen().also { relay.attach(it.launch) }
        val asking = async { relay.request(intent("a")) }
        runCurrent()
        relay.deliver(false, null, id = "a")
        assertNull(asking.await())
    }

    @Test fun `a signer that never answers times out with a retryable error`() = runTest {
        val relay = relay(timeoutMs = 60_000); val screen = Screen().also { relay.attach(it.launch) }
        val asking = async { runCatching { relay.request(intent("a")) } }
        runCurrent()
        advanceTimeBy(59_000); runCurrent()
        assertTrue(asking.isActive, "still waiting inside the limit")
        advanceTimeBy(2_000); runCurrent()
        val error = asking.await().exceptionOrNull()
        assertTrue(error is SignerTimeoutException)
        assertEquals("Your signer didn't answer. Open it, unlock it, then try again.", error.message)
        assertTrue(error is SignerException, "anything that already handles a signer failure handles this one")
    }

    @Test fun `after a timeout the next request goes straight through`() = runTest {
        val relay = relay(timeoutMs = 1_000); val screen = Screen().also { relay.attach(it.launch) }
        val first = async { runCatching { relay.request(intent("a")) } }
        advanceTimeBy(1_500); runCurrent()
        assertTrue(first.await().exceptionOrNull() is SignerTimeoutException)
        val reply = intent("b")
        val second = async { relay.request(intent("b")) }
        runCurrent()
        assertEquals(2, screen.launched.size)
        relay.deliver(true, reply)
        assertSame(reply, second.await())
    }

    @Test fun `a late result for a request that timed out is ignored, by id`() = runTest {
        val relay = relay(timeoutMs = 1_000); val screen = Screen().also { relay.attach(it.launch) }
        val first = async { runCatching { relay.request(intent("a")) } }
        advanceTimeBy(1_500); runCurrent()
        first.await()
        val second = async { relay.request(intent("b")) }
        runCurrent()
        relay.deliver(true, intent("a"))
        runCurrent()
        assertTrue(second.isActive, "the late answer to the first request did not answer the second")
        val reply = intent("b")
        relay.deliver(true, reply)
        assertSame(reply, second.await())
    }

    @Test fun `a late result is ignored by launch order when the signer does not echo the id`() = runTest {
        val relay = relay(timeoutMs = 1_000); val screen = Screen().also { relay.attach(it.launch) }
        val first = async { runCatching { relay.request(intent(null)) } }
        advanceTimeBy(1_500); runCurrent()
        first.await()
        val second = async { relay.request(intent(null)) }
        runCurrent()
        relay.deliver(true, intent(null))
        runCurrent()
        assertTrue(second.isActive)
        val reply = intent(null)
        relay.deliver(true, reply)
        assertSame(reply, second.await())
    }

    @Test fun `a signer that echoes an id of its own, as My Signet does, is matched by launch order`() = runTest {
        val relay = relay(); val screen = Screen().also { relay.attach(it.launch) }
        val asking = async { relay.request(intent("ours")) }
        runCurrent()
        val reply = intent("signets-own-id")
        relay.deliver(true, reply)
        assertSame(reply, asking.await())
    }

    @Test fun `a result for a launch that was skipped drops what came before it`() = runTest {
        val relay = relay(timeoutMs = 1_000); val screen = Screen().also { relay.attach(it.launch) }
        val first = async { runCatching { relay.request(intent("a")) } }
        advanceTimeBy(1_500); runCurrent()
        first.await()
        val second = async { relay.request(intent("b")) }
        runCurrent()
        val reply = intent("b")
        relay.deliver(true, reply)
        assertSame(reply, second.await(), "the first one never came, and the second still got its own")
    }

    @Test fun `a caller that gives up leaves nothing behind for the next`() = runTest {
        val relay = relay(); val screen = Screen().also { relay.attach(it.launch) }
        val first = async { relay.request(intent("a")) }
        runCurrent()
        first.cancel()
        runCurrent()
        val second = async { relay.request(intent("b")) }
        runCurrent()
        relay.deliver(true, intent("a"))
        runCurrent()
        assertTrue(second.isActive)
        val reply = intent("b")
        relay.deliver(true, reply)
        assertSame(reply, second.await())
    }

    @Test fun `an activity recreated while the signer is up still gets the answer`() = runTest {
        val relay = relay()
        val before = Screen().also { relay.attach(it.launch) }
        val asking = async { relay.request(intent("a")) }
        runCurrent()
        // A language or font-size change: the old activity goes, a new one comes, the signer is still showing.
        relay.detach(before.launch)
        val after = Screen().also { relay.attach(it.launch) }
        runCurrent()
        assertTrue(asking.isActive, "recreation did not end the wait")
        assertEquals(0, after.launched.size, "and nothing was launched twice")
        val reply = intent("a")
        relay.deliver(true, reply)   // redelivered by Android to the new activity's callback
        assertSame(reply, asking.await())
    }

    @Test fun `the wait still ends if the activity never comes back`() = runTest {
        val relay = relay(timeoutMs = 60_000)
        val screen = Screen().also { relay.attach(it.launch) }
        val asking = async { runCatching { relay.request(intent("a")) } }
        runCurrent()
        relay.detach(screen.launch)
        advanceTimeBy(61_000); runCurrent()
        assertTrue(asking.await().exceptionOrNull() is SignerTimeoutException)
    }

    @Test fun `a request made while the activity is being recreated waits for it`() = runTest {
        val relay = relay(attachWaitMs = 5_000)
        val asking = async { relay.request(intent("a")) }
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        val screen = Screen().also { relay.attach(it.launch) }
        runCurrent()
        assertEquals(1, screen.launched.size)
        val reply = intent("a")
        relay.deliver(true, reply)
        assertSame(reply, asking.await())
    }

    @Test fun `with no activity at all the request fails cleanly rather than hanging`() = runTest {
        val relay = relay(attachWaitMs = 5_000)
        val asking = async { runCatching { relay.request(intent("a")) } }
        advanceTimeBy(6_000); runCurrent()
        val error = asking.await().exceptionOrNull()
        assertTrue(error is SignerException && error !is SignerTimeoutException)
        assertEquals("The screen is not ready to open the signer.", error.message)
    }

    @Test fun `an old activity going away does not detach the new one`() = runTest {
        val relay = relay()
        val old = Screen().also { relay.attach(it.launch) }
        val new = Screen().also { relay.attach(it.launch) }
        relay.detach(old.launch)
        val asking = async { relay.request(intent("a")) }
        runCurrent()
        assertEquals(1, new.launched.size)
        relay.deliver(true, intent("a"))
        assertNotNull(asking.await())
    }

    @Test fun `a result with nothing waiting, as after the process was killed, is dropped`() = runTest {
        val relay = relay(); val screen = Screen().also { relay.attach(it.launch) }
        relay.deliver(true, intent("a"))
        relay.deliver(false, null)
        val asking = async { relay.request(intent("b")) }
        runCurrent()
        val reply = intent("b")
        relay.deliver(true, reply)
        assertSame(reply, asking.await())
    }

    @Test fun `a launcher that throws is a clear error and frees the turn`() = runTest {
        val relay = relay()
        relay.attach { throw IllegalStateException("no such app") }
        val error = assertFailsWith<SignerException> { relay.request(intent("a")) }
        assertTrue("could not be opened" in error.message!!)
        val screen = Screen().also { relay.attach(it.launch) }
        val asking = async { relay.request(intent("b")) }
        runCurrent()
        relay.deliver(true, intent("b"))
        assertNotNull(asking.await())
    }

    @Test fun `waiting behind another request does not use up the time limit`() = runTest {
        val relay = relay(timeoutMs = 60_000); val screen = Screen().also { relay.attach(it.launch) }
        val first = async { relay.request(intent("a")) }
        runCurrent()
        val second = async(start = CoroutineStart.DEFAULT) { relay.request(intent("b")) }
        runCurrent()
        advanceTimeBy(50_000); runCurrent()
        relay.deliver(true, intent("a"))
        first.await(); runCurrent()
        assertEquals(2, screen.launched.size)
        advanceTimeBy(50_000); runCurrent()
        assertTrue(second.isActive, "its own 60 seconds only began when its signer opened")
        relay.deliver(true, intent("b"))
        assertNotNull(second.await())
    }
}

class SignerTimeoutTest {
    @Test fun `a signer name is used when known, and your signer when not`() {
        assertEquals("My Signet didn't answer. Open it, unlock it, then try again.", signerDidNotAnswer("My Signet"))
        assertEquals("Your signer didn't answer. Open it, unlock it, then try again.", signerDidNotAnswer(null))
        assertEquals("Your signer didn't answer. Open it, unlock it, then try again.", signerDidNotAnswer(" "))
    }

    @Test fun `the silent query gets a short budget and the screen a minute`() {
        assertEquals(10_000L, SIGNER_SILENT_TIMEOUT_MS)
        assertEquals(60_000L, SIGNER_INTENT_TIMEOUT_MS)
    }

    @Test fun `a provider that is too slow falls back to the intent path rather than failing`() = runTest {
        val slow = async { silentOrNull<String>(SIGNER_SILENT_TIMEOUT_MS) { delay(15_000); "late" } }
        advanceTimeBy(SIGNER_SILENT_TIMEOUT_MS + 1); runCurrent()
        assertNull(slow.await(), "null is NIP-55's 'ask by intent', and the caller then opens the signer")
        assertEquals("quick", silentOrNull(SIGNER_SILENT_TIMEOUT_MS) { delay(100); "quick" })
    }

    @Test fun `a provider's refusal is not swallowed by the budget`() = runTest {
        assertFailsWith<SignerException> { silentOrNull<String>(SIGNER_SILENT_TIMEOUT_MS) { throw SignerException("declined") } }
    }

    @Test fun `a null answer from a quick signer is not a timeout`() = runTest {
        assertNull(silentOrNull<String>(1_000) { null })
    }

    @Test fun `a caller cancelled from outside is still cancelled`() = runTest {
        val job = async { silentOrNull<String>(SIGNER_SILENT_TIMEOUT_MS) { delay(60_000); "x" } }
        runCurrent()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
    }
}
