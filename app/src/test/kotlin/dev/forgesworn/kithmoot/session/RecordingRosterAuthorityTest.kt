package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import kotlin.test.*

class RecordingRosterAuthorityTest {
    private val credential = NostrEvent(20460, 0, emptyList(), "", "0".repeat(64), "0".repeat(64), "0".repeat(128))
    private fun entry(device: String = "remote", role: String = Roles.CAMERA, id: String = "advertised") =
        RosterEntry("person-$device", device, credential, updatedAt = 1, recordingProfile = 3,
            call = CallMembership("original", 1), tracks = listOf(TrackRef(id, role)))

    @Test fun `heartbeat retains a source grant but mute and restore cannot reuse it`() {
        val authority = RecordingRosterAuthority("me", "mine")
        val source = entry()
        authority.update(listOf(source))
        val before = assertNotNull(authority.source("original", "remote", "advertised", null))
        authority.update(listOf(source.copy(updatedAt = 2, name = "Changed label")))
        assertEquals(before, authority.source("original", "remote", "advertised", null))
        authority.update(listOf(source.copy(tracks = listOf(TrackRef("advertised", Roles.CAMERA, muted = true)))))
        assertNull(authority.source("original", "remote", "advertised", null))
        authority.update(listOf(source))
        assertNotEquals(before.revision, assertNotNull(authority.source("original", "remote", "advertised", null)).revision)
    }

    @Test fun `revoke one source preserves another and capability or call changes revise permission`() {
        val authority = RecordingRosterAuthority("me", "mine")
        val first = entry(); val second = entry("other")
        authority.update(listOf(first, second))
        val kept = authority.source("original", "other", "advertised", null)
        val original = assertNotNull(authority.source("original", "remote", "advertised", null))
        authority.update(listOf(second))
        assertNull(authority.source("original", "remote", "advertised", null))
        assertEquals(kept, authority.source("original", "other", "advertised", null))
        authority.update(listOf(first.copy(recordingProfile = 2), second))
        assertNotEquals(original.revision, assertNotNull(authority.source("original", "remote", "advertised", null)).revision)
        authority.update(listOf(first.copy(call = CallMembership("elsewhere", 2)), second))
        assertNull(authority.source("original", "remote", "advertised", null))
        assertEquals(kept, authority.source("original", "other", "advertised", null))
    }

    @Test fun `receiver ids differ from advertised ids and authenticated fixed slots resolve by role`() {
        val authority = RecordingRosterAuthority("me", "mine")
        authority.update(listOf(entry()))
        assertNull(authority.source("original", "remote", "receiver-id", null))
        assertNotNull(authority.source("original", "remote", "advertised", null))
        assertNull(authority.source("original", "remote", "fixed-slot-msid", Roles.CAMERA))
        authority.update(listOf(entry().copy(callProfile = CALL_PROFILE_2)))
        // A profile-2-capable peer can still negotiate an ordinary pair;
        // an exact advertised-ID binding remains authorised there.
        assertNotNull(authority.source("original", "remote", "advertised", null))
        assertNotNull(authority.source("original", "remote", "fixed-slot-msid", Roles.CAMERA))
        assertNull(authority.source("original", "remote", "fixed-slot-msid", Roles.SCREEN))
    }

    @Test fun `microphone and monitor arbitration revoke and restore with distinct revisions`() {
        val authority = RecordingRosterAuthority("me", "mine")
        val mine = entry("mine", Roles.MIC).copy(participant = "me", claims = mapOf(Roles.MIC to 1L, Roles.MONITOR to 1L))
        val other = entry("other", Roles.MIC).copy(participant = "me", claims = mapOf(Roles.MIC to 2L, Roles.MONITOR to 2L))
        authority.update(listOf(mine))
        val mic = authority.localMicrophone(); val monitor = authority.localMonitor()
        assertNotNull(mic); assertNotNull(monitor)
        authority.update(listOf(mine, other))
        assertNull(authority.localMicrophone()); assertNull(authority.localMonitor())
        assertNull(authority.source("original", "mine", "advertised", null))
        assertNotNull(authority.source("original", "other", "advertised", null))
        authority.update(listOf(mine))
        assertNotEquals(mic, authority.localMicrophone()); assertNotEquals(monitor, authority.localMonitor())
    }
}
