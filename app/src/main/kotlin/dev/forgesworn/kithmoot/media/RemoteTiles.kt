package dev.forgesworn.kithmoot.media

/**
 * Resolves live remote tracks to the participant-advertised role for their
 * WebRTC trackId, so a tile stays bound to a slot rather than to whichever
 * WebRTC id happened to be sitting in it.
 *
 * Generic over the caller's track type on purpose: `MediaStreamTrack` and its
 * relatives are native-backed and cannot be constructed on a plain JVM, so
 * this stays testable against a fake without pulling WebRTC into the test
 * classpath. [device], [trackId] and [receiving] are the three things this
 * needs off each element; [valueFor] extracts whatever the caller wants
 * keyed by role.
 *
 * Two things this does that a plain `device|trackId` key cannot (H5, call
 * reliability spec section 4):
 *
 * - **Drops non-receiving receivers.** An element whose [receiving] is false
 *   is precisely the stale-muted-receiver shape H5 describes: the far end
 *   has moved this slot on (renegotiated it away, or stopped sending) and
 *   `RtpTransceiver.currentDirection` has already said so, even though the
 *   receiver object itself lingers and its track never fires `ended`.
 * - **Keys by role, not by trackId.** A renegotiation mints a fresh WebRTC
 *   track id for the same logical slot; the roster's `trackId -> role`
 *   mapping is what ties the new id back to the tile the old one filled.
 * - **Believes the slot over the roster.** On a profile-2 pair [declaredRole]
 *   answers outright, because the role came off the generation-opening offer's
 *   `slots` map by mid, and a mid agrees on both ends of a pair where a
 *   sender's `a=msid` in a fixed slot does not. The roster lookup stays as the
 *   profile-1 answer and as the fallback for a track that arrived before the
 *   map did.
 *
 * Where more than one live, receiving element still resolves to the same
 * role - a brief window mid-renegotiation - the later entry in [remote]
 * wins. That is a name, not a promise: a genuine "packets are actually
 * moving" preference needs `getStats()` sampling, which is the pair-health
 * ladder's job (call reliability spec section 3.4), not this one.
 */
internal fun <T, V> resolveRemoteByRole(
    remote: List<T>,
    device: (T) -> String,
    trackId: (T) -> String,
    receiving: (T) -> Boolean,
    roleForTrackId: (device: String, trackId: String) -> String?,
    valueFor: (T) -> V,
    declaredRole: (T) -> String? = { null },
): Map<String, V> {
    val result = LinkedHashMap<String, V>()
    for (track in remote) {
        if (!receiving(track)) continue
        val role = declaredRole(track) ?: roleForTrackId(device(track), trackId(track)) ?: continue
        result[roleKey(device(track), role)] = valueFor(track)
    }
    return result
}

/** A tile's identity: the device and the role it fills. */
internal fun roleKey(device: String, role: String): String = "$device|$role"
