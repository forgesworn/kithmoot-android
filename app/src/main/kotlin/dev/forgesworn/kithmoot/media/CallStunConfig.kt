package dev.forgesworn.kithmoot.media

import android.content.Context
import dev.forgesworn.kithmoot.storage.EncryptedRoomStorage
import java.util.Locale

/**
 * The STUN servers a person chose for calls on this device. Explicit device
 * configuration, like [CallRelayConfig]: never copied into a room, invitation
 * or log. Plain `stun:` only, as the web's "Your STUN server" (kithmoot #544):
 * Safari refuses `stuns:`, so a setting means the same on every app.
 */
internal class CallStunConfig private constructor(val urls: List<String>) {
    fun encode(): ByteArray = urls.joinToString("\n").toByteArray(Charsets.UTF_8)

    companion object {
        const val MAX_SERVERS = 4
        const val FORMAT_HELP = "Use stun:host:3478, separated by commas, or leave it empty to use the default."
        private val address = Regex("^(stun):(\\[[0-9a-f:.]+\\]|[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::([0-9]{1,5}))?$", RegexOption.IGNORE_CASE)

        /** Null for an empty field, which means the default. */
        fun parse(addresses: String): CallStunConfig? {
            val urls = addresses.split(',', '\n').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty).distinct()
            if (urls.isEmpty()) return null
            require(urls.size <= MAX_SERVERS && urls.all(::isStunUrl)) { FORMAT_HELP }
            return CallStunConfig(urls)
        }

        fun isStunUrl(url: String): Boolean {
            val match = address.matchEntire(url) ?: return false
            val port = match.groupValues[3]
            return url.length <= 256 && (port.isEmpty() || port.toInt() in 1..65535)
        }

        fun decode(bytes: ByteArray): CallStunConfig {
            try {
                require(bytes.size <= 4096)
                return requireNotNull(parse(bytes.toString(Charsets.UTF_8)))
            } catch (_: Exception) {
                throw IllegalArgumentException(UNREADABLE)
            }
        }

        const val UNREADABLE = "Your saved STUN server could not be read. Save it again or remove it in Settings, Connections, then reopen the room."
    }
}

/** Keystore-encrypted, excluded from backups. Call only on an IO dispatcher. */
internal class CallStunStore(context: Context) {
    private val storage = EncryptedRoomStorage(context, "kithmoot.call-stun.v1", 4096)
    fun read(): CallStunConfig? = storage.read()?.let(CallStunConfig::decode)
    fun save(value: CallStunConfig?) {
        if (value == null) storage.reset() else storage.write(value.encode())
    }
}
