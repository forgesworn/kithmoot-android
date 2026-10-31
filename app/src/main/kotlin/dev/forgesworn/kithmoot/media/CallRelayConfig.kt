package dev.forgesworn.kithmoot.media

import kotlinx.serialization.json.*
import java.util.Locale

/** Explicit device configuration. Never copied into a room, invitation or log. */
internal class CallRelayConfig private constructor(val urls: List<String>, val username: String, val credential: String) {
    override fun toString() = "CallRelayConfig(redacted)"

    fun encode(): ByteArray = buildJsonObject {
        put("urls", JsonArray(urls.map(::JsonPrimitive)))
        put("username", username)
        put("credential", credential)
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        private val address = Regex("^(turns?):(\\[[0-9a-f:.]+\\]|[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?)(?::([0-9]{1,5}))?(?:\\?transport=(udp|tcp))?$", RegexOption.IGNORE_CASE)

        fun parse(addresses: String, username: String, credential: String): CallRelayConfig? {
            val urls = addresses.split(',').map { it.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
            if (urls.isEmpty() && username.isEmpty() && credential.isEmpty()) return null
            require(urls.isNotEmpty() && urls.size <= 8 && username.isNotBlank() && username.length <= 1024 && credential.isNotEmpty() && credential.length <= 1024 &&
                (username + credential).none { it.code < 32 || it.code == 127 }) {
                "Enter a TURN address, username and password, or clear all three fields."
            }
            require(urls.all { url ->
                val match = address.matchEntire(url)
                match != null && url.length <= 512 &&
                    (match.groupValues[3].isEmpty() || match.groupValues[3].toInt() in 1..65535) &&
                    !(match.groupValues[1].equals("turns", true) && match.groupValues[4].equals("udp", true))
            }) { "Use turn:host:3478 or turns:host:5349, separated by commas. Put login details in their own fields." }
            return CallRelayConfig(urls, username, credential)
        }

        fun decode(bytes: ByteArray): CallRelayConfig {
            try {
                require(bytes.size <= 16_384)
                val value = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                val urls = value.getValue("urls").jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content }
                val username = value.getValue("username").jsonPrimitive.also { require(it.isString) }.content
                val credential = value.getValue("credential").jsonPrimitive.also { require(it.isString) }.content
                return requireNotNull(parse(urls.joinToString(","), username, credential))
            } catch (_: Exception) {
                // Do not retain a parser exception whose message can contain the password.
                throw IllegalArgumentException("The saved call relay could not be read. Open Settings › Calls › Relay server and save it again or remove it.")
            }
        }
    }
}
