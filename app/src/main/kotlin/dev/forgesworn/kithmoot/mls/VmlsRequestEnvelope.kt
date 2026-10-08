package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.checkedSignedEvent
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import java.security.SecureRandom
import kotlinx.serialization.json.*

/** NIP-17/NIP-59 for the revocation channel; the device-key vault never seals. */
object VmlsRequestEnvelope {
    suspend fun wrap(request: VmlsRevocationRequest, signer: ParticipantSigner, current: () -> Boolean, random: SecureRandom = SecureRandom()): NostrEvent {
        check(signer.pubkey == request.sender && current())
        val encrypted = signer.nip44Encrypt(request.keeper, request.rumor().toString())
        check(current())
        val stamp = (request.createdAt - random.nextInt(172801)).coerceAtLeast(0)
        val seal = checkedSignedEvent(signer.sign(13, stamp, emptyList(), encrypted), signer.pubkey, 13, stamp, emptyList(), encrypted)
        check(current())
        val key = Entropy.bytes(32)
        try {
            val content = Nip44.encrypt(seal.toCompactJson(), Nip44.conversationKey(key, request.keeper.hexToBytes()))
            return Events.sign(key, 1059, (request.createdAt - random.nextInt(172801)).coerceAtLeast(0), listOf(listOf("p", request.keeper)), content)
        } finally { key.fill(0) }
    }

    suspend fun unwrap(wrap: NostrEvent, signer: ParticipantSigner, now: Long, current: () -> Boolean): VmlsRevocationRequest {
        require(wrap.kind == 1059 && wrap.tags == listOf(listOf("p", signer.pubkey)) && wrap.content.length <= 40_000 && Events.verify(wrap))
        check(current())
        val plain = signer.nip44Decrypt(wrap.pubkey, wrap.content)
        check(current()); require(plain.length <= 32_768)
        val seal = NostrEvent.fromJson(Json.parseToJsonElement(plain))
        require(seal.kind == 13 && seal.tags.isEmpty() && seal.content.length <= 24_000 && Events.verify(seal))
        val rumor = signer.nip44Decrypt(seal.pubkey, seal.content)
        check(current())
        return VmlsRevocationRequest.parse(rumor, seal.pubkey, signer.pubkey, now)
    }
}
