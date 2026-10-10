package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.BackgroundInbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Device-encrypted, no-backup background receipts bound to the saved room and its exact account/device. */
class BackgroundInboxVault(context: Context, roomId: String, participant: String, device: String) {
    private val encrypted = EncryptedRoomStorage(context,
        "kithmoot.background-inbox." + Digests.sha256("$roomId:$participant:$device".toByteArray(Charsets.UTF_8)).toHex(),
        BackgroundInbox.MAX_BYTES)
    private val storage = object : RoomStorage {
        override fun read() = encrypted.read()
        override fun write(value: ByteArray) { encrypted.write(value); changes.update { it + 1 } }
        override fun reset() { encrypted.reset(); changes.update { it + 1 } }
    }
    val inbox = BackgroundInbox(storage, roomId, participant, device)

    companion object {
        private val changes = MutableStateFlow(0L)
        /** Local writes only, without account/room identifiers or message data. */
        val revision = changes.asStateFlow()
    }
}
