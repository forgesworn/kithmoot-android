package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class TestAuthenticator(private val signer: LocalSigner, private val clock: () -> Long) : RelayAuthenticator {
    override val pubkey: String get() = signer.pubkey
    var calls = 0
    override suspend fun sign(url: String, challenge: String): NostrEvent {
        calls += 1
        return signer.sign(22242, clock() / 1_000, listOf(listOf("relay", url), listOf("challenge", challenge)), "")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RelayPoolTest {

    private val relays = listOf("wss://one.example", "wss://two.example", "wss://three.example")

    private fun event(id: String, kind: Int = 20461) = NostrEvent(
        kind = kind,
        createdAt = 1799995000,
        tags = listOf(listOf("d", "room")),
        content = "opaque",
        pubkey = "aa".repeat(32),
        id = id,
        sig = "cc".repeat(64),
    )

    @Test
    fun `the circle is asked each time, so a card added mid-room moves the lane`() = runTest {
        var circle = emptySet<String>()
        val pool = RelayPool(relays, FakeSocketFactory(), backgroundScope, now = { currentTime }, random = Random(1), circle = { circle })
        assertEquals(emptySet(), pool.circleRelays())
        assertEquals(dev.forgesworn.kithmoot.protocol.Lane.PUBLIC, dev.forgesworn.kithmoot.protocol.laneOfRelays(pool.describe(), pool.circleRelays()))
        circle = relays.toSet()
        assertEquals(relays.toSet(), pool.circleRelays())
        assertEquals(dev.forgesworn.kithmoot.protocol.Lane.SHELTERED, dev.forgesworn.kithmoot.protocol.laneOfRelays(pool.describe(), pool.circleRelays()))
    }

    @Test
    fun `sheltered relay waits for successful auth before releasing work`() = runTest {
        val sockets = FakeSocketFactory()
        val auth = TestAuthenticator(LocalSigner(ByteArray(32) { 9 }), { currentTime })
        val url = "wss://one.example"
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1),
            authenticators = RelayAuthenticatorProvider { requested -> auth.takeIf { requested == url } })
        pool.start()
        runCurrent()
        pool.publish(event("a1".repeat(32)))
        val socket = sockets.opened.single()
        socket.open()
        runCurrent()
        assertTrue(pool.connected.value.isEmpty())
        assertTrue(socket.sent.isEmpty())

        socket.deliverAuth("challenge-1")
        runCurrent()
        assertEquals(1, auth.calls)
        val authFrame = socket.authFrames().single()
        val signed = NostrEvent.fromJson(Json.parseToJsonElement(authFrame.substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)
        socket.deliverOk(signed.id, true)
        runCurrent()

        assertEquals(setOf(url), pool.connected.value)
        assertEquals(1, socket.publishedFrames().size)
    }

    @Test
    fun `NIP-77 is Link and NIP-42 gated, returns IDs only, then closes`() = runTest {
        val sockets = FakeSocketFactory()
        val auth = TestAuthenticator(LocalSigner(ByteArray(32) { 7 }), { currentTime })
        val url = LinkRelayAddress.canonicalForNode("11".repeat(32))
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1), circle = { setOf(url) },
            authenticators = RelayAuthenticatorProvider { requested -> auth.takeIf { requested == url } })
        pool.start(); runCurrent()
        val socket = sockets.opened.single()
        socket.open(); socket.deliverAuth("negentropy-challenge"); runCurrent()
        val signed = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single().substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)
        socket.deliverOk(signed.id, true); runCurrent()

        val result = async {
            pool.reconcileNip77(url, Filter(kinds = listOf(1), since = 1, until = 2, limit = 1), emptyList())
        }
        runCurrent()
        val open = socket.sent.single { it.startsWith("[\"NEG-OPEN\"") }
        val subscriptionId = open.substringAfter("[\"NEG-OPEN\",\"").substringBefore("\"")
        assertTrue(socket.publishedFrames().isEmpty(), "reconciliation must not publish events")
        socket.deliverRaw("[\"NEG-MSG\",\"$subscriptionId\",\"6100000201${"bb".repeat(32)}\"]")
        runCurrent()

        assertEquals(listOf("bb".repeat(32)), result.await().need.map { id -> id.joinToString("") { "%02x".format(it.toInt() and 0xff) } })
        assertTrue(socket.sent.any { it == "[\"NEG-CLOSE\",\"$subscriptionId\"]" })
        assertTrue(socket.sent.none { it.startsWith("[\"REQ\"") || it.startsWith("[\"EVENT\"") })
    }

    @Test
    fun `NIP-77 refuses an ordinary relay before opening a socket`() = runTest {
        val sockets = FakeSocketFactory()
        val url = "wss://ordinary.example"
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1), circle = { setOf(url) },
            authenticators = RelayAuthenticatorProvider { TestAuthenticator(LocalSigner(ByteArray(32) { 6 }), { currentTime }) })
        val failure = runCatching {
            pool.reconcileNip77(url, Filter(kinds = listOf(1), since = 1, until = 2, limit = 1), emptyList())
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(sockets.opened.isEmpty())
    }

    @Test
    fun `NIP-77 stops when its circle authority is withdrawn mid-session`() = runTest {
        supervisorScope {
        val sockets = FakeSocketFactory()
        val auth = TestAuthenticator(LocalSigner(ByteArray(32) { 5 }), { currentTime })
        val url = LinkRelayAddress.canonicalForNode("12".repeat(32))
        var circle = setOf(url)
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1), circle = { circle },
            authenticators = RelayAuthenticatorProvider { auth })
        pool.start(); runCurrent()
        val socket = sockets.opened.single()
        socket.open(); socket.deliverAuth("withdraw-challenge"); runCurrent()
        val signed = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single().substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)
        socket.deliverOk(signed.id, true); runCurrent()
        val operation = async { pool.reconcileNip77(url, Filter(kinds = listOf(1), since = 1, until = 2, limit = 1), emptyList()) }
        runCurrent()
        val id = socket.sent.single { it.startsWith("[\"NEG-OPEN\"") }.substringAfter("[\"NEG-OPEN\",\"").substringBefore("\"")
        circle = emptySet()
        socket.deliverRaw("[\"NEG-MSG\",\"$id\",\"6100000200\"]")
        runCurrent()
        val error = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(error?.message?.contains("withdrawn") == true)
        assertTrue(socket.sent.any { it == "[\"NEG-CLOSE\",\"$id\"]" })
        }
    }

    @Test
    fun `NIP-77 fetch requests only compared IDs from the authenticated Link box`() = runTest {
        val sockets = FakeSocketFactory()
        val auth = TestAuthenticator(LocalSigner(ByteArray(32) { 4 }), { currentTime })
        val url = LinkRelayAddress.canonicalForNode("13".repeat(32))
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1), circle = { setOf(url) },
            authenticators = RelayAuthenticatorProvider { auth })
        pool.start(); runCurrent()
        val socket = sockets.opened.single()
        socket.open(); socket.deliverAuth("fetch-challenge"); runCurrent()
        val signed = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single().substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)
        socket.deliverOk(signed.id, true); runCurrent()

        val returned = LocalSigner(ByteArray(32) { 3 }).sign(1460, 10, listOf(listOf("d", "room")), "opaque")
        val operation = async {
            pool.fetchNip77Events(
                url,
                Filter(ids = listOf(returned.id), kinds = listOf(1460), tags = mapOf("#d" to listOf("room")), since = 1, until = 20, limit = 1),
                listOf(returned.id),
            )
        }
        runCurrent()
        val request = socket.sent.single { it.startsWith("[\"REQ\"") }
        val subscriptionId = request.substringAfter("[\"REQ\",\"").substringBefore("\"")
        assertTrue(request.contains(returned.id))
        assertTrue(socket.sent.none { it.startsWith("[\"NEG-OPEN\"") || it.startsWith("[\"EVENT\"") })
        socket.deliverEvent(subscriptionId, returned)
        socket.deliverRaw("[\"EOSE\",\"$subscriptionId\"]")
        runCurrent()

        assertEquals(listOf(returned.id), operation.await().map(NostrEvent::id))
        assertTrue(socket.sent.any { it == "[\"CLOSE\",\"$subscriptionId\"]" })
    }

    @Test
    fun `NIP-77 fetch refuses an event outside the compared ID set`() = runTest {
        val sockets = FakeSocketFactory()
        val auth = TestAuthenticator(LocalSigner(ByteArray(32) { 4 }), { currentTime })
        val url = LinkRelayAddress.canonicalForNode("14".repeat(32))
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1), circle = { setOf(url) },
            authenticators = RelayAuthenticatorProvider { auth })
        pool.start(); runCurrent()
        val socket = sockets.opened.single()
        socket.open(); socket.deliverAuth("fetch-refusal"); runCurrent()
        val signed = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single().substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)
        socket.deliverOk(signed.id, true); runCurrent()

        val expected = LocalSigner(ByteArray(32) { 3 }).sign(1460, 10, listOf(listOf("d", "room")), "opaque")
        val extra = LocalSigner(ByteArray(32) { 2 }).sign(1460, 10, listOf(listOf("d", "room")), "opaque")
        val operation = async {
            runCatching {
                pool.fetchNip77Events(
                    url,
                    Filter(ids = listOf(expected.id), kinds = listOf(1460), tags = mapOf("#d" to listOf("room")), since = 1, until = 20, limit = 1),
                    listOf(expected.id),
                )
            }
        }
        runCurrent()
        val subscriptionId = socket.requestedSubscriptions().single()
        socket.deliverEvent(subscriptionId, extra)
        runCurrent()

        assertTrue(operation.await().exceptionOrNull()?.message?.contains("unexpected") == true)
        assertTrue(socket.sent.any { it == "[\"CLOSE\",\"$subscriptionId\"]" })
    }

    @Test
    fun `NIP-77 fetch refuses an ordinary relay before opening a socket`() = runTest {
        val sockets = FakeSocketFactory()
        val url = "wss://ordinary.example"
        val id = "ab".repeat(32)
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1), circle = { setOf(url) },
            authenticators = RelayAuthenticatorProvider { TestAuthenticator(LocalSigner(ByteArray(32) { 6 }), { currentTime }) })

        val failure = runCatching {
            pool.fetchNip77Events(
                url,
                Filter(ids = listOf(id), kinds = listOf(1460), tags = mapOf("#d" to listOf("room")), since = 1, until = 2, limit = 1),
                listOf(id),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(sockets.opened.isEmpty())
    }

    @Test
    fun `auth refusal blocks reconnect until explicit retry`() = runTest {
        val sockets = FakeSocketFactory()
        val auth = TestAuthenticator(LocalSigner(ByteArray(32) { 8 }), { currentTime })
        val url = "wss://one.example"
        val pool = RelayPool(listOf(url), sockets, backgroundScope, now = { currentTime }, random = Random(1),
            authenticators = RelayAuthenticatorProvider { auth })
        pool.start(); runCurrent()
        val first = sockets.opened.single(); first.open(); first.deliverAuth("challenge-2"); runCurrent()
        val signed = NostrEvent.fromJson(Json.parseToJsonElement(first.authFrames().single().substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)
        first.deliverOk(signed.id, false); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, sockets.opened.size)
        assertTrue(first.closedByPool)
        assertTrue(pool.retryAuthentication(url))
        runCurrent()
        assertEquals(2, sockets.opened.size)
    }

    @Test
    fun `the same event from every relay is delivered once`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()

        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()
        // Three relays, one event, three copies arriving. This is the normal
        // case, not an unusual one: publishing everywhere means receiving
        // everywhere.
        sockets.opened.forEach { it.deliverEvent(subscriptionId, event("ff".repeat(32))) }
        runCurrent()

        assertEquals(1, received.size)
    }

    @Test
    fun `distinct events all get through`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()

        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()
        sockets.opened[0].deliverEvent(subscriptionId, event("11".repeat(32)))
        sockets.opened[1].deliverEvent(subscriptionId, event("22".repeat(32)))
        sockets.opened[2].deliverEvent(subscriptionId, event("33".repeat(32)))
        runCurrent()

        assertEquals(3, received.size)
    }

    @Test
    fun `a publish goes to every connected relay`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        pool.publish(event("44".repeat(32)))

        assertEquals(3, sockets.opened.count { it.publishedFrames().size == 1 })
    }

    @Test
    fun `a publish made before any relay is up is not lost`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()

        // Joining a room means announcing at once, which is always a few hundred
        // milliseconds before the first socket finishes opening. Losing this
        // publish means losing the announce that tells the room you arrived.
        pool.publish(event("55".repeat(32)))
        assertTrue(sockets.opened.all { it.publishedFrames().isEmpty() })

        sockets.openAll()
        assertEquals(3, sockets.opened.count { it.publishedFrames().size == 1 })
    }

    @Test
    fun `rekey blocks publication and discards old offline frames`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one.example"), sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        val old = event("57".repeat(32))
        val successor = event("58".repeat(32))
        pool.publish(old)

        pool.beginRekey()
        assertFailsWith<IllegalStateException> { pool.publish(successor) }
        pool.rekey(ByteArray(32) { 9 })
        pool.completeRekey()
        pool.publish(successor)

        sockets.opened.single().open()
        val frames = sockets.opened.single().publishedFrames()
        assertEquals(1, frames.size)
        assertTrue(successor.id in frames.single())
        assertTrue(old.id !in frames.single())
    }

    @Test
    fun `recovery control can cross the publication barrier while room traffic cannot`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one.example"), sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        val socket = sockets.opened.single()
        socket.open()
        val ordinary = event("59".repeat(32))
        val recovery = event("5a".repeat(32), 20_468)

        pool.beginRekey()
        assertFailsWith<IllegalStateException> { pool.publish(ordinary) }
        pool.publishRecovery(recovery)

        val frames = socket.publishedFrames()
        assertEquals(1, frames.size)
        assertTrue(recovery.id in frames.single())
    }

    @Test
    fun `a queued publish is dropped once it is stale`() = runTest {
        val sockets = FakeSocketFactory()
        val policy = RelayPolicy(outboxTtlMs = 5_000)
        val pool = RelayPool(
            listOf("wss://one.example"),
            sockets,
            backgroundScope,
            policy,
            now = { currentTime },
            random = Random(1),
        )
        pool.start()
        runCurrent()
        pool.publish(event("66".repeat(32)))

        advanceTimeBy(6_000)
        runCurrent()
        sockets.opened.first().open()

        // A stale signalling frame is worse than a dropped one: the negotiation
        // it belonged to has moved on.
        assertTrue(sockets.opened.first().publishedFrames().isEmpty())
    }

    @Test
    fun `a dropped relay is reconnected and its subscriptions re-sent`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(
            listOf("wss://one.example"),
            sockets,
            backgroundScope,
            now = { currentTime },
            random = Random(1),
        )
        pool.start()
        runCurrent()
        sockets.opened.first().open()

        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { } }
        runCurrent()
        val first = sockets.opened.first()
        val subscriptionId = first.requestedSubscriptions().single()

        first.drop()
        assertEquals(emptySet(), pool.connected.value)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, sockets.opened.size, "the pool should have reopened the socket")

        val second = sockets.opened[1]
        second.open()
        runCurrent()

        // Subscriptions do not survive a dropped socket. A client that does not
        // re-send them goes silently deaf while still looking connected.
        assertEquals(listOf(subscriptionId), second.requestedSubscriptions())
        assertEquals(setOf("wss://one.example"), pool.connected.value)
    }

    @Test
    fun `backoff grows and is capped`() {
        val policy = RelayPolicy(baseDelayMs = 500, maxDelayMs = 30_000)
        assertEquals(500, policy.delayFor(0))
        assertEquals(1_000, policy.delayFor(1))
        assertEquals(4_000, policy.delayFor(3))
        assertEquals(30_000, policy.delayFor(9))
        assertEquals(30_000, policy.delayFor(1_000))
    }

    @Test
    fun `one relay going down leaves the others carrying the room`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(relays, sockets, backgroundScope, now = { currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.openAll()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()
        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()

        sockets.forUrl("wss://one.example").first().drop()
        runCurrent()

        // No single relay is load-bearing. That is the point of the project.
        sockets.forUrl("wss://two.example").first().deliverEvent(subscriptionId, event("77".repeat(32)))
        runCurrent()
        assertEquals(1, received.size)
        assertEquals(2, pool.connected.value.size)
    }

    @Test
    fun `a hostile frame does not kill the socket`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(
            listOf("wss://one.example"),
            sockets,
            backgroundScope,
            now = { currentTime },
            random = Random(1),
        )
        pool.start()
        runCurrent()
        sockets.opened.first().open()

        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(20461)))).collect { received += it } }
        runCurrent()
        val subscriptionId = sockets.opened.first().requestedSubscriptions().single()

        sockets.opened.first().deliverRaw("this is not json")
        sockets.opened.first().deliverRaw("""["EVENT","$subscriptionId",{"kind":"nonsense"}]""")
        sockets.opened.first().deliverEvent(subscriptionId, event("88".repeat(32)))
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(setOf("wss://one.example"), pool.connected.value)
    }
}
