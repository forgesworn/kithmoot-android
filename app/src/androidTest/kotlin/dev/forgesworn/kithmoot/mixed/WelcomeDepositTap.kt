package dev.forgesworn.kithmoot.mixed

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.VmlsRuntime
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Lab-only fault on one Welcome mailbox, below the product's box client.
 *
 * The product driver and box client are unchanged: the tap replaces the
 * Link transport inside each `VmlsBoxClient` the runtime creates, so every
 * round (the app's own foreground loop and the controller's) passes through
 * it. Only `PUT /vmls/v1/mailboxes/{welcomeMailbox}/records` is touched.
 *
 * - [Mode.DropFirst]: the first deposit reaches the box; its answer is
 *   recorded and then lost, so the client sees no reply (`Unreachable`).
 *   Later deposits in the same process are held.
 * - [Mode.Hold]: no deposit leaves the device (the route to that mailbox is
 *   down); each attempt is counted.
 * - [Mode.Observe]: deposits pass and each box answer is recorded.
 *
 * A first deposit is claimed when it is sent, so a concurrent second one is
 * held, never forwarded. [report] closes the tap first: nothing is forwarded
 * after the evidence is read.
 */
internal class WelcomeDepositTap(val mailbox: String, val mode: Mode) {
    enum class Mode(val wire: String) { DropFirst("drop-first"), Hold("hold"), Observe("observe") }

    class Answer(val status: Int, val code: String?, val receipt: String?, val acknowledged: Boolean?, val bodySha256: String, val replyDropped: Boolean)

    init { require(mailbox.matches(HEX64)) }

    val path = "/vmls/v1/mailboxes/$mailbox/records"
    private val answers = mutableListOf<Answer>()
    private var held = 0
    private var failed = 0
    private var wrapped = 0
    private var forwarded = 0
    private var closed = false

    fun wrap(real: LinkJsonTransport): LinkJsonTransport = synchronized(this) { wrapped++ }.let { Transport(real) }

    private inner class Transport(val real: LinkJsonTransport) : LinkJsonTransport {
        override fun request(request: LinkJsonRequest): CompletableFuture<LinkJsonResponse> {
            if (request.method != "PUT" || request.path != path) return real.request(request)
            val bodySha = Digests.sha256(request.body).toHex()
            val drop = synchronized(this@WelcomeDepositTap) {
                val holding = closed || mode == Mode.Hold || (mode == Mode.DropFirst && forwarded > 0)
                if (holding) { held++; return CompletableFuture.failedFuture(IOException("lab: Welcome route held")) }
                forwarded++
                mode == Mode.DropFirst
            }
            return real.request(request).handle { response, error ->
                if (error != null || response == null) {
                    synchronized(this@WelcomeDepositTap) { failed++ }
                    throw (error as? CompletionException) ?: CompletionException(error ?: IOException("lab: no response"))
                }
                synchronized(this@WelcomeDepositTap) { answers += answerOf(response, bodySha, drop) }
                if (drop) throw CompletionException(IOException("lab: Welcome deposit reply dropped"))
                response
            }
        }
    }

    fun report(): JsonObject = synchronized(this) {
        closed = true
        buildJsonObject {
            put("mode", mode.wire); put("mailbox", mailbox); put("clientsWrapped", wrapped)
            put("forwarded", forwarded); put("held", held); put("transportFailures", failed)
            put("answers", buildJsonArray {
                answers.forEach { a ->
                    add(buildJsonObject {
                        put("status", a.status); a.code?.let { put("code", it) }; a.receipt?.let { put("receipt", it) }
                        a.acknowledged?.let { put("acknowledged", it) }
                        put("bodySha256", a.bodySha256); put("replyDropped", a.replyDropped)
                    })
                }
            })
        }
    }

    companion object {
        private val HEX64 = Regex("[0-9a-f]{64}")

        fun answerOf(response: LinkJsonResponse, bodySha: String, dropped: Boolean): Answer {
            val json = runCatching { Json.parseToJsonElement(response.body.decodeToString()).jsonObject }.getOrNull()
            val code = (json?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val receipt = (json?.get("receipt") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val acknowledged = runCatching { json?.get("welcome")?.jsonObject?.get("acknowledged")?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull()
            return Answer(response.status, code, receipt, acknowledged, bodySha, dropped)
        }

        /**
         * Puts the tap on every box client [runtime] holds or later creates.
         * The runtime's driver map is replaced with one that wraps each new
         * driver's client as it is stored, before its first round. Returns a
         * restore function.
         */
        @Suppress("UNCHECKED_CAST")
        fun install(runtime: VmlsRuntime, tap: WelcomeDepositTap): () -> Unit {
            val field = VmlsRuntime::class.java.getDeclaredField("drivers").apply { isAccessible = true }
            val original = field.get(runtime) as HashMap<String, Any?>
            val restores = mutableListOf<() -> Unit>()
            fun tapDriven(driven: Any?) {
                if (driven == null) return
                val driver = driven.javaClass.getDeclaredField("driver").apply { isAccessible = true }.get(driven)
                val client = driver.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(driver)
                val transportField = client.javaClass.getDeclaredField("transport").apply { isAccessible = true }
                val real = transportField.get(client) as LinkJsonTransport
                transportField.set(client, tap.wrap(real))
                synchronized(restores) { restores += { transportField.set(client, real) } }
            }
            class Tapped : HashMap<String, Any?>() {
                override fun put(key: String, value: Any?): Any? { tapDriven(value); return super.put(key, value) }
            }
            val tapped = Tapped()
            synchronized(runtime) {
                // Each held driver is wrapped exactly once, by put.
                original.forEach { (key, value) -> tapped.put(key, value) }
                field.set(runtime, tapped)
            }
            return {
                synchronized(runtime) {
                    synchronized(restores) { restores.forEach { it() } }
                    original.clear(); original.putAll(tapped)
                    field.set(runtime, original)
                }
            }
        }

        fun modeOf(wire: String): Mode = Mode.entries.single { it.wire == wire }
    }
}
