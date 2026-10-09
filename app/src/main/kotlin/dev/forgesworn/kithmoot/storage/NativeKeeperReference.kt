package dev.forgesworn.kithmoot.storage

import dev.forgesworn.kithmoot.epoch.NativeKeeperBinding
import dev.forgesworn.kithmoot.relay.RoomRoute
import kotlinx.serialization.json.*

/** Public binding only. The independently encrypted source owns all root signing material. */
internal object NativeKeeperReference {
    fun encode(binding: NativeKeeperBinding, invitationId: String): JsonObject = buildJsonObject {
        put("v", 1)
        put("profile", "native-keeper-v1")
        put("room", binding.room)
        put("root", binding.authority)
        put("participant", binding.participant)
        put("device", binding.device)
        put("route", binding.route.stored)
        put("relays", JsonArray(binding.relays.map(::JsonPrimitive)))
        put("pin", binding.pin)
        put("invitation", invitationId)
    }

    fun decode(value: JsonElement): NativeKeeperBinding {
        val json = value.jsonObject
        require(json.keys == setOf("v", "profile", "room", "root", "participant", "device", "route", "relays", "pin", "invitation"))
        val version = json.getValue("v").jsonPrimitive
        require(!version.isString && version.intOrNull == 1) { "Native authority reference needs explicit migration" }
        fun text(key: String) = json.getValue(key).jsonPrimitive.also { require(it.isString) }.content
        require(text("profile") == "native-keeper-v1")
        require(text("invitation").matches(Regex("[0-9a-f]{64}")))
        val relays = json.getValue("relays").jsonArray.map { it.jsonPrimitive.also { url -> require(url.isString) }.content }
        val binding = NativeKeeperBinding(text("room"), text("root"), text("participant"), text("device"),
            RoomRoute.fromStored(text("route")), relays)
        require(relays == binding.relays && text("pin") == binding.pin) { "Native authority binding changed" }
        return binding
    }
}
