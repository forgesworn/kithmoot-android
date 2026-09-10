package dev.forgesworn.kithmoot.storage

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.session.AssignmentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Each room and participant has an independent, device-encrypted atomic vault. */
class AssignmentVault(context:Context,roomId:String,participant:String):AssignmentStorage {
    private val vault=EncryptedRoomStorage(context,"kithmoot.work."+Digests.sha256("$roomId:$participant".toByteArray(Charsets.UTF_8)).toHex())
    override suspend fun load():String?=withContext(Dispatchers.IO){vault.read()?.toString(Charsets.UTF_8)}
    override suspend fun save(encrypted:String)=withContext(Dispatchers.IO){
        val bytes=encrypted.toByteArray(Charsets.UTF_8)
        require(bytes.size<=4*1024*1024){"Assignment history has reached this device’s storage limit"}
        vault.write(bytes)
    }
    fun reset()=vault.reset()
}
