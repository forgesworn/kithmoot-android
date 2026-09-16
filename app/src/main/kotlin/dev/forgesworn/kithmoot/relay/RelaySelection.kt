package dev.forgesworn.kithmoot.relay

import kotlinx.serialization.json.*
import java.net.URI

data class RelayChoice(val url: String, val read: Boolean = true, val write: Boolean = true)
data class RelayHealth(val connection: String = "Not connected", val read: String = "Not checked", val write: String = "Not checked")

/** A successful bookmark read must not hide a refused project read on the same relay. */
fun combinedRelayHealth(sources: Collection<Map<String, RelayHealth>>): Map<String, RelayHealth> =
    sources.flatMap { it.keys }.distinct().associateWith { url ->
        val values = sources.mapNotNull { it[url] }
        fun evidence(values: List<String>): String = values.firstOrNull {
            it.contains("refused", true) || it.contains("authentication", true) || it.contains("No acknowledgement")
        } ?: values.firstOrNull { it != "Not checked" } ?: "Not checked"
        val connected = values.any { it.connection == "Connected" }
        val retrying = values.any { it.connection.contains("retry", true) || it.connection == "Reconnecting" }
        RelayHealth(if (connected && retrying) "Some connections are retrying" else if (connected) "Connected"
            else values.firstOrNull { it.connection != "Not connected" }?.connection ?: "Not connected",
            evidence(values.map { it.read }), evidence(values.map { it.write }))
    }

object RelaySelection {
    fun validate(choices: List<RelayChoice>): List<RelayChoice> {
        require(choices.size in 1..16) { "Choose between 1 and 16 relays." }
        val clean = choices.map { choice ->
            val url = choice.url.trim(); val uri = URI(url)
            require(uri.scheme in setOf("wss", "ws") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) { "Use a valid wss:// relay address." }
            require(uri.scheme == "wss" || uri.host in setOf("localhost", "127.0.0.1", "[::1]") || uri.host.endsWith(".onion")) { "Public relays need wss://." }
            require(url.length <= 2048 && uri.port in -1..65535) { "Invalid relay address." }
            choice.copy(url = uri.toString().removeSuffix("/"))
        }
        require(clean.map { it.url }.distinct().size == clean.size) { "Each relay should appear only once." }
        require(clean.any { it.read } && clean.any { it.write }) { "Keep at least one read relay and one write relay." }
        return clean
    }
    fun encode(choices: List<RelayChoice>) = JsonArray(choices.map {
        buildJsonObject { put("url", it.url); put("read", it.read); put("write", it.write) }
    }).toString()
    fun decode(raw: String): List<RelayChoice> = validate(Json.parseToJsonElement(raw).jsonArray.map {
        val obj = it.jsonObject
        RelayChoice(obj.getValue("url").jsonPrimitive.content, obj.getValue("read").jsonPrimitive.boolean, obj.getValue("write").jsonPrimitive.boolean)
    })
    fun tags(choices: List<RelayChoice>): List<List<String>> = validate(choices).filter { it.read || it.write }.map {
        listOf("r", it.url) + if (it.read && it.write) emptyList() else listOf(if (it.read) "read" else "write")
    }
}
