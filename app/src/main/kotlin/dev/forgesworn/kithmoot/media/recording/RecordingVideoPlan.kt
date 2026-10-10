package dev.forgesworn.kithmoot.media.recording

import kotlin.math.ceil
import kotlin.math.min

enum class RecordingVideoLayout(val wire: String, val label: String) {
    GALLERY("gallery", "Gallery with audio"), SPEAKER("speaker", "Speaker with audio"), SCREEN_CAMERA("screen-camera", "Screen and camera with audio")
}

data class RecordingOrigin(val room: String, val call: String, val name: String) {
    init {
        require(room.matches(Regex("[0-9a-f]{64}")))
        require(call.matches(Regex("[0-9a-f]{32}")))
        require(name.length <= 256)
    }
}

data class RecordingEndpointKey(val participant: String, val device: String) {
    init {
        require(participant.matches(Regex("[0-9a-f]{64}")))
        require(device.matches(Regex("[0-9a-f]{64}")))
    }
}

enum class RecordingVideoRole { CAMERA, SCREEN }
data class RecordingVideoKey(val endpoint: RecordingEndpointKey, val role: RecordingVideoRole)

/** The originating call owner supplies membership and meeting permission.
 * Capability alone never grants permission. Absent/legacy capability excludes
 * video, while keeping the named device in the exported layout. */
data class RecordingVideoEndpoint(
    val key: RecordingEndpointKey,
    val name: String,
    val recordingProfile: Int?,
    val allowed: Boolean,
    val cameraOn: Boolean,
    val screenOn: Boolean,
) {
    init { require(name.length <= 256) }
}

data class RecordingRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(x >= 0 && y >= 0 && width > 0 && height > 0) }
}

data class RecordingVideoSlot(
    val endpoint: RecordingVideoEndpoint?,
    val role: RecordingVideoRole,
    val bounds: RecordingRect,
    val captionHeight: Int,
    val unavailable: String?,
    val overflow: List<RecordingVideoEndpoint> = emptyList(),
) {
    /** No sink may attach to an excluded source, even when a caller supplied a
     * matching native track. Explicit placeholders retain its name/identity. */
    val source: RecordingVideoKey? get() = endpoint?.takeIf { unavailable == null }?.let { RecordingVideoKey(it.key, role) }
    val videoHeight: Int get() = (bounds.height - captionHeight).coerceAtLeast(0)
}

/** Independent export geometry, never derived from UI tiles, paging or collapse.
 * A selected speaker/share stays pinned to an exact person and device. Losing
 * that source yields a named placeholder rather than another person's video. */
class RecordingVideoPlan(
    val origin: RecordingOrigin,
    val layout: RecordingVideoLayout,
    endpoints: List<RecordingVideoEndpoint>,
    val selected: RecordingEndpointKey? = null,
    selectedName: String = "Selected device",
) {
    val slots: List<RecordingVideoSlot>
    val inputs: Set<RecordingVideoKey> get() = slots.mapNotNull { it.source }.toSet()

    init {
        require(endpoints.size <= 1024) { "Too many recording endpoint descriptions" }
        require(endpoints.map { it.key }.distinct().size == endpoints.size) { "Duplicate recording device" }
        require(selectedName.length <= 256)
        val sorted = endpoints.sortedWith(compareBy({ it.key.participant }, { it.key.device }))
        slots = when (layout) {
            RecordingVideoLayout.GALLERY -> gallery(sorted)
            RecordingVideoLayout.SPEAKER, RecordingVideoLayout.SCREEN_CAMERA -> {
                val key = requireNotNull(selected) { "Choose the exact recording device" }
                val actual = sorted.firstOrNull { it.key == key }
                val endpoint = actual ?: RecordingVideoEndpoint(key, selectedName, null, false, false, false)
                val full = RecordingRect(0, HEADER, WIDTH, HEIGHT - HEADER)
                val mainRole = if (layout == RecordingVideoLayout.SPEAKER) RecordingVideoRole.CAMERA else RecordingVideoRole.SCREEN
                val main = slot(endpoint, mainRole, full, missing = actual == null)
                if (layout == RecordingVideoLayout.SPEAKER) listOf(main)
                else listOf(main, slot(endpoint, RecordingVideoRole.CAMERA,
                    RecordingRect(WIDTH - 336, HEADER + 16, 320, 252), missing = actual == null))
            }
        }
    }

    private fun gallery(endpoints: List<RecordingVideoEndpoint>): List<RecordingVideoSlot> {
        if (endpoints.isEmpty()) return listOf(RecordingVideoSlot(null, RecordingVideoRole.CAMERA,
            RecordingRect(0, HEADER, WIDTH, HEIGHT - HEADER), 0, "No devices on the originating call"))
        val visible = endpoints.take(MAX_VIDEO_DEVICES)
        val overflow = endpoints.drop(MAX_VIDEO_DEVICES)
        val count = visible.size + if (overflow.isEmpty()) 0 else 1
        // Choose stable geometry using source count and fixed identity captions,
        // not display names or which cameras happen to be switched on.
        val columns = (1..min(count, 8)).maxBy { columns ->
            val rows = (count + columns - 1) / columns
            val width = WIDTH / columns
            val height = (HEIGHT - HEADER) / rows - captionHeight(width)
            val videoWidth = min(width.toDouble(), height.coerceAtLeast(0) * 16.0 / 9)
            videoWidth * videoWidth * 9 / 16
        }
        val rows = (count + columns - 1) / columns
        val width = WIDTH / columns
        val height = (HEIGHT - HEADER) / rows
        return (0 until count).map { index ->
            val bounds = RecordingRect(index % columns * width, HEADER + index / columns * height, width, height)
            if (index < visible.size) slot(visible[index], RecordingVideoRole.CAMERA, bounds)
            else RecordingVideoSlot(null, RecordingVideoRole.CAMERA, bounds, 0,
                "${overflow.size} further devices: audio only", overflow)
        }
    }

    private fun slot(endpoint: RecordingVideoEndpoint, role: RecordingVideoRole, bounds: RecordingRect, missing: Boolean = false): RecordingVideoSlot {
        val unavailable = when {
            missing -> "Selected device left the call"
            !endpoint.allowed -> "Video unavailable under the meeting policy"
            endpoint.recordingProfile != 2 -> "Video unavailable: update this client"
            role == RecordingVideoRole.CAMERA && !endpoint.cameraOn -> "Camera off"
            role == RecordingVideoRole.SCREEN && !endpoint.screenOn -> "Selected screen share ended"
            else -> null
        }
        return RecordingVideoSlot(endpoint, role, bounds, min(bounds.height, captionHeight(bounds.width)), unavailable)
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val HEADER = 72
        const val MAX_VIDEO_DEVICES = 24
        /** Full participant and device keys, plus a separate display-name line. */
        fun captionHeight(width: Int): Int {
            val characters = ((width - 16) / 6).coerceAtLeast(1)
            return 20 + ceil(66.0 / characters).toInt() * 24
        }
    }
}
