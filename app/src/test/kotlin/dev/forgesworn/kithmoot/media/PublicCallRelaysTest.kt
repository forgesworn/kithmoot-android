package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.PublicRelayAdvert
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.relay.RelayHealth
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MemoryStorage(var bytes: ByteArray? = null) : RoomStorage {
    var writes = 0
    override fun read(): ByteArray? = bytes
    override fun write(value: ByteArray) { writes++; bytes = value }
    override fun reset() { bytes = null }
}

internal object Adverts {
    const val BASE = 1_793_000_000L
    fun signed(seed: Int, host: String = "relay$seed.example.org", at: Long = BASE, note: String? = null, region: String? = null): NostrEvent {
        val tags = listOf(listOf("d", "spry-turn"), listOf("v", "1"), listOf("status", "open"), listOf("auth", "public"),
            listOf("expiration", (at + 3600).toString())) + (region?.let { listOf(listOf("g", it)) } ?: emptyList())
        val content = buildJsonObject {
            put("credentials", "https://$host/v1/public/credentials")
            put("urls", JsonArray(listOf(JsonPrimitive("turn:$host:3478?transport=udp"), JsonPrimitive("turns:$host:5349?transport=tcp"))))
            note?.let { put("note", it) }
        }.toString()
        return Events.sign(ByteArray(32) { (seed + 1).toByte() }, 31478, at, tags, content, ByteArray(32))
    }
    fun issued(advert: PublicRelayAdvert, expiresAt: Long) = PublicRelayLoginResult.Issued(PublicRelayLogin(advert.publicKey, advert.host!!,
        advert.offer!!.urls, "$expiresAt:pub-0123456789abcdef01234567", "c2VjcmV0", expiresAt))
}

@OptIn(ExperimentalCoroutinesApi::class)
class PublicCallRelaysTest {
    private val now = Adverts.BASE + 60

    private fun relays(storage: MemoryStorage = MemoryStorage(), clock: () -> Long = { now },
                       login: suspend (PublicRelayAdvert) -> PublicRelayLoginResult = { Adverts.issued(it, clock() + 3600) },
                       io: kotlinx.coroutines.CoroutineDispatcher) =
        PublicCallRelays(PublicCallRelayStore(storage), login, clock, io)

    @Test fun theQueryIsTheOneTheDesignNames() {
        val filter = PublicRelayDiscovery.filter(now).toJson().toString()
        assertEquals("{\"kinds\":[31478],\"#d\":[\"spry-turn\"],\"since\":${now - 7200},\"limit\":64}", filter)
    }

    @Test fun onlyReadRelaysThatAreNotFailingAreAsked() {
        val choices = listOf(RelayChoice("wss://a.example.org"), RelayChoice("wss://b.example.org", read = false),
            RelayChoice("wss://c.example.org/"), RelayChoice("wss://d.example.org"), RelayChoice("wss://e.example.org"))
        val health = mapOf(
            "wss://c.example.org" to RelayHealth(connection = "Connection failed: timeout"),
            "wss://d.example.org/" to RelayHealth(connection = "Connected", read = "Read refused"),
            "wss://e.example.org" to RelayHealth(connection = "Reconnecting", read = "Not checked"),
        )
        assertEquals(listOf("wss://a.example.org", "wss://e.example.org"), PublicRelayDiscovery.queryRelays(choices, health))
    }

    @Test fun atMostTheNewest64AreConsideredOldestFirst() {
        val events = (0 until 70).map { NostrEvent(31478, Adverts.BASE + it, emptyList(), "", "p", "%064x".format(it), "s") }
        val kept = PublicRelayDiscovery.newest(events + events.take(3))
        assertEquals(64, kept.size)
        assertEquals(Adverts.BASE + 6, kept.first().createdAt)
        assertEquals(Adverts.BASE + 69, kept.last().createdAt)
    }

    @Test fun offIsTheDefaultAndNothingIsSearchedOrFetchedWhileOff() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var fetched = 0
        val r = relays(login = { fetched++; PublicRelayLoginResult.Failed }, io = io)
        assertEquals(PublicCallRelayMode.OFF, r.load())
        assertTrue(runCatching { r.discover { error("must not search") } }.isFailure)
        assertEquals(PublicRelayLoginResult.Failed, r.login("a".repeat(64)))
        assertEquals(0, fetched)
        assertEquals(emptyList(), r.callCandidates())
    }

    @Test fun foundAdvertsAreListedAndWatermarksPersistedTurningOffClearsAll() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val storage = MemoryStorage()
        val r = relays(storage, io = io)
        r.setEnabled(true)
        val found = r.discover { listOf(Adverts.signed(1, note = "Hello\u202e there", region = "GB"), Adverts.signed(2)) }
        assertEquals(2, found.size)
        assertEquals("Hello there", found.first { it.host == "relay1.example.org" }.note)
        assertEquals("GB", found.first { it.host == "relay1.example.org" }.region)
        val saved = PublicCallRelayStateCodec.decode(storage.bytes!!)!!
        assertEquals(2, saved.watermarks.size)
        r.select(found[0].key)
        r.markGood(found[0].key)
        assertEquals(found[0].key, PublicCallRelayStateCodec.decode(storage.bytes!!)!!.lastGood?.key)
        r.setEnabled(false)
        assertNull(storage.bytes)
        assertEquals(emptyList(), r.listing())
        assertNull(r.selected)
    }

    @Test fun aKnownHostnameUnderANewKeyIsFlagged() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val r = relays(io = io)
        r.setEnabled(true)
        val first = r.discover { listOf(Adverts.signed(1, host = "same.example.org")) }.single()
        r.markGood(first.key)
        val again = r.discover { listOf(Adverts.signed(5, host = "same.example.org")) }
        assertEquals(listOf(true, false), again.map { it.lastGood })
        assertEquals(listOf(false, true), again.map { it.knownHostNewKey })
    }

    @Test fun anUnreadableFileIsOffAndNeverWrittenButCanBeCleared() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val storage = MemoryStorage("not json".toByteArray())
        val r = relays(storage, io = io)
        assertEquals(PublicCallRelayMode.UNAVAILABLE, r.load())
        assertTrue(runCatching { r.setEnabled(true) }.isFailure)
        assertEquals(0, storage.writes)
        r.setEnabled(false)
        assertNull(storage.bytes)
        assertEquals(PublicCallRelayMode.OFF, r.mode)
    }

    @Test fun aHeldLoginIsReusedAndAFourthOperatorIsRefused() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var fetched = 0
        val r = relays(login = { fetched++; Adverts.issued(it, now + 3600) }, io = io)
        r.setEnabled(true)
        val keys = r.discover { (1..4).map { Adverts.signed(it) } }.map { it.key }
        assertIs<PublicRelayLoginResult.Issued>(r.login(keys[0]))
        assertIs<PublicRelayLoginResult.Issued>(r.login(keys[0]))
        assertEquals(1, fetched)
        assertIs<PublicRelayLoginResult.Issued>(r.login(keys[1]))
        assertIs<PublicRelayLoginResult.Issued>(r.login(keys[2]))
        assertEquals(PublicRelayLoginResult.Failed, r.login(keys[3]))
        assertEquals(3, fetched)
        r.newBudget()
        assertIs<PublicRelayLoginResult.Issued>(r.login(keys[3]))
        assertEquals(4, fetched)
    }

    @Test fun aLoginFetchedAcrossALeaveIsNotKept() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val answer = CompletableDeferred<Unit>()
        var fetched = 0
        val r = relays(login = { fetched++; answer.await(); Adverts.issued(it, now + 3600) }, io = io)
        r.setEnabled(true)
        val key = r.discover { listOf(Adverts.signed(1)) }.single().key
        val oldCall = async { r.login(key) }
        runCurrent()
        assertEquals(1, fetched)
        r.dropLogins()
        answer.complete(Unit)
        assertEquals(PublicRelayLoginResult.Failed, oldCall.await())
        // Not held: the next login is fetched again.
        assertIs<PublicRelayLoginResult.Issued>(r.login(key))
        assertEquals(2, fetched)
    }

    // ---- one call --------------------------------------------------------

    private class Harness(val scope: TestScope) {
        val io = StandardTestDispatcher(scope.testScheduler)
        val clock = { Adverts.BASE + 60 + scope.testScheduler.currentTime / 1000 }
        val answers = ArrayDeque<(PublicRelayAdvert) -> PublicRelayLoginResult>()
        var fetches = 0
        val relays = PublicCallRelays(PublicCallRelayStore(MemoryStorage()), { advert ->
            fetches++
            answers.removeFirstOrNull()?.invoke(advert) ?: Adverts.issued(advert, clock() + 3600)
        }, clock, io)
        val applied = ArrayList<PublicRelayLogin?>()
        val notices = ArrayList<PublicRelayNotice>()
        var superseded = false
        var searches = 0
        fun call(relayOnly: Boolean = false, applies: Boolean = true) = PublicRelayCall(relays, scope.backgroundScope,
            { searches++; listOf(Adverts.signed(1, at = clock() - 60), Adverts.signed(2, at = clock() - 60)) },
            apply = { login -> applied += login; applies }, notice = { notices += it }, relayOnly = relayOnly,
            superseded = { superseded }, clock = clock, random = Random(1))
        suspend fun ready(): List<String> {
            relays.setEnabled(true)
            val keys = relays.discover { searches++; listOf(Adverts.signed(1, at = clock() - 60), Adverts.signed(2, at = clock() - 60)) }.map { it.key }
            relays.select(keys[0])
            return keys
        }
    }

    @Test fun theCallLogsInAtJoinAndRenewsBeforeTheLoginEnds() = runTest {
        val h = Harness(this)
        h.ready()
        val call = h.call()
        assertTrue(call.start())
        assertEquals(1, h.applied.size)
        val first = h.applied.single()!!
        // Not before ten minutes plus at most 30 s of jitter ahead of the end.
        advanceTimeBy((3600 - 600 - 1) * 1000L)
        runCurrent()
        assertEquals(1, h.applied.size)
        advanceTimeBy(31_000)
        runCurrent()
        assertEquals(2, h.applied.size)
        assertTrue(h.applied.last()!!.expiresAt > first.expiresAt)
        assertTrue(h.notices.isEmpty())
        call.end()
    }

    @Test fun aRenewedLoginIsRenewedAgainWhileItsAdvertIsOpen() = runTest {
        val h = Harness(this)
        h.ready()
        val call = h.call()
        assertTrue(call.start())
        // Renewed within the advert's hour: the next renewal is scheduled from the new login.
        advanceTimeBy((3600 - 600 + 31) * 1000L)
        runCurrent()
        assertEquals(2, h.applied.size)
        assertTrue(h.notices.isEmpty())
        // The second falls after the advert ends; no query is made mid-call, so it is shown as failed.
        advanceTimeBy((3600 - 600 + 31) * 1000L)
        runCurrent()
        assertEquals(2, h.applied.size)
        assertEquals(listOf(PublicRelayNotice.RENEW_FAILED), h.notices)
        assertEquals(1, h.searches)
        call.end()
    }

    @Test fun aBusyRenewalIsRetriedBeforeTheDeadline() = runTest {
        val h = Harness(this)
        h.ready()
        val call = h.call()
        assertTrue(call.start())
        h.answers += { PublicRelayLoginResult.Busy }
        h.answers += { PublicRelayLoginResult.Busy }
        advanceTimeBy((3600 - 600 + 31) * 1000L)
        runCurrent()
        assertEquals(1, h.applied.size)
        advanceTimeBy(10_000 + 20_000 + 1)
        runCurrent()
        assertEquals(2, h.applied.size)
        assertTrue(h.notices.isEmpty())
        call.end()
    }

    @Test fun aFailedRenewalIsShownAndTheRelayIsLostWhenTheLoginEnds() = runTest {
        for (relayOnly in listOf(false, true)) {
            val h = Harness(this)
            h.ready()
            val call = h.call(relayOnly)
            assertTrue(call.start())
            h.answers += { PublicRelayLoginResult.Failed }
            advanceTimeBy((3600 - 600 + 31) * 1000L)
            runCurrent()
            assertEquals(listOf(PublicRelayNotice.RENEW_FAILED), h.notices)
            advanceTimeBy(600 * 1000L)
            runCurrent()
            assertNull(h.applied.last())
            assertEquals(if (relayOnly) PublicRelayNotice.LOST_RELAY_ONLY else PublicRelayNotice.LOST, h.notices.last())
            assertFalse(call.active)
            call.end()
        }
    }

    @Test fun busyRetriesStopBeforeTheDeadline() = runTest {
        val h = Harness(this)
        h.ready()
        val call = h.call()
        assertTrue(call.start())
        repeat(20) { h.answers += { PublicRelayLoginResult.Busy } }
        advanceTimeBy(3600 * 1000L)
        runCurrent()
        assertEquals(PublicRelayNotice.RENEW_FAILED, h.notices.first())
        // 10, 20, 40, 80, 160 s waits fit in the 600 s window; the next would not.
        assertTrue(h.fetches in 2..8, "fetches=${h.fetches}")
        call.end()
    }

    @Test fun nothingRenewsAfterTheCallEnds() = runTest {
        val h = Harness(this)
        h.ready()
        val call = h.call()
        assertTrue(call.start())
        call.end()
        advanceTimeBy(4000 * 1000L)
        runCurrent()
        assertEquals(1, h.applied.size)
        assertEquals(1, h.fetches)
        assertTrue(h.notices.isEmpty())
    }

    @Test fun startFailureIsShownAndRelayOnlySaysCallRelayLost() = runTest {
        for (relayOnly in listOf(false, true)) {
            val h = Harness(this)
            h.ready()
            repeat(3) { h.answers += { PublicRelayLoginResult.Failed } }
            assertFalse(h.call(relayOnly).start())
            assertEquals(listOf(if (relayOnly) PublicRelayNotice.START_FAILED_RELAY_ONLY else PublicRelayNotice.START_FAILED), h.notices)
            if (relayOnly) assertTrue(h.notices.single().text.startsWith("Call relay lost"))
            assertTrue(h.applied.isEmpty())
        }
    }

    @Test fun aSupersededStartAppliesNothingAndSaysNothing() = runTest {
        val h = Harness(this)
        h.ready()
        h.superseded = true
        assertFalse(h.call().start())
        assertTrue(h.applied.isEmpty())
        assertTrue(h.notices.isEmpty())
        assertEquals(0, h.fetches)
    }

    @Test fun theChecksLoginIsReusedForTheCall() = runTest {
        val h = Harness(this)
        val keys = h.ready()
        assertIs<PublicRelayLoginResult.Issued>(h.relays.login(keys[0]))
        assertTrue(h.call().start())
        assertEquals(1, h.fetches)
    }

    @Test fun aChosenRelayNotInHandCostsOneQueryAtStart() = runTest {
        val h = Harness(this)
        h.relays.setEnabled(true)
        val key = h.relays.discover { listOf(Adverts.signed(1)) }.single().key
        h.relays.markGood(key)
        // A fresh run: the last good relay is known, its advert is not.
        val restarted = PublicCallRelays(PublicCallRelayStore(MemoryStorage(PublicCallRelayStateCodec.encode(PublicCallRelayState(PublicCallRelayLastGood(key, "relay1.example.org"))))),
            { Adverts.issued(it, h.clock() + 3600) }, h.clock, h.io)
        restarted.load()
        var searched = 0
        val call = PublicRelayCall(restarted, backgroundScope, { searched++; listOf(Adverts.signed(1)) }, { true }, {}, false, { false }, h.clock, Random(1))
        assertTrue(call.start())
        assertEquals(1, searched)
        call.end()
    }
}
