package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.notifications.RoomNotifyMode
import dev.forgesworn.kithmoot.session.ChatAttachment
import dev.forgesworn.kithmoot.session.FileDestination
import dev.forgesworn.kithmoot.session.GROUP_FILES_LINE
import dev.forgesworn.kithmoot.session.GROUP_NO_STORAGE
import dev.forgesworn.kithmoot.session.GroupStorageReason
import dev.forgesworn.kithmoot.session.WaitingFile
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.ComposerFiles
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import dev.forgesworn.kithmoot.ui.room.ROOM_TITLE_TAG
import dev.forgesworn.kithmoot.ui.room.RoomNotifyActions
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Screenshots of a room that keeps its files on its group's own storage (S5b,
 * web #532): the composer's line with a stored file and one waiting for the
 * group's storage, a withdrawn room, a lock-out that will not change by itself,
 * and Room info's line. Seeded state, no network; saved under
 * `files/group-files-shots/`.
 */
class GroupFilesCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val me = "03".repeat(32)
    private val notify = RoomNotifyActions({ RoomNotifyMode.DEFAULT }, { _, _ -> }, { _, _ -> }, {})
    private fun here(who: String, name: String) = ParticipantTile(who, false, 1, emptyList(), null, false, name = name)
    private val room = RoomState(roomId = "3c".repeat(32), selfParticipant = me, name = "Allotment group", relaysUp = 3, relaysTotal = 3,
        joinUrl = "https://example.org/j/#allotment", tiles = listOf(here(me, "Ada"), here("a2".repeat(32), "Priya Natarajan")))
    private val stored = ChatAttachment("https://grove.example.net/" + "ab".repeat(32), "ab".repeat(32), "cd".repeat(32), "plot-map.png", "image/png", 70_000)

    @Composable
    private fun Room(files: ComposerFiles, attachments: List<ChatAttachment> = emptyList(), filesLine: String? = null) =
        RoomScreen(room, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
            modifier = Modifier.fillMaxSize(), pinned = false, onAddToProject = {}, notify = notify, filesLine = filesLine,
            chat = { ChatPane(emptyList(), me, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false, files = files, attachments = attachments) })

    private fun SemanticsNodeInteraction.tap() { runCatching { performScrollTo() }; performClick() }

    @Test fun capture_group_files() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "group-files-shots").apply { mkdirs() }
        val font = "%.1f".format(context.resources.configuration.fontScale)
        val device = FileChoice(FileDestination.Server("https://blossom.yakihonne.com", dev.forgesworn.kithmoot.session.FileServerVia.DEFAULT))
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

            // A listed room: one file stored on the group's computers, one waiting for them.
            val waiting = WaitingFile("ef".repeat(32), "seed-list.jpg", GroupStorageReason.UNREACHABLE.words)
            show { Room(ComposerFiles(choice = device, roomLine = GROUP_FILES_LINE, waiting = listOf(waiting)), attachments = listOf(stored)) }
            ui.onNodeWithText(GROUP_FILES_LINE).assertExists()
            ui.onNodeWithText("seed-list.jpg · Waiting for the group’s storage. ×").assertExists()
            shoot("listed-waiting")

            // No grant yet: the keeper has not let this device in.
            show { Room(ComposerFiles(choice = device, roomLine = GROUP_FILES_LINE,
                waiting = listOf(waiting.copy(words = GroupStorageReason.NO_GRANT.words)))) }
            shoot("listed-no-grant")

            // Locked out: said, and not retried.
            show { Room(ComposerFiles(choice = device, roomLine = GROUP_FILES_LINE,
                waiting = listOf(waiting.copy(words = GroupStorageReason.LOCKED_OUT.words, final = true)))) }
            shoot("listed-locked-out")

            // A withdrawn room: files stay off here, whatever this device chose.
            show { Room(ComposerFiles(choice = device, roomLine = GROUP_NO_STORAGE)) }
            ui.onNodeWithContentDescription("Add a file").performClick()
            ui.onNodeWithText(GROUP_NO_STORAGE).assertExists()
            shoot("withdrawn")

            // Room info › Privacy and connection says the room overrides this device's choice.
            show { Room(ComposerFiles(choice = device, roomLine = GROUP_FILES_LINE), filesLine = GROUP_FILES_LINE) }
            ui.onNodeWithTag(ROOM_TITLE_TAG).performClick()
            ui.onNodeWithText("Privacy and connection").tap()
            ui.onNodeWithText(GROUP_FILES_LINE).assertExists()
            shoot("room-info")
        }
    }
}
