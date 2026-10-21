package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.normaliseHex

/**
 * The tag on a rekey (kind 1462) or an authority's grant (kind 20469) that names the one-time
 * key its copies were sealed from. The authority still signs the event, so the tag is the
 * authority's word. fold-kit's `SENDER_TAG`; see its `docs/seal-key.md`, "One-time senders".
 */
const val SENDER_TAG = "from"

private val SEAL_PUBKEY = Regex("[0-9a-fA-F]{64}")

/** 32-byte hex that lifts to a point on secp256k1. Matches fold-kit's `isSealPubkey`. */
fun isSealPubkey(value: String?): Boolean =
    value != null && SEAL_PUBKEY.matches(value) && Schnorr.liftsToPoint(value.hexToBytes())

/**
 * Who sealed the copies in a rekey or an authority's grant: the key its single `from` tag
 * names (lower case), or the signer when there is no such tag, which is every event written
 * before one-time senders. Null when the tags are unusable, more than one `from` or one that
 * is not a point on the curve. Only the authority could have signed either, so a reader treats
 * null as a copy it cannot open. Matches fold-kit's `sealSender`.
 */
fun sealSender(event: NostrEvent): String? {
    val from = event.tags.filter { it.firstOrNull() == SENDER_TAG }
    if (from.isEmpty()) return event.pubkey.normaliseHex()
    if (from.size > 1) return null
    return from.single().getOrNull(1)?.takeIf(::isSealPubkey)?.normaliseHex()
}
