package dev.forgesworn.kithmoot.vmls

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex

/** Independent strict reader for the experimental invitation signing ceremony. */
object InvitationAuthorization {
    const val METHOD = "signInvitationAuthorizationV1/1"
    private fun bad(): Nothing = throw BindingException(BindingErrorCode.Malformed)
    fun digest(body: ByteArray): ByteArray = Digests.sha256("Vennel invitation/1 authorization\u0000".toByteArray() + body)

    fun readBody(body: ByteArray): InvitationSigningBody {
        if (body.size > 12_288) bad()
        val r = CborReader(body)
        r.map(3); r.key(1); if (r.head(0) != 1UL) bad()
        r.key(2); val context = r.bytes(1024)
        r.key(3); val binding = r.bytes(LeafBinding.MAX_BINDING_BYTES)
        r.finish()
        if (binding.size < 68 || binding[0] != 0xa8.toByte()) bad()
        val tail = binding.copyOfRange(binding.size - 67, binding.size)
        if (!tail.copyOfRange(0, 3).contentEquals(byteArrayOf(8, 0x58, 0x40))) bad()
        val unsigned = binding.copyOfRange(0, binding.size - 67).also { it[0] = 0xa7.toByte() }
        val leaf = LeafBinding.readUnsigned(unsigned)
        if (!Schnorr.verify(tail.copyOfRange(3, 67), LeafBinding.digest(unsigned), leaf.device)) bad()
        val c = readContext(context)
        if (leaf.event.pubkey != c.inviterPersona || leaf.device.toHex() != c.inviterDevice) bad()
        if (c.expiresAt > leaf.expiresAt) bad()
        return InvitationSigningBody(c, leaf, context.copyOf())
    }

    fun readContext(bytes: ByteArray): InvitationSigningContext {
        if (bytes.size > 1024) bad()
        val r = CborReader(bytes)
        r.map(16); r.key(1); if (r.head(0) != 1UL) bad()
        r.key(2); if (r.text(64) != "Vennel/invitation/1") bad()
        r.key(3); if (r.head(0) != 1UL) bad()
        val values = (4..14).map { key -> r.key(key); r.fixed(32).toHex() }
        r.key(15)
        val routeSize = r.head(5)
        r.key(1)
        val route = r.head(0)
        val routeNode = when {
            routeSize == 1UL && route == 1UL -> null
            routeSize == 2UL && route == 2UL -> { r.key(2); r.fixed(32).toHex() }
            else -> bad()
        }
        r.key(16); val expires = r.timestamp(); r.finish()
        if (expires <= 0) bad()
        return InvitationSigningContext(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8], values[9], values[10], routeNode, expires)
    }
}

data class InvitationSigningContext(
    val invitationId: String, val inviterPersona: String, val inviterDevice: String, val inviterRendezvous: String,
    val joinerPersona: String, val joinerDevice: String, val joinerRendezvous: String,
    val group: String, val groupHome: String, val installation: String, val leafHome: String,
    /** Null denotes authenticated private out of band exchange. Never a storage grant. */
    val returnNode: String?, val expiresAt: Long,
)

class InvitationSigningBody(val context: InvitationSigningContext, val binding: UnsignedBinding, val contextBytes: ByteArray)
