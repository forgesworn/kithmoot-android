package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.start.StartScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StandaloneWokUiTest {
    @get:Rule val compose = createComposeRule()
    private val pubkey = "a".repeat(64)
    private val account = AccountView(pubkey, npubOf(pubkey), shortNpub(pubkey), "local")
    private val room = SavedRoomSummary(id = "room", name = "Private room", secondary = false, openedAt = 0, account = pubkey)

    @Test fun connect_dialog_explains_scope_and_submits_the_exact_url() {
        var submitted: Pair<String, String>? = null
        compose.setContent {
            KithMootTheme {
                StartScreen(
                    state = StartState(loadingRooms = false, savedRooms = listOf(room), account = account),
                    onRoomNameChanged = {}, onJoinUrlChanged = {}, onRelaysChanged = {}, onAnonymousModeChanged = {},
                    onPersistentGroupChanged = {}, onStartRoom = {}, onJoin = {}, onReopen = {}, onForget = {}, onProject = { _, _ -> },
                    onRetryStorage = {}, onResetStorage = {},
                    onPairStandaloneWok = { id, url -> submitted = id to url },
                )
            }
        }
        compose.onNodeWithText(room.name).performTouchInput { longClick() }
        compose.onNodeWithText("Connect standalone Wok").performClick()
        compose.onNodeWithText("Only room text and presence use this route. Calls and advanced room controls are off while it is selected.").performScrollTo().assertIsDisplayed()
        val url = "wss://relay.example.org/events"
        compose.onNodeWithText("Wok event address").performScrollTo().assertIsDisplayed().performTextInput(url)
        compose.onNodeWithText("Connect and verify").assertIsEnabled()
        capture("standalone-wok-connect")
        compose.onNodeWithText("Connect and verify").performClick()
        assertEquals("room" to url, submitted)
    }

    @Test fun connected_room_requires_confirmation_before_withdrawing() {
        var withdrawn: String? = null
        compose.setContent {
            KithMootTheme {
                StartScreen(
                    state = StartState(loadingRooms = false, savedRooms = listOf(room), account = account, standaloneWokConnectedRooms = setOf(room.id)),
                    onRoomNameChanged = {}, onJoinUrlChanged = {}, onRelaysChanged = {}, onAnonymousModeChanged = {},
                    onPersistentGroupChanged = {}, onStartRoom = {}, onJoin = {}, onReopen = {}, onForget = {}, onProject = { _, _ -> },
                    onRetryStorage = {}, onResetStorage = {}, onDisconnectStandaloneWok = { withdrawn = it },
                )
            }
        }
        compose.onNodeWithText(room.name).performTouchInput { longClick() }
        compose.onNodeWithText("Connect Bothy").assertDoesNotExist()
        compose.onNodeWithText("Disconnect standalone Wok").performClick()
        compose.onNodeWithText("Disconnect standalone Wok?").assertIsDisplayed()
        capture("standalone-wok-disconnect")
        compose.onNodeWithText("Disconnect").performClick()
        assertEquals("room", withdrawn)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val directory = File("/storage/emulated/0/Pictures/standalone-wok-ui-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val colours = mutableSetOf<Int>()
        for (y in bitmap.height / 8 until bitmap.height * 7 / 8 step 8) {
            for (x in bitmap.width / 8 until bitmap.width * 7 / 8 step 8) colours += bitmap.getPixel(x, y)
        }
        assertTrue("UI evidence must contain rendered content", colours.size > 20)
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
