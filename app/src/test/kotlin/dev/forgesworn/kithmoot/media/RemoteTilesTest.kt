package dev.forgesworn.kithmoot.media

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A fake remote track: everything [resolveRemoteByRole] reads off a real
 * `RemoteTrack`, without a `MediaStreamTrack` anywhere - see the function's
 * own doc for why that matters on a plain JVM.
 */
private data class FakeTrack(val device: String, val trackId: String, val receiving: Boolean, val label: String)

private fun resolve(
    remote: List<FakeTrack>,
    roles: Map<Pair<String, String>, String>,
    advertised: Map<String, List<String>> = emptyMap(),
): Map<String, String> =
    resolveRemoteByRole(
        remote = remote,
        device = { it.device },
        trackId = { it.trackId },
        receiving = { it.receiving },
        roleForTrackId = { device, trackId -> roles[device to trackId] },
        valueFor = { it.label },
        advertisedRoles = { advertised[it].orEmpty() },
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

    @Test
    fun `a live picture whose id the advert no longer names takes the camera its device announced`() {
        // The far end swapped its camera under the sender: the msid still
        // names the old track, the roster the new one.
        val remote = listOf(FakeTrack("linux", "msid-of-old-camera", receiving = true, label = "morgs-camera"))
        val roles = mapOf(("linux" to "new-camera-track") to "camera")

        assertEquals(mapOf("linux|camera" to "morgs-camera"), resolve(remote, roles, mapOf("linux" to listOf("camera"))))
    }

    @Test
    fun `the fallback never takes a role a matched track already fills`() {
        val remote = listOf(
            FakeTrack("linux", "unmatched", receiving = true, label = "leftover"),
            FakeTrack("linux", "cam", receiving = true, label = "camera"),
        )
        val roles = mapOf(("linux" to "cam") to "camera")

        assertEquals(mapOf("linux|camera" to "camera"), resolve(remote, roles, mapOf("linux" to listOf("camera"))))
    }

    @Test
    fun `a stale receiver is not rescued by the fallback`() {
        val remote = listOf(FakeTrack("linux", "unmatched", receiving = false, label = "stale"))

        assertEquals(emptyMap(), resolve(remote, emptyMap(), mapOf("linux" to listOf("camera"))))
    }
}
