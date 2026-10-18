package dev.forgesworn.kithmoot.media.effects

/** Same names and geometry as the desktop client. No identity recognition. */
enum class FacePrivacyMode(val label: String) {
    OFF("Off"), MASK("Tracked mask"), AVATAR("Avatar");

    companion object {
        fun byName(value: String?): FacePrivacyMode = entries.firstOrNull { it.name == value } ?: OFF
    }
}

data class FaceBox(val x: Float, val y: Float, val width: Float, val height: Float)

/** One bad or absent detection covers the whole picture. Never reuse a box
 * from an older frame, including after a camera flip or rotation. */
fun faceCovers(boxes: List<FaceBox>, width: Int, height: Int): List<FaceBox>? {
    if (width <= 0 || height <= 0 || boxes.isEmpty()) return null
    return boxes.map { box ->
        if (!listOf(box.x, box.y, box.width, box.height).all { it.isFinite() } ||
            box.width <= 0 || box.height <= 0 || box.x >= width || box.y >= height ||
            box.x + box.width <= 0 || box.y + box.height <= 0) return null
        val left = maxOf(0f, box.x - box.width * 0.4f)
        val top = maxOf(0f, box.y - box.height * 0.5f)
        val right = minOf(width.toFloat(), box.x + box.width * 1.4f)
        val bottom = minOf(height.toFloat(), box.y + box.height * 1.35f)
        FaceBox(left, top, right - left, bottom - top)
    }
}
