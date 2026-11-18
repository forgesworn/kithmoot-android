package dev.forgesworn.kithmoot.ui.room

import dev.forgesworn.kithmoot.media.AudioReading
import dev.forgesworn.kithmoot.media.CallAudioActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileMicrophoneTest {
    private val own = ParticipantTile("self", true, 2, emptyList(), "phone", true)
    private val other = ParticipantTile("other", false, 2, emptyList(), "laptop", false)
    private val local = AudioReading(0.1, listOf(-0.1f, 0.1f))
    private val remote = AudioReading(0.2, listOf(-0.2f, 0.2f))
    private val levels = CallAudioActivity(local.level, local.waveform,
        mapOf("laptop" to remote, "stale-device" to AudioReading(0.9)), "room")
    private fun read(tile: ParticipantTile, value: CallAudioActivity = levels, micOn: Boolean = true, muted: Boolean = false) =
        tileMicrophoneReading(tile, value, "room", "phone", micOn, muted)

    @Test fun `each tile follows its claimed microphone rather than the loudest incoming sound`() {
        assertEquals(local, read(own).reading)
        assertEquals(local, read(own.copy(micDevice = null, micIsThisDevice = false)).reading)
        assertEquals(remote, read(other).reading)
        assertNull(read(other.copy(micDevice = "new-device")).reading.level)
        // My laptop's microphone must not show this phone's samples.
        assertEquals(remote, read(own.copy(micDevice = "laptop", micIsThisDevice = false)).reading)
    }
    @Test fun `actual mute off and local silence hide PCM without pretending quiet is muted`() {
        for (signal in listOf(read(own, muted = true), read(other.copy(micMuted = true)))) {
            assertEquals("Muted", signal.status)
            assertNull(signal.reading.level)
            assertEquals(emptyList(), signal.reading.waveform)
        }
        assertEquals("Off", read(own, micOn = false).status)
        assertEquals("Off", read(other.copy(micDevice = null)).status)
        assertEquals("Silenced for you", read(other.copy(callVolume = 0f)).status)
        assertNull(read(other, levels.copy(microphones = mapOf("laptop" to AudioReading(0.0)))).status)
    }
    @Test fun `room switch and missing samples clear the trace`() {
        assertNull(read(own, levels.copy(roomId = "previous-room")).reading.level)
        assertNull(read(other, levels.copy(roomId = "previous-room")).reading.level)
        assertNull(read(other, levels.copy(microphones = emptyMap())).reading.level)
        assertEquals(emptyList(), read(other, levels.copy(microphones = emptyMap())).reading.waveform)
    }
}
