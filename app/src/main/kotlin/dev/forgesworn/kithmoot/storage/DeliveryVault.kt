package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.DeliveryTicks

/** Device-encrypted, no-backup verdicts on this device's own messages (`session/DeliveryState.kt`),
 *  bound to the saved room and its exact account and device, as `PendingChatVault` is. */
class DeliveryVault(context: Context, roomId: String, participant: String, device: String) {
    val storage: RoomStorage = EncryptedRoomStorage(context,
        "kithmoot.delivery." + Digests.sha256("$roomId:$participant:$device".toByteArray(Charsets.UTF_8)).toHex(),
        DeliveryTicks.MAX_FILE_BYTES, AAD)

    fun forget() = storage.reset()

    private companion object {
        val AAD = "KithMoot delivery v1".toByteArray(Charsets.UTF_8)
    }
}
