package dev.forgesworn.kithmoot.ui

import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.AccountNetworkMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a preflighted synthetic emulator and an already-private installation.
 * The real constructor can perform existing local maintenance: orphan Link route
 * cleanup, epoch/member retention and recording-draft recovery. This is NOT a
 * read-only test. It does not reset/preseed state or change the network choice.
 * An uncaught observer exception must fail instrumentation even if a DTO exists. */
@RunWith(AndroidJUnit4::class)
class RoomViewModelConstructionTest {
    @Test fun privateConstructionInitializesEveryPropertyBeforeFirstPolicyPass() = runBlocking {
        assertTrue("Synthetic emulator required",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertEquals("Review the synthetic fixture snapshot and constructor maintenance before opting in",
            "reviewed", InstrumentationRegistry.getArguments().getString("privateConstructorFixturePreflight"))
        val application = ApplicationProvider.getApplicationContext<KithMootApplication>()
        assertEquals("Run this regression on the private-invitations candidate",
            AccountNetworkMode.PRIVATE_INVITATIONS, application.accountNetwork.token().mode)
        val retainedPolicy = application.accountNetwork.token()
        repeat(16) {
            val owner = ViewModelStore()
            lateinit var model: RoomViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // chatOnly executes the same policy collector without restoring a
                // second signer session or starting ordinary account services.
                model = RoomViewModel(application, chatOnly = true)
                owner.put("constructor-regression", model)
            }
            try {
                withTimeout(10_000) { model.awaitInitialNetworkPolicyObservation() }
                assertEquals(AccountNetworkMode.PRIVATE_INVITATIONS, model.start.value.accountNetworkMode)
                assertTrue(model.start.value.followProfiles.isEmpty())
                assertTrue(model.start.value.privateChatProfiles.isEmpty())
                assertEquals(retainedPolicy, application.accountNetwork.token())
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
            }
        }
    }
}
