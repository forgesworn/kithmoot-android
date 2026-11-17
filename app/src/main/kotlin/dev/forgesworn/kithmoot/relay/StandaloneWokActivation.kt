package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class StandaloneWokActivation(private val sockets: RelaySocketFactory) {
    suspend fun publish(
        consent: StandaloneWokConsent,
        signer: ParticipantSigner,
        events: List<NostrEvent>,
        readback: NostrEvent? = null,
    ) {
        val context = currentCoroutineContext()
        val scope = CoroutineScope(context + SupervisorJob(context[Job]))
        val relay = StandaloneWokRelay(sockets).pool(consent.canonicalUrl, signer, scope)
        try {
            relay.start()
            withTimeoutOrNull(20_000) { relay.connected.first { consent.canonicalUrl in it } }
                ?: error("The standalone Wok relay did not authenticate this account.")
            coroutineScope {
                val pendingReadback = readback?.let { expected ->
                    val filters = listOf(Filter(
                        ids = listOf(expected.id), kinds = listOf(expected.kind), tags = mapOf("#d" to listOf(consent.roomId)),
                    ))
                    async(start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(15_000) { relay.subscribe(filters).first { it.id == expected.id && Events.verify(it) } }
                    }
                }
                for (event in events) {
                    var failure: Exception? = null
                    var confirmed = false
                    repeat(2) {
                        if (confirmed) return@repeat
                        try {
                            confirmed = relay.publishConfirmed(event)
                            if (!confirmed) error("Wok refused a required room event.")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failure = error
                        }
                    }
                    if (!confirmed) throw IllegalStateException(failure?.message ?: "Wok did not confirm a required room event.")
                }
                pendingReadback?.await()
            }
        } finally {
            relay.stop()
            scope.cancel()
        }
    }
}
