package dev.forgesworn.kithmoot.mixed

import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * Lab-only hold on one local session's box traffic (M1b historical-key check).
 *
 * The harness supplies every mailbox and commit-slot id the session watched at
 * its last epoch. A box request naming any of them is failed before it leaves
 * the device: a commit-slot read or deposit by path, a fetch or ack by the hex
 * ids in its body. The product client sees no reply (`Unreachable`), so that
 * session never learns of a later commit. It models a removed device that
 * ignores its removal. Other sessions' requests pass unchanged.
 */
internal class SessionHoldTap(ids: Collection<String>) {
    private val held = ids.toSet()
    private var refused = 0
    private var passed = 0
    private var wrapped = 0

    init { require(held.isNotEmpty() && held.all { it.matches(HEX64) }) }

    fun wrap(real: LinkJsonTransport): LinkJsonTransport {
        synchronized(this) { wrapped++ }
        return LinkJsonTransport { request -> forward(real, request) }
    }

    private fun forward(real: LinkJsonTransport, request: LinkJsonRequest): CompletableFuture<LinkJsonResponse> {
        if (names(request)) {
            synchronized(this) { refused++ }
            return CompletableFuture.failedFuture(IOException("lab: held session"))
        }
        synchronized(this) { passed++ }
        return real.request(request)
    }

    private fun names(request: LinkJsonRequest): Boolean {
        if (held.any { request.path.contains(it) }) return true
        if (request.path != "/vmls/v1/fetch" && request.path != "/vmls/v1/ack") return false
        val body = request.body.decodeToString()
        return held.any { body.contains(it) }
    }

    fun report(): JsonObject = synchronized(this) {
        buildJsonObject { put("ids", held.size); put("clientsWrapped", wrapped); put("refused", refused); put("passed", passed) }
    }

    private companion object { val HEX64 = Regex("[0-9a-f]{64}") }
}
