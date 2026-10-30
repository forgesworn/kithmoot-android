package dev.forgesworn.kithmoot.callrelay

import android.util.AtomicFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File

/**
 * Today's relayed bytes, for the daily limit: a day and a count, no secret,
 * kept beside the node's state in `noBackupFilesDir`. Written only by the
 * relay process.
 */
internal class RelayUsageFile(directory: File) : RelayUsageStore {
    private val file = AtomicFile(File(directory, "usage.json"))
    private val day = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    override fun load(): RelayDailyUsage = try {
        val bytes = file.readFully()
        require(bytes.size <= 1024)
        val raw = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(raw.getValue("v").jsonPrimitive.int == 1)
        val saved = raw.getValue("day").jsonPrimitive.content
        val count = raw.getValue("bytes").jsonPrimitive.long
        require(day.matches(saved) && count >= 0)
        RelayDailyUsage(saved, count)
    } catch (_: Exception) { RelayDailyUsage() }

    override fun save(usage: RelayDailyUsage) {
        val bytes = buildJsonObject { put("v", 1); put("day", usage.day); put("bytes", usage.bytes) }.toString().toByteArray(Charsets.UTF_8)
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
