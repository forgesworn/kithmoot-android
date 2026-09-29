package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.BackgroundInbox

/** Device-encrypted, no-backup background receipts bound to the saved room and its exact account/device. */
class BackgroundInboxVault(context: Context, roomId: String, participant: String, device: String) {
    private val storage = EncryptedRoomStorage(context,
        "kithmoot.background-inbox." + Digests.sha256("$roomId:$participant:$device".toByteArray(Charsets.UTF_8)).toHex(),
        BackgroundInbox.MAX_BYTES)
    val inbox = BackgroundInbox(storage, roomId, participant, device)
}
