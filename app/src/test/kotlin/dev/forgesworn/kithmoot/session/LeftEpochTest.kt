package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_REQUEST
import dev.forgesworn.kithmoot.protocol.KIND_MEMBER_EPOCH_REQUEST
import dev.forgesworn.kithmoot.protocol.LeftEpoch
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeEpochRequest
import dev.forgesworn.kithmoot.protocol.decodeMemberEpochRequest
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An epoch is not forgotten the moment it is left, as in the web client
 * (`docs/decisions.md`, 3 October 2026): a message that lands late on the
 * epoch just left is still read, a removed member's is not, a member's grant
 * that carried this device over epochs lets it read them, and a jump over
 * epochs it holds no key for, or two rekeys for one epoch, is said rather
 * than passing silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LeftEpochTest {
    private val authoritySecret = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private val stable = Fixtures.room()
    private val me = Fixtures.primary(stable, 1, 2)
    private val epochs = (0..MAX_PAST_EPOCHS + 2).map { RoomEpoch(it, ByteArray(32) { b -> (if (it == 0) 7 else 70 + it + b % 2).toByte() }) }

    private fun keys(epoch: Int): EpochKeys = deriveEpoch(epochs[epoch])

    private fun rekey(into: Int, sealedTo: List<String> = listOf(me.devicePubkey), removed: List<String> = emptyList(), secret: RoomEpoch = epochs[into]) =
        encodeRekeyEvent(stable.roomId, authoritySecret, keys(into - 1), secret, sealedTo, removed, 0, commit = true)

    private fun chat(body: String, from: PrimaryIdentity, epoch: Int): NostrEvent = encodeChatEvent(
        body = body,
        participant = from.participant,
        credential = from.credential,
        roomId = keys(epoch).id,
        roomKey = keys(epoch).key,
        deviceSecretKey = from.deviceSecretKey,
        sentAt = 0,
        credentialRoomId = stable.roomId,
    )

    private fun TestScope.following(relay: FakeRelay, initial: Int = 0, expected: Int? = null, transport: RoomTransport = relay.transport()) = session(
        stable, me, relay, authority = authority, initialEpoch = keys(initial), expectedEpoch = expected,
        epochGate = { _, _ -> EpochGateResult.COMMITTED }, transport = transport,
    )

    @Test fun `completed replay joins immediately after applying its rekey`() = runTest {
        val relay = FakeRelay()
        val transport = object : RoomTransport by relay.transport() {
            override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit) = flow {
                emit(rekey(1))
                onReplayComplete()
                awaitCancellation()
            }
        }
        val live = session(stable, me, relay, authority = authority, epochSettleMs = 1_500,
            epochGate = { _, _ -> EpochGateResult.COMMITTED }, transport = transport)
        val joining = launch { live.join() }
        runCurrent()
        assertTrue(joining.isCompleted)
        assertEquals(1, live.epochKeys().epoch)
    }

    @Test fun `a transport without replay completion retains the settling delay`() = runTest {
        val relay = FakeRelay()
        val live = session(stable, me, relay, authority = authority, epochSettleMs = 1_500)
        val joining = launch { live.join() }
        runCurrent()
        assertTrue(joining.isActive)
        advanceTimeBy(1_499); runCurrent()
        assertTrue(joining.isActive)
        advanceTimeBy(1); runCurrent()
        assertTrue(joining.isCompleted)
    }

    @Test fun `in a quiet room a drop sealed under the epoch just left is still read, unless its sender was removed`() = runTest {
        val relay = FakeRelay()
        val lagging = Fixtures.primary(stable, 3, 4)
        val removed = Fixtures.primary(stable, 5, 6)
        val members = listOf(me.participant, lagging.participant, removed.participant)
        fun quietOn(who: PrimaryIdentity, epoch: Int) = QuietTransport(
            relay.transport(), keys(epoch).key, who.participant, members, 0, backgroundScope,
            intervalSeconds = 60, now = { currentTime / 1000 }, ticking = false, slotOffset = { 0 },
        )
        val live = following(relay, transport = quietOn(me, 0))
        live.join()
        runCurrent()
        relay.publish(rekey(1, removed = listOf(removed.participant)))
        runCurrent()
        assertEquals(1, live.epochKeys().epoch)

        for ((who, body) in listOf(lagging to "sent before my phone followed", removed to "let me back in")) {
            val theirs = quietOn(who, 0)
            theirs.publish(chat(body, who, 0))
            theirs.tick()
            theirs.stop()
        }
        runCurrent()

        assertEquals(listOf("sent before my phone followed"), live.chat.value.map { it.body })
    }

    @Test fun `a message that lands on the epoch just left is still read, unless its sender was removed`() = runTest {
        val relay = FakeRelay()
        val lagging = Fixtures.primary(stable, 3, 4)
        val removed = Fixtures.primary(stable, 5, 6)
        val live = following(relay)
        live.join()
        runCurrent()
        relay.publish(rekey(1, removed = listOf(removed.participant)))
        runCurrent()
        assertEquals(1, live.epochKeys().epoch)

        relay.publish(chat("sent before my phone followed", lagging, 0))
        relay.publish(chat("let me back in", removed, 0))
        relay.publish(chat("on the new key", lagging, 1))
        runCurrent()

        assertEquals(setOf("sent before my phone followed", "on the new key"), live.chat.value.map { it.body }.toSet())
        assertEquals(emptyList(), live.epochGaps.value)
        assertEquals(emptyList(), live.epochConflicts.value)
    }

    @Test fun `a room reopened after a rekey still reads the epoch it left, unless the sender was removed`() = runTest {
        val relay = FakeRelay()
        val lagging = Fixtures.primary(stable, 3, 4)
        val removed = Fixtures.primary(stable, 5, 6)
        // Opened at epoch 2, with epochs 1 and 0 rebuilt from the journal, as
        // RoomViewModel does from EpochVault through pastEpochsFor.
        val live = session(
            stable, me, relay, authority = authority, initialEpoch = keys(2),
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            initialPastEpochs = listOf(PastEpoch(keys(1), 0), PastEpoch(keys(0), 0)),
            initialRemoved = listOf(removed.participant.uppercase()),
        )
        live.join()
        runCurrent()

        relay.publish(chat("late on the epoch left", lagging, 1))
        relay.publish(chat("two epochs back", lagging, 0))
        relay.publish(chat("let me back in", removed, 1))
        relay.publish(chat("on the current key", lagging, 2))
        runCurrent()

        assertEquals(
            setOf("late on the epoch left", "two epochs back", "on the current key"),
            live.chat.value.map { it.body }.toSet(),
        )
    }

    @Test fun `a room reopened with left epochs hands their keys to its transport at join`() = runTest {
        val relay = FakeRelay()
        val handed = mutableListOf<List<ByteArray>>()
        val recording = object : dev.forgesworn.kithmoot.relay.RoomTransport by relay.transport() {
            override fun keepPast(roomKeys: List<ByteArray>) { handed += roomKeys }
        }
        val live = session(
            stable, me, relay, authority = authority, initialEpoch = keys(2), transport = recording,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            initialPastEpochs = listOf(PastEpoch(keys(0), 0), PastEpoch(keys(1), 0)),
        )
        live.join()
        runCurrent()

        assertEquals(1, handed.size)
        assertEquals(listOf(keys(1).key.toList(), keys(0).key.toList()), handed.single().map { it.toList() })
    }

    @Test fun `a room opened with no left epochs tells its transport nothing at join`() = runTest {
        val relay = FakeRelay()
        val handed = mutableListOf<List<ByteArray>>()
        val recording = object : dev.forgesworn.kithmoot.relay.RoomTransport by relay.transport() {
            override fun keepPast(roomKeys: List<ByteArray>) { handed += roomKeys }
        }
        val live = session(stable, me, relay, authority = authority, initialEpoch = keys(1), transport = recording,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        live.join()
        runCurrent()
        assertEquals(emptyList(), handed)
    }

    @Test fun `a seeded epoch at or above the one the room opens on is ignored`() = runTest {
        val relay = FakeRelay()
        val other = Fixtures.primary(stable, 3, 4)
        val live = session(
            stable, me, relay, authority = authority, initialEpoch = keys(1),
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            initialPastEpochs = listOf(PastEpoch(keys(2), 0)),
        )
        live.join()
        runCurrent()
        relay.publish(chat("not a left epoch", other, 2))
        runCurrent()
        assertEquals(emptyList(), live.chat.value.map { it.body })
    }

    @Test fun `only the last sixteen epochs left are read`() = runTest {
        val relay = FakeRelay()
        val other = Fixtures.primary(stable, 3, 4)
        val live = following(relay)
        live.join()
        runCurrent()
        for (epoch in 1..MAX_PAST_EPOCHS + 1) {
            relay.publish(rekey(epoch))
            runCurrent()
        }
        assertEquals(MAX_PAST_EPOCHS + 1, live.epochKeys().epoch)

        relay.publish(chat("too far back", other, 0))
        relay.publish(chat("sixteen epochs back", other, 1))
        runCurrent()

        assertEquals(listOf("sixteen epochs back"), live.chat.value.map { it.body })
    }

    @Test fun `of two rekeys for one epoch the first is followed and the second is said`() = runTest {
        val relay = FakeRelay()
        val live = following(relay)
        live.join()
        runCurrent()
        val kept = rekey(1)
        val other = rekey(1, secret = RoomEpoch(1, ByteArray(32) { 99 }))
        relay.publish(kept)
        runCurrent()
        relay.publish(other)
        // The same rekey again, as a second relay delivers it, is no conflict.
        relay.publish(kept)
        relay.publish(other)
        runCurrent()

        assertEquals(keys(1).id, live.epochKeys().id)
        assertEquals(listOf(EpochConflict(1, kept.id, other.id)), live.epochConflicts.value)
    }

    @Test fun `a jump over epochs this device holds no key for is a gap`() = runTest {
        val relay = FakeRelay()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST), tags = mapOf("#d" to listOf(stable.roomId)))))
                .collect { event ->
                    val request = decodeEpochRequest(event, stable.roomId, authoritySecret, stable.roomKey, currentTime / 1000) ?: return@collect
                    relay.publish(encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, epoch = epochs[3]))
                }
        }
        val live = following(relay, initial = 1, expected = 3)
        live.join()
        runCurrent()

        assertEquals(3, live.epochKeys().epoch)
        assertIs<RoomEpochState.Active>(live.epochState.value)
        val gap = live.epochGaps.value.single()
        assertEquals(1, gap.from)
        assertEquals(3, gap.to)
    }

    @Test fun `a member grant that carries this device over an epoch lets it read what was said there`() = runTest {
        val relay = FakeRelay().apply { replays = true }
        val member = Fixtures.primary(stable, 5, 6)
        val one = rekey(1, sealedTo = listOf(member.devicePubkey))
        val two = rekey(2, sealedTo = listOf(member.devicePubkey))
        relay.publish(one)
        relay.publish(chat("said at epoch 1", member, 1))
        relay.publish(two)
        var answered = false
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_MEMBER_EPOCH_REQUEST), tags = mapOf("#d" to listOf(stable.roomId)))))
                .collect { event ->
                    if (answered) return@collect
                    val request = decodeMemberEpochRequest(event, stable.roomId, authority, stable.roomKey, currentTime / 1000) ?: return@collect
                    answered = true
                    relay.publish(encodeMemberEpochGrant(stable.roomId, request.device, request.request, listOf(epochs[1], epochs[2]), listOf(one, two), currentTime / 1000))
                }
        }
        val live = following(relay, expected = 2)
        live.join()
        advanceTimeBy(3_001)
        runCurrent()

        assertTrue(answered, "a member answered")
        assertEquals(2, live.epochKeys().epoch)
        assertEquals(listOf("said at epoch 1"), live.chat.value.map { it.body })
        assertEquals(emptyList(), live.epochGaps.value)
    }

    @Test fun `an authority grant's passed epochs are read, and kept with when each was left`() = runTest {
        val relay = FakeRelay().apply { replays = true }
        val other = Fixtures.primary(stable, 3, 4)
        relay.publish(chat("said at epoch 2", other, 2))
        relay.publish(chat("said at epoch 4", other, 4))
        val passed = (1..4).map { LeftEpoch(it, epochs[it].secret, 100L * it) }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST), tags = mapOf("#d" to listOf(stable.roomId)))))
                .collect { event ->
                    val request = decodeEpochRequest(event, stable.roomId, authoritySecret, stable.roomKey, currentTime / 1000) ?: return@collect
                    relay.publish(encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, epoch = epochs[5], passed = passed))
                }
        }
        val history = mutableListOf<Pair<List<Int>, Map<Int, Long>>>()
        val live = session(
            stable, me, relay, authority = authority, expectedEpoch = 5,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            onEpochHistory = { secrets, _, leftAt -> if (secrets.isNotEmpty()) history += secrets.map { it.epoch } to leftAt },
        )
        live.join()
        runCurrent()

        assertEquals(5, live.epochKeys().epoch)
        assertEquals(setOf("said at epoch 2", "said at epoch 4"), live.chat.value.map { it.body }.toSet())
        // Every epoch between was handed over, so nothing was skipped.
        assertEquals(emptyList(), live.epochGaps.value)
        assertEquals(listOf(listOf(1, 2, 3, 4) to mapOf(1 to 100L, 2 to 200L, 3 to 300L, 4 to 400L)), history)
    }

    @Test fun `a newcomer's first jump from epoch 0 is not said as a gap, but a conflict always is`() {
        assertEquals(emptyList(), epochTroubleLines(listOf(EpochGap(0, 4, 10)), emptyList()))
        val lines = epochTroubleLines(listOf(EpochGap(2, 4, 10)), listOf(EpochConflict(3, "a", "b")))
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("This device was away"))
        assertTrue("(epoch 3)" in lines[1])
    }
}
