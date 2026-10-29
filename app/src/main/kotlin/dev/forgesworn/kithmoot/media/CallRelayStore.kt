package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage

/** Keystore-encrypted, excluded from backups. Call only on an IO dispatcher. */
internal class CallRelayStore(context: Context) {
    private val storage = EncryptedRoomStorage(context, "kithmoot.call-relay.v1", 16_384)
    fun read(): CallRelayConfig? = storage.read()?.let(CallRelayConfig::decode)
    fun save(value: CallRelayConfig?) {
        if (value == null) storage.reset() else storage.write(value.encode())
        _saves.value++
    }

    companion object {
        private val _saves = kotlinx.coroutines.flow.MutableStateFlow(0)
        /** Bumped on every save or removal in this process, so what depends on
         *  whether a relay is set (the room's Spry share row) can look again. */
        val saves: kotlinx.coroutines.flow.StateFlow<Int> = _saves
    }
}
