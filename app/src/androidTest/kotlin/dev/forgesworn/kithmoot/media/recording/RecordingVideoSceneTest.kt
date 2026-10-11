package dev.forgesworn.kithmoot.media.recording

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*

class RecordingVideoSceneTest {
    private val origin = RecordingOrigin("1".repeat(64), "2".repeat(32), "Synthetic original room")
    private val first = RecordingVideoEndpoint(RecordingEndpointKey("3".repeat(64), "4".repeat(64)), "First camera", 2, true, true, false)
    private val second = RecordingVideoEndpoint(RecordingEndpointKey("5".repeat(64), "6".repeat(64)), "Second camera", null, true, true, false)

    @Test fun delayed_export_keeps_the_last_picture_before_its_audio_sample() {
        withSources { directory, egl, sources, tracks ->
            val clock = AtomicLong()
            val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first))
            val scene = RecordingVideoScene(plan)
            val file = File(directory, "delayed-gallery.mp4")
            val writer = ComposedAvRecordingFile(file, egl.eglBaseContext, scene)
            writer.bindTimeline(clock::get)
            try {
                scene.setPlan(plan, mapOf(RecordingVideoKey(first.key, RecordingVideoRole.CAMERA) to tracks[0]))
                // Source callbacks keep arriving while the export worker is
                // busy. It must retain a past picture, rather than replacing
                // every queued picture with one from the future audio clock.
                repeat(15) { frame -> feedUntil(scene, plan.inputs, sources, frame * 3200L, clock) }
                writer.write(ShortArray(48_000))
                writer.close()
                assertRed(pixel(file, 200_000, plan.slots.single()))
                assertRed(pixel(file, 800_000, plan.slots.single()))
            } finally { writer.discard() }
        }
    }

    @Test fun source_permission_changes_without_plan_collection_remove_only_its_queued_pictures() {
        withSources { directory, egl, sources, tracks ->
            val clock = AtomicLong()
            val firstRevision = AtomicLong(1)
            val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY,
                listOf(first, second.copy(recordingProfile = 2)))
            val firstKey = RecordingVideoKey(first.key, RecordingVideoRole.CAMERA)
            val secondKey = RecordingVideoKey(second.key, RecordingVideoRole.CAMERA)
            val scene = RecordingVideoScene(plan, sourceGrant = { key, _ ->
                if (key == firstKey) firstRevision.get().takeIf { it != 0L } else 1L
            })
            val file = File(directory, "source-policy.mp4")
            val writer = ComposedAvRecordingFile(file, egl.eglBaseContext, scene)
            writer.bindTimeline(clock::get)
            try {
                scene.setPlan(plan, mapOf(firstKey to tracks[0], secondKey to tracks[1]))
                writer.prepare()
                repeat(30) { frame -> feedUntil(scene, plan.inputs, sources, frame * 3200L, clock) }
                writer.write(ShortArray(48_000))
                // Neither setPlan nor a source callback runs at revocation.
                firstRevision.set(0)
                writer.write(ShortArray(24_000))
                assertTrue(scene.inputHistorySamples()[firstKey].isNullOrEmpty())
                assertFalse(scene.inputHistorySamples()[secondKey].isNullOrEmpty())
                firstRevision.set(3)
                writer.write(ShortArray(24_000))
                assertTrue(scene.inputHistorySamples()[firstKey].isNullOrEmpty())
                feedUntil(scene, plan.inputs, sources, 96_000, clock)
                writer.write(ShortArray(12_000))
                writer.close()
                assertRed(pixel(file, 500_000, plan.slots[0]))
                assertGrey(pixel(file, 1_200_000, plan.slots[0]))
                assertGrey(pixel(file, 1_800_000, plan.slots[0]))
                assertGreen(pixel(file, 1_800_000, plan.slots[1]))
                assertRed(pixel(file, 2_100_000, plan.slots[0]))
                assertGreen(pixel(file, 2_100_000, plan.slots[1]))
            } finally { writer.discard() }
        }
    }

    @Test fun continuing_sources_retain_the_audio_window_without_unbounded_video_history() {
        withSources { _, _, sources, tracks ->
            val clock = AtomicLong()
            val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first))
            val key = RecordingVideoKey(first.key, RecordingVideoRole.CAMERA)
            val scene = RecordingVideoScene(plan)
            scene.bindTimeline(clock::get)
            try {
                scene.setPlan(plan, mapOf(key to tracks[0]))
                repeat(75) { frame -> feedUntil(scene, setOf(key), sources, frame * 3200L, clock) }
                val history = requireNotNull(scene.inputHistorySamples()[key])
                val newest = clock.get()
                assertEquals(newest, history.last())
                assertTrue("Oldest queued audio still has its matching video", history.first() <= newest - PcmRecordingMixer.CAPACITY)
                assertTrue("Old video is released as capture continues", history.first() >= newest - PcmRecordingMixer.CAPACITY - 3200)
                scene.revoke(key, "Camera off")
                assertTrue(scene.inputHistorySamples().isEmpty())
            } finally { scene.dispose() }
        }
    }

    @Test fun delayed_audio_writer_keeps_matching_video_and_revocation_clears_only_that_source() {
        withSources { directory, egl, sources, tracks ->
            val clock = AtomicLong()
            val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY,
                listOf(first, second.copy(recordingProfile = 2)))
            val firstKey = RecordingVideoKey(first.key, RecordingVideoRole.CAMERA)
            val secondKey = RecordingVideoKey(second.key, RecordingVideoRole.CAMERA)
            val scene = RecordingVideoScene(plan)
            val file = File(directory, "delayed-writer.mp4")
            val writer = ComposedAvRecordingFile(file, egl.eglBaseContext, scene)
            writer.bindTimeline(clock::get)
            try {
                scene.setPlan(plan, mapOf(firstKey to tracks[0], secondKey to tracks[1]))
                writer.prepare()
                // Both sources arrive while the audio writer is delayed within
                // its two-second bound. Render the original sample positions,
                // rather than substituting newer pictures for earlier audio.
                repeat(30) { frame -> feedUntil(scene, plan.inputs, sources, frame * 3200L, clock) }
                writer.write(ShortArray(48_000))
                scene.revoke(firstKey, "Camera off")
                assertFalse(scene.inputSamples().containsKey(firstKey))
                assertTrue(scene.inputSamples().containsKey(secondKey))
                writer.write(ShortArray(48_000))
                writer.close()
                assertRed(pixel(file, 500_000, plan.slots[0]))
                assertGreen(pixel(file, 500_000, plan.slots[1]))
                assertGrey(pixel(file, 1_500_000, plan.slots[0]))
                assertGreen(pixel(file, 1_500_000, plan.slots[1]))
            } finally { writer.discard() }
        }
    }

    @Test fun gallery_exports_independent_tracks_and_drops_legacy_and_withdrawn_video() {
        withSources { directory, egl, sources, tracks ->
            val clock = AtomicLong()
            val scene = RecordingVideoScene(RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first, second)))
            val mappings = mapOf(RecordingVideoKey(first.key, RecordingVideoRole.CAMERA) to tracks[0], RecordingVideoKey(second.key, RecordingVideoRole.CAMERA) to tracks[1])
            val file = File(directory, "gallery.mp4")
            val writer = ComposedAvRecordingFile(file, egl.eglBaseContext, scene)
            writer.bindTimeline(clock::get)
            try {
                var plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first, second))
                scene.setPlan(plan, mappings)
                repeat(30) { frame ->
                    if (frame == 15) {
                        plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first, second.copy(recordingProfile = 2)))
                        scene.setPlan(plan, mappings)
                    }
                    if (frame == 23) {
                        plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first.copy(allowed = false), second.copy(recordingProfile = 2)))
                        scene.setPlan(plan, mappings)
                    }
                    feedUntil(scene, plan.inputs, sources, frame * 3200L, clock)
                    assertTrue(scene.inputSamples().keys.all { it in plan.inputs })
                    writer.write(ShortArray(3200))
                }
                writer.close()
                val slots = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first, second)).slots
                assertRed(pixel(file, 500_000, slots[0]))
                assertGrey(pixel(file, 500_000, slots[1]))
                assertGreen(pixel(file, 1_200_000, slots[1]))
                assertGrey(pixel(file, 1_800_000, slots[0]))
                assertGreen(pixel(file, 1_800_000, slots[1]))
                file.copyTo(File(directory.parentFile, "synthetic-scene-passed.mp4"), overwrite = true)
                // Export only these generated camera frames for an independent
                // Wildbloom reader and host decoder. Recovery keys remain in
                // app-private synthetic qualification data, never logs.
                val sealed = dev.forgesworn.kithmoot.session.sealFile(file, File(directory, "gallery.enc"),
                    "synthetic-gallery.mp4", "video/mp4")
                val opened = dev.forgesworn.kithmoot.session.openFileAttachment(sealed.file, File(directory, "opened.mp4"),
                    dev.forgesworn.kithmoot.session.ChatAttachment("https://interop.invalid/file", sealed.hash, sealed.key))
                assertEquals(dev.forgesworn.kithmoot.session.fileSha256(file), dev.forgesworn.kithmoot.session.fileSha256(opened.file))
                assertRed(pixel(opened.file, 500_000, slots[0]))
                assertGreen(pixel(opened.file, 1_800_000, slots[1]))
                val evidence = File(directory.parentFile, "synthetic-recording-interop").also { check(it.mkdirs() || it.isDirectory) }
                sealed.file.copyTo(File(evidence, "synthetic-gallery.mp4.enc"), overwrite = true)
                File(evidence, "synthetic-gallery.mp4.json").writeText(kotlinx.serialization.json.buildJsonObject {
                    put("synthetic", kotlinx.serialization.json.JsonPrimitive(true))
                    put("sha256", kotlinx.serialization.json.JsonPrimitive(sealed.hash))
                    put("key", kotlinx.serialization.json.JsonPrimitive(sealed.key))
                    put("sourceSha256", kotlinx.serialization.json.JsonPrimitive(dev.forgesworn.kithmoot.session.fileSha256(file)))
                    put("sourceSize", kotlinx.serialization.json.JsonPrimitive(file.length()))
                    put("name", kotlinx.serialization.json.JsonPrimitive(sealed.name))
                    put("type", kotlinx.serialization.json.JsonPrimitive(sealed.type))
                }.toString())
            } finally { writer.discard() }
        }
    }

    @Test fun unavailable_screen_keeps_the_selected_camera_visible_in_its_own_overlay() {
        withSources { directory, egl, sources, tracks ->
            val clock = AtomicLong()
            val plan = RecordingVideoPlan(origin, RecordingVideoLayout.SCREEN_CAMERA, listOf(first), first.key)
            val scene = RecordingVideoScene(plan)
            val file = File(directory, "screen-camera.mp4")
            val writer = ComposedAvRecordingFile(file, egl.eglBaseContext, scene)
            writer.bindTimeline(clock::get)
            try {
                scene.setPlan(plan, mapOf(RecordingVideoKey(first.key, RecordingVideoRole.CAMERA) to tracks[0]))
                repeat(15) { frame ->
                    feedUntil(scene, plan.inputs, sources, frame * 3200L, clock)
                    writer.write(ShortArray(3200))
                }
                writer.close()
                assertGrey(pixel(file, 500_000, plan.slots[0]))
                assertRed(pixel(file, 500_000, plan.slots[1]))
            } finally { writer.discard() }
        }
    }

    @Test fun source_off_revokes_queued_pictures_and_an_authoritative_restart_restores_only_that_source() {
        withSources { _, _, sources, tracks ->
            val clock = AtomicLong()
            val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first))
            val scene = RecordingVideoScene(plan)
            val key = RecordingVideoKey(first.key, RecordingVideoRole.CAMERA)
            val mappings = mapOf(key to tracks[0])
            scene.bindTimeline(clock::get)
            try {
                scene.setPlan(plan, mappings)
                feedUntil(scene, setOf(key), sources, 0, clock)
                scene.revoke(key, "Camera off")
                assertTrue(scene.inputSamples().isEmpty())
                feedUntil(scene, emptySet(), sources, 3200, clock)
                assertTrue(scene.inputSamples().isEmpty())
                scene.setPlan(plan, mappings)
                feedUntil(scene, setOf(key), sources, 6400, clock)
                assertEquals(mapOf(key to 6400L), scene.inputSamples())
            } finally { scene.dispose() }
        }
    }

    @Test fun revoked_capture_drops_queued_video_and_cannot_resume_when_the_predicate_recovers() {
        withSources { directory, egl, sources, tracks ->
            val clock = AtomicLong()
            val authorised = AtomicBoolean(true)
            val permission = RecordingCapturePermission(authorised::get)
            val plan = RecordingVideoPlan(origin, RecordingVideoLayout.GALLERY, listOf(first))
            val scene = RecordingVideoScene(plan, permission::allowed)
            val key = RecordingVideoKey(first.key, RecordingVideoRole.CAMERA)
            val file = File(directory, "revoked-capture.mp4")
            val writer = ComposedAvRecordingFile(file, egl.eglBaseContext, scene)
            writer.bindTimeline(clock::get)
            try {
                scene.setPlan(plan, mapOf(key to tracks[0]))
                repeat(30) { frame ->
                    val sample = frame * 3200L
                    if (frame < 15) {
                        feedUntil(scene, setOf(key), sources, sample, clock)
                    } else {
                        // The last accepted picture is still queued when
                        // permission changes. Rendering must drop it even
                        // without another source callback.
                        if (frame == 15) authorised.set(false)
                        if (frame == 20) {
                            authorised.set(true)
                            scene.setPlan(plan, mapOf(key to tracks[0]))
                        }
                        clock.set(sample)
                    }
                    writer.write(ShortArray(3200))
                    if (frame >= 15) {
                        assertTrue(permission.revoked())
                        assertTrue(scene.inputSamples().isEmpty())
                        // New callbacks and a refreshed plan must not rearm
                        // the revoked capture, even after state recovers.
                        feedUntil(scene, emptySet(), sources, sample, clock)
                        assertTrue(scene.inputSamples().isEmpty())
                    }
                }
                writer.close()
                assertRed(pixel(file, 500_000, plan.slots[0]))
                assertGrey(pixel(file, 1_200_000, plan.slots[0]))
                assertGrey(pixel(file, 1_800_000, plan.slots[0]))
            } finally { writer.discard() }
        }
    }

    private fun feedUntil(scene: RecordingVideoScene, wanted: Set<RecordingVideoKey>, sources: List<VideoSource>, sample: Long, clock: AtomicLong) {
        clock.set(sample)
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            sources.forEachIndexed { index, source ->
                val buffer = JavaI420Buffer.allocate(320, 240)
                val values = if (index == 0) listOf(81, 90, 240) else listOf(145, 54, 34)
                val y = buffer.dataY
                val u = buffer.dataU
                val v = buffer.dataV
                y.put(ByteArray(y.remaining()) { values[0].toByte() })
                u.put(ByteArray(u.remaining()) { values[1].toByte() })
                v.put(ByteArray(v.remaining()) { values[2].toByte() })
                val frame = VideoFrame(buffer, 0, System.nanoTime())
                try { source.capturerObserver.onFrameCaptured(frame) } finally { frame.release() }
            }
            if (wanted.all { scene.inputSamples()[it] == sample }) return
            SystemClock.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        fail("Native scene inputs did not arrive: wanted=$wanted sample=$sample observed=${scene.inputSamples()}")
    }

    private fun pixel(file: File, at: Long, slot: RecordingVideoSlot): Int {
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.absolutePath)
            val bitmap = requireNotNull(reader.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST))
            try {
                assertEquals(1280, bitmap.width); assertEquals(720, bitmap.height)
                return bitmap.getPixel(slot.bounds.x + slot.bounds.width / 2, slot.bounds.y + slot.videoHeight.coerceAtLeast(1) / 2)
            } finally { bitmap.recycle() }
        } finally { reader.release() }
    }
    private fun assertRed(pixel: Int) = assertTrue("Expected selected red camera: ${Integer.toHexString(pixel)}", Color.red(pixel) > 150 && Color.green(pixel) < 100)
    private fun assertGreen(pixel: Int) = assertTrue("Expected selected green camera: ${Integer.toHexString(pixel)}", Color.green(pixel) > 150 && Color.red(pixel) < 100)
    private fun assertGrey(pixel: Int) = assertTrue("Excluded video must be a placeholder: ${Integer.toHexString(pixel)}", Color.red(pixel) < 100 && Color.green(pixel) < 100 && Color.blue(pixel) < 100)

    private fun withSources(run: (File, EglBase, List<VideoSource>, List<VideoTrack>) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-scene-${System.nanoTime()}").also { check(it.mkdirs()) }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val egl = EglBase.create()
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val sources = List(2) { factory.createVideoSource(false) }
        val tracks = sources.mapIndexed { index, source -> factory.createVideoTrack("synthetic-scene-$index", source) }
        try {
            sources.forEach { it.capturerObserver.onCapturerStarted(true) }
            run(directory, egl, sources, tracks)
        } finally {
            sources.forEach { it.capturerObserver.onCapturerStopped() }
            tracks.forEach { it.dispose() }; sources.forEach { it.dispose() }
            factory.dispose(); egl.release(); directory.deleteRecursively()
        }
    }
}
