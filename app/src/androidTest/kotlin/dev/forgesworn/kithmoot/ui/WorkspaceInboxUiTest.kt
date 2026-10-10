package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.ui.start.WorkspaceScreen
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI fixtures test routing and retained composition. Real crypto/admission and
 * cancellation are checked separately by WorkspaceActivity/AdmissionTest. */
class WorkspaceInboxUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val human = "a".repeat(64); private val agent = "b".repeat(64); private val colleague = "c".repeat(64)
    private fun task(id: String, objective: String, status: String) = SharedAssignment(buildJsonObject {
        put("id", id); put("head", "d".repeat(64)); put("creator", human); put("owner", agent)
        put("objective", objective); put("criteria", "Verified evidence"); put("status", status)
        put("next", "Continue quietly"); if (status == "blocked") put("question", "Which build?")
    })
    private fun snapshot(): WorkspaceSnapshot {
        val mention = ChatMessage("human_mention", colleague, "e".repeat(64), "Please check the release", 200, mentions = listOf(human))
        val progress = mention.copy(id = "agent_progress", participant = agent, body = "Routine build progress")
        val people = listOf(Named(colleague, "Alex"), Named(agent, "Rowan", true))
        fun room(id: String, project: String, work: SharedAssignment, chat: List<ChatMessage> = emptyList()) =
            WorkspaceRoomActivity(id, "$project workshop", project, project,
                WorkspaceActivitySnapshot(AssignmentSnapshot(listOf(work), ready = true), chat, people))
        return WorkspaceSnapshot(human, listOf(
            room("1".repeat(64), "Project A", task("4".repeat(64), "Prepare project A brief", "blocked"), listOf(mention, progress)),
            room("2".repeat(64), "Project B", task("5".repeat(64), "Review project B result", "review")),
            room("3".repeat(64), "Project C", task("6".repeat(64), "Inspect project C", "running"))))
    }
    @Test fun threeProjectsHaveHumanAttentionProjectFiltersAndExactOriginRoutes() {
        var origin: WorkspaceOrigin? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                WorkspaceScreen(snapshot(), true, {}, {}, { origin = it }, {}, Modifier.fillMaxSize())
            } } }
            ui.onNodeWithText("3 items need attention").assertExists()
            ui.onNodeWithText("Routine build progress").assertDoesNotExist()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Open message in room"))
            ui.onNodeWithText("Open message in room").performClick()
            ui.runOnIdle { assertEquals("1".repeat(64), origin!!.room); assertEquals(MessageRef("human_mention", colleague), origin!!.message) }
            ui.onNode(hasText("All projects") and hasClickAction()).performClick()
            ui.onNode(hasText("Project B") and hasClickAction()).performClick()
            ui.onNodeWithText("1 item needs attention").assertExists()
            ui.onNodeWithText("Prepare project A brief").assertDoesNotExist()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Open task in room"))
            ui.onNodeWithText("Open task in room").performClick()
            ui.runOnIdle { assertEquals("2".repeat(64), origin!!.room); assertEquals("5".repeat(64), origin!!.assignment) }
            ui.onNode(hasText("Work") and hasClickAction()).performClick()
            ui.onNodeWithText("1 active task").assertExists()
        }
    }
    @Test fun workspaceNavigationLeavesTheRoomDraftAndMediaControlsUntouched() {
        var media = 0
        val opened = mutableStateOf(false)
        val state = RoomState(roomId = "1".repeat(64), selfParticipant = human, name = "Project A", onCall = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                RoomScreen(state, emptyMap(), null, { media++ }, { media++ }, { media++ }, {}, { media++ }, {}, {}, {},
                    onOpenWorkspace = { opened.value = true }, chat = { ChatPane(emptyList(), human, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false) })
                if (opened.value) androidx.compose.ui.window.Dialog(onDismissRequest = { opened.value = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    WorkspaceScreen(snapshot(), true, { opened.value = false }, {}, {}, {}, Modifier.fillMaxSize())
                }
            } } }
            ui.onNodeWithText("Say something").performTextInput("Keep my unsent draft")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            ui.onNode(hasText("Inbox") and hasClickAction()).performClick()
            ui.onNodeWithText("3 items need attention").assertExists()
            ui.onNode(hasText("Back") and hasClickAction()).performClick()
            ui.onNodeWithText("Keep my unsent draft").assertExists()
            ui.runOnIdle { assertEquals(0, media) }
        }
    }
    @Test fun signOutClearsWorkspaceCards() {
        val current = mutableStateOf(snapshot())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                WorkspaceScreen(current.value, true, {}, {}, {}, {}, Modifier.fillMaxSize())
            } } }
            ui.onNodeWithText("Prepare project A brief").assertExists()
            ui.runOnIdle { current.value = WorkspaceSnapshot() }
            ui.onNodeWithText("Prepare project A brief").assertDoesNotExist()
            ui.onNodeWithText("Sign in with Nostr to see your work across projects.").assertExists()
        }
    }
    @Test fun selectedOriginTaskUsesTheExactCanonicalIdAndHead() {
        val selected = task("4".repeat(64), "Answer project A question", "blocked")
        val other = task("5".repeat(64), "Unrelated project A work", "running")
        val state = RoomState(roomId = "1".repeat(64), selfParticipant = human,
            work = AssignmentSnapshot(listOf(other, selected), ready = true))
        var command: Triple<String?, JsonObject, String?>? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                WorkPane(state, { id, operation, head -> command = Triple(id, operation, head) }, {}, {}, targetAssignment = selected.id)
            } } }
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Your answer"))
            ui.onNodeWithText("Your answer").performTextInput("Use build 41")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Send answer"))
            ui.onNodeWithText("Send answer").performClick()
            ui.runOnIdle {
                assertEquals(selected.id, command!!.first); assertEquals(selected.head, command!!.third)
                assertEquals(JsonPrimitive("Use build 41"), command!!.second["text"])
            }
        }
    }
    @Test fun duplicateMessageIdsFromDifferentAuthorsKeepTheirExactOrigin() {
        val messages = listOf(
            ChatMessage("shared_id", human, "d".repeat(64), "Your own message", 200),
            ChatMessage("shared_id", colleague, "e".repeat(64), "The colleague's question", 201),
        )
        val request = mutableStateOf(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                ChatPane(messages, human, { _, _ -> }, Modifier.fillMaxSize(),
                    targetMessage = MessageRef("shared_id", colleague), targetRequest = request.value)
            } } }
            ui.onNodeWithText("The colleague's question").assertExists()
            ui.onNodeWithText("Message opened from workspace").assertExists()
            ui.runOnIdle { request.value++ }
            ui.onNodeWithText("The colleague's question").assertIsDisplayed()
        }
    }
}
