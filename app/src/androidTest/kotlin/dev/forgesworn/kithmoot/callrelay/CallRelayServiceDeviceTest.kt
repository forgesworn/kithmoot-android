package dev.forgesworn.kithmoot.callrelay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The relay service answers over its binder from its own `:relay` process and
 * refuses a malformed start. No node is started and nothing touches the
 * network.
 */
@RunWith(AndroidJUnit4::class)
class CallRelayServiceDeviceTest {
    @Test fun the_relay_process_answers_and_refuses_a_malformed_start() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connected = CountDownLatch(1)
        var service: ICallRelay? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { service = ICallRelay.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(Intent(context, CallRelayService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("bound within 10 s", connected.await(10, TimeUnit.SECONDS))
            val relay = requireNotNull(service)
            val state = RelayState.parse(relay.state())
            assertNotNull(state)
            assertEquals(RelayPhase.OFF, state!!.phase)
            assertEquals(ICallRelay.ERROR_INVALID, relay.start(ByteArray(31), "a".repeat(64), arrayOf("wss://relay.example.com/"), 23456))
            assertEquals(ICallRelay.ERROR_INVALID, relay.start(ByteArray(32), "not a key", arrayOf("wss://relay.example.com/"), 23456))
            assertEquals(ICallRelay.ERROR_INVALID, relay.start(ByteArray(32), "a".repeat(64), arrayOf("ws://relay.example.com/"), 23456))
            assertEquals(ICallRelay.ERROR_INVALID, relay.start(ByteArray(32), "a".repeat(64), arrayOf("wss://relay.example.com/"), 80))
            assertEquals(RelayPhase.OFF, RelayState.parse(relay.state())!!.phase)
        } finally {
            context.unbindService(connection)
        }
    }
}
