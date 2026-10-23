package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.GrantReceiver
import kotlinx.serialization.json.Json

/**
 * This device's group storage grants for one room (S5b), device-encrypted
 * and never backed up. A grant is a permission the keeper signed, not a
 * secret, but it is this device's way in, so it lives under the room and goes
 * with it. What is read back is checked again event by event
 * (`GrantReceiver.restore`): a row altered on the device is dropped.
 */
class StorageGrantVault(context: Context, roomId: String, device: String) {
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.storage-grants." + Digests.sha256("$roomId:$device".toByteArray(Charsets.UTF_8)).toHex(), MAX_BYTES)

    fun load(authority: String, roomId: String, device: String): GrantReceiver {
        val bytes = runCatching { storage.read() }.getOrNull() ?: return GrantReceiver(authority, roomId, device)
        return try {
            GrantReceiver.restore(authority, roomId, device, Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)))
        } catch (_: Exception) { GrantReceiver(authority, roomId, device) } finally { bytes.fill(0) }
    }

    /** False when it could not be kept: the grants still apply for this visit. A receiver holding nothing leaves nothing. */
    fun save(receiver: GrantReceiver): Boolean = runCatching {
        val snapshot = synchronized(receiver) { if (receiver.isEmpty) null else receiver.snapshot().toString() }
        if (snapshot == null) storage.reset() else snapshot.toByteArray(Charsets.UTF_8).let { try { storage.write(it) } finally { it.fill(0) } }
    }.isSuccess

    fun forget() { runCatching { storage.reset() } }

    companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}
