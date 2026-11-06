package dev.forgesworn.kithmoot.media

import org.webrtc.JniCommon
import org.webrtc.MediaStreamTrack
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/** A receiver callback lends its wrapper. Keep an independent native reference
 * and sink map for consumers that can outlive receiver or connection teardown. */
internal class RetainedVideoTrack private constructor(
    private val pointer: Long,
    private val trackId: String,
) : VideoTrack(pointer) {
    private val lifetime = NativeSinkLifetime()

    override fun id(): String = trackId
    override fun kind(): String = "video"
    override fun enabled(): Boolean = lifetime.use(false) { super.enabled() }
    override fun setEnabled(enable: Boolean): Boolean = lifetime.use(false) { super.setEnabled(enable) }
    override fun state(): MediaStreamTrack.State = lifetime.use(MediaStreamTrack.State.ENDED) { super.state() }
    override fun addSink(sink: VideoSink) = lifetime.use(Unit) { super.addSink(sink) }
    override fun removeSink(sink: VideoSink) = lifetime.use(Unit) { super.removeSink(sink) }
    override fun setShouldReceive(receive: Boolean) = lifetime.use(Unit) { super.setShouldReceive(receive) }
    override fun shouldReceive(): Boolean = lifetime.use(false) { super.shouldReceive() }
    override fun dispose() = lifetime.close { super.dispose() }

    fun refersTo(track: VideoTrack): Boolean = lifetime.use(false) { pointer == track.nativeVideoTrack }

    companion object {
        /** The callback's wrapper must still be live while this reference is taken. */
        fun retain(track: VideoTrack, id: String): RetainedVideoTrack {
            val pointer = track.nativeVideoTrack
            JniCommon.nativeAddRef(pointer)
            return try { RetainedVideoTrack(pointer, id) }
            catch (failure: Throwable) { JniCommon.nativeReleaseRef(pointer); throw failure }
        }
    }
}
