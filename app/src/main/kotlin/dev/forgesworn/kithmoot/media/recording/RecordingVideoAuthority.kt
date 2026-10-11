package dev.forgesworn.kithmoot.media.recording

import dev.forgesworn.kithmoot.session.Participant
import dev.forgesworn.kithmoot.session.Roles

/** Resolve export membership from each device's own original-call claim.
 * A participant's freshest device can be on a different call; grouping that
 * participant must not grant recording access to their other devices. Local
 * tracks override delayed self-presence, using the actual privacy pipeline. */
fun recordingVideoEndpoints(
    origin: RecordingOrigin,
    people: List<Participant>,
    self: RecordingEndpointKey,
    localName: String,
    localCamera: Boolean,
    localScreen: Boolean,
    held: Boolean,
    allowed: (String) -> Boolean,
): List<RecordingVideoEndpoint> {
    val result = people.flatMap { person -> person.devices.mapNotNull { device ->
        if (device.left || device.call?.id != origin.call || device.device == self.device) return@mapNotNull null
        RecordingVideoEndpoint(RecordingEndpointKey(person.participant, device.device), (device.name ?: person.participant).take(256),
            device.recordingProfile, !held && allowed(person.participant),
            device.tracks.any { it.role == Roles.CAMERA && it.muted != true },
            device.tracks.any { it.role == Roles.SCREEN && it.muted != true })
    } }.toMutableList()
    result += RecordingVideoEndpoint(self, localName, 2, !held && allowed(self.participant), localCamera, localScreen)
    return result.sortedWith(compareBy({ it.key.participant }, { it.key.device }))
}
