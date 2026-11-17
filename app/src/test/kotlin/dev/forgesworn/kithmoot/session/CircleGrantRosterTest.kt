package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CircleGrantRosterTest {
    @Test fun `only fresh verified devices whose latest entry is present receive grants`() {
        val now = 1_900_000_000L
        val room = "a".repeat(64)
        val key = Entropy.bytes(32)
        val participantKey = Entropy.bytes(32)
        val participant = Schnorr.publicKeyHex(participantKey)
        val leftDevice = Entropy.bytes(32)
        val presentDevice = Entropy.bytes(32)
        fun event(deviceKey: ByteArray, at: Long, gone: Boolean = false) = encodeRosterEvent(
            RosterEntry(
                participant,
                Schnorr.publicKeyHex(deviceKey),
                createDeviceCredential(participantKey, Schnorr.publicKeyHex(deviceKey), room, now + 600, now - 10),
                updatedAt = at,
                left = gone,
            ),
            room, key, deviceKey,
        )
        val events = listOf(
            event(leftDevice, now - 2),
            event(leftDevice, now - 1, gone = true),
            event(presentDevice, now - 76),
            event(presentDevice, now),
        )

        assertEquals(
            listOf(participant to Schnorr.publicKeyHex(presentDevice)),
            currentCircleGuestDevices(events, room, key, participant, now, 75),
        )
    }

    @Test fun `empty replay followed by separate live devices yields every current guest device`() = runTest {
        val now = 1_900_000_000L
        val room = "a".repeat(64)
        val key = Entropy.bytes(32)
        val participantKey = Entropy.bytes(32)
        val participant = Schnorr.publicKeyHex(participantKey)
        val firstDevice = Entropy.bytes(32)
        val secondDevice = Entropy.bytes(32)
        fun event(deviceKey: ByteArray) = encodeRosterEvent(
            RosterEntry(
                participant,
                Schnorr.publicKeyHex(deviceKey),
                createDeviceCredential(participantKey, Schnorr.publicKeyHex(deviceKey), room, now + 600, now - 10),
                updatedAt = now,
            ),
            room, key, deviceKey,
        )
        val url = "wss://one.example"
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf(url), sockets, backgroundScope,
            now = { now * 1_000 + currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.opened.single().open()
        runCurrent()
        try {
            val result = async {
                observeCircleGuestDevices(pool, room, key, participant, { now + currentTime / 1_000 }, 75,
                    timeoutMs = 5_000, settleMs = 100)
            }
            runCurrent()
            val socket = sockets.opened.single()
            val subscription = socket.requestedSubscriptions().single()
            socket.deliverRaw("[\"EOSE\",\"$subscription\"]")
            runCurrent()
            socket.deliverEvent(subscription, event(firstDevice))
            runCurrent()
            advanceTimeBy(50)
            socket.deliverEvent(subscription, event(secondDevice))
            runCurrent()
            advanceTimeBy(101)
            runCurrent()

            assertEquals(
                listOf(participant to Schnorr.publicKeyHex(firstDevice), participant to Schnorr.publicKeyHex(secondDevice))
                    .sortedBy { it.second },
                result.await(),
            )
            assertTrue(socket.sent.contains("[\"CLOSE\",\"$subscription\"]"))
        } finally {
            pool.stop()
        }
    }

    @Test fun `live roster discovery refuses stale invalid wrong room and wrong person entries`() = runTest {
        val now = 1_900_000_000L
        val room = "a".repeat(64)
        val otherRoom = "b".repeat(64)
        val key = Entropy.bytes(32)
        val participantKey = Entropy.bytes(32)
        val otherParticipantKey = Entropy.bytes(32)
        val participant = Schnorr.publicKeyHex(participantKey)
        fun event(personKey: ByteArray, deviceKey: ByteArray, scope: String, at: Long) = encodeRosterEvent(
            RosterEntry(
                Schnorr.publicKeyHex(personKey),
                Schnorr.publicKeyHex(deviceKey),
                createDeviceCredential(personKey, Schnorr.publicKeyHex(deviceKey), scope, now + 600, now - 80),
                updatedAt = at,
            ),
            scope, key, deviceKey,
        )
        val url = "wss://one.example"
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf(url), sockets, backgroundScope,
            now = { now * 1_000 + currentTime }, random = Random(1))
        pool.start()
        runCurrent()
        sockets.opened.single().open()
        runCurrent()
        try {
            val result = async {
                observeCircleGuestDevices(pool, room, key, participant, { now + currentTime / 1_000 }, 75,
                    timeoutMs = 500, settleMs = 100)
            }
            runCurrent()
            val socket = sockets.opened.single()
            val subscription = socket.requestedSubscriptions().single()
            socket.deliverRaw("[\"EOSE\",\"$subscription\"]")
            socket.deliverEvent(subscription, event(participantKey, Entropy.bytes(32), room, now - 76))
            socket.deliverEvent(subscription, event(participantKey, Entropy.bytes(32), otherRoom, now))
            socket.deliverEvent(subscription, event(otherParticipantKey, Entropy.bytes(32), room, now))
            socket.deliverEvent(subscription, event(participantKey, Entropy.bytes(32), room, now).copy(sig = "00".repeat(64)))
            runCurrent()
            advanceTimeBy(501)
            runCurrent()

            assertEquals(emptyList(), result.await())
            assertTrue(socket.sent.contains("[\"CLOSE\",\"$subscription\"]"))
        } finally {
            pool.stop()
        }
    }
}
