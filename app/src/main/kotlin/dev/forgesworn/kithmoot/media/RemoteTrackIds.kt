package dev.forgesworn.kithmoot.media

/** Sender track IDs by media-section ID. Receiver IDs can differ after reuse. */
internal fun remoteTrackIds(sdp: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var mid: String? = null
    var track: String? = null
    var sends = true
    var media = false
    var rejected = false
    fun finish() {
        val m = mid; val t = track
        if (media && sends && !rejected && m != null && t != null) result[m] = t
    }
    for (raw in sdp.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("m=") -> {
                finish(); mid = null; track = null
                val fields = line.substring(2).split(' ')
                media = fields.firstOrNull() in setOf("audio", "video")
                sends = true
                rejected = fields.getOrNull(1) == "0"
            }
            line == "a=bundle-only" -> rejected = false
            line.startsWith("a=mid:") -> mid = line.substringAfter("a=mid:").takeIf { it.isNotBlank() }
            line == "a=recvonly" || line == "a=inactive" -> sends = false
            line.startsWith("a=msid:") -> track = line.substringAfter("a=msid:").split(Regex("\\s+")).getOrNull(1)?.takeIf { it != "-" && it.isNotBlank() }
        }
    }
    finish()
    return result
}

/**
 * The negotiated mid identifies the source even when libwebrtc keeps an old
 * receiver ID.
 *
 * Also carries `receiving`: false when `currentDirection` is not actually
 * receiving (`sendonly`, `inactive`, `stopped` or null) - the stale-muted-
 * receiver shape H5 describes, where the far end has moved this slot on and
 * the transceiver already says so even though the receiver and its track
 * linger. See `RemoteTrack` and `resolveRemoteByRole`.
 */
internal fun remoteTracksFor(
    device: String,
    connection: org.webrtc.PeerConnection,
    received: List<org.webrtc.MediaStreamTrack>,
    /** The generation-opening offer's mid-to-role map, on a profile-2 pair.
     *  Null on profile 1, where a role comes from the roster advert instead. */
    slots: Map<String, String>? = null,
): List<RemoteTrack> {
    val ids = remoteTrackIds(connection.remoteDescription?.description ?: return emptyList())
    return connection.transceivers.mapNotNull { transceiver ->
        val advertised = ids[transceiver.mid] ?: return@mapNotNull null
        val receiverId = transceiver.receiver.track()?.id() ?: return@mapNotNull null
        // getTransceivers disposes its previous Java wrappers on every read.
        // UI sinks must keep the separately owned onAddTrack object instead.
        val track = received.firstOrNull { it.id() == receiverId } ?: return@mapNotNull null
        val direction = transceiver.currentDirection
        val receiving = direction == org.webrtc.RtpTransceiver.RtpTransceiverDirection.SEND_RECV ||
            direction == org.webrtc.RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        RemoteTrack(
            device = device,
            track = track,
            trackId = advertised,
            mid = transceiver.mid,
            receiving = receiving,
            role = slots?.get(transceiver.mid),
        )
    }
}
