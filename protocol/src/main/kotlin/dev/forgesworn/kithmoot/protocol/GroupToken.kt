package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy

/** A `get` token's life, seconds (contract 6.3). */
const val GROUP_GET_TOKEN_SECONDS: Long = 120
/** An `upload` token's life, seconds. */
const val GROUP_UPLOAD_TOKEN_SECONDS: Long = 90
/** A `delete` token's life: the week KithMoot keeps a signed delete, and a second, exactly the longest a node allows. */
const val GROUP_DELETE_TOKEN_SECONDS: Long = 7 * 86_400 + 1
/** The Blossom authorisation kind (BUD-01, BUD-11). */
const val BLOSSOM_AUTH_KIND: Int = 24242
/** The tag tying a stored delete to the exact URL it was made for. */
const val DELETE_TARGET_TAG = "kithmoot-delete-target-v1"

private val HASH = Regex("^[0-9a-f]{64}$")

/**
 * The signed kind 24242 token for one request to a group node (contract 6.3),
 * signed with the room's device key. `created_at` is one second in the past.
 * A `delete` token may carry [target], the exact URL it was made for. Mirrors
 * `buildGroupToken` in the web client's `group-storage.ts`.
 */
fun buildGroupToken(verb: String, sha256: String, server: String, nowSeconds: Long, deviceSecretKey: ByteArray,
    lifetime: Long? = null, target: String? = null, auxRand: ByteArray = Entropy.bytes(32)): NostrEvent {
    require(verb == "get" || verb == "upload" || verb == "delete") { "A token is for get, upload or delete." }
    val hash = sha256.lowercase()
    require(HASH.matches(hash)) { "The blob hash must be 64 hex characters." }
    require(isHostName(server)) { "The node's name must be a lower-case host name." }
    val life = lifetime ?: when (verb) { "get" -> GROUP_GET_TOKEN_SECONDS; "upload" -> GROUP_UPLOAD_TOKEN_SECONDS; else -> GROUP_DELETE_TOKEN_SECONDS }
    require(life > 0) { "The token lifetime must be a positive number of seconds." }
    require(nowSeconds >= 0) { "The token needs a valid clock." }
    require(target == null || verb == "delete") { "Only a delete token names a target." }
    val createdAt = maxOf(0, nowSeconds - 1)
    val tags = mutableListOf(listOf("t", verb), listOf("expiration", (createdAt + life).toString()), listOf("server", server), listOf("x", hash))
    if (target != null) tags += listOf(DELETE_TARGET_TAG, target)
    val content = when (verb) { "get" -> "Get blob $hash"; "upload" -> "Upload blob $hash to $server"; else -> "Delete blob $hash" }
    return Events.sign(deviceSecretKey, BLOSSOM_AUTH_KIND, createdAt, tags, content, auxRand)
}
