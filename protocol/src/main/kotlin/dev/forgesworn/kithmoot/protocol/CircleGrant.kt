package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex

/** Private Bothy room authority. The event is intercepted and never enters room history. */
const val KIND_CIRCLE_EVENT_GRANT: Int = 24242
const val MAX_CIRCLE_GRANT_LIFETIME_SECONDS: Long = 400L * 24 * 60 * 60

enum class CircleGrantStatus(val wire: String) { ACTIVE("active"), REVOKED("revoked") }

data class CircleGrantTerms(
    val server: String,
    val room: String,
    val persona: String,
    val device: String,
    val grantId: String,
    val expiration: Long,
) {
    init {
        require(SERVER.matches(server)) { "The Bothy relay URL is not canonical." }
        require(HEX64.matches(room) && HEX64.matches(persona) && HEX64.matches(device))
        require(HEX32.matches(grantId))
        require(expiration > 0)
    }

    fun tags(status: CircleGrantStatus): List<List<String>> = listOf(
        listOf("t", "event-grant"),
        listOf("server", server),
        listOf("d", room),
        listOf("p", persona),
        listOf("device", device),
        listOf("grant", grantId),
        listOf("read", "1460"),
        listOf("read", "1462"),
        listOf("read", "20461"),
        listOf("write", "1460"),
        listOf("write", "20461"),
        listOf("expiration", expiration.toString()),
        listOf("status", status.wire),
    )

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        val HEX32 = Regex("[0-9a-f]{32}")
        val SERVER = Regex("ws://[a-z2-7]{52}/events")
    }
}

/** The VMLS/1 deposit scope's byte ceiling (D5), 64 MiB per device (P3-03b-3 decision 9). */
const val VMLS_GRANT_CEILING_BYTES: Long = 64L * 1024 * 1024
const val MAX_VMLS_GRANT_CEILING_BYTES: Long = 1L shl 30
/** A VMLS grant's term (P3-03b-3 decision 9), renewed as room grants are. */
const val VMLS_GRANT_LIFETIME_SECONDS: Long = 30L * 24 * 60 * 60

/**
 * A box's VMLS/1 grant to one MLS device (P3-03b-3 decision 9): one per
 * device per box, not per room, so its `d` is derived from the device and
 * names no room. It reads kind 1460 only, since a grant must read one kind,
 * writes nothing, and carries the `vmls` byte ceiling. Bothy keeps one
 * active grant id per scope (its rule 18), and a revocation must carry that
 * id and an expiration no earlier than the latest it granted (rule 17), so
 * an issuer keeps [grantId] and that expiration for as long as the grant may
 * be active: a renewal and the revocation reuse them.
 */
data class VmlsGrantTerms(
    val server: String,
    val persona: String,
    val device: String,
    val grantId: String,
    val expiration: Long,
    val ceiling: Long = VMLS_GRANT_CEILING_BYTES,
) {
    init {
        require(SERVER.matches(server)) { "The Bothy relay URL is not canonical." }
        require(HEX64.matches(persona) && HEX64.matches(device))
        require(HEX32.matches(grantId))
        require(expiration > 0)
        require(ceiling in 1..MAX_VMLS_GRANT_CEILING_BYTES)
    }

    val room: String get() = vmlsGrantRoom(device)

    fun tags(status: CircleGrantStatus): List<List<String>> = listOf(
        listOf("t", "event-grant"),
        listOf("server", server),
        listOf("d", room),
        listOf("p", persona),
        listOf("device", device),
        listOf("grant", grantId),
        listOf("read", "1460"),
        listOf("expiration", expiration.toString()),
        listOf("vmls", ceiling.toString()),
        // Last, as in room grants: a revocation differs from its grant only here.
        listOf("status", status.wire),
    )

    private companion object {
        val HEX64 = Regex("[0-9a-f]{64}")
        val HEX32 = Regex("[0-9a-f]{32}")
        val SERVER = Regex("ws://[a-z2-7]{52}/events")
    }
}

/** `SHA-256("VMLS/1 box grant" || device)`: the VMLS grant's `d`, which names no room. */
fun vmlsGrantRoom(device: String): String =
    Digests.sha256("VMLS/1 box grant".toByteArray() + device.hexToBytes()).toHex()
