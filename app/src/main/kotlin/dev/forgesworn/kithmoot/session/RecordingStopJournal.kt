package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*

/** Device-private, already signed Off notices. No room, device or authority
 * secret is stored. Arm before publishing On so process death cannot lose the
 * notice needed after capture disappears. The application supplies encrypted,
 * backup-excluded storage. An Off is re-enveloped by the current live session. */
class RecordingStopJournal(private val storage: RoomStorage) {
    private data class Entry(val room: String, val device: String, val signed: SignedRecordingNotice)
    private val generations = mutableMapOf<String, Long>()
    @Synchronized fun generation(room: String): Long = generations[room] ?: 0L

    @Synchronized fun pending(room: String, device: String, authority: String): SignedRecordingNotice? {
        val entry = read().firstOrNull { it.room == room && it.device == device } ?: return null
        check(verifyRecordingNotice(room, entry.signed.notice, entry.signed.sig, authority)) {
            "The retained recording stop notice could not be verified"
        }
        return entry.signed
    }

    @Synchronized fun arm(room: String, device: String, signed: SignedRecordingNotice, authority: String,
        generation: Long = generation(room)) {
        check(generation == generation(room)) { "This room's recording notices were forgotten" }
        require(room.matches(HEX) && device.matches(HEX) && !signed.notice.on)
        require(verifyRecordingNotice(room, signed.notice, signed.sig, authority))
        val items = read()
        val existing = items.firstOrNull { it.room == room && it.device == device }
        check(existing == null || existing.signed == signed) { "An earlier recording still needs its stop notice" }
        if (existing != null) return
        check(items.size < MAX_ENTRIES) { "Too many recording stop notices are waiting" }
        write(items + Entry(room, device, signed))
    }

    @Synchronized fun confirm(room: String, device: String, signed: SignedRecordingNotice) {
        val items = read()
        write(items.filterNot { it.room == room && it.device == device && it.signed == signed })
    }

    @Synchronized fun forgetRoom(room: String) {
        generations[room] = generation(room) + 1
        write(read().filterNot { it.room == room })
    }

    private fun read(): List<Entry> {
        val bytes = storage.read() ?: return emptyList()
        val array = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray
        check(array.size <= MAX_ENTRIES)
        val entries = array.map { value ->
            val obj = value.jsonObject
            val room = obj.getValue("room").jsonPrimitive.content
            val device = obj.getValue("device").jsonPrimitive.content
            val signed = checkNotNull(decodeRecordingOp(obj.getValue("body").jsonPrimitive.content))
            check(room.matches(HEX) && device.matches(HEX) && !signed.notice.on)
            Entry(room, device, signed)
        }
        check(entries.map { it.room to it.device }.distinct().size == entries.size)
        return entries
    }

    private fun write(items: List<Entry>) = storage.write(buildJsonArray {
        items.forEach { entry -> add(buildJsonObject {
            put("room", entry.room); put("device", entry.device); put("body", encodeRecordingOp(entry.signed))
        }) }
    }.toString().toByteArray(Charsets.UTF_8))

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        private const val MAX_ENTRIES = 128
    }
}
