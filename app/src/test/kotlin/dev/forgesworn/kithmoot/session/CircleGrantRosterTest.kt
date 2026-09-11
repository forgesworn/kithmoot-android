package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
