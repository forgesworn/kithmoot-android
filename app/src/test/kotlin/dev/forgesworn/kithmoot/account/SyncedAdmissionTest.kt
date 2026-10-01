package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import org.junit.Test
import kotlin.test.*

/** The adopt and ignore rules for a group secret that arrives with a bookmark,
 *  mirroring the "group admission" cases in app/src/room-bookmarks.test.ts. */
class SyncedAdmissionTest {
    private val inviter = LocalSigner(ByteArray(32) { 1 }).pubkey
    private val invitation = RoomInvitation(ByteArray(32) { 2 }, inviter, persistent = true)
    private val link = encodeInvitationUrl("https://kithmoot.example/j/", invitation, listOf("wss://relay.example"))
    private val secret = ByteArray(32) { 7 }
    private val roomId = deriveRoom(secret).roomId
    private val group = AccountRoom(roomId, link, "The Moot", 1_789_000_000, secret.toHex())

    @Test fun opensTheGroupItsInvitationNamesWithTheSecretAndNoDelegate() {
        val synced = assertNotNull(syncedGroup(listOf(group), invitation))
        assertEquals(roomId, synced.room.roomId)
        assertContentEquals(secret, synced.admission.secret)
        assertNull(synced.admission.delegate)
        assertFalse(synced.toString().contains(secret.toHex()))
    }

    @Test fun findsItWhicheverWebAppTheLinkWasMadeOn() {
        val elsewhere = group.copy(link = encodeInvitationUrl("https://other.example/j/", invitation, listOf("wss://relay.other")))
        assertNotNull(syncedGroup(listOf(elsewhere), invitation))
    }

    @Test fun aBookmarkWithoutASecretLeavesTheInvitationToBeFetched() {
        assertNull(syncedGroup(listOf(group.copy(admission = null)), invitation))
    }

    @Test fun ignoresASecretThatIsNotTheRoomsOwn() {
        assertNull(syncedGroup(listOf(group.copy(admission = "b".repeat(64))), invitation))
        assertNull(syncedGroup(listOf(group.copy(admission = "not hex")), invitation))
        assertNull(syncedGroup(listOf(group.copy(roomId = "a".repeat(64))), invitation))
    }

    @Test fun onlyTheInvitationTheBookmarkNames() {
        val other = RoomInvitation(ByteArray(32) { 3 }, inviter, persistent = true)
        assertNull(syncedGroup(listOf(group), other))
        val legacy = group.copy(link = encodeJoinUrl("https://kithmoot.example/j/", secret, listOf("wss://relay.example")))
        assertNull(syncedGroup(listOf(legacy), invitation))
    }

    @Test fun neverForATemporaryInvitation() {
        val temporary = RoomInvitation(ByteArray(32) { 2 }, inviter, persistent = false)
        val bookmarked = group.copy(link = encodeInvitationUrl("https://kithmoot.example/j/", temporary, listOf("wss://relay.example")))
        assertNull(syncedGroup(listOf(bookmarked), temporary))
    }

    @Test fun twoRoomsNamingOneInvitationAdmitNeither() {
        val otherSecret = ByteArray(32) { 9 }
        val clash = AccountRoom(deriveRoom(otherSecret).roomId, link, "Impostor", 1_789_000_000, otherSecret.toHex())
        assertNull(syncedGroup(listOf(group, clash), invitation))
    }

    @Test fun anUnreadableLinkIsSkippedNotThrown() {
        val broken = AccountRoom("c".repeat(64), "https://kithmoot.example/j/#not-a-payload", null, 0)
        assertNotNull(syncedGroup(listOf(broken, group), invitation))
    }
}
