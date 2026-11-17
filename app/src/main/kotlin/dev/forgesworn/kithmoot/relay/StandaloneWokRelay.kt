package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.protocol.canonicalStandaloneRoomServer
import kotlinx.coroutines.CoroutineScope

class StandaloneWokRelay(private val sockets: RelaySocketFactory = OkHttpRelaySockets()) {
    fun pool(url: String, signer: ParticipantSigner, scope: CoroutineScope): RelayPool {
        require(canonicalStandaloneRoomServer(url)) { "The standalone Wok relay URL is not canonical." }
        val authenticator = object : RelayAuthenticator {
            override val pubkey = signer.pubkey
            override suspend fun sign(url: String, challenge: String) = signer.sign(
                22242, System.currentTimeMillis() / 1000,
                listOf(listOf("relay", url), listOf("challenge", challenge)), "",
            )
        }
        return RelayPool(listOf(url), sockets, scope,
            authenticators = RelayAuthenticatorProvider { requested -> authenticator.takeIf { requested == url } },
            pinnedUrls = setOf(url))
    }
}
