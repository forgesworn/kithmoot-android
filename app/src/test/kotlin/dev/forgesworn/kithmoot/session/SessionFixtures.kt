package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Room
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlin.random.Random

/** Shared setup for the session tests. Keys are fixed so failures are reproducible. */
object Fixtures {

    const val CREDENTIAL_EXPIRY: Long = 100_000

    /** A quiet room: no heartbeat, no sweep, so a test counts only the traffic it caused. */
    val QUIET = SessionTiming(heartbeatIntervalMs = 600_000, sweepIntervalMs = 600_000)

    fun room(seed: Byte = 7): Room = deriveRoom(ByteArray(32) { seed })

    fun key(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }

    fun primary(room: Room, participantSeed: Int, deviceSeed: Int): PrimaryIdentity =
        PrimaryIdentity.create(
            roomId = room.roomId,
            expiresAt = CREDENTIAL_EXPIRY,
            createdAt = 0,
            participantSecretKey = key(participantSeed),
            deviceSecretKey = key(deviceSeed),
        )

    /**
     * A second device for a person who already has one. The participant key is
     * used to sign the credential and then goes nowhere near the new device.
     */
    fun secondary(room: Room, owner: PrimaryIdentity, deviceSeed: Int): SecondaryIdentity {
        val deviceSecretKey = key(deviceSeed)
        val credential = owner.enrol(
            devicePubkey = Schnorr.publicKeyHex(deviceSecretKey),
            roomId = room.roomId,
            expiresAt = CREDENTIAL_EXPIRY,
            createdAt = 0,
        )
        return SecondaryIdentity.adopt(credential, deviceSecretKey, room.roomId, now = 0)
            ?: error("the credential we just minted should be adoptable")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.session(
    room: Room,
    identity: RoomIdentity,
    relay: FakeRelay,
    timing: SessionTiming = Fixtures.QUIET,
    seed: Int = 7,
): RoomSession = RoomSession(
    room = room,
    identity = identity,
    transport = relay.transport(),
    scope = backgroundScope,
    timing = timing,
    now = { currentTime / 1000 },
    random = Random(seed),
)
