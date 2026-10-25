package dev.forgesworn.kithmoot.media

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SpryClientDnsTest {
    /** Runs one async call and returns how it ended, plus anything thrown uncaught on another thread meanwhile. */
    private fun enqueue(client: OkHttpClient, url: String): Pair<Throwable?, List<Throwable>> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        try {
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            client.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { failure.set(e); done.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); done.countDown() }
            })
            assertTrue("the call never finished", done.await(10, TimeUnit.SECONDS))
            Thread.sleep(200)
            return failure.get() to uncaught.toList()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test fun spryUsesTheSharedPublicOnlyResolver() {
        assertTrue(SpryClient.client.dns is PublicOnlyDns)
    }

    @Test fun aNameThatResolvesToANonPublicAddressIsAnOrdinaryFailureNotACrash() {
        // localhost resolves to loopback through the system resolver; SpryClient's own client must refuse it.
        val (failure, uncaught) = enqueue(SpryClient.client, "https://localhost/")
        assertTrue(failure.toString(), failure is UnknownHostException)
        assertEquals(PublicAddress.REFUSED, failure?.message)
        assertTrue(uncaught.toString(), uncaught.isEmpty())
    }

    @Test fun everyRefusedAnswerSurfacesAsAnIoException() {
        for (answer in listOf("10.0.0.1", "192.168.0.7", "169.254.169.254", "::1", "fd00::1", "100.64.0.1")) {
            val client = OkHttpClient.Builder().dns(PublicOnlyDns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName(answer))
            })).build()
            val (failure, uncaught) = enqueue(client, "https://spry.example.org/")
            assertTrue("$answer: $failure", failure is UnknownHostException)
            assertTrue(uncaught.toString(), uncaught.isEmpty())
        }
    }
}
