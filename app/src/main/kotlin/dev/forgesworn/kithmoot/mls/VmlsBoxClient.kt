package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.BoxRequest
import dev.forgesworn.kithmoot.account.BoxRequestReply
import dev.forgesworn.kithmoot.account.VaultRefusal
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Signs one box request: the vault's `signBoxRequestV1` bound to the persona's context and consent (§6.2.1). */
fun interface BoxRequestSigner {
    suspend fun sign(request: BoxRequest): VaultResult<BoxRequestReply>
}

/** How one request to the box ended. Only [Ok] carries an answer the engine may be given. */
sealed class BoxAnswer<out T> {
    /** [serverTime] is the box's clock, absent on the capabilities answer, which carries none. */
    data class Ok<T>(val value: T, val serverTime: Long?) : BoxAnswer<T>()
    /**
     * The box refused: [code] is its stable refusal code (`replay`,
     * `authority`, `restore-fenced`, `busy`, ...), null when the refusal had
     * no body (a box without VMLS answers 404 with nothing).
     */
    data class Refused(val status: Int, val code: String?, val serverTime: Long?) : BoxAnswer<Nothing>()
    /** The vault signed nothing; nothing was sent. */
    data class NotSigned(val refusal: VaultRefusal) : BoxAnswer<Nothing>()
    /**
     * No answer: the request may or may not have taken effect. A request
     * given up on here can still reach the box later (the Link call is not
     * cancelled), which is safe because every route is idempotent and a
     * retry is a new event.
     */
    data object Unreachable : BoxAnswer<Nothing>()
    /** An answer that is not the box's documented shape: treated as no answer. */
    data object Malformed : BoxAnswer<Nothing>()
}

/** A mailbox record deposit (`stored` or `duplicate`): the receipt is the envelope's SHA-256. */
class Deposited(val duplicate: Boolean, val receipt: ByteArray, val welcomeAcknowledged: Boolean?)

/** A commit-slot deposit: the winning [attempt] and receipt, and the box's 197-byte signed receipt when it is durable. */
class SlotDeposited(val outcome: Outcome, val attempt: Long, val receipt: ByteArray, val signedReceipt: ByteArray?) {
    enum class Outcome { Won, Duplicate, Taken }
}

/** A slot's status at the asked attempt. Every state but [State.Empty] carries the winner's facts and signed receipt. */
class SlotState(val state: State, val attempt: Long?, val receipt: ByteArray?, val signedReceipt: ByteArray?, val envelope: ByteArray?) {
    enum class State { Empty, Filled, Expired, Void }
}

class FetchedRecord(val mailbox: ByteArray, val receipt: ByteArray, val envelope: ByteArray)

/** One page of a fetch, oldest first; [next] continues it, for the same mailboxes only. */
class FetchPage(val records: List<FetchedRecord>, val next: String?)

/** An acknowledgement: [deleted] for a keeper device, a read marker for a guest grant; [acked] held records it named. */
class Acked(val deleted: Boolean, val acked: Long)

class AckItem(val mailbox: ByteArray, val receipt: ByteArray)

/** A package registration: [fresh] for `registered`, false for the same registration again (`unchanged`). */
class Registered(val fresh: Boolean)

/**
 * The home box's `/vmls/v1/` routes over the persona's Link route (P3-03b-3a,
 * bothy-link `vmls.rs`). Every request is authenticated by the vault's
 * [BoxRequestSigner] (the MLS device key, §6.2.1) and freshly signed, so a
 * retry is a new event. The Link engine writes the content type and bounds
 * each route; this client checks every answer strictly and hands the engine
 * nothing it has not checked.
 *
 * It decides nothing: whether an answer is acted on is the driver's, through
 * the session host, so no effect is released before its step is witnessed.
 */
class VmlsBoxClient(
    private val transport: LinkJsonTransport,
    private val routeId: String,
    /** The box's Link node id, 32 bytes in hex: the one the vault's approval names. */
    private val box: String,
    private val signer: BoxRequestSigner,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init { require(HEX64.matches(box)) }

    /** `GET /vmls/v1/capabilities`: the raw body, for the engine's strict `parse_capabilities`. */
    suspend fun capabilities(): BoxAnswer<ByteArray> = request("GET", "/vmls/v1/capabilities", ByteArray(0)) { status, body ->
        if (status != 200 || body.isEmpty() || body.size > MAX_JSON_BYTES) null else body.copyOf() to null
    }

    /** `PUT /vmls/v1/mailboxes/{mailbox}/records`. */
    suspend fun deposit(mailbox: ByteArray, envelope: ByteArray): BoxAnswer<Deposited> {
        require(mailbox.size == 32 && envelope.size <= MAX_ENVELOPE_BYTES)
        return request("PUT", "/vmls/v1/mailboxes/${mailbox.toHex()}/records", envelope) { status, body ->
            val answer = answer(body, setOf("code", "receipt", "welcome")) ?: return@request null
            val code = answer.code
            if (!((status == 201 && code == "stored") || (status == 200 && code == "duplicate"))) return@request null
            val receipt = answer.hex32("receipt") ?: return@request null
            // The receipt is the whole envelope's SHA-256 (rev 3): a box naming another is not trusted.
            if (!receipt.contentEquals(Digests.sha256(envelope))) return@request null
            val welcome = when (val w = answer.json["welcome"]) {
                null -> null
                is JsonObject -> {
                    if (w.keys != setOf("acknowledged")) return@request null
                    ((w["acknowledged"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()) ?: return@request null
                }
                else -> return@request null
            }
            Deposited(code == "duplicate", receipt, welcome) to answer.serverTime
        }
    }

    /** `PUT /vmls/v1/slots/{slot}/{attempt}`. */
    suspend fun depositSlot(slot: ByteArray, attempt: Long, envelope: ByteArray): BoxAnswer<SlotDeposited> {
        require(slot.size == 32 && attempt in 0..U32_MAX && envelope.size <= MAX_ENVELOPE_BYTES)
        return request("PUT", "/vmls/v1/slots/${slot.toHex()}/$attempt", envelope) { status, body ->
            val answer = answer(body, setOf("code", "receipt", "signed_receipt", "attempt")) ?: return@request null
            val outcome = when {
                status == 201 && answer.code == "won" -> SlotDeposited.Outcome.Won
                status == 200 && answer.code == "duplicate" -> SlotDeposited.Outcome.Duplicate
                status == 409 && answer.code == "taken" -> SlotDeposited.Outcome.Taken
                else -> return@request null
            }
            val winner = answer.u32("attempt") ?: return@request null
            val receipt = answer.hex32("receipt") ?: return@request null
            // A win or a duplicate is this envelope at this attempt; only `taken` names another.
            if (outcome != SlotDeposited.Outcome.Taken && (winner != attempt || !receipt.contentEquals(Digests.sha256(envelope)))) return@request null
            val signed = answer.signedReceipt() ?: return@request null
            // The signed receipt must state exactly the facts the answer gives.
            if (signed.value != null && !receiptStates(signed.value, slot, winner, receipt)) return@request null
            SlotDeposited(outcome, winner, receipt, signed.value) to answer.serverTime
        }
    }

    /** `POST /vmls/v1/slots/{slot}/{attempt}/status`, read at the attempt the slot was derived from. */
    suspend fun slotStatus(slot: ByteArray, attempt: Long): BoxAnswer<SlotState> {
        require(slot.size == 32 && attempt in 0..U32_MAX)
        val body = """{"v":1}""".toByteArray()
        return request("POST", "/vmls/v1/slots/${slot.toHex()}/$attempt/status", body) { status, raw ->
            if (status != 200) return@request null
            // A filled slot answers its whole envelope: bounded as a fetch page is, not as ordinary JSON.
            val answer = answer(raw, setOf("code", "receipt", "signed_receipt", "attempt", "envelope"), maxBytes = MAX_FETCH_BYTES) ?: return@request null
            val state = when (answer.code) {
                "empty" -> return@request if (answer.json.keys == setOf("v", "code", "server_time")) SlotState(SlotState.State.Empty, null, null, null, null) to answer.serverTime else null
                "filled" -> SlotState.State.Filled
                "expired" -> SlotState.State.Expired
                "void" -> SlotState.State.Void
                else -> return@request null
            }
            val winner = answer.u32("attempt") ?: return@request null
            val receipt = answer.hex32("receipt") ?: return@request null
            val signed = answer.signedReceipt()?.value ?: return@request null
            val envelope = if (state == SlotState.State.Filled) {
                val bytes = answer.base64("envelope", MAX_ENVELOPE_BYTES) ?: return@request null
                if (!receipt.contentEquals(Digests.sha256(bytes))) return@request null
                bytes
            } else {
                if ("envelope" in answer.json) return@request null
                null
            }
            // The box reads at the asked attempt: filled and expired are that attempt, void is always another.
            // The signed receipt's label still decides for the engine (§5.1); these only refuse a box contradicting itself.
            if ((state == SlotState.State.Void) == (winner == attempt)) return@request null
            if (!receiptStates(signed, slot, winner, receipt)) return@request null
            SlotState(state, winner, receipt, signed, envelope) to answer.serverTime
        }
    }

    /** `POST /vmls/v1/fetch`: at most 16 mailboxes, continuing [after] for the same set. */
    suspend fun fetch(mailboxes: List<ByteArray>, after: String? = null): BoxAnswer<FetchPage> {
        require(mailboxes.size in 1..MAX_FETCH_MAILBOXES && mailboxes.all { it.size == 32 } && mailboxes.map { it.toHex() }.toSet().size == mailboxes.size)
        require(after == null || (after.length <= MAX_CURSOR_CHARS && CURSOR.matches(after)))
        val asked = mailboxes.map { it.toHex() }.toSet()
        val body = buildJsonObject {
            put("v", 1)
            putJsonArray("mailboxes") { asked.forEach { add(it) } }
            if (after != null) put("after", after)
        }.toString().toByteArray()
        return request("POST", "/vmls/v1/fetch", body) { status, raw ->
            if (status != 200) return@request null
            val answer = answer(raw, setOf("code", "records", "next"), maxBytes = MAX_FETCH_BYTES) ?: return@request null
            if (answer.code != "ok") return@request null
            val records = (answer.json["records"] as? JsonArray) ?: return@request null
            if (records.size > MAX_FETCH_RECORDS) return@request null
            val parsed = records.map { element ->
                val record = element as? JsonObject ?: return@request null
                if (record.keys != setOf("mailbox", "receipt", "envelope")) return@request null
                val mailbox = hex32(record["mailbox"]) ?: return@request null
                // Only the mailboxes asked for: a record from another is not trusted.
                if (mailbox.toHex() !in asked) return@request null
                val receipt = hex32(record["receipt"]) ?: return@request null
                val envelope = base64(record["envelope"], MAX_ENVELOPE_BYTES) ?: return@request null
                if (!receipt.contentEquals(Digests.sha256(envelope))) return@request null
                FetchedRecord(mailbox, receipt, envelope)
            }
            val next = when (val n = answer.json["next"]) {
                null, JsonNull -> null
                is JsonPrimitive -> n.takeIf { it.isString }?.content?.takeIf { it.length <= MAX_CURSOR_CHARS && CURSOR.matches(it) } ?: return@request null
                else -> return@request null
            }
            FetchPage(parsed, next) to answer.serverTime
        }
    }

    /** `POST /vmls/v1/ack`: at most 64 records, each named by its mailbox and receipt. */
    suspend fun ack(records: List<AckItem>): BoxAnswer<Acked> {
        require(records.size in 1..MAX_ACKS && records.all { it.mailbox.size == 32 && it.receipt.size == 32 })
        val body = buildJsonObject {
            put("v", 1)
            putJsonArray("records") {
                records.forEach { r -> addJsonObject { put("mailbox", r.mailbox.toHex()); put("receipt", r.receipt.toHex()) } }
            }
        }.toString().toByteArray()
        return request("POST", "/vmls/v1/ack", body) { status, raw ->
            if (status != 200) return@request null
            val answer = answer(raw, setOf("code", "acked")) ?: return@request null
            val deleted = when (answer.code) { "deleted" -> true; "marked" -> false; else -> return@request null }
            val acked = (answer.json["acked"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..records.size.toLong() } ?: return@request null
            Acked(deleted, acked) to answer.serverTime
        }
    }

    /**
     * `PUT /vmls/v1/packages/{id}`: the box's single-use Welcome slot for a
     * joiner's package (keeper devices only, D5). [Registered.fresh] is false
     * for the same registration again (`unchanged`).
     */
    suspend fun registerPackage(packageId: ByteArray, welcomeMailbox: ByteArray, expiresAt: Long, ciphertext: ByteArray): BoxAnswer<Registered> {
        require(packageId.size == 32 && welcomeMailbox.size == 32 && expiresAt >= 0 && ciphertext.size <= MAX_PACKAGE_CIPHERTEXT_BYTES)
        val body = buildJsonObject {
            put("v", 1)
            put("welcome_mailbox", welcomeMailbox.toHex())
            put("expires_at", expiresAt)
            put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
        }.toString().toByteArray()
        return request("PUT", "/vmls/v1/packages/${packageId.toHex()}", body) { status, raw ->
            val answer = answer(raw, setOf("code")) ?: return@request null
            when {
                status == 201 && answer.code == "registered" -> Registered(true) to answer.serverTime
                status == 200 && answer.code == "unchanged" -> Registered(false) to answer.serverTime
                else -> null
            }
        }
    }

    /** `DELETE /vmls/v1/packages/{id}`, with an empty body (keeper devices only). */
    suspend fun withdrawPackage(packageId: ByteArray): BoxAnswer<Unit> {
        require(packageId.size == 32)
        return request("DELETE", "/vmls/v1/packages/${packageId.toHex()}", ByteArray(0)) { status, raw ->
            val answer = answer(raw, setOf("code")) ?: return@request null
            if (status == 200 && answer.code == "withdrawn") Unit to answer.serverTime else null
        }
    }

    // ---- inside ----

    private suspend fun <T> request(method: String, path: String, body: ByteArray, parse: (Int, ByteArray) -> Pair<T, Long?>?): BoxAnswer<T> {
        // A signer that throws is a fault, not a refusal: it propagates rather than looking retryable.
        val signed = signer.sign(BoxRequest(box, method, path, Digests.sha256(body).toHex()))
        val authorization = when (signed) {
            is VaultResult.Refused -> return BoxAnswer.NotSigned(signed.refusal)
            is VaultResult.Ok -> signed.value.authorization
        }
        val response = try {
            withTimeout(timeoutMillis) { transport.request(LinkJsonRequest(routeId, method, path, authorization, body.copyOf())).await() }
        } catch (_: TimeoutCancellationException) {
            return BoxAnswer.Unreachable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return BoxAnswer.Unreachable
        }
        return answerOf(response, parse)
    }

    private fun <T> answerOf(response: LinkJsonResponse, parse: (Int, ByteArray) -> Pair<T, Long?>?): BoxAnswer<T> {
        val status = response.status
        if (status in 200..299 || (status == 409 && response.body.isNotEmpty())) {
            // 409 is a slot's `taken`, an answer; elsewhere a 409 is a refusal.
            parse(status, response.body)?.let { (value, time) -> return BoxAnswer.Ok(value, time) }
            if (status != 409) return BoxAnswer.Malformed
        }
        if (status in 200..299) return BoxAnswer.Malformed
        if (response.body.isEmpty()) return BoxAnswer.Refused(status, null, null)
        val refusal = answer(response.body, setOf("code")) ?: return BoxAnswer.Malformed
        if (!REFUSAL_CODE.matches(refusal.code)) return BoxAnswer.Malformed
        return BoxAnswer.Refused(status, refusal.code, refusal.serverTime)
    }

    /**
     * Whether a 197-byte slot receipt (vmls-core `SlotReceipt`: version 1,
     * node, installation, slot, attempt big-endian, envelope hash, signature)
     * names this box, [slot], [attempt] and [receipt]. The signature and the
     * installation are the engine's to check.
     */
    private fun receiptStates(signed: ByteArray, slot: ByteArray, attempt: Long, receipt: ByteArray): Boolean {
        if (signed.size != SIGNED_RECEIPT_BYTES || signed[0] != 1.toByte()) return false
        val node = box.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val labelled = signed.copyOfRange(97, 101).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        return signed.copyOfRange(1, 33).contentEquals(node) && signed.copyOfRange(65, 97).contentEquals(slot) &&
            labelled == attempt && signed.copyOfRange(101, 133).contentEquals(receipt)
    }

    /** A box answer: `{v:1, code, server_time, ...}` with only [allowed] keys besides `v` and `server_time`. */
    private class Answer(val json: JsonObject, val code: String, val serverTime: Long) {
        fun hex32(name: String): ByteArray? = hex32(json[name])
        fun u32(name: String): Long? = (json[name] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            ?.takeIf { ATTEMPT.matches(it) }?.toLongOrNull()?.takeIf { it in 0..U32_MAX }
        fun base64(name: String, max: Int): ByteArray? = base64(json[name], max)

        /** The 197-byte signed receipt, absent only where the box attaches none. */
        fun signedReceipt(): Holder? {
            val value = json["signed_receipt"] ?: return Holder(null)
            val bytes = base64(value, SIGNED_RECEIPT_BYTES) ?: return null
            return if (bytes.size == SIGNED_RECEIPT_BYTES) Holder(bytes) else null
        }
    }

    private class Holder(val value: ByteArray?)

    private fun answer(body: ByteArray, allowed: Set<String>, maxBytes: Int = MAX_JSON_BYTES): Answer? {
        if (body.isEmpty() || body.size > maxBytes) return null
        val json = try { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject } catch (_: Exception) { null } ?: return null
        if (!json.keys.all { it == "v" || it == "server_time" || it in allowed }) return null
        val v = (json["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
        if (v != "1") return null
        val code = (json["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val time = (json["server_time"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 } ?: return null
        return Answer(json, code, time)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        /** Bothy's `MAX_ENVELOPE_BYTES`. */
        const val MAX_ENVELOPE_BYTES = 44 + 1_048_576
        const val MAX_JSON_BYTES = 128 * 1024
        /** Link's fetch response bound. */
        const val MAX_FETCH_BYTES = 2 * 1024 * 1024
        const val MAX_FETCH_MAILBOXES = 16
        const val MAX_FETCH_RECORDS = 64
        const val MAX_ACKS = 64
        /** Bothy's `MAX_PACKAGE_CIPHERTEXT_BYTES`. */
        const val MAX_PACKAGE_CIPHERTEXT_BYTES = 64 * 1024
        const val SIGNED_RECEIPT_BYTES = 197
        const val U32_MAX = 0xFFFF_FFFFL
        private const val MAX_CURSOR_CHARS = 1024
        private val HEX64 = Regex("[0-9a-f]{64}")
        private val ATTEMPT = Regex("0|[1-9][0-9]{0,9}")
        private val CURSOR = Regex("[A-Za-z0-9+/=_-]+")
        private val REFUSAL_CODE = Regex("[a-z][a-z0-9-]{0,63}")

        private fun hex32(element: Any?): ByteArray? {
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!HEX64.matches(text)) return null
            return ByteArray(32) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }

        /** Canonical padded base64 only: a string that does not re-encode to itself is refused. */
        private fun base64(element: Any?, max: Int): ByteArray? {
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (text.length > (max + 2) / 3 * 4) return null
            val bytes = try { Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) { return null }
            if (bytes.size > max || Base64.getEncoder().encodeToString(bytes) != text) return null
            return bytes
        }
    }
}
