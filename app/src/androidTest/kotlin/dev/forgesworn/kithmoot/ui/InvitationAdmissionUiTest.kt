package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.AdmissionDecisionPhase
import dev.forgesworn.kithmoot.session.PendingInvitationAdmission
import dev.forgesworn.kithmoot.ui.room.InvitationAdmissionPanel
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class InvitationAdmissionUiTest {
    @get:Rule val compose = createComposeRule()
    private fun row(id: String = "a", name: String = "Rowan") = PendingInvitationAdmission(
        requestId = id.repeat(64), device = id.repeat(64), name = name,
        claimedParticipant = null, verifiedParticipant = null, expiresAt = 1_800_000_090,
    )

    @Test fun aGuestNameAndAnUnverifiedAccountNeverLookLikeVerifiedIdentity() {
        val request = row().copy(claimedParticipant = "b".repeat(64))
        compose.setContent { KithMootTheme { InvitationAdmissionPanel(request) { _, _ -> } } }
        compose.onNodeWithText("Rowan wants to join").assertIsDisplayed()
        compose.onNodeWithText("Unverified account claim", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Signed account proof", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Let in").assertIsEnabled()
    }

    @Test fun sameNameRequestsKeepIndividualAdmitAndDismissActions() {
        val answers = mutableListOf<Pair<String, Boolean>>()
        val first = row("a"); val second = row("b")
        compose.setContent {
            KithMootTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InvitationAdmissionPanel(first) { id, admit -> answers += id to admit }
                    InvitationAdmissionPanel(second) { id, admit -> answers += id to admit }
                }
            }
        }
        compose.onAllNodesWithText("Let in")[0].performClick()
        compose.onAllNodesWithText("Dismiss")[1].performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(first.requestId to true, second.requestId to false), answers) }
    }

    @Test fun theGrantWaitDisablesActionsAndFailureOffersAnExplicitRetry() {
        var request by mutableStateOf(row().copy(phase = AdmissionDecisionPhase.SENDING))
        var answer: Pair<String, Boolean>? = null
        compose.setContent { KithMootTheme { InvitationAdmissionPanel(request) { id, yes -> answer = id to yes } } }
        compose.onNodeWithText("Let in").assertIsNotEnabled()
        compose.onNodeWithText("Dismiss").assertIsNotEnabled()
        compose.onNodeWithText("Waiting for relay confirmation", substring = true).assertIsDisplayed()
        compose.runOnIdle { request = request.copy(phase = AdmissionDecisionPhase.RETRY, error = "No relay confirmed the grant.") }
        compose.onNodeWithText("No relay confirmed the grant.").assertIsDisplayed()
        compose.onNodeWithText("Retry grant").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(request.requestId to true, answer) }
    }

    @Test fun bothActionsRemainReachableOnASmallPhoneAtTwiceTheFontSize() {
        val answers = mutableListOf<Boolean>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                KithMootTheme {
                    Box(Modifier.requiredSize(320.dp, 440.dp)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            InvitationAdmissionPanel(row().copy(phase = AdmissionDecisionPhase.RETRY,
                                error = "No relay confirmed the grant. The guest may still have received it.")) { _, yes -> answers += yes }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Dismiss").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Retry grant").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(false, true), answers) }
    }

    @Test fun theRealRoomShowsTheQueueAndPreservesAnUnsentDraftWhileDecisionsChangeIt() {
        val first = row("a"); val second = row("b")
        var state by mutableStateOf(RoomState(roomId = "01".repeat(32), selfParticipant = "02".repeat(32),
            name = "Workshop", joinUrl = "https://example.test/j/#room", invitationAdmissions = listOf(first, second)))
        val answers = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            KithMootTheme {
                val context = LocalContext.current
                SideEffect {
                    // Match MainActivity's adjustResize declaration. The generic
                    // Compose test activity otherwise pans the whole room for IME.
                    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
                        .filterIsInstance<android.app.Activity>().first()
                    activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
                Box(Modifier.fillMaxSize().widthIn(max = 360.dp)) {
                    RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {},
                        chat = { ChatPane(emptyList(), state.selfParticipant, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false) },
                        onAnswerInvitationAdmission = { id, yes ->
                            answers += id to yes
                            state = state.copy(invitationAdmissions = state.invitationAdmissions.filterNot { it.requestId == id })
                        })
                }
            }
        }
        compose.onNodeWithText("Waiting to join (2)").assertIsDisplayed()
        compose.onNodeWithText("Say something").assertIsDisplayed().performTextInput("Keep my unfinished note")
        compose.onAllNodesWithText("Let in")[0].performScrollTo().performClick()
        compose.onNodeWithText("Waiting to join (1)").assertIsDisplayed()
        compose.onNodeWithText("Keep my unfinished note").assertExists()
        compose.onAllNodesWithText("Dismiss")[0].performScrollTo().performClick()
        compose.onNodeWithText("Waiting to join (1)").assertDoesNotExist()
        compose.onNodeWithText("Keep my unfinished note").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf(first.requestId to true, second.requestId to false), answers) }
    }
}
