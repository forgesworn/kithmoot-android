package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.session.Roles
import org.webrtc.RtpSender

/**
 * What the camera sends for a call of this size.
 *
 * Every remote device on a call has its own peer connection and so its own
 * encoder for this device's camera. A phone on a four-way call runs three
 * encoders and three decoders at once, and with nothing to bound them each
 * encodes the full capture (1280 by 720 at 30) at libwebrtc's own ceiling of
 * about 2.5 Mbps. That is the heat, and the uplink.
 *
 * A rung is one downscale at the source, done once and fed to every encoder,
 * and one bitrate ceiling per sender, which is where libwebrtc takes it.
 * Nothing on the wire changes: resolution and bitrate are a sender's own
 * choice, and the far end plays what arrives.
 */
data class VideoRung(val width: Int, val height: Int, val fps: Int, val maxBitrateBps: Int)

object VideoLadder {
    /** One far end: the camera as captured. */
    val FULL: VideoRung = VideoRung(1280, 720, 30, 1_200_000)

    /** Two or three far ends. */
    val MEDIUM: VideoRung = VideoRung(960, 540, 24, 800_000)

    /** Four or more. */
    val SMALL: VideoRung = VideoRung(640, 360, 15, 500_000)

    /** The rung for a call where this device's camera goes to [peers] devices. */
    fun rungFor(peers: Int): VideoRung = when {
        peers <= 1 -> FULL
        peers <= 3 -> MEDIUM
        else -> SMALL
    }
}

/**
 * Whether a track id names this device's camera.
 *
 * Track ids are `role-uuid` (see `LocalMedia.trackId`), so the role is
 * readable off the id alone, which is all a sender has. Screen shares are
 * left at libwebrtc's defaults: text needs the bits, and they already run at
 * 15 frames a second.
 */
internal fun isCameraTrackId(trackId: String): Boolean = trackId.startsWith("${Roles.CAMERA}-")

/**
 * Put a bitrate ceiling on one sender. False when nothing was set: no
 * ceiling asked for, no encodings to set it on yet, or libwebrtc refused.
 */
internal fun capSender(sender: RtpSender, maxBitrateBps: Int): Boolean {
    if (maxBitrateBps <= 0) return false
    return runCatching {
        val parameters = sender.parameters
        if (parameters.encodings.isEmpty()) return@runCatching false
        for (encoding in parameters.encodings) encoding.maxBitrateBps = maxBitrateBps
        sender.setParameters(parameters)
    }.getOrDefault(false)
}
