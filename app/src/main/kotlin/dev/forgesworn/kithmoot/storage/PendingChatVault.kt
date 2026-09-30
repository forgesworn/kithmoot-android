package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.PendingChatOutbox

/** Device-encrypted, no-backup journal bound to the saved room and its exact account/device. */
class PendingChatVault(context: Context, roomId: String, participant: String, device: String) {
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.pending-chat." + Digests.sha256("$roomId:$participant:$device".toByteArray(Charsets.UTF_8)).toHex(),
        64 * 1024)
    val outbox = PendingChatOutbox(storage, roomId, participant, device)
}
