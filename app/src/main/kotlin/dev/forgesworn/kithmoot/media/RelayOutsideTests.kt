package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.RELAY_TEST_COOLDOWN_MS
import dev.forgesworn.kithmoot.protocol.RelayTestRequest
import dev.forgesworn.kithmoot.protocol.RelayTestResult
import dev.forgesworn.kithmoot.protocol.RelayTestVerdict
import dev.forgesworn.kithmoot.protocol.SIGNAL_RELAY_TEST
import dev.forgesworn.kithmoot.protocol.SIGNAL_RELAY_TEST_RESULT
import dev.forgesworn.kithmoot.session.RemoteRelayTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The room session surface the tester uses ([dev.forgesworn.kithmoot.session.RoomSession]). */
internal interface RelayTestSession {
    /** This device's own key, never answered. */
    val device: String
    val relayTests: Flow<RemoteRelayTest>
    fun sendRelayTest(toDevice: String, type: String, relayTest: JsonObject): Boolean
}

/** Who offers the built-in relay at an endpoint in this room (null when no
 *  single participant does), and whether this device may use it now. */
internal data class RelayTestOffer(val host: String?, val usable: Boolean)

/** The room's Spry surface the tester uses ([SpryRoomAccess]). */
internal interface RelayTestRelay {
    /** Null when this device holds no unexpired standard offer for [endpoint]. */
    suspend fun testOffer(endpoint: String): RelayTestOffer?
    /** A relay login for the test alone, never applied to calls or counted for
     *  vouching. Throws when the node gives none. */
    suspend fun testCredential(endpoint: String): CallRelayConfig
}

/**
 * The tester's side of the relay outside test for one room session
 * (docs/protocol.md, "Relay outside test"; the web's `RelayOutsideTests` in
 * `app/src/relay-outside-test.ts`, tester half). This phone never asks: it
 * only answers a host.
 *
 * A request is honoured only from a device whose participant is the one
 * member offering that built-in relay in this room, and only for a standard
 * offer this device holds; anything else is ignored without a word. A genuine
 * host is answered `declined` when a test is already running, when a test for
 * the same asking device ran within [cooldownMs], or when this device may not
 * use its relay (Hide my IP address on and no choice made for that host: a
 * read of the stored choice, never a question). A refused login is answered
 * `credentials`, and does not start the cooldown. Otherwise [run] decides the
 * verdict, within its own time limit.
 */
internal class RelayOutsideTests(
    private val session: RelayTestSession,
    private val relay: RelayTestRelay,
    parent: CoroutineScope,
    private val run: suspend (CallRelayConfig) -> RelayTestVerdict,
    private val cooldownMs: Long = RELAY_TEST_COOLDOWN_MS,
    /** Milliseconds; injectable for tests. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + job)
    private val lock = Any()
    private var running = false
    /** When this device last ran a test for each asking device, oldest first. */
    private val lastRun = LinkedHashMap<String, Long>()
    @Volatile private var closed = false

    init {
        scope.launch {
            session.relayTests.collect { message ->
                // Results are for a host; this phone asks nobody.
                if (closed || message.device == session.device || message.type != SIGNAL_RELAY_TEST) return@collect
                val request = RelayTestRequest.parse(message.relayTest) ?: return@collect
                scope.launch { answer(message.device, message.participant, request) }
            }
        }
    }

    internal suspend fun answer(device: String, participant: String, request: RelayTestRequest) {
        if (closed) return
        val offer = try { relay.testOffer(request.endpoint) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        // Only the member who offers this relay may ask; anyone else hears nothing.
        if (offer?.host == null || offer.host != participant) return
        val start = synchronized(lock) {
            val last = lastRun[device]
            if (running || !offer.usable || last != null && now() - last < cooldownMs) false
            else { running = true; true }
        }
        if (!start) return reply(device, request, DECLINED)
        try {
            val login = try { relay.testCredential(request.endpoint) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return reply(device, request, CREDENTIALS) }
            if (closed) return
            // The minute starts with traffic to the relay, not with a refused login.
            synchronized(lock) {
                lastRun.remove(device)
                lastRun[device] = now()
                if (lastRun.size > 64) lastRun.remove(lastRun.keys.first())
            }
            reply(device, request, run(login))
        } finally {
            synchronized(lock) { running = false }
        }
    }

    private suspend fun reply(device: String, request: RelayTestRequest, verdict: RelayTestVerdict) {
        if (closed) return
        val body = RelayTestResult.of(request, verdict).toJson()
        withContext(Dispatchers.Default) { if (!closed) session.sendRelayTest(device, SIGNAL_RELAY_TEST_RESULT, body) }
    }

    fun close() {
        closed = true
        job.cancel()
    }

    private companion object {
        val DECLINED = RelayTestVerdict(ok = false, stage = "declined")
        val CREDENTIALS = RelayTestVerdict(ok = false, stage = "credentials")
    }
}
