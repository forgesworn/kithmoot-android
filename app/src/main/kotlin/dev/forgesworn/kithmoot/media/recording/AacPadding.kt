package dev.forgesworn.kithmoot.media.recording

/** A final encoder access unit may contain only AAC flush padding. The
 * preceding unit must already cover the original 48 kHz PCM end; trimming
 * never fills a missing source interval or extends that clock. */
internal fun isFinalAacPadding(timeUs: Long, previousUs: Long, sourceEndUs: Long): Boolean {
    if (previousUs < 0 || sourceEndUs <= previousUs || timeUs <= sourceEndUs) return false
    val interval = timeUs - previousUs
    return interval in 21_333L..21_334L && sourceEndUs - previousUs < 21_334L
}
