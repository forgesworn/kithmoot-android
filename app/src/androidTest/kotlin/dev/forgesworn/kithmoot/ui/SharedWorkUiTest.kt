package dev.forgesworn.kithmoot.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SharedWorkUiTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val human="a".repeat(64);private val agent="b".repeat(64)
    private fun assignment(status:String="blocked",head:String="c".repeat(64))=SharedAssignment(buildJsonObject{
        put("id","1".repeat(64));put("head",head);put("creator",human);put("owner",agent)
        put("objective","Prepare the project brief");put("criteria","Explain the evidence for the chosen build");put("status",status)
        put("next",if(status=="blocked")"Waiting for an answer" else "Review the result");put("progress","")
        if(status=="blocked")put("question","Which build should I review?")
        if(status=="review")put("result",buildJsonObject{put("id",head);put("summary","Build 41 checked");put("evidence","Build 41: all required checks passed. Report retained with the task.")})
    })
    @Test fun decisions_preserve_drafts_and_accept_the_exact_displayed_result() {
        val state=mutableStateOf(RoomState(roomId="2".repeat(64),selfParticipant=human,name="Project workshop",work=AssignmentSnapshot(listOf(assignment()),ready=true)))
        val commands=mutableListOf<Triple<String?,JsonObject,String?>>()
        var media=0
        ActivityScenario.launch(MainActivity::class.java).use{scenario->
            scenario.onActivity{activity->activity.setContent{KithMootTheme{RoomScreen(state.value,emptyMap(),null,{media++},{media++},{media++},{},{media++},{},{},{},
                work={WorkPane(state.value,{id,op,head->commands.add(Triple(id,op,head));state.value=state.value.copy(work=AssignmentSnapshot(listOf(assignment(if(op["op"]==JsonPrimitive("accept"))"accepted" else "running","d".repeat(64))),ready=true))},{},{})},
                chat={ChatPane(emptyList(),human,{},Modifier.fillMaxSize(),showTitle=false)})}}}
            ui.onNode(hasText("Work · 1") and hasClickAction()).performClick()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Your answer"))
            ui.onNodeWithText("Your answer").performTextInput("Build 41")
            Espresso.closeSoftKeyboard()
            ui.onNode(hasText("Chat") and hasClickAction()).performClick()
            ui.onNodeWithText("Say something").performTextInput("Keep this conversation draft")
            Espresso.closeSoftKeyboard()
            ui.onNode(hasText("Work · 1") and hasClickAction()).performClick()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Build 41"))
            ui.onNodeWithText("Build 41").assertExists()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Send answer"))
            ui.onNodeWithText("Send answer").performClick()
            ui.runOnIdle{assertEquals("c".repeat(64),commands.single().third);assertEquals(JsonPrimitive("Build 41"),commands.single().second["text"]);assertEquals(0,media)}
            ui.onNode(hasText("Chat") and hasClickAction()).performClick()
            ui.onNodeWithText("Keep this conversation draft").assertExists()
            ui.runOnIdle{state.value=state.value.copy(work=AssignmentSnapshot(listOf(assignment("review","e".repeat(64))),ready=true))}
            ui.onNode(hasText("Work · 1") and hasClickAction()).performClick()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Accept result"))
            ui.onNodeWithText("Build 41: all required checks passed. Report retained with the task.").assertExists()
            val instrumentation=InstrumentationRegistry.getInstrumentation();val image=instrumentation.uiAutomation.takeScreenshot()
            val file=File(instrumentation.targetContext.getExternalFilesDir("ui-proof"),"shared-work-review.png")
            file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            ui.onNodeWithText("Accept result").performClick()
            ui.runOnIdle{assertEquals("e".repeat(64),commands.last().third);assertEquals(JsonPrimitive("e".repeat(64)),commands.last().second["result"]);assertEquals(0,media)}
        }
    }
    @Test fun creates_only_the_selected_action_and_exposes_exact_retry() {
        val action=AvailableAssignmentAction(agent,"Rowan",buildJsonObject{put("id","project-brief");put("label","Prepare a brief");put("description","Review the supplied objective");put("inputs",JsonArray(emptyList()))})
        val state=mutableStateOf(RoomState(roomId="3".repeat(64),selfParticipant=human,work=AssignmentSnapshot(ready=true),workActions=listOf(action),tiles=listOf(ParticipantTile(agent,false,1,emptyList(),null,false))))
        var shared:JsonObject?=null;var retries=0
        ActivityScenario.launch(MainActivity::class.java).use{scenario->scenario.onActivity{activity->activity.setContent{KithMootTheme{WorkPane(state.value,{_,op,_->shared=op},{retries++},{})}}}
            ui.onNodeWithText("New task").performClick()
            ui.onNodeWithText("Choose an agent action").performClick()
            ui.onNodeWithText("Rowan · Prepare a brief").performClick()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("What needs doing?"))
            ui.onNodeWithText("What needs doing?").performTextInput("Prepare a release brief")
            ui.onNodeWithText("What makes it done?").performTextInput("Explain the verified checks")
            Espresso.closeSoftKeyboard()
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Share task"))
            ui.onNodeWithText("Share task").performClick()
            ui.runOnIdle{assertEquals(JsonPrimitive(agent),shared!!["owner"]);assertEquals(JsonPrimitive("project-brief"),shared!!["action"]);state.value=state.value.copy(work=AssignmentSnapshot(ready=true,pendingSends=1))}
            ui.onNode(hasScrollAction()).performScrollToNode(hasText("Retry saved update"))
            ui.onNodeWithText("Retry saved update").performClick()
            ui.runOnIdle{assertEquals(1,retries)}
        }
    }
}
