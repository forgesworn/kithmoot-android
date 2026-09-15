package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.RendezvousProvisionEnvelopeResult
import dev.forgesworn.kithmoot.protocol.RendezvousProvisionExpect
import dev.forgesworn.kithmoot.protocol.RendezvousProvisionResult
import dev.forgesworn.kithmoot.protocol.readRendezvousProvision
import dev.forgesworn.kithmoot.protocol.readRendezvousProvisionEnvelope
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.storage.RoomStorageException
import java.nio.ByteBuffer

/** Public receipt fields only.  In particular, this never renders a scalar. */
data class RendezvousReceipt(
    val identity: String,
    val device: String,
    val rendezvousPubkey: String,
    val index: Long,
    val expiresAt: Long,
)

/**
 * An active root-derived rendezvous child.  It is intentionally neither a
 * Nostr account key nor room/contact state.  A caller gets a copy only at the
 * point it has to derive one of Vennel's permitted pairwise materials.
 */
class StoredRendezvousChild internal constructor(
    val receipt: RendezvousReceipt,
    private val secret: ByteArray,
) {
    init { require(secret.size == SCALAR_BYTES) }

    fun copyScalar(): ByteArray = secret.copyOf()
    override fun toString(): String = "StoredRendezvousChild(${receipt.rendezvousPubkey.take(12)}…, index=${receipt.index})"

    internal fun copyForUse() = StoredRendezvousChild(receipt, secret.copyOf())
    internal fun wipe() = secret.fill(0)

    private companion object { const val SCALAR_BYTES = 32 }
}

sealed class RendezvousVaultResult {
    data class Accepted(val receipt: RendezvousReceipt) : RendezvousVaultResult()
    data class Refused(val reason: String) : RendezvousVaultResult()
}

/**
 * Dedicated encrypted device vault for the Vennel rendezvous child.
 *
 * `storage` must be a platform-protected store (the application wires this to
 * Android Keystore-backed [EncryptedRoomStorage]).  This class accepts only
 * Heartwood's encrypted response wrapper: callers cannot put a raw child or
 * generic contact secret into it.  One installed KithMoot instance holds one
 * account; a new index replaces the old child only after every binding and the
 * scalar/public-key relationship have been checked.
 */
class RendezvousVault(private val storage: RoomStorage) {
    /**
     * Open Heartwood's ciphertext with this device's retained NIP-46 client
     * key, validate both wrapper and canonical inner record, then commit the
     * verified child.  `deviceSecretKey` is never retained by this vault.
     */
    @Synchronized fun accept(
        response: String,
        expect: RendezvousProvisionExpect,
        deviceSecretKey: ByteArray,
    ): RendezvousVaultResult = guarded {
        if (deviceSecretKey.size != 32) return@guarded RendezvousVaultResult.Refused("device key")
        val envelope = when (val parsed = readRendezvousProvisionEnvelope(response, expect)) {
            is RendezvousProvisionEnvelopeResult.Refused -> return@guarded RendezvousVaultResult.Refused(parsed.reason)
            is RendezvousProvisionEnvelopeResult.Accepted -> parsed.envelope
        }
        val conversation = runCatching {
            Nip44.conversationKey(deviceSecretKey, envelope.rendezvousPubkey.hexToBytes())
        }.getOrElse { return@guarded RendezvousVaultResult.Refused("ciphertext") }
        val plaintext = try {
            runCatching { Nip44.decrypt(envelope.ciphertext, conversation) }.getOrElse {
                return@guarded RendezvousVaultResult.Refused("ciphertext")
            }
        } finally {
            conversation.fill(0)
        }
        acceptPlaintext(plaintext, expect, envelope.rendezvousPubkey)
    }

    /** The returned scalar is copied; callers must wipe it after permitted derivation. */
    @Synchronized fun active(identity: String, device: String): StoredRendezvousChild? = guarded {
        val child = read() ?: return@guarded null
        try {
            child.takeIf { it.receipt.identity == identity && it.receipt.device == device }?.copyForUse()
        } finally {
            child.wipe()
        }
    }

    /** Sign-out/revocation erases the child's dedicated vault entry. */
    @Synchronized fun clear(identity: String) = guarded {
        val child = read() ?: return@guarded
        try {
            if (child.receipt.identity == identity) storage.reset()
        } finally {
            child.wipe()
        }
    }

    private fun acceptPlaintext(
        plaintext: String,
        expect: RendezvousProvisionExpect,
        outerRendezvousPubkey: String,
    ): RendezvousVaultResult {
        val parsed = readRendezvousProvision(plaintext, expect)
        val provision = when (parsed) {
            is RendezvousProvisionResult.Refused -> return RendezvousVaultResult.Refused(parsed.reason)
            is RendezvousProvisionResult.Accepted -> parsed.provision
        }
        try {
            val rendezvousPubkey = Schnorr.publicKeyHex(provision.scalar)
            if (rendezvousPubkey != outerRendezvousPubkey) return RendezvousVaultResult.Refused("rendezvous key")
            val receipt = RendezvousReceipt(expect.identity, expect.device, rendezvousPubkey, provision.index, provision.expiresAt)
            val previous = read()
            try {
                if (previous != null && (previous.receipt.identity != expect.identity || previous.receipt.device != expect.device)) {
                    return RendezvousVaultResult.Refused("account")
                }
                if (previous != null && provision.index <= previous.receipt.index) return RendezvousVaultResult.Refused("index")
                val child = StoredRendezvousChild(receipt, provision.scalar.copyOf())
                try { write(child) } finally { child.wipe() }
                return RendezvousVaultResult.Accepted(receipt)
            } finally {
                previous?.wipe()
            }
        } finally {
            provision.wipe()
        }
    }

    private fun read(): StoredRendezvousChild? {
        val bytes = storage.read() ?: return null
        try {
            require(bytes.size == RECORD_BYTES)
            val buffer = ByteBuffer.wrap(bytes)
            require(buffer.get().toInt() == VERSION)
            val identity = ByteArray(32).also(buffer::get).toHex()
            val device = ByteArray(32).also(buffer::get).toHex()
            val rendezvous = ByteArray(32).also(buffer::get).toHex()
            val index = buffer.int.toUInt().toLong()
            val expiresAt = buffer.long
            val scalar = ByteArray(32).also(buffer::get)
            require(expiresAt >= 0 && Schnorr.publicKeyHex(scalar) == rendezvous)
            return StoredRendezvousChild(RendezvousReceipt(identity, device, rendezvous, index, expiresAt), scalar)
        } finally {
            bytes.fill(0)
        }
    }

    private fun write(child: StoredRendezvousChild) {
        val scalar = child.copyScalar()
        val bytes = ByteBuffer.allocate(RECORD_BYTES).apply {
            put(VERSION.toByte())
            put(child.receipt.identity.hexToBytes())
            put(child.receipt.device.hexToBytes())
            put(child.receipt.rendezvousPubkey.hexToBytes())
            putInt(child.receipt.index.toInt())
            putLong(child.receipt.expiresAt)
            put(scalar)
        }.array()
        try {
            storage.write(bytes)
        } finally {
            scalar.fill(0)
            bytes.fill(0)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is RoomStorageException) throw error
        throw RoomStorageException(error)
    }

    private companion object {
        const val VERSION = 1
        const val RECORD_BYTES = 1 + 32 + 32 + 32 + 4 + 8 + 32
    }
}
