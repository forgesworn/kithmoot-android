package dev.forgesworn.kithmoot.media.recording

import java.io.File
import org.webrtc.EglBase

/** Scene lifetime follows the capture, not the room UI or tile renderers. */
class ComposedAvRecordingFile(file: File, sharedContext: EglBase.Context, private val scene: RecordingVideoScene) : PcmFileOutput {
    private val output = try {
        AvRecordingFile(file, sharedContext, scene::draw, releaseDraw = scene::releaseGl)
    } catch (error: Throwable) { scene.dispose(); throw error }

    override fun bindTimeline(clock: () -> Long?) = scene.bindTimeline(clock)
    override fun pauseInputs(paused: Boolean) = scene.pause(paused)
    override fun detachInputs() = scene.detachInputs()
    override fun write(samples: ShortArray) = output.write(samples)
    override fun close() { try { output.close() } finally { scene.dispose() } }
    override fun discard() { try { output.discard() } finally { scene.dispose() } }
}
