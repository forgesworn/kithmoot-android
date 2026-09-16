package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.account.ProjectStorage
import dev.forgesworn.kithmoot.account.RoomBookmarks
import java.util.concurrent.ConcurrentHashMap

/** Separate account cache: room admissions and device credentials never enter this journal. */
class RoomBookmarkVault(context: Context, identity: String) : ProjectStorage {
    private val alias = "kithmoot.bookmarks.$identity"
    private val owner = Any()
    private val vault = EncryptedRoomStorage(context, alias, RoomBookmarks.MAX_CACHE_BYTES)
    override suspend fun acquire() { check(owners.putIfAbsent(alias, owner) == null) { "Room sync is open in another window" } }
    private fun held() = check(owners[alias] === owner) { "Room bookmark vault is not open" }
    override suspend fun load(): String? { held(); return vault.read()?.let { bytes -> try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) } } }
    override suspend fun save(value: String) { held(); val bytes = value.toByteArray(); try { vault.write(bytes) } finally { bytes.fill(0) } }
    override suspend fun release() { owners.remove(alias, owner) }
    companion object { private val owners = ConcurrentHashMap<String, Any>() }
}
