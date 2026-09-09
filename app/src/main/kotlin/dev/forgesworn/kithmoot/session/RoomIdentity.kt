package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.KIND_DEVICE_CREDENTIAL
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential

/**
 * Who this device is in one room.
 *
 * A participant is a person; a device is one of the things they are sitting in
 * front of. Every device signs for itself with its own key and carries a
 * credential, signed by the participant key, saying it may speak for that
 * person in that room.
 */
sealed interface RoomIdentity {
    /** The key this device signs its own events with. */
    val deviceSecretKey: ByteArray
    val devicePubkey: String

    /** The person. Everyone's devices group under this. */
    val participant: String

    /** The participant's signed statement that this device speaks for them here. */
    val credential: NostrEvent
}

/**
 * The device that can speak for the participant key: it holds the key, or it
 * holds the way to the signer that does. It is the only one that can enrol
 * another device, because enrolling means signing a credential.
 */
class PrimaryIdentity(
    /** Whoever signs as the person: a key held here, a signer app, a bunker. */
    val signer: ParticipantSigner,
    override val deviceSecretKey: ByteArray,
    override val credential: NostrEvent,
) : RoomIdentity {

    override val devicePubkey: String = Schnorr.publicKeyHex(deviceSecretKey)
    override val participant: String = signer.pubkey

    /** The participant key, when this device holds one; a signed-in account keeps its key elsewhere. */
    internal fun participantKeyForStorage(): ByteArray? = (signer as? LocalSigner)?.secretKeyForStorage()

    /**
     * Mints a credential for another of this person's devices, so it can join
     * the room as them without ever being handed the participant key. With a
     * remote signer this is a round trip the person may have to approve.
     */
    suspend fun enrol(
        devicePubkey: String,
        roomId: String,
        expiresAt: Long,
        createdAt: Long,
    ): NostrEvent = signer.sign(
        kind = KIND_DEVICE_CREDENTIAL,
        createdAt = createdAt,
        tags = listOf(listOf("d", roomId), listOf("device", devicePubkey), listOf("expiration", expiresAt.toString())),
        content = "",
    )

    companion object {
        /** Creates a participant key here and their first device in one go. */
        fun create(
            roomId: String,
            expiresAt: Long,
            createdAt: Long,
            participantSecretKey: ByteArray = Entropy.bytes(32),
            deviceSecretKey: ByteArray = Entropy.bytes(32),
        ): PrimaryIdentity = PrimaryIdentity(
            signer = LocalSigner(participantSecretKey),
            deviceSecretKey = deviceSecretKey,
            credential = createDeviceCredential(
                participantSecretKey = participantSecretKey,
                devicePubkey = Schnorr.publicKeyHex(deviceSecretKey),
                roomId = roomId,
                expiresAt = expiresAt,
                createdAt = createdAt,
            ),
        )

        /** A first device for a person whose key is with a signer: one signature, on the device credential. */
        suspend fun createWith(
            signer: ParticipantSigner,
            roomId: String,
            expiresAt: Long,
            createdAt: Long,
            deviceSecretKey: ByteArray = Entropy.bytes(32),
        ): PrimaryIdentity {
            val devicePubkey = Schnorr.publicKeyHex(deviceSecretKey)
            val credential = signer.sign(
                kind = KIND_DEVICE_CREDENTIAL,
                createdAt = createdAt,
                tags = listOf(listOf("d", roomId), listOf("device", devicePubkey), listOf("expiration", expiresAt.toString())),
                content = "",
            )
            val check = verifyDeviceCredential(credential, roomId, createdAt)
            require(check is CredentialCheck.Valid && check.device == devicePubkey && check.participant == signer.pubkey) {
                "The signer did not produce a usable credential"
            }
            return PrimaryIdentity(signer, deviceSecretKey, credential)
        }
    }
}

/**
 * A second device, working from a credential and its own key and **nothing
 * else**.
 *
 * There is deliberately no field here that could hold the participant key, and
 * no constructor that accepts one. A phone that joins a room as your second
 * screen is a phone you can lose; what it can do when lost is bounded by the
 * one room its credential names and the expiry it was given, and the worst case
 * never includes losing the identity itself.
 */
class SecondaryIdentity private constructor(
    override val deviceSecretKey: ByteArray,
    override val credential: NostrEvent,
    override val participant: String,
) : RoomIdentity {

    override val devicePubkey: String = Schnorr.publicKeyHex(deviceSecretKey)

    companion object {
        /**
         * Takes up a credential, or returns null if it does not actually
         * authorise this device in this room. The participant is read off the
         * credential's signer rather than taken on trust from the caller.
         */
        fun adopt(
            credential: NostrEvent,
            deviceSecretKey: ByteArray,
            roomId: String,
            now: Long,
        ): SecondaryIdentity? {
            val devicePubkey = Schnorr.publicKeyHex(deviceSecretKey)
            val check = verifyDeviceCredential(credential, roomId, now)
            if (check !is CredentialCheck.Valid) return null
            if (check.device != devicePubkey) return null
            return SecondaryIdentity(deviceSecretKey, credential, check.participant)
        }
    }
}
