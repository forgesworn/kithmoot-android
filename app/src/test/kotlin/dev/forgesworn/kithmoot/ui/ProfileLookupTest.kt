package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.relay.RoomRoute
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileLookupTest {
    private val event = Events.sign(ByteArray(32) { 1 }, 0, 100, emptyList(), """{"name":"TheCryptoDonkey"}""")

    @Test fun `a timed out profile read recovers without rejoining the room`() = runTest {
        var attempts = 0
        val names = mutableListOf<String?>()
        collectPublicProfiles(listOf(event.pubkey), { true }, {
            attempts++
            flow {
                if (attempts == 2) emit(event)
                awaitCancellation()
            }
        }, { 100 }) { _, profile -> names += profile.name }
        assertEquals(2, attempts)
        assertEquals(listOf<String?>("TheCryptoDonkey"), names)
    }

    @Test fun `only missing authors are retried and permanent misses are bounded`() = runTest {
        val missing = "ab".repeat(32)
        val requested = mutableListOf<List<String>>()
        collectPublicProfiles(listOf(event.pubkey, missing), { true }, { authors ->
            requested += authors
            flow { if (event.pubkey in authors) emit(event); awaitCancellation() }
        }, { 100 }) { _, _ -> }
        assertEquals(listOf(listOf(event.pubkey, missing), listOf(missing), listOf(missing)), requested)
    }

    @Test fun `private mode or an account change prevents a scheduled retry`() = runTest {
        var current = true
        var attempts = 0
        launch { delay(15_500); current = false }
        collectPublicProfiles(listOf(event.pubkey), { current }, {
            attempts++
            flow { awaitCancellation() }
        }, { 100 }) { _, _ -> error("No profile should arrive") }
        assertEquals(1, attempts)
    }

    @Test fun `own account profile names the room even with other people profiles off`() {
        val profile = PublicProfile("TheCryptoDonkey", null, 100, event.id)
        val room = RoomState(selfParticipant = event.pubkey, profilesEnabled = false)
        assertEquals("TheCryptoDonkey", room.withAccountProfile(event.pubkey, profile).profiles[event.pubkey]?.name)
        assertTrue(room.withAccountProfile("cd".repeat(32), profile).profiles.isEmpty())
        val anonymous = room.copy(anonymous = true)
        assertSame(anonymous, anonymous.withAccountProfile(event.pubkey, profile))
        assertTrue(room.copy(route = RoomRoute.NEARBY).withAccountProfile(event.pubkey, profile).profiles.isEmpty())
    }

    @Test fun `pausing networking keeps a learned own name without a profile picture`() {
        val self = ParticipantTile(event.pubkey, true, 1, emptyList(), null, false)
        val other = self.copy(participant = "cd".repeat(32), isSelf = false)
        val room = RoomState(selfParticipant = event.pubkey, tiles = listOf(self, other))
        val named = room.withAccountName(event.pubkey, "TheCryptoDonkey")
        assertEquals("TheCryptoDonkey", named.tiles.first().name)
        assertEquals(null, named.tiles.last().name)
        assertTrue(named.profiles.isEmpty())
        assertSame(room, room.withAccountName("ab".repeat(32), "Other account"))
    }

    @Test fun `the private chat list asks only for peers it has no profile for, and nothing once all are known`() {
        val peers = listOf("aa".repeat(32), "bb".repeat(32), "cc".repeat(32))
        val known = mapOf(peers[0] to PublicProfile("Ann", null, 1, "e1"))
        assertEquals(peers.drop(1), profileAuthorsToAsk(peers, known.keys, emptySet()))
        assertEquals(listOf(peers[2]), profileAuthorsToAsk(peers, known.keys, setOf(peers[1])))
        val all = peers.associateWith { PublicProfile(it.take(4), null, 1, "e-$it") }
        assertTrue(profileAuthorsToAsk(peers, all.keys, emptySet()).isEmpty())
    }

    @Test fun `private chat profiles survive the list being hidden, but not a change of account or peer`() {
        val peers = listOf("aa".repeat(32), "bb".repeat(32))
        val kept = peers.associateWith { PublicProfile(it.take(4), null, 1, "e-$it") }
        // Hidden or shown, the same account keeps what it has for peers still listed,
        // so showing the list again has nothing left to ask for.
        assertEquals(kept, keptPrivateChatProfiles(kept, sameAccount = true, scope = "me" to peers))
        assertEquals(kept.filterKeys { it == peers[0] }, keptPrivateChatProfiles(kept, true, "me" to peers.take(1)))
        // Public profiles off leaves no peers in scope; another account keeps nothing.
        assertTrue(keptPrivateChatProfiles(kept, true, "me" to emptyList()).isEmpty())
        assertTrue(keptPrivateChatProfiles(kept, false, "other" to peers).isEmpty())
    }
}
