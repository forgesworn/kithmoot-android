package dev.forgesworn.kithmoot.media

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TURN fetch gives up on time even when DNS does not.
 *
 * A Pixel answering a ring from the lock screen waited 38 seconds for this
 * fetch, with a four-second `callTimeout` set: a blocking call stuck in the
 * system resolver does not come back when the timeout fires.
 */
class CallIceServersFetchTest {
    @Test fun `a lookup that hangs still gives up within the limit`() = runBlocking {
        val hung = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> { Thread.sleep(10_000); return listOf(InetAddress.getLoopbackAddress()) }
        }
        val client = OkHttpClient.Builder().dns(hung).callTimeout(500, TimeUnit.MILLISECONDS).build()
        val begun = System.nanoTime()
        val value = CallIceServers.fetchTurn(client, "https://turn.invalid/turn", 500)
        val tookMs = (System.nanoTime() - begun) / 1_000_000
        assertNull(value)
        assertTrue(tookMs < 2_000, "gave up after ${tookMs}ms")
    }
}
