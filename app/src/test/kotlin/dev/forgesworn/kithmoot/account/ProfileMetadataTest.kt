package dev.forgesworn.kithmoot.account

import kotlinx.serialization.json.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

class ProfileMetadataTest {
    @Test fun preservesOtherClientsFieldsAndOnlyRemovesExplicitlyClearedFields() {
        val base = Json.parseToJsonElement("""{"name":"old","custom":{"x":1},"lud16":"alice@example.com","about":"before"}""").jsonObject
        val edited = ProfileMetadata.edit(base, mapOf("name" to "Alice", "about" to ""))
        assertEquals(JsonPrimitive("Alice"), edited["name"])
        assertEquals(base["custom"], edited["custom"]); assertEquals(base["lud16"], edited["lud16"])
        assertFalse("about" in edited)
    }
    @Test fun rejectsInvalidImageUrlsAndOversizedContent() {
        for (url in listOf("javascript:alert(1)", "http://example.com/me.png", "https://user:pass@example.com/a"))
            assertFails { ProfileMetadata.edit(JsonObject(emptyMap()), mapOf("picture" to url)) }
        assertFails { ProfileMetadata.edit(JsonObject(emptyMap()), mapOf("about" to "a".repeat(4097))) }
    }
    @Test fun selectsVerifiedLatestProfileWithNostrTieBreakAndRejectsForeignEvents() = runTest {
        val actor = LocalSigner(ByteArray(32) { 7 }); val foreign = LocalSigner(ByteArray(32) { 8 })
        val a = actor.sign(0, 100, emptyList(), """{"name":"A"}""")
        val b = actor.sign(0, 100, emptyList(), """{"name":"B"}""")
        val other = foreign.sign(0, 101, emptyList(), "{}")
        assertEquals(listOf(a,b).minBy { it.id }, ProfileMetadata.latest(listOf(a,b,other), actor.pubkey, 100))
        assertNull(ProfileMetadata.latest(listOf(other), actor.pubkey, 100))
    }
}
