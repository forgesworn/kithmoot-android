package dev.forgesworn.kithmoot.relay

import kotlinx.serialization.json.*
import kotlin.test.*

class RoomNearbyDiscoveryTest {
    @Test fun `shared discovery fixtures preserve root scope and separate service identity`() {
        val text = checkNotNull(javaClass.getResource("/nearby-discovery-v1.json")).readText()
        for (row in Json.parseToJsonElement(text).jsonArray) {
            fun field(name: String) = row.jsonObject.getValue(name).jsonPrimitive.content
            val scope = RoomNearbyDiscovery.scope(field("rootRoomId"))
            assertEquals(field("scope"), scope)
            assertEquals(field("serviceUuid"), RoomNearbyDiscovery.serviceUuid(scope).toString())
        }
    }
    @Test fun `malformed root and scope rejected without normalisation`() {
        for (value in listOf("", "aa", "FF".repeat(32), "g0".repeat(32))) {
            assertFailsWith<IllegalArgumentException> { RoomNearbyDiscovery.scope(value) }
            assertFailsWith<IllegalArgumentException> { RoomNearbyDiscovery.serviceUuid(value) }
        }
    }
}
