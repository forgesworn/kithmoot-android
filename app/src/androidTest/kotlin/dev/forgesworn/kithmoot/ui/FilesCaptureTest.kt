package dev.forgesworn.kithmoot.ui

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.notifications.RoomNotifyMode
import dev.forgesworn.kithmoot.session.FileDestination
import dev.forgesworn.kithmoot.session.FileServerVia
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.ComposerFiles
import dev.forgesworn.kithmoot.ui.room.FileChoiceActions
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.ROOM_TITLE_TAG
import dev.forgesworn.kithmoot.ui.room.RoomNotifyActions
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.settings.SettingsPage
import dev.forgesworn.kithmoot.ui.start.SettingsScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Screenshots of files, public first, on Android (kithmoot #527): the one-time
 * notice for a picked image, with the retired ForgeSworn line and for a long
 * paste; the choices opened from it; the uploads-off line; Settings › Privacy
 * and Where files go in each state; and Room info's line. Seeded state, no
 * network; saved under `files/files-shots/`.
 */
class FilesCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val me = "03".repeat(32)
    private val yaki = "https://blossom.yakihonne.com"
    private val notify = RoomNotifyActions({ RoomNotifyMode.DEFAULT }, { _, _ -> }, { _, _ -> }, {})
    private fun here(who: String, name: String) = ParticipantTile(who, false, 1, emptyList(), null, false, name = name)
    private val room = RoomState(roomId = "3c".repeat(32), selfParticipant = me, name = "Town hall", relaysUp = 3, relaysTotal = 3,
        joinUrl = "https://example.org/j/#townhall", tiles = listOf(here(me, "Ada"), here("a2".repeat(32), "Priya Natarajan")))

    @Composable
    private fun Room(files: ComposerFiles, filesLine: String? = null) = RoomScreen(room, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
        modifier = Modifier.fillMaxSize(), pinned = false, onAddToProject = {}, notify = notify, filesLine = filesLine,
        chat = { ChatPane(emptyList(), me, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false, files = files) })

    @Composable
    private fun Settings(choice: FileChoice, start: SettingsPage) {
        var page by remember { mutableStateOf(start) }
        SettingsScreen(state = StartState(files = choice), signIn = dev.forgesworn.kithmoot.ui.start.AccountActions.None,
            accountSettings = dev.forgesworn.kithmoot.ui.start.AccountSettingsActions(),
            accountRooms = dev.forgesworn.kithmoot.ui.start.AccountRoomActions(), relayChoices = emptyList(),
            onWebAppAddressChanged = { false }, notificationSettings = {}, page = page, onPageChange = { page = it }, onBack = {},
            fileChoiceActions = FileChoiceActions(onOwn = { null }))
    }

    /** At a large font a control can sit below the fold of a scrolling dialog or page. */
    private fun SemanticsNodeInteraction.tap() { runCatching { performScrollTo() }; performClick() }

    @Test fun capture_files() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "files-shots").apply { mkdirs() }
        val font = "%.1f".format(context.resources.configuration.fontScale)
        val picked = Uri.parse("content://example/cat.png")
        val notDecided = FileChoice(FileDestination.Default(yaki, 209_715_200L))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var theme = "light"
            fun show(content: @Composable () -> Unit) = scenario.onActivity { activity -> activity.setContent {
                val dark = isSystemInDarkTheme()
                theme = if (dark) "dark" else "light"
                KithMootTheme(darkTheme = dark) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() } }
            } }
            fun shoot(name: String) {
                ui.waitForIdle()
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                File(out, "$name-$theme-font$font.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }

            // The first image picked on this device: the notice names the server, and its button uploads.
            show { Room(ComposerFiles(choice = notDecided, notice = FileNotice(yaki, picked))) }
            ui.onNodeWithText("Upload to blossom.yakihonne.com").assertExists()
            shoot("notice-image")

            // A device whose choice named ForgeSworn's storage: said first, in the same notice.
            show { Room(ComposerFiles(choice = notDecided.copy(retired = true), notice = FileNotice(yaki, picked))) }
            ui.onNodeWithText("KithMoot no longer sends files to ForgeSworn’s storage.").assertExists()
            shoot("notice-retired")

            // A long paste going as a document: the button sends.
            show { Room(ComposerFiles(choice = notDecided, notice = FileNotice(yaki, null))) }
            ui.onNodeWithText("Send").assertExists()
            shoot("notice-paste")

            // Choose another server opens the same choices as Settings, without leaving the room.
            ui.onNodeWithText("Choose another server").tap()
            ui.onNodeWithText("Store files I send on").assertExists()
            shoot("choices-in-room")

            // Uploads off: the picked image is held, and the line says why.
            show { Room(ComposerFiles(choice = FileChoice(FileDestination.Off(chosen = true)), off = FileDestination.Off(chosen = true))) }
            ui.onNodeWithText("File uploads are off on this device.").assertExists()
            shoot("files-off")

            // Settings › Privacy: the Files row.
            show { Settings(notDecided, SettingsPage.PRIVACY) }
            ui.onNodeWithText("A public server · blossom.yakihonne.com").assertExists()
            shoot("settings-privacy")

            // Where files go: a public server, my own server, and Nowhere.
            show { Settings(FileChoice(FileDestination.Server(yaki, FileServerVia.DEFAULT)), SettingsPage.FILES) }
            shoot("settings-public")
            show { Settings(FileChoice(FileDestination.Server("https://files.example", FileServerVia.TYPED), ownServer = "https://files.example"), SettingsPage.FILES) }
            ui.onNodeWithText("Use this server").assertExists()
            shoot("settings-own")
            show { Settings(FileChoice(FileDestination.Off(chosen = true)), SettingsPage.FILES) }
            shoot("settings-nowhere")

            // Room info › Privacy and connection.
            show { Room(ComposerFiles(choice = notDecided), filesLine = "Files you send in this room go to blossom.yakihonne.com.") }
            ui.onNodeWithTag(ROOM_TITLE_TAG).performClick()
            ui.onNodeWithText("Privacy and connection").tap()
            ui.onNodeWithText("Files you send in this room go to blossom.yakihonne.com.").assertExists()
            shoot("room-info")
        }
    }
}
