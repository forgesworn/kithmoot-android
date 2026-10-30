package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The outside test of a member's built-in call relay (docs/protocol.md,
 * "Relay outside test"; the web's `src/relay-test.ts`).
 *
 * A relay's host asks one other device in the room to test the host's relay
 * from wherever that device is. The tester runs two temporary peers of its
 * own: A, relay-only on the host's relay, and B, with no TURN, using the
 * relay's listener as its STUN server. B's checks reach A's relayed address
 * from B's own public address, so the host's allocation port as well as its
 * listener is proved reachable from outside.
 *
 * Both messages ride the existing call signalling wrap ([wrapSignal]): a
 * `relay-test` request from the host and a `relay-test-result` back to the
 * host alone. Neither carries an address, a credential or a name; the
 * result's time is the signed inner `created_at`.
 */

/** The parts a failed test names, in the order the test reaches them.
 *  `declined`: the tester did not run it. `credentials`: the host's node gave
 *  the tester no relay login. `allocate`: no relayed address. `stun`: the
 *  listener did not answer plain STUN. `inbound`: both ends ready, nothing
 *  reached the relayed address from outside. */
val RELAY_TEST_STAGES: List<String> = listOf("declined", "credentials", "allocate", "stun", "inbound")

const val SIGNAL_RELAY_TEST: String = "relay-test"
const val SIGNAL_RELAY_TEST_RESULT: String = "relay-test-result"

/** How long a host waits for a result before it says nobody answered. */
const val RELAY_TEST_ANSWER_MS: Long = 45_000
/** The tester's own limit on one run, from creating the peers to the verdict. */
const val RELAY_TEST_RUN_MS: Long = 15_000
/** A tester runs at most one test per asking device in this many milliseconds. */
const val RELAY_TEST_COOLDOWN_MS: Long = 60_000
/** Relay-test signals admitted per sending device per [RATE_WINDOW_SECONDS],
 *  apart from the call's own signalling budget. */
const val RELAY_TEST_SIGNALS_PER_WINDOW: Int = 8
/** The longest endpoint a relay-test message may name. */
const val MAX_RELAY_TEST_ENDPOINT_LENGTH: Int = 1024

private val RELAY_TEST_ID = Regex("^[0-9a-f]{16}$")

/** A host asking one device to test the relay the host shares in this room. */
data class RelayTestRequest(val id: String, val endpoint: String) {
    fun toJson(): JsonObject = buildJsonObject { put("id", id); put("endpoint", endpoint) }

    companion object {
        fun parse(value: JsonElement?): RelayTestRequest? {
            if (!isValidRelayTestRequest(value)) return null
            val raw = value as JsonObject
            return RelayTestRequest(raw.string("id")!!, raw.string("endpoint")!!)
        }
    }
}

/** What the tester tells the host, and nobody else. */
data class RelayTestResult(
    val id: String,
    val endpoint: String,
    /** True only when data went through the relayed address from outside. */
    val ok: Boolean,
    /** On a failure only: which part failed, one of [RELAY_TEST_STAGES]. */
    val stage: String? = null,
    /** The tester was on the relay's own network: a pass is a hint, never "reachable". */
    val sameNetwork: Boolean = false,
) {
    /** `stage` only when set, `sameNetwork` only as `true`. */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("endpoint", endpoint)
        put("ok", ok)
        if (stage != null) put("stage", stage)
        if (sameNetwork) put("sameNetwork", true)
    }

    companion object {
        fun of(request: RelayTestRequest, verdict: RelayTestVerdict) =
            RelayTestResult(request.id, request.endpoint, verdict.ok, verdict.stage, verdict.sameNetwork)

        fun parse(value: JsonElement?): RelayTestResult? {
            if (!isValidRelayTestResult(value)) return null
            val raw = value as JsonObject
            return RelayTestResult(raw.string("id")!!, raw.string("endpoint")!!, raw.boolean("ok")!!, raw.string("stage"), raw.boolean("sameNetwork") == true)
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

/** A JSON boolean literal only: never the string "true", never 1. */
private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toBooleanStrictOrNull()

private fun exactKeys(value: JsonObject, allowed: Set<String>, required: Set<String>): Boolean =
    value.keys.all { it in allowed } && required.all { it in value.keys }

private fun validEndpoint(raw: JsonObject): Boolean {
    val value = raw.string("endpoint") ?: return false
    if (value.length > MAX_RELAY_TEST_ENDPOINT_LENGTH || !value.startsWith("spry:")) return false
    return runCatching { spryEndpoint(value) == value }.getOrDefault(false)
}

private fun validId(raw: JsonObject): Boolean = raw.string("id")?.let(RELAY_TEST_ID::matches) == true

/** A request: exactly `id` and `endpoint`, nothing else. The web's `validRelayTestRequest`. */
fun isValidRelayTestRequest(value: JsonElement?): Boolean {
    val raw = value as? JsonObject ?: return false
    return exactKeys(raw, setOf("id", "endpoint"), setOf("id", "endpoint")) && validId(raw) && validEndpoint(raw)
}

/**
 * A result: `id`, `endpoint` and `ok`; `stage` exactly when `ok` is false;
 * `sameNetwork` only as `true`, and never with `declined` or `credentials`.
 * Any other key, or a key present with the wrong type (`null` included),
 * refuses the whole signal. The web's `validRelayTestResult`.
 */
fun isValidRelayTestResult(value: JsonElement?): Boolean {
    val raw = value as? JsonObject ?: return false
    if (!exactKeys(raw, setOf("id", "endpoint", "ok", "stage", "sameNetwork"), setOf("id", "endpoint", "ok"))) return false
    if (!validId(raw) || !validEndpoint(raw)) return false
    val ok = raw.boolean("ok") ?: return false
    val stage = raw.string("stage")
    if (ok) {
        if ("stage" in raw) return false
    } else if (stage == null || stage !in RELAY_TEST_STAGES) return false
    if ("sameNetwork" in raw && (raw.boolean("sameNetwork") != true || stage == "declined" || stage == "credentials")) return false
    return true
}

/** What the tester saw, gathered from its two peers. Addresses stay on the
 *  tester: only the verdict leaves it. */
data class RelayTestObservations(
    /** The relay's IPv4 literal, from the TURN URLs the node returned. */
    val turnHost: String,
    /** Peer A gathered a relayed candidate. */
    val relayed: Boolean,
    /** Addresses of peer B's server-reflexive candidates. */
    val reflexive: List<String>,
    /** Addresses of peer B's host candidates. */
    val hosts: List<String>,
    /** Address of peer B's local candidate on its selected pair, when known. */
    val local: String? = null,
    /** The data-channel echo came back. */
    val echoed: Boolean,
    /** Peer A's selected pair (else its nominated, succeeded one) has a `relay` local candidate and received bytes. */
    val pathA: Boolean,
    /** Peer B's selected pair (else its nominated, succeeded one) has a local candidate that is not `relay` and received bytes. */
    val pathB: Boolean,
)

data class RelayTestVerdict(val ok: Boolean, val stage: String? = null, val sameNetwork: Boolean = false)

/**
 * The verdict a tester sends. `sameNetwork` when any of B's reflexive or host
 * addresses, or B's selected local address, is the relay's own address. A
 * pass needs the echo and both paths. A failure names the first part that
 * did not work: no relayed address, then (when B learnt no outside address
 * and is not on the relay's network) no STUN answer, otherwise inbound. The
 * web's `relayTestVerdict`, branch for branch.
 */
fun relayTestVerdict(seen: RelayTestObservations): RelayTestVerdict {
    val sameNetwork = seen.turnHost in seen.reflexive || seen.turnHost in seen.hosts || seen.local == seen.turnHost
    val verdict = when {
        seen.echoed && seen.pathA && seen.pathB -> RelayTestVerdict(ok = true)
        !seen.relayed -> RelayTestVerdict(ok = false, stage = "allocate")
        seen.reflexive.isEmpty() && !sameNetwork -> RelayTestVerdict(ok = false, stage = "stun")
        else -> RelayTestVerdict(ok = false, stage = "inbound")
    }
    return if (sameNetwork) verdict.copy(sameNetwork = true) else verdict
}

/** What a host may say about its relay from one result: `reachable` only for
 *  a pass from another network; a pass from its own network is a hint. */
fun relayTestFinding(ok: Boolean, stage: String?, sameNetwork: Boolean): String = when {
    stage == "declined" || stage == "credentials" -> "not-run"
    sameNetwork -> "same-network"
    ok -> "reachable"
    else -> "unreachable"
}

fun relayTestFinding(verdict: RelayTestVerdict): String = relayTestFinding(verdict.ok, verdict.stage, verdict.sameNetwork)
