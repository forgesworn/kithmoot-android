package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout

/**
 * A persona's witness traffic over Link (P3-03b-2): `POST
 * /vmls-witness/v1/read` and `/advance` with an empty Authorization on the
 * persona's own pinned route. The box authorises by the Link session's peer
 * node id alone, so no Nostr authorisation is sent.
 *
 * PR 5 gives each persona its own LinkEngine and witness route; here the
 * transport and route id are given.
 */
class WitnessLink(
    private val transport: LinkJsonTransport,
    private val routeId: String,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : WitnessChannel {
    override suspend fun read(request: ByteArray): WitnessAnswer = exchange(READ_PATH, request)
    override suspend fun advance(request: ByteArray): WitnessAnswer = exchange(ADVANCE_PATH, request)

    private suspend fun exchange(path: String, body: ByteArray): WitnessAnswer {
        val response = try {
            withTimeout(timeoutMillis) {
                transport.request(LinkJsonRequest(routeId, "POST", path, "", body.copyOf())).await()
            }
        } catch (_: TimeoutCancellationException) {
            return WitnessAnswer.Unavailable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return WitnessAnswer.Unavailable
        }
        return witnessAnswer(response)
    }

    companion object {
        const val READ_PATH = "/vmls-witness/v1/read"
        const val ADVANCE_PATH = "/vmls-witness/v1/advance"
        const val RECEIPT_BYTES = 170
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L

        /**
         * - 200, 409 or 410 with a 170-byte version-1 receipt whose status byte
         *   matches the HTTP status: the receipt (the coordinator verifies it);
         * - an empty 409 (sequence exhausted): unavailable, since the
         *   coordinator fences on exhaustion itself when it stages;
         * - a 403 the bridge marks as the witness's refusal: refused;
         * - anything else, or a mismatch: unavailable.
         */
        fun witnessAnswer(response: LinkJsonResponse): WitnessAnswer {
            val expected = when (response.status) {
                200 -> 0
                409 -> 1
                410 -> 2
                403 -> return if (response.witnessRefused) WitnessAnswer.Refused else WitnessAnswer.Unavailable
                else -> return WitnessAnswer.Unavailable
            }
            val body = response.body
            if (body.size != RECEIPT_BYTES || body[0] != 1.toByte() || body[1].toInt() != expected) return WitnessAnswer.Unavailable
            return WitnessAnswer.Receipt(body.copyOf())
        }
    }
}
