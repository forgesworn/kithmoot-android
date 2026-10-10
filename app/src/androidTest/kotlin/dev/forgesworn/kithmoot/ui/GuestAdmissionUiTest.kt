package dev.forgesworn.kithmoot.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.LifecycleOwner
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class GuestAdmissionUiTest {
    @get:Rule val compose = createComposeRule()
    private val gate = GuestAdmissionGate()
    private val devices = Devices()
    private val attempts = mutableListOf<GuestAdmissionAttempt>()

    private class Devices : GuestDevicePreview {
        var cameraStarts = 0; var microphoneStarts = 0
        var cameraLive = false; var microphoneLive = false
        var fail = false
        override fun startCamera(owner: LifecycleOwner, view: PreviewView, onReady: () -> Unit, onFailure: () -> Unit) {
            cameraStarts++; if (fail) onFailure() else { cameraLive = true; onReady() }
        }
        override fun startMicrophone(scope: CoroutineScope, onLevel: (Float) -> Unit, onFailure: () -> Unit) {
            microphoneStarts++; if (fail) onFailure() else { microphoneLive = true; onLevel(0.2f) }
        }
        override fun stopCamera() { cameraLive = false }
        override fun stopMicrophone() { microphoneLive = false }
        override fun stop() { stopCamera(); stopMicrophone() }
        override fun close() { stop() }
    }

    private fun show(permission: PermissionAsker = PermissionAsker { it.onGranted() }, available: Boolean = true,
        dark: Boolean = false, large: Boolean = false) {
        gate.prepare("synthetic-invitation", "Guest preview workshop", "Rowan")
        compose.setContent {
            val view by gate.view.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.6f else density.fontScale)) {
                KithMootTheme(darkTheme = dark) {
                    view?.let { current -> GuestAdmissionDialog(current, false, available,
                        onName = gate::editName,
                        onRequest = {
                            assertFalse("Camera must stop before the request callback", devices.cameraLive)
                            assertFalse("Microphone must stop before the request callback", devices.microphoneLive)
                            gate.request()?.let(attempts::add)
                        }, onCancel = gate::cancel, onClose = gate::clear, onRetry = { gate.retry() },
                        devicePreview = devices, permissionAsker = permission) }
                }
            }
        }
    }

    private fun action(text: String) = compose.onNode(hasText(text) and hasClickAction())

    private fun assertWholeControlInsideDisplay(text: String) {
        val node = action(text).fetchSemanticsNode()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val screenshot = automation.takeScreenshot()!!
        val position = node.layoutInfo.coordinates.localToScreen(Offset.Zero)
        val ancestors = generateSequence(node) { it.parent }.joinToString("; ") {
            "${it.layoutInfo.coordinates.localToScreen(Offset.Zero)} ${it.size} ${it.config}"
        }
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val evidence = File(context.getExternalFilesDir(null), "test-evidence/guest-admission-20261010")
            evidence.mkdirs()
            File(evidence, "control-${text.replace(' ', '-')}.png").outputStream().use {
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("$text must fit across the screen", position.x >= 0 && position.x + node.size.width <= screenshot.width)
            assertTrue("$text must fit vertically: top=${position.y}, height=${node.size.height}, display=${screenshot.height}. $ancestors",
                position.y >= 0 && position.y + node.size.height <= screenshot.height)
        } finally { screenshot.recycle() }
    }

    private fun screenshot(scheme: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val folder = File(context.getExternalFilesDir(null), "test-evidence/guest-admission-20261010").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        assertNotNull(bitmap)
        File(folder, "large-$scheme.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun openingShowsReviewWithoutStartingDevicesOrRequestingEntry() {
        show()
        compose.onNodeWithText("Review this invitation").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(attempts.isEmpty()); assertEquals(0, devices.cameraStarts); assertEquals(0, devices.microphoneStarts)
        }
        compose.onNodeWithText("Name for your request").performTextReplacement("Keep my name")
        action("Request to join").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, attempts.size); assertEquals("Keep my name", attempts.single().name) }
        compose.onNodeWithText("Waiting for relay confirmation", substring = true).assertIsDisplayed()
        compose.runOnIdle { gate.phase(attempts.single(), GuestAdmissionPhase.WAITING) }
        compose.onNodeWithText("Your request reached a relay", substring = true).assertIsDisplayed()
    }

    @Test fun requestingStopsBothLocalChecksBeforeTheRequestCallback() {
        show()
        action("Preview camera").performScrollTo().performClick()
        action("Check microphone").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(devices.cameraLive); assertTrue(devices.microphoneLive) }
        action("Request to join").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, attempts.size); assertFalse(devices.cameraLive); assertFalse(devices.microphoneLive) }
    }

    @Test fun lateCameraAndMicrophonePermissionsAfterRequestCannotStartInputs() {
        val asks = mutableListOf<PermissionAsk>()
        show(PermissionAsker { asks += it })
        action("Preview camera").performScrollTo().performClick()
        action("Check microphone").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, asks.size) }
        action("Request to join").performScrollTo().performClick()
        compose.runOnIdle {
            asks.forEach { it.onGranted() }
            assertEquals(0, devices.cameraStarts); assertEquals(0, devices.microphoneStarts)
        }
    }

    @Test fun cancelledEntryKeepsTheNameButLateGrantCannotChangeItsState() {
        show()
        compose.onNodeWithText("Name for your request").performTextReplacement("Keep my name")
        action("Request to join").performScrollTo().performClick()
        val old = compose.runOnIdle { attempts.single() }
        action("Cancel request").performScrollTo().performClick()
        compose.onNodeWithText("Your request was cancelled").assertIsDisplayed()
        compose.runOnIdle { assertFalse(gate.phase(old, GuestAdmissionPhase.ADMITTED)) }
        action("Review and retry").performScrollTo().performClick()
        compose.onNodeWithText("Keep my name").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, attempts.size) }
        action("Request to join").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, attempts.size); assertNotEquals(old.id, attempts.last().id) }
    }

    @Test fun aFailedLocalDeviceCheckDoesNotPreventRequestingEntry() {
        devices.fail = true
        show()
        action("Preview camera").performScrollTo().performClick()
        compose.onNodeWithText("The camera could not start", substring = true).assertIsDisplayed()
        action("Check microphone").performScrollTo().performClick()
        compose.onNodeWithText("The microphone could not start", substring = true).assertIsDisplayed()
        action("Request to join").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, attempts.size) }
    }

    @Test fun anExistingCallKeepsItsInputsOutOfThePreview() {
        show(available = false)
        action("Preview camera").assertIsNotEnabled()
        action("Check microphone").assertIsNotEnabled()
        action("Request to join").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, devices.cameraStarts); assertEquals(0, devices.microphoneStarts) }
    }

    @Test fun largeTextLightAndDarkKeepRequestAndCloseReachable() {
        // Each theme has its own fixture instance; neither changes stored app preferences.
        show(large = true, dark = false)
        action("Request to join").performScrollTo().assertIsDisplayed()
        assertWholeControlInsideDisplay("Request to join")
        action("Close invitation").performScrollTo().assertIsDisplayed()
        assertWholeControlInsideDisplay("Close invitation")
        screenshot("light")
        action("Close invitation").performClick()
        compose.runOnIdle { assertNull(gate.view.value); assertTrue(attempts.isEmpty()) }
    }

    @Test fun largeTextDarkKeepsRequestAndCloseReachable() {
        show(large = true, dark = true)
        action("Request to join").performScrollTo().assertIsDisplayed()
        assertWholeControlInsideDisplay("Request to join")
        action("Close invitation").performScrollTo().assertIsDisplayed()
        assertWholeControlInsideDisplay("Close invitation")
        screenshot("dark")
        action("Close invitation").performClick()
        compose.runOnIdle { assertNull(gate.view.value); assertTrue(attempts.isEmpty()) }
    }
}
