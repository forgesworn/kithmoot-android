package dev.forgesworn.kithmoot.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Kotlin list against the web app's exported `public-relays.json`. */
class PublicRelaysTest {
    private fun fromJson(): List<PublicRelay> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/public-relays.json")) { "public-relays.json is missing" }
        val array: JsonArray = Json.parseToJsonElement(stream.readBytes().decodeToString()).jsonArray
        return array.map { e ->
            val o = e.jsonObject
            PublicRelay(
                url = o.getValue("url").jsonPrimitive.content,
                operator = o.getValue("operator").jsonPrimitive.content,
                default = o.getValue("default").jsonPrimitive.boolean,
                quietRooms = o.getValue("quietRooms").jsonPrimitive.boolean,
                keeps = o["keeps"]?.jsonPrimitive?.content,
                test = o.getValue("test").jsonPrimitive.content,
                checked = o.getValue("checked").jsonPrimitive.content,
            )
        }
    }

    @Test fun theKotlinListMatchesTheWebListEntryByEntry() {
        val web = fromJson()
        assertEquals(web.size, PUBLIC_RELAYS.size)
        web.zip(PUBLIC_RELAYS).forEach { (expected, actual) -> assertEquals(expected, actual) }
    }

    @Test fun threeDefaultsFromThreeHosts() {
        assertEquals(3, DEFAULT_PUBLIC_RELAYS.size)
        assertEquals(3, DEFAULT_PUBLIC_RELAYS.map { java.net.URI(it.url).host }.toSet().size)
    }
}
