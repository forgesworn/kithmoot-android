package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.epoch.NativeKeeperBinding
import dev.forgesworn.kithmoot.relay.RoomRoute
import kotlinx.serialization.json.*

/** Public binding only. The independently encrypted source owns all root signing material. */
internal object NativeKeeperReference {
    fun encode(binding: NativeKeeperBinding, invitationId: String): JsonObject = encode(binding, invitationId, null)
    fun encode(binding: NativeKeeperBinding, invitationId: String, generation: Int?): JsonObject = buildJsonObject {
        require(invitationId.matches(Regex("[0-9a-f]{64}")))
        generation?.let { require(it in 0 until dev.forgesworn.kithmoot.epoch.NativeKeeperJournal.MAX_RETIREMENTS) }
        put("v", if (generation == null) 1 else 2)
        put("profile", "native-keeper-v1")
        put("room", binding.room)
        put("root", binding.authority)
        put("participant", binding.participant)
        put("device", binding.device)
        put("route", binding.route.stored)
        put("relays", JsonArray(binding.relays.map(::JsonPrimitive)))
        put("pin", binding.pin)
        put("invitation", invitationId)
        generation?.let { put("generation", it) }
    }

    fun decode(value: JsonElement): NativeKeeperBinding {
        val json = value.jsonObject
        val version = json.getValue("v").jsonPrimitive
        require(!version.isString && version.intOrNull in setOf(1, 2)) { "Native authority reference needs explicit migration" }
        require(json.keys == setOf("v", "profile", "room", "root", "participant", "device", "route", "relays", "pin", "invitation") +
            if (version.intOrNull == 2) setOf("generation") else emptySet())
        if (version.intOrNull == 2) {
            val generation = json.getValue("generation").jsonPrimitive
            require(!generation.isString && generation.intOrNull in 0 until dev.forgesworn.kithmoot.epoch.NativeKeeperJournal.MAX_RETIREMENTS)
        }
        fun text(key: String) = json.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        require(text("profile") == "native-keeper-v1")
        require(text("invitation").matches(Regex("[0-9a-f]{64}")))
        val relays = json.getValue("relays").jsonArray.map { it.jsonPrimitive.also { url -> require(url.isString) }.content }
        val binding = NativeKeeperBinding(text("room"), text("root"), text("participant"), text("device"),
            RoomRoute.fromStored(text("route")), relays)
        require(relays == binding.relays && text("pin") == binding.pin) { "Native authority binding changed" }
        return binding
    }

    fun generation(value: JsonElement): Int {
        decode(value) // Never accept a bare number or partial reference as evidence.
        return value.jsonObject["generation"]?.jsonPrimitive?.int ?: 0
    }
}
