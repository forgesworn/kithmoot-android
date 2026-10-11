package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.ui.room.ActiveSpeaker

/** Owned by one recording, independently of the visible call tiles. Legacy
 * devices can hold the stage, but the plan still excludes their video. */
class RecordingSpeaker {
    private val speaker = ActiveSpeaker()
    private var device: RecordingEndpointKey? = null

    @Synchronized fun select(endpoints: List<RecordingVideoEndpoint>, speaking: Set<String>, nowMs: Long): RecordingVideoEndpoint? {
        val ordered = endpoints.sortedWith(compareBy({ it.key.participant }, { it.key.device }))
        val present = ordered.filter { it.allowed }.map { it.key.participant }.distinct()
        val participant = speaker.update(speaking.intersect(present.toSet()), present, nowMs)
        val selected = ordered.firstOrNull { it.key == device && it.key.participant == participant && it.allowed }
            ?: ordered.firstOrNull { it.key.participant == participant && it.allowed }
        device = selected?.key
        return selected
    }
}
