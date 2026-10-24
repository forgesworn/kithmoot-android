package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * A member's own link names the room's authority (link version 5), read the
 * way fold-kit 0.15.0 reads it: `link-authority-vectors.json` is its
 * `linkAuthority` vector group, copied unchanged.
 */
class LinkAuthorityTest {
    private val group = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/link-authority-vectors.json")).bufferedReader().readText(),
    ).jsonObject.getValue("group").jsonObject
    private val input = group.getValue("input").jsonObject
    private val inviter = input.getValue("inviter").jsonPrimitive.content
    private val authority = input.getValue("authority").jsonPrimitive.content

    @Test fun `reads every vector link as fold-kit does, refusing the same ones`() {
        for (vector in group.getValue("read").jsonArray.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            val url = vector.getValue("url").jsonPrimitive.content
            val result = vector.getValue("result").jsonObject
            if (result.getValue("threw").jsonPrimitive.boolean) {
                assertThrows(name, JoinUrlException::class.java) { decodeInvitationUrl(url) }
                continue
            }
            val invitation = requireNotNull(decodeInvitationUrl(url), { name }).invitation
            assertEquals(name, result.getValue("inviter").jsonPrimitive.content, invitation.canonicalInviter)
            assertEquals(name, result.getValue("authority").jsonPrimitive.content, invitation.roomAuthority)
            assertEquals(name, (result.getValue("named") as? JsonPrimitive)?.contentOrNull, invitation.authority)
            assertEquals(name, result.getValue("persistent").jsonPrimitive.boolean, invitation.persistent)
        }
    }

    @Test fun `the links fold-kit wrote read back with their authority`() {
        for (vector in group.getValue("written").jsonArray.map { it.jsonObject }) {
            val name = vector.getValue("name").jsonPrimitive.content
            val named = (vector.getValue("authority") as? JsonPrimitive)?.contentOrNull
            val invitation = requireNotNull(decodeInvitationUrl(vector.getValue("url").jsonPrimitive.content), { name }).invitation
            assertEquals(name, inviter, invitation.canonicalInviter)
            assertEquals(name, if (named == null || named == inviter) inviter else named, invitation.roomAuthority)
        }
    }

    @Test fun `this phone writes a member's link as version 5 and every other link as before`() {
        val bearer = ByteArray(32) { 7 }
        val member = RoomInvitation(bearer, inviter, authority = authority)
        val url = encodeInvitationUrl("https://example.test/j/", member, listOf("wss://relay.example"))
        val payload = Json.parseToJsonElement(String(base64UrlDecode(url.substringAfter('#')), Charsets.UTF_8)).jsonObject
        assertEquals(5, payload.getValue("v").jsonPrimitive.int)
        assertEquals(authority, payload.getValue("o").jsonPrimitive.content)
        val read = requireNotNull(decodeInvitationUrl(url)).invitation
        assertEquals(member, read)
        assertEquals(authority, read.roomAuthority)

        for (plain in listOf(RoomInvitation(bearer, inviter), RoomInvitation(bearer, inviter, authority = inviter.uppercase()))) {
            val written = Json.parseToJsonElement(String(base64UrlDecode(encodeInvitationUrl("https://example.test/j/", plain, emptyList()).substringAfter('#')), Charsets.UTF_8)).jsonObject
            assertEquals(2, written.getValue("v").jsonPrimitive.int)
            assertFalse("o" in written)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeInvitationUrl("https://example.test/j/", RoomInvitation(bearer, inviter, persistent = true, authority = authority), emptyList())
        }
    }

    @Test fun `the authority plays no part in which link it is`() {
        val bearer = ByteArray(32) { 9 }
        assertEquals(RoomInvitation(bearer, inviter), RoomInvitation(bearer, inviter, authority = authority))
        assertEquals(RoomInvitation(bearer, inviter).hashCode(), RoomInvitation(bearer, inviter, authority = authority).hashCode())
    }
}
