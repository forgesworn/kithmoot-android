package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.protocol.*
import kotlinx.serialization.json.*

/** Optional artwork provenance, never a room capability or identity proof. */
data class SavedRoomLogo(val record: RoomLogoRecord, val epoch: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("image", record.image?.toJson() ?: JsonNull)
        put("id", record.id); put("at", record.at); put("sentAt", record.sentAt); put("epoch", epoch)
    }

    companion object {
        fun read(value: JsonElement?): SavedRoomLogo? = runCatching {
            val raw = value as? JsonObject ?: return null
            if (raw.keys != setOf("image", "id", "at", "sentAt", "epoch")) return null
            val id = (raw["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val at = (raw["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            val sentAt = (raw["sentAt"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
            val epoch = (raw["epoch"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
            val image = if (raw["image"] == JsonNull) null else LogoImages.read(raw["image"]) ?: return null
            roomLogoOp(image, at, id)
            if (epoch < 0 || sentAt !in 0..9_007_199_254_740_991L || Math.floorDiv(at, 1000L) > sentAt) return null
            SavedRoomLogo(RoomLogoRecord(image, id, at, sentAt), epoch)
        }.getOrNull()
    }
}
