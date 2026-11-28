package dev.forgesworn.kithmoot.account

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The silent NIP-55 path across a real ContentResolver, against a provider in
 * another package ([FixtureSilentSignerProvider]): replies, refusals and no
 * cursor read as they always have, and a signer that does not reply releases
 * its caller when the budget ends instead of holding it until it replies.
 */
@RunWith(AndroidJUnit4::class)
class Nip55ProviderQueryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val peer = "b".repeat(64)
    private val signer = Nip55Signer("a".repeat(64), instrumentation.context.packageName, instrumentation.targetContext) { null }

    @Test fun a_reply_a_refusal_and_no_cursor_read_as_before() = runBlocking {
        assertEquals("plain", signer.nip44DecryptSilently(peer, "answer:plain"))
        assertNull(signer.nip44DecryptSilently(peer, "none"))
        try {
            signer.nip44DecryptSilently(peer, "reject")
            fail("A refusal must surface as an error, not as no answer.")
        } catch (_: SignerException) {
        }
    }

    @Test fun a_signer_that_does_not_reply_releases_the_caller_at_the_budget() = runBlocking {
        val started = System.nanoTime()
        assertNull(signer.nip44DecryptSilently(peer, "hang"))
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("released after $waited ms", waited in SIGNER_SILENT_TIMEOUT_MS..(SIGNER_SILENT_TIMEOUT_MS + 5_000))
    }
}
