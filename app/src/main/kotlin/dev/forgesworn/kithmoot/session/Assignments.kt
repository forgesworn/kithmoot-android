package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.*

/** Signed inner records. Transport must encrypt these to the assignments channel. */
const val ASSIGNMENT_KIND = 1464
const val ASSIGNMENT_VERSION = "kithmoot/assignment/v1"
const val ASSIGNMENT_CHANNEL = "assignments"
private val assignmentHex = Regex("[0-9a-f]{64}")
private val assignmentRequest = Regex("[a-zA-Z0-9_-]{16,80}")
private val assignmentInput = Regex("[a-z][a-z0-9_-]{0,31}")
private val jsWhitespace = Regex("[\\u0009-\\u000D\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]")
internal fun JsonObject.assignmentText(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.only(vararg names: String) = keys.all { it in names }
private fun JsonObject.text(key: String, max: Int = 2000): Boolean = assignmentText(key)?.let { s -> s.length <= max && s.any { !jsWhitespace.matches(it.toString()) } } == true
private fun JsonObject.hex(key: String) = assignmentText(key)?.matches(assignmentHex) == true
private fun JsonObject.request(key: String) = assignmentText(key)?.matches(assignmentRequest) == true

fun assignmentId(creator: String, request: String): String =
    Digests.sha256("$ASSIGNMENT_VERSION:$creator:$request".toByteArray(Charsets.UTF_8)).toHex()

/** Unknown fields never enter the shared projection, including private notes or executable instructions. */
fun validAssignmentOperation(value: JsonElement?): Boolean {
    val v = value as? JsonObject ?: return false
    return when (v.assignmentText("op")) {
        "create" -> v.only("op", "objective", "criteria", "owner", "ownerDevice", "action", "inputs") &&
            v.text("objective", 1000) && v.text("criteria") && v.hex("owner") &&
            ("ownerDevice" !in v || v.hex("ownerDevice")) && ("action" !in v || v.text("action", 64)) &&
            ("inputs" !in v || (v["inputs"] as? JsonObject)?.let { inputs -> inputs.size <= 8 && inputs.keys.all { it.matches(assignmentInput) && inputs.text(it, 1000) } } == true)
        "claim" -> v.only("op", "executor", "next") && v.request("executor") && v.text("next")
        "progress" -> v.only("op", "executor", "text", "next") && v.request("executor") && v.text("text") && v.text("next")
        "block" -> v.only("op", "executor", "question") && v.request("executor") && v.text("question")
        "answer" -> v.only("op", "text") && v.text("text")
        "result" -> v.only("op", "executor", "summary", "evidence") && v.request("executor") && v.text("summary") && v.text("evidence", 6000)
        "accept" -> v.only("op", "result") && v.hex("result")
        "reject" -> v.only("op", "result", "reason") && v.hex("result") && v.text("reason")
        "stop" -> v.only("op", "reason", "purpose") && v.text("reason") && v.assignmentText("purpose") in listOf("cancel", "handoff")
        "release" -> v.only("op", "executor", "evidence") && v.request("executor") && v.text("evidence")
        "assign" -> v.only("op", "owner", "ownerDevice", "reason") && v.hex("owner") && v.text("reason") && ("ownerDevice" !in v || v.hex("ownerDevice"))
        else -> false
    }
}

data class AssignmentPayload(val assignment: String, val request: String, val previous: String?, val device: String?, val operation: JsonObject) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1); put("assignment", assignment); put("request", request)
        put("previous", previous?.let(::JsonPrimitive) ?: JsonNull)
        device?.let { put("device", it) }; put("operation", operation)
    }
}

fun assignmentPayload(event: NostrEvent, room: String): AssignmentPayload? = try {
    if (!room.matches(assignmentHex) || event.kind != ASSIGNMENT_KIND ||
        event.tags != listOf(listOf("d", ASSIGNMENT_VERSION), listOf("room", room)) ||
        event.content.toByteArray(Charsets.UTF_8).size > 16_384 || !Events.verify(event)) null
    else {
        val v = Json.parseToJsonElement(event.content) as? JsonObject
        val version = v?.get("v") as? JsonPrimitive
        if (v == null || !v.only("v", "assignment", "request", "previous", "device", "operation") ||
            version == null || version.isString || version.doubleOrNull != 1.0 ||
            ("device" in v && !v.hex("device")) || !v.request("assignment") || !v.request("request") ||
            (v["previous"] != JsonNull && !v.hex("previous")) || !validAssignmentOperation(v["operation"])) null
        else {
            val op = v.getValue("operation").jsonObject
            if ((op.assignmentText("op") == "create") != (v["previous"] == JsonNull) ||
                (op.assignmentText("op") == "create" && v.assignmentText("assignment") != assignmentId(event.pubkey, v.assignmentText("request")!!))) null
            else AssignmentPayload(v.assignmentText("assignment")!!, v.assignmentText("request")!!, v.assignmentText("previous"), v.assignmentText("device"), op)
        }
    }
} catch (_: Exception) { null }

suspend fun signAssignment(signer: ParticipantSigner, room: String, payload: AssignmentPayload, at: Long): NostrEvent {
    val content = payload.toJson().toString()
    val event = signer.sign(ASSIGNMENT_KIND, at, listOf(listOf("d", ASSIGNMENT_VERSION), listOf("room", room)), content)
    require(event.pubkey == signer.pubkey && event.content == content && assignmentPayload(event, room) != null) { "The signer changed or refused the assignment" }
    return event
}

/** Immutable wire-compatible state, with named accessors for the Android UI. */
data class SharedAssignment(val fields: JsonObject) {
    val id get() = fields.assignmentText("id")!!
    val head get() = fields.assignmentText("head")!!
    val creator get() = fields.assignmentText("creator")!!
    val owner get() = fields.assignmentText("owner")!!
    val status get() = fields.assignmentText("status")!!
    val objective get() = fields.assignmentText("objective")!!
    val criteria get() = fields.assignmentText("criteria")!!
    val next get() = fields.assignmentText("next")!!
    val question get() = fields.assignmentText("question")
    val result get() = fields["result"] as? JsonObject
    val action get() = fields.assignmentText("action")
    val needsDecision get() = status in setOf("blocked", "review", "stopped", "conflicted")
}
data class AssignmentProjection(val assignments: List<SharedAssignment>, val pending: List<String>, val rejected: List<String>)

private fun transition(before: SharedAssignment?, event: NostrEvent, p: AssignmentPayload, room: String): SharedAssignment? {
    val op = p.operation
    val historyEntry = buildJsonObject { put("id", event.id); put("by", event.pubkey); put("at", event.createdAt); put("operation", op) }
    if (before == null) {
        if (op.assignmentText("op") != "create") return null
        return SharedAssignment(buildJsonObject {
            put("id", p.assignment); put("room", room); put("creator", event.pubkey)
            for (key in listOf("owner", "ownerDevice", "objective", "criteria", "action", "inputs")) op[key]?.let { put(key, it) }
            put("status", "offered"); put("head", event.id); put("attempt", 1)
            put("next", "Waiting for the owner to start"); put("progress", ""); put("history", JsonArray(listOf(historyEntry)))
        })
    }
    if (p.previous != before.head || before.status in setOf("accepted", "cancelled", "conflicted")) return null
    val s = before.fields.toMutableMap()
    fun set(key: String, value: String) { s[key] = JsonPrimitive(value) }
    fun from(key: String) { s[key] = op.getValue(key) }
    fun attempt() { s["attempt"] = JsonPrimitive(s.getValue("attempt").jsonPrimitive.int + 1) }
    val creator = event.pubkey == before.creator
    val owner = event.pubkey == before.owner
    val executor = owner && "executor" in op && op["executor"] == s["executor"]
    when (op.assignmentText("op")) {
        "claim" -> {
            if (!owner || before.status != "offered" || (s["ownerDevice"] != null && before.fields.assignmentText("ownerDevice") != p.device)) return null
            set("status", "running"); from("executor"); from("next")
        }
        "progress" -> { if (!executor || before.status != "running") return null; s["progress"] = op.getValue("text"); from("next") }
        "block" -> { if (!executor || before.status != "running") return null; set("status", "blocked"); from("question"); set("next", "Waiting for an answer") }
        "answer" -> { if (!creator || before.status != "blocked") return null; set("status", "running"); s["answer"] = op.getValue("text"); s.remove("question"); set("next", "Continue with the answer") }
        "result" -> {
            if (!executor || before.status != "running") return null
            set("status", "review"); s["result"] = buildJsonObject { put("id", event.id); put("summary", op.getValue("summary")); put("evidence", op.getValue("evidence")) }; set("next", "Review the result")
        }
        "accept" -> { if (!creator || before.status != "review" || before.result?.get("id") != op["result"]) return null; set("status", "accepted"); set("next", "Result accepted") }
        "reject" -> { if (!creator || before.status != "review" || before.result?.get("id") != op["result"]) return null; set("status", "offered"); attempt(); s["next"] = op.getValue("reason"); s.remove("executor"); s.remove("result") }
        "stop" -> {
            if (!creator || before.status in setOf("stopping", "stopped")) return null
            s["stop"] = buildJsonObject { put("purpose", op.getValue("purpose")); put("reason", op.getValue("reason")) }
            val status = if (before.status in setOf("offered", "review")) { if (op.assignmentText("purpose") == "cancel") "cancelled" else "stopped" } else "stopping"
            set("status", status); s["next"] = if (status == "stopping") JsonPrimitive("Waiting for the owner to confirm work has stopped") else op.getValue("reason")
        }
        "release" -> {
            if (!executor || before.status != "stopping") return null
            val cancelled = (s["stop"] as? JsonObject)?.assignmentText("purpose") == "cancel"
            set("status", if (cancelled) "cancelled" else "stopped"); s["progress"] = op.getValue("evidence"); set("next", if (cancelled) "Cancelled; execution stopped" else "Choose the next owner")
        }
        "assign" -> {
            if (!creator || before.status != "stopped" || (s["stop"] as? JsonObject)?.assignmentText("purpose") != "handoff") return null
            from("owner"); attempt(); set("status", "offered"); s["next"] = op.getValue("reason")
            if ("ownerDevice" in op) from("ownerDevice") else s.remove("ownerDevice")
            listOf("executor", "stop", "question", "answer", "result").forEach(s::remove)
        }
        else -> return null
    }
    set("head", event.id); s["history"] = JsonArray(before.fields.getValue("history").jsonArray + historyEntry)
    return SharedAssignment(JsonObject(s))
}

/** Replay has no side effects. A missing predecessor waits; competing authorised branches halt. */
fun projectAssignments(events: List<NostrEvent>, room: String): AssignmentProjection {
    val groups = linkedMapOf<String, MutableList<Pair<NostrEvent, AssignmentPayload>>>()
    val rejected = mutableListOf<String>(); val seen = hashSetOf<String>()
    for (event in events) {
        if (!seen.add(event.id)) continue
        val payload = assignmentPayload(event, room)
        if (payload == null) { rejected.add(event.id); continue }
        groups.getOrPut(payload.assignment) { mutableListOf() }.add(event to payload)
    }
    val assignments = mutableListOf<SharedAssignment>(); val pending = mutableListOf<String>()
    for (group in groups.values) {
        val remaining = group.associateByTo(linkedMapOf()) { it.first.id }
        var current: SharedAssignment? = null
        val requests = hashSetOf<String>()
        while (remaining.isNotEmpty()) {
            val candidates = mutableListOf<Triple<SharedAssignment, NostrEvent, AssignmentPayload>>()
            for ((event, payload) in remaining.values.toList()) {
                if (payload.previous != current?.head) continue
                val state = transition(current, event, payload, room)
                if (state == null || "${event.pubkey}:${payload.request}" in requests) { rejected.add(event.id); remaining.remove(event.id); continue }
                candidates.add(Triple(state, event, payload))
            }
            if (candidates.isEmpty()) break
            if (candidates.size > 1) {
                candidates.sortBy { it.second.id }
                current = SharedAssignment(JsonObject((current ?: candidates.first().first).fields + mapOf("status" to JsonPrimitive("conflicted"), "next" to JsonPrimitive("Conflicting updates; execution must stop for reconciliation"))))
                candidates.forEach { remaining.remove(it.second.id) }; break
            }
            val (state, event, payload) = candidates.single()
            current = state; requests.add("${event.pubkey}:${payload.request}"); remaining.remove(event.id)
        }
        current?.let(assignments::add); pending.addAll(remaining.keys)
    }
    return AssignmentProjection(assignments.sortedBy { it.id }, pending, rejected)
}

fun validateAssignmentActions(raw: JsonElement?): JsonArray? {
    val actions = raw as? JsonArray ?: return null
    if (actions.size > 8) return null
    val ids = hashSetOf<String>()
    for (element in actions) {
        val a = element as? JsonObject ?: return null
        if (!a.only("id", "label", "description", "inputs") || !a.text("id",64) || !ids.add(a.assignmentText("id")!!) || !a.text("label",80) || !a.text("description",300)) return null
        val inputs = a["inputs"] as? JsonArray ?: return null
        if (inputs.size > 8) return null
        val inputIds = hashSetOf<String>()
        for (element in inputs) {
            val i = element as? JsonObject ?: return null
            val id = i.assignmentText("id") ?: return null
            val required = i["required"] as? JsonPrimitive ?: return null
            if (!i.only("id", "label", "required") || !id.matches(assignmentInput) || !inputIds.add(id) || !i.text("label",80) || required.isString || required.booleanOrNull == null) return null
        }
    }
    return actions
}
