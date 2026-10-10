package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.AdmissionDecisionPhase
import dev.forgesworn.kithmoot.session.PendingInvitationAdmission
import dev.forgesworn.kithmoot.ui.room.InvitationAdmissionPanel
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
}
