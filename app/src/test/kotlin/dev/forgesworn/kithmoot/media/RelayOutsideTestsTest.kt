package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.RelayTestRequest
import dev.forgesworn.kithmoot.protocol.RelayTestResult
import dev.forgesworn.kithmoot.protocol.RelayTestVerdict
import dev.forgesworn.kithmoot.session.RemoteRelayTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tester's gate for a relay outside test, in the order the web's
 * `RelayOutsideTests` (`app/src/relay-outside-test.ts`) applies it: silence
 * for anyone but the relay's one host or an offer this device does not hold,
 * then `declined`, then `credentials`, then the verdict.
 */
class RelayOutsideTestsTest {
    private val endpoint = "spry://1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f?relay=wss%3A%2F%2Frelay.example%2F"
    private val host = "a".repeat(64)
    private val hostDevice = "b".repeat(64)
    private val otherDevice = "c".repeat(64)
    private val login = requireNotNull(CallRelayConfig.parse("turn:203.0.113.9:40000?transport=udp", "1800000300:0123456789abcdef01234567", "a".repeat(27) + "="))

    private class Session : RelayTestSession {
        override val device = "d".repeat(64)
        override val relayTests = MutableSharedFlow<RemoteRelayTest>(extraBufferCapacity = 16)
        val sent = mutableListOf<Triple<String, String, JsonObject>>()
        override fun sendRelayTest(toDevice: String, type: String, relayTest: JsonObject): Boolean {
            synchronized(sent) { sent += Triple(toDevice, type, relayTest) }
            return true
        }
        fun results() = synchronized(sent) { sent.map { it.first to requireNotNull(RelayTestResult.parse(it.third)) } }
    }

    private inner class Relay(var offer: RelayTestOffer? = RelayTestOffer(host, usable = true)) : RelayTestRelay {
        var logins = 0
        var refuse = false
        override suspend fun testOffer(endpoint: String) = offer
        override suspend fun testCredential(endpoint: String): CallRelayConfig {
            logins++
            if (refuse) throw SpryRequestException(403, SHARED_RELAY_UNAVAILABLE)
            return login
        }
    }

    private var clock = 1_000_000L
    private var runs = 0

    private fun tester(session: Session, relay: Relay, run: suspend (CallRelayConfig) -> RelayTestVerdict = { runs++; RelayTestVerdict(ok = true) }) =
        RelayOutsideTests(session, relay, CoroutineScope(SupervisorJob() + Dispatchers.Default), run, cooldownMs = 60_000, now = { clock })

    private fun request(id: String = "0123456789abcdef") = RelayTestRequest(id, endpoint)

    @Test fun `anyone but the relay's one host hears nothing`() = runBlocking {
        val session = Session()
        val relay = Relay(RelayTestOffer(host, usable = true))
        val tests = tester(session, relay)
        tests.answer(hostDevice, "e".repeat(64), request())
        relay.offer = RelayTestOffer(null, usable = true)
        tests.answer(hostDevice, host, request())
        relay.offer = null
        tests.answer(hostDevice, host, request())
        assertTrue(session.sent.isEmpty())
        assertEquals(0, relay.logins)
        assertEquals(0, runs)
    }

    @Test fun `a host this device may not use is declined without a login or a question`() = runBlocking {
        val session = Session()
        val relay = Relay(RelayTestOffer(host, usable = false))
        tester(session, relay).answer(hostDevice, host, request())
        assertEquals(listOf(hostDevice to RelayTestResult("0123456789abcdef", endpoint, ok = false, stage = "declined")), session.results())
        assertEquals(0, relay.logins)
        assertEquals(0, runs)
    }

    @Test fun `a refused login is credentials and does not start the minute`() = runBlocking {
        val session = Session()
        val relay = Relay().apply { refuse = true }
        val tests = tester(session, relay)
        tests.answer(hostDevice, host, request())
        assertEquals("credentials", session.results().single().second.stage)
        assertEquals(0, runs)
        relay.refuse = false
        tests.answer(hostDevice, host, request("00000000000000aa"))
        assertEquals(RelayTestResult("00000000000000aa", endpoint, ok = true), session.results().last().second)
        assertEquals(1, runs)
    }

    @Test fun `one test per asking device a minute, counted from the login`() = runBlocking {
        val session = Session()
        val relay = Relay()
        val tests = tester(session, relay, run = { runs++; RelayTestVerdict(ok = false, stage = "inbound", sameNetwork = true) })
        tests.answer(hostDevice, host, request())
        assertEquals(RelayTestResult("0123456789abcdef", endpoint, ok = false, stage = "inbound", sameNetwork = true), session.results().single().second)
        clock += 59_999
        tests.answer(hostDevice, host, request("0000000000000002"))
        assertEquals("declined", session.results().last().second.stage)
        // Another of the host's devices is its own asker.
        tests.answer(otherDevice, host, request("0000000000000003"))
        assertEquals("inbound", session.results().last().second.stage)
        clock += 1
        tests.answer(hostDevice, host, request("0000000000000004"))
        assertEquals("inbound", session.results().last().second.stage)
        assertEquals(3, runs)
        assertEquals(3, relay.logins)
    }

    @Test fun `a request while a test runs is declined, not queued`() = runBlocking {
        val session = Session()
        val relay = Relay()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val tests = tester(session, relay, run = { started.complete(Unit); release.await(); RelayTestVerdict(ok = true) })
        val first = launch(Dispatchers.Default) { tests.answer(hostDevice, host, request()) }
        started.await()
        tests.answer(otherDevice, host, request("0000000000000002"))
        assertEquals(listOf(otherDevice to RelayTestResult("0000000000000002", endpoint, ok = false, stage = "declined")), session.results())
        release.complete(Unit)
        first.join()
        assertEquals(hostDevice to RelayTestResult("0123456789abcdef", endpoint, ok = true), session.results().last())
        assertEquals(1, relay.logins)
    }

    @Test fun `nothing is sent once the room closes`() = runBlocking {
        val session = Session()
        val relay = Relay()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val tests = tester(session, relay, run = { started.complete(Unit); release.await(); RelayTestVerdict(ok = true) })
        val first = launch(Dispatchers.Default) { tests.answer(hostDevice, host, request()) }
        started.await()
        tests.close()
        release.complete(Unit)
        first.join()
        tests.answer(hostDevice, host, request("0000000000000002"))
        assertTrue(session.sent.isEmpty())
    }

    @Test fun `requests arrive from the room, results and its own device are ignored`() = runBlocking {
        val session = Session()
        val relay = Relay()
        val tests = tester(session, relay)
        // Let the collector subscribe before anything is emitted.
        withTimeout(5_000) { while (session.relayTests.subscriptionCount.value == 0) delay(5) }
        session.relayTests.emit(RemoteRelayTest(host, hostDevice, 0, "relay-test-result",
            RelayTestResult("0123456789abcdef", endpoint, ok = true).toJson()))
        session.relayTests.emit(RemoteRelayTest(host, session.device, 0, "relay-test", request().toJson()))
        session.relayTests.emit(RemoteRelayTest(host, hostDevice, 0, "relay-test", request("00000000000000ff").toJson()))
        withTimeout(5_000) { while (synchronized(session.sent) { session.sent.isEmpty() }) delay(5) }
        delay(50)
        assertEquals(listOf(hostDevice to RelayTestResult("00000000000000ff", endpoint, ok = true)), session.results())
        assertEquals("relay-test-result", session.sent.single().second)
        tests.close()
    }
}
