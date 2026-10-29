package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.DeliveredLedger
import dev.forgesworn.kithmoot.session.DeliveryReceiptWriter
import dev.forgesworn.kithmoot.session.DeliveryTicks

/**
 * Device-encrypted, no-backup delivery state for one saved room, bound to the
 * room and its exact account and device, as `PendingChatVault` is:
 *
 * - [storage]: whether the relays kept this device's own messages, the one tick (`session/DeliveryState.kt`);
 * - [acks]: which messages this device has confirmed with a delivery receipt, and when it first saw the
 *   room (`session/DeliveryReceiptWriter.kt`);
 * - [delivered]: who has confirmed this person's own messages, the two ticks (`session/DeliveredLedger.kt`).
 *
 * Each file has its own key alias and its own associated data, so none can be opened as another.
 */
class DeliveryVault(context: Context, roomId: String, participant: String, device: String) {
    private val scope = Digests.sha256("$roomId:$participant:$device".toByteArray(Charsets.UTF_8)).toHex()

    val storage: RoomStorage = EncryptedRoomStorage(context, "kithmoot.delivery.$scope", DeliveryTicks.MAX_FILE_BYTES, AAD)
    val acks: RoomStorage = EncryptedRoomStorage(context, "kithmoot.delivery-acks.$scope", DeliveryReceiptWriter.MAX_FILE_BYTES, ACKS_AAD)
    val delivered: RoomStorage = EncryptedRoomStorage(context, "kithmoot.delivered.$scope", DeliveredLedger.MAX_FILE_BYTES, DELIVERED_AAD)

    /** Every file, each on its own: one that cannot be cleared does not keep the others. Throws after trying all three. */
    fun forget() {
        var failure: Exception? = null
        for (file in listOf(storage, acks, delivered)) {
            try { file.reset() } catch (e: Exception) { if (failure == null) failure = e }
        }
        failure?.let { throw it }
    }

    private companion object {
        val AAD = "KithMoot delivery v1".toByteArray(Charsets.UTF_8)
        val ACKS_AAD = "KithMoot delivery acks v1".toByteArray(Charsets.UTF_8)
        val DELIVERED_AAD = "KithMoot delivered v1".toByteArray(Charsets.UTF_8)
    }
}
