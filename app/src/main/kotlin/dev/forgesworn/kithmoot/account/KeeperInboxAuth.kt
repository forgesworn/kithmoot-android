package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CancellationException

/** One challenge from a private Link box. The selected keeper is explicit and never inferred from untrusted permission data. */
data class KeeperInboxAuthRequest(
    val box: String,
    val keeper: String,
    val permission: NostrEvent,
    val challenge: String,
    val expiresAt: Long,
    val v: Int = 1,
) {
    internal fun snapshot(): KeeperInboxAuthRequest? = snapshotKeeperInboxPermission(permission)?.let { copy(permission = it) }
    internal val destination: String get() = "ws://${linkNodeBase32(box.hexToBytes())}/events"
    internal fun authTags() = listOf(listOf("relay", destination), listOf("challenge", challenge))

    internal fun refusal(now: Long): VaultRefusal? {
        if (v != 1 || !MlsVault.HEX64.matches(challenge) || now !in 0..KEEPER_MAX_SAFE_EPOCH || expiresAt !in 0..KEEPER_MAX_SAFE_EPOCH)
            return VaultRefusal.Malformed
        if (expiresAt <= now) return VaultRefusal.Expired
        if (expiresAt - now > 120) return VaultRefusal.Malformed
        return keeperInboxPermissionRefusal(box, keeper, permission, now)
    }

}

private const val KEEPER_MAX_SAFE_EPOCH = 9_007_199_254_740_991L
private val KEEPER_TAGS = setOf("t", "server", "device", "grant", "expiration", "status")
private fun boundedKeeperPermission(p: NostrEvent) = p.tags.size == KEEPER_TAGS.size &&
    p.tags.all { tag -> tag.size == 2 && tag.all { it.length <= 128 } }

internal fun snapshotKeeperInboxPermission(permission: NostrEvent): NostrEvent? =
    if (!boundedKeeperPermission(permission)) null else permission.copy(tags = permission.tags.map { it.toList() })

/** Signature validation does not establish current keeper authority; the destination box checks it. */
internal fun keeperInboxPermissionRefusal(box: String, keeper: String, p: NostrEvent, now: Long): VaultRefusal? {
    if (!MlsVault.HEX64.matches(box) || !MlsVault.HEX64.matches(keeper) || now !in 0..KEEPER_MAX_SAFE_EPOCH ||
        !boundedKeeperPermission(p) || p.createdAt !in 0..KEEPER_MAX_SAFE_EPOCH || p.createdAt > now + 300 ||
        p.tags.map { it[0] }.toSet() != KEEPER_TAGS) return VaultRefusal.Malformed
    if (p.kind != 24242 || p.content.isNotEmpty() || p.pubkey != keeper || !MlsVault.HEX64.matches(p.id) ||
        !Regex("[0-9a-f]{128}").matches(p.sig) || !Events.verify(p)) return VaultRefusal.Unauthorised
    val destination = "ws://${linkNodeBase32(box.hexToBytes())}/events"
    if (p.tagValue("t") != "keeper-inbox-deposit" || p.tagValue("server") != destination ||
        !MlsVault.HEX64.matches(p.tagValue("device")!!) || !Regex("[0-9a-f]{32}").matches(p.tagValue("grant")!!) ||
        p.tagValue("status") != "active") return VaultRefusal.Malformed
    val expiration = p.tagValue("expiration")!!.takeIf { Regex("0|[1-9][0-9]*").matches(it) }?.toLongOrNull()
        ?: return VaultRefusal.Malformed
    if (expiration <= now) return VaultRefusal.Expired
    if (expiration > KEEPER_MAX_SAFE_EPOCH || expiration - now > 30 * 86_400L) return VaultRefusal.Malformed
    return null
}

/** A purpose-specific, one-operation prompt. No scope is retained in the vault policy. */
class KeeperInboxConsent internal constructor(
    val principal: String,
    val persona: String,
    val device: String,
    val box: String,
    val keeper: String,
    val grant: String,
    val destination: String,
    val permissionId: String,
    val expiresAt: Long,
    val isCurrent: () -> Boolean,
) {
    val purpose: String get() = "keeper-inbox-deposit-auth"
    val method: String get() = "signKeeperInboxAuthV1/1"
}

fun interface KeeperInboxConsentPrompt { suspend fun ask(scope: KeeperInboxConsent): ConsentDecision }

/** Identity is significant: copying the signed event cannot manufacture vault reply provenance. */
class KeeperInboxAuthReply internal constructor(val device: String, val event: NostrEvent) {
    val v: Int get() = 1
}

internal fun keeperOperationCurrent(current: () -> Boolean): Boolean = try { current() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
