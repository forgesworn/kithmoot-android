package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.account.ProjectStorage
import dev.forgesworn.kithmoot.account.SharedProjects
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Device-encrypted account cache, excluded from Android backups; one owner across app activities. */
class ProjectVault(context: Context, identity: String) : ProjectStorage {
    private val alias = "kithmoot.projects." + Digests.sha256(identity.toByteArray()).toHex()
    private val owner = Any()
    private val vault = EncryptedRoomStorage(context, alias, SharedProjects.MAX_CACHE_BYTES)
    override suspend fun acquire() { check(owners.putIfAbsent(alias, owner) == null) { "Projects are already open in another window" } }
    private fun held() = check(owners[alias] === owner) { "This project vault is not open" }
    override suspend fun load(): String? = withContext(Dispatchers.IO) { held(); vault.read()?.toString(Charsets.UTF_8) }
    override suspend fun save(value: String) = withContext(Dispatchers.IO) {
        held(); val bytes = value.toByteArray(Charsets.UTF_8)
        check(bytes.size <= SharedProjects.MAX_CACHE_BYTES) { "Projects have reached this device's storage limit" }
        vault.write(bytes)
    }
    override suspend fun release() { owners.remove(alias, owner) }
    companion object { private val owners = ConcurrentHashMap<String, Any>() }
}
