package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*

/** Device-private, already signed Off notices. No room, device or authority
 * secret is stored. Arm before publishing On so process death cannot lose the
 * notice needed after capture disappears. The application supplies encrypted,
 * backup-excluded storage. An Off is re-enveloped by the current live session. */
class RecordingStopJournal(private val storage: RoomStorage) {
    private data class Entry(val room: String, val device: String, val signed: SignedRecordingNotice,
        val call: String? = null, val participant: String? = null)
    private val generations = mutableMapOf<String, Long>()
    @Synchronized fun generation(room: String): Long = generations[room] ?: 0L
    @Synchronized fun pendingCall(room: String, device: String): String? =
        read().firstOrNull { it.room == room && it.device == device }?.call
    @Synchronized fun pendingParticipant(room: String, device: String): String? =
        read().firstOrNull { it.room == room && it.device == device }?.participant

    @Synchronized fun pending(room: String, device: String, authority: String): SignedRecordingNotice? {
        val entry = read().firstOrNull { it.room == room && it.device == device } ?: return null
        check(verifyRecordingNotice(room, entry.signed.notice, entry.signed.sig, authority)) {
            "The retained recording stop notice could not be verified"
        }
        return entry.signed
    }

    @Synchronized fun arm(room: String, device: String, signed: SignedRecordingNotice, authority: String,
        generation: Long = generation(room), call: String? = null, participant: String? = null) {
        check(generation == generation(room)) { "This room's recording notices were forgotten" }
        require(room.matches(HEX) && device.matches(HEX) && !signed.notice.on)
        require(verifyRecordingNotice(room, signed.notice, signed.sig, authority))
        require(call == null || call.matches(Regex("[0-9a-f]{32}")))
        require(participant == null || (call != null && participant.matches(HEX)))
        val items = read()
        val existing = items.firstOrNull { it.room == room && it.device == device }
        check(existing == null || (existing.signed == signed && existing.call == call && existing.participant == participant)) {
            "An earlier recording still needs its original stop context"
        }
        if (existing != null) return
        check(items.size < MAX_ENTRIES) { "Too many recording stop notices are waiting" }
        write(items + Entry(room, device, signed, call, participant))
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
            val callValue = obj["call"]
            check(callValue == null || (callValue is JsonPrimitive && callValue.isString))
            val call = (callValue as? JsonPrimitive)?.content
            check(call == null || call.matches(Regex("[0-9a-f]{32}")))
            // Older participant journals have a call but no participant. Keep
            // those readable; new entries retain the original person as well.
            val participantValue = obj["participant"]
            check(participantValue == null || (participantValue is JsonPrimitive && participantValue.isString))
            val participant = (participantValue as? JsonPrimitive)?.content
            check(participant == null || (call != null && participant.matches(HEX)))
            Entry(room, device, signed, call, participant)
        }
        check(entries.map { it.room to it.device }.distinct().size == entries.size)
        return entries
    }

    private fun write(items: List<Entry>) = storage.write(buildJsonArray {
        items.forEach { entry -> add(buildJsonObject {
            put("room", entry.room); put("device", entry.device); put("body", encodeRecordingOp(entry.signed))
            entry.call?.let { put("call", it) }
            entry.participant?.let { put("participant", it) }
        }) }
    }.toString().toByteArray(Charsets.UTF_8))

    companion object {
        private val HEX = Regex("[0-9a-f]{64}")
        private const val MAX_ENTRIES = 128
    }
}
