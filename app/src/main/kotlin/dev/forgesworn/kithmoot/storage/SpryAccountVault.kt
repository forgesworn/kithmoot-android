package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.account.ProjectStorage
import java.util.concurrent.ConcurrentHashMap

/** Account-scoped encrypted cache and outbox, separate from the local pairing. */
internal class SpryAccountVault(context: Context, identity: String) : ProjectStorage {
    private val alias = "kithmoot.spry-sync.$identity"
    private val owner = Any()
    private val vault = EncryptedRoomStorage(context, alias, 40000)
    override suspend fun acquire() { check(owners.putIfAbsent(alias, owner) == null) { "Spry sync is open in another window" } }
    private fun held() = check(owners[alias] === owner)
    override suspend fun load(): String? { held(); return vault.read()?.let { bytes -> try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) } } }
    override suspend fun save(value: String) { held(); val bytes = value.toByteArray(Charsets.UTF_8); try { vault.write(bytes) } finally { bytes.fill(0) } }
    override suspend fun release() { owners.remove(alias, owner) }
    companion object { private val owners = ConcurrentHashMap<String, Any>() }
}
