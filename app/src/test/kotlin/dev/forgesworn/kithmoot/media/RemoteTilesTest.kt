package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A fake remote track: everything [resolveRemoteByRole] reads off a real
 * `RemoteTrack`, without a `MediaStreamTrack` anywhere - see the function's
 * own doc for why that matters on a plain JVM.
 */
private data class FakeTrack(val device: String, val trackId: String, val receiving: Boolean, val label: String)

private fun resolve(remote: List<FakeTrack>, roles: Map<Pair<String, String>, String>): Map<String, String> =
    resolveRemoteByRole(
        remote = remote,
        device = { it.device },
        trackId = { it.trackId },
        receiving = { it.receiving },
        roleForTrackId = { device, trackId -> roles[device to trackId] },
        valueFor = { it.label },
    )

class RemoteTilesTest {

    @Test
    fun `keys by device and role, not by trackId`() {
        val remote = listOf(FakeTrack("laptop", "webrtc-track-1", receiving = true, label = "camera-video"))
        val roles = mapOf(("laptop" to "webrtc-track-1") to "camera")

        assertEquals(mapOf("laptop|camera" to "camera-video"), resolve(remote, roles))
    }

    @Test
    fun `a non-receiving transceiver is dropped even though its track lingers`() {
        // The H5 shape: a receiver the far end has moved on from, whose
        // transceiver.currentDirection has already stopped being sendrecv or
        // recvonly, but whose track object never fires ended.
        val remote = listOf(FakeTrack("laptop", "stale-track", receiving = false, label = "stale-video"))
        val roles = mapOf(("laptop" to "stale-track") to "camera")

        assertEquals(emptyMap(), resolve(remote, roles))
    }

    @Test
    fun `a track with no roster advert for its trackId is dropped, not guessed at`() {
        val remote = listOf(FakeTrack("laptop", "unadvertised-track", receiving = true, label = "video"))

        assertEquals(emptyMap(), resolve(remote, roles = emptyMap()))
    }

    @Test
    fun `two devices each get their own role-keyed entry`() {
        val remote = listOf(
            FakeTrack("laptop", "t1", receiving = true, label = "laptop-camera"),
            FakeTrack("phone", "t2", receiving = true, label = "phone-camera"),
        )
        val roles = mapOf(
            ("laptop" to "t1") to "camera",
            ("phone" to "t2") to "camera",
        )

        assertEquals(
            mapOf("laptop|camera" to "laptop-camera", "phone|camera" to "phone-camera"),
            resolve(remote, roles),
        )
    }

    @Test
    fun `when two live receivers resolve to the same role, the later one in the list wins`() {
        // The brief window mid-renegotiation where an old and a new
        // transceiver are both still reporting as receiving. Not a promise
        // that the later one is the one whose packets are moving - see the
        // function's own doc.
        val remote = listOf(
            FakeTrack("laptop", "old-track", receiving = true, label = "old-video"),
            FakeTrack("laptop", "new-track", receiving = true, label = "new-video"),
        )
        val roles = mapOf(
            ("laptop" to "old-track") to "camera",
            ("laptop" to "new-track") to "camera",
        )

        assertEquals(mapOf("laptop|camera" to "new-video"), resolve(remote, roles))
    }

    @Test
    fun `roleKey is device pipe role`() {
        assertEquals("device-1|camera", roleKey("device-1", "camera"))
    }
}
