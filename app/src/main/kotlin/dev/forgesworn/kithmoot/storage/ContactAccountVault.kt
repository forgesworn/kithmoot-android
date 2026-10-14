package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.account.ProjectStorage
import java.util.concurrent.ConcurrentHashMap

/** Contacts and their invitation inbox have independent account-bound vaults.
 * Android Keystore and AtomicFile supply the same at-rest guarantees as rooms. */
class ContactAccountVault(context: Context, account: String, purpose: String) : ProjectStorage {
    init { require(account.matches(Regex("[0-9a-f]{64}"))); require(purpose in setOf("directory", "inbox")) }
    private val alias = "kithmoot.contacts.$purpose.$account"
    private val vault = EncryptedRoomStorage(context, alias, 4 * 1024 * 1024)
    private val owner = Any()
    override suspend fun acquire() { check(owners.putIfAbsent(alias, owner) == null) { "Contacts are open in another window" } }
    private fun held() = check(owners[alias] === owner) { "Contact storage is not open" }
    override suspend fun load(): String? { held(); return vault.read()?.let { try { it.toString(Charsets.UTF_8) } finally { it.fill(0) } } }
    override suspend fun save(value: String) { held(); val bytes = value.toByteArray(Charsets.UTF_8); try { vault.write(bytes) } finally { bytes.fill(0) } }
    override suspend fun release() { owners.remove(alias, owner) }
    companion object { private val owners = ConcurrentHashMap<String, Any>() }
}
