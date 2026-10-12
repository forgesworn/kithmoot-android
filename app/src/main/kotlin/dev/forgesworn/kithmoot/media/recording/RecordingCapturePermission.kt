package dev.forgesworn.kithmoot.media.recording

/** A capture never regains ownership after revocation. The predicate must
 * only read authoritative state, never acquire the capture owner's monitor. */
class RecordingCapturePermission(private val predicate: () -> Boolean) {
    private var revoked = false
    @Synchronized fun allowed(): Boolean {
        if (!revoked && !runCatching(predicate).getOrDefault(false)) revoked = true
        return !revoked
    }
    @Synchronized fun revoked(): Boolean = revoked
}
