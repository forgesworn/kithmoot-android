package dev.forgesworn.kithmoot.callrelay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal enum class RelayPhase { OFF, STARTING, RUNNING, STOPPING, PAUSED, CANNOT_HOST, FAILED }

/** Why the relay stopped by itself, in the desktop's words. */
internal enum class RelayFailure(val text: String) {
    START_FAILED("The relay couldn't start."),
    START_TIMEOUT("The relay didn't get ready in time."),
    LIBRARY("Part of KithMoot that runs the relay is missing or damaged."),
    CRASHED("The relay stopped unexpectedly. Starting it again…"),
    CRASHED_TWICE("The relay stopped unexpectedly twice, so it was left off."),
    STOP_TIMEOUT("The relay hasn't stopped yet. Press Stop again."),
}

internal data class RelayMapping(val method: String, val allocationPorts: Long, val requested: Long, val permanent: Boolean, val restored: Long)

/**
 * What the relay process tells the app, across the binder, as JSON. It never
 * carries the TURN secret, the pairing secret or the public address: the
 * endpoint and pairing id are what the node prints in `ready` for its host,
 * and [bind] with [listenPort] is the phone's own Wi-Fi listener, which this
 * device's own calls use in place of the router's public address.
 */
internal data class RelayState(
    val phase: RelayPhase = RelayPhase.OFF,
    /** The person left the relay on: it resumes when the gates pass. */
    val wanted: Boolean = false,
    val gate: RelayGate? = null,
    /** The node's reason word for a network that cannot host. */
    val reason: String? = null,
    val failure: RelayFailure? = null,
    /** The node's refusal line, which names a setting and never its value. */
    val refusal: String? = null,
    val endpoint: String? = null,
    val pairing: String? = null,
    val bind: String? = null,
    val listenPort: Int? = null,
    val allocations: Long = 0,
    val bytesRelayed: Long = 0,
    /** Whether any control relay is connected; null until the node says. */
    val controlConnected: Boolean? = null,
    val mapping: RelayMapping? = null,
    val todayBytes: Long = 0,
    val dailyLimit: Long = RelayCaps.DAILY_LIMIT_BYTES,
) {
    /** The plain words for why the relay is not running, if it is not. */
    val message: String? get() = when (phase) {
        RelayPhase.PAUSED -> gate?.text
        RelayPhase.CANNOT_HOST -> CANNOT_HOST_TEXT[reason] ?: CANNOT_HOST_UNKNOWN
        RelayPhase.FAILED -> failure?.text ?: RelayFailure.START_FAILED.text
        else -> null
    }

    override fun toString() = "RelayState(phase=$phase, wanted=$wanted, gate=$gate, reason=$reason, failure=$failure, allocations=$allocations)"

    fun toJson(): String = buildJsonObject {
        put("v", 1)
        put("phase", phase.name)
        put("wanted", wanted)
        gate?.let { put("gate", it.name) }
        reason?.let { put("reason", it) }
        failure?.let { put("failure", it.name) }
        refusal?.let { put("refusal", it) }
        endpoint?.let { put("endpoint", it) }
        pairing?.let { put("pairing", it) }
        bind?.let { put("bind", it) }
        listenPort?.let { put("listenPort", it) }
        put("allocations", allocations)
        put("bytesRelayed", bytesRelayed)
        controlConnected?.let { put("controlConnected", it) }
        mapping?.let { map ->
            put("mapping", buildJsonObject {
                put("method", map.method); put("allocationPorts", map.allocationPorts); put("requested", map.requested)
                put("permanent", map.permanent); put("restored", map.restored)
            })
        }
        put("todayBytes", todayBytes)
        put("dailyLimit", dailyLimit)
    }.toString()

    companion object {
        /** The state the relay process sent, or null for anything malformed. */
        fun parse(text: String?): RelayState? = try {
            require(text != null && text.length <= 16_384)
            val raw = Json.parseToJsonElement(text).jsonObject
            require(raw.getValue("v").jsonPrimitive.int == 1)
            fun string(name: String) = (raw[name] as? JsonPrimitive)?.also { require(it.isString) }?.content
            val mapping = (raw["mapping"] as? JsonObject)?.let { map ->
                RelayMapping(map.getValue("method").jsonPrimitive.content.also { require(it in setOf("pcp", "natpmp", "upnp")) },
                    map.getValue("allocationPorts").jsonPrimitive.long, map.getValue("requested").jsonPrimitive.long,
                    map.getValue("permanent").jsonPrimitive.boolean, map.getValue("restored").jsonPrimitive.long)
            }
            val bind = string("bind")?.also { require(RelayConfig.usableAddress(it)) }
            val port = raw["listenPort"]?.jsonPrimitive?.int?.also { require(it in RelayCaps.LISTEN_PORTS) }
            RelayState(
                phase = RelayPhase.valueOf(raw.getValue("phase").jsonPrimitive.content),
                wanted = raw.getValue("wanted").jsonPrimitive.boolean,
                gate = string("gate")?.let(RelayGate::valueOf),
                reason = string("reason")?.also { require(it in RelayNodeLine.PORTMAP_REASONS) },
                failure = string("failure")?.let(RelayFailure::valueOf),
                refusal = string("refusal")?.also { require(it.length <= 512) },
                endpoint = string("endpoint")?.also { require(it.startsWith("spry://") && it.length <= 2048) },
                pairing = string("pairing")?.also { require(Regex("^[A-Za-z0-9_-]{43}$").matches(it)) },
                bind = bind,
                listenPort = port,
                allocations = raw.getValue("allocations").jsonPrimitive.long.also { require(it >= 0) },
                bytesRelayed = raw.getValue("bytesRelayed").jsonPrimitive.long.also { require(it >= 0) },
                controlConnected = raw["controlConnected"]?.jsonPrimitive?.boolean,
                mapping = mapping,
                todayBytes = raw.getValue("todayBytes").jsonPrimitive.long.also { require(it >= 0) },
                dailyLimit = raw.getValue("dailyLimit").jsonPrimitive.long.also { require(it > 0) },
            )
        } catch (_: Exception) { null }
    }
}
