package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.*
import kotlin.test.*

class RecordingVideoAuthorityTest {
    private val origin = RecordingOrigin("1".repeat(64), "2".repeat(32), "Original room")
    private val self = RecordingEndpointKey("3".repeat(64), "4".repeat(64))
    private val credential = NostrEvent(20460, 0, emptyList(), "", "0".repeat(64), "0".repeat(64), "0".repeat(128))
    private fun device(key: String, call: String?, profile: Int? = 2, left: Boolean = false, at: Long = 1) =
        RosterEntry("5".repeat(64), key.repeat(64), credential, updatedAt = at, recordingProfile = profile, left = left,
            call = call?.let { CallMembership(it, at) }, tracks = listOf(TrackRef("camera-$key", Roles.CAMERA), TrackRef("screen-$key", Roles.SCREEN)))
    private fun resolve(roster: List<RosterEntry>, held: Boolean = false, allowed: (String) -> Boolean = { true }) =
        recordingVideoEndpoints(origin, groupByParticipant(roster), self, "My actual name", true, false, held, allowed)

    @Test fun `freshest device on another call cannot move an original-call device into or out of recording`() {
        val result = resolve(listOf(device("6", origin.call), device("7", "8".repeat(32), at = 100)))
        assertEquals(setOf(self, RecordingEndpointKey("5".repeat(64), "6".repeat(64))), result.map { it.key }.toSet())
        assertTrue(result.single { it.key.device == "6".repeat(64) }.cameraOn)
        assertTrue(result.none { it.key.device == "7".repeat(64) })
    }

    @Test fun `room listeners and departed devices grant no recording inputs`() {
        val result = resolve(listOf(device("6", null), device("7", origin.call, left = true)))
        assertEquals(listOf(self), result.map { it.key })
    }

    @Test fun `legacy devices retain attribution while the plan excludes their video`() {
        val result = resolve(listOf(device("6", origin.call, profile = null)))
        val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, result)
        assertEquals(2, plan.slots.size)
        assertEquals(setOf(RecordingVideoKey(self, RecordingVideoRole.CAMERA)), plan.inputs)
    }

    @Test fun `hold and meeting withdrawal exclude queued video without changing membership or labels`() {
        val roster = listOf(device("6", origin.call))
        val held = resolve(roster, held = true)
        assertEquals(2, held.size)
        assertTrue(held.all { !it.allowed })
        assertTrue(RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, held).inputs.isEmpty())
        val gated = resolve(roster, allowed = { it == self.participant })
        assertEquals(setOf(RecordingVideoKey(self, RecordingVideoRole.CAMERA)), RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, gated).inputs)
    }

    @Test fun `actual local privacy tracks override a delayed self roster and muted remote roles stay off`() {
        val staleSelf = RosterEntry(self.participant, self.device, credential, updatedAt = 1,
            recordingProfile = null, tracks = listOf(TrackRef("old-screen", Roles.SCREEN)))
        val muted = device("6", origin.call).copy(tracks = listOf(TrackRef("camera", Roles.CAMERA, muted = true)))
        val result = resolve(listOf(staleSelf, muted))
        val local = result.single { it.key == self }
        assertEquals("My actual name", local.name)
        assertEquals(2, local.recordingProfile)
        assertTrue(local.cameraOn); assertFalse(local.screenOn)
        assertFalse(result.single { it.key != self }.cameraOn)
    }
}
