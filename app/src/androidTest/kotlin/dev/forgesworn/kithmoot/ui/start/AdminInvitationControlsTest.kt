package dev.forgesworn.kithmoot.ui.start

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import dev.forgesworn.kithmoot.account.AdminInvitationConsent
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.mls.VmlsBoxes
import dev.forgesworn.kithmoot.mls.VmlsBoxesState
import dev.forgesworn.kithmoot.vmls.AdminInvitationSigningContext
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AdminInvitationControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun consentDisplaysCompleteScopeAndRefusesWithoutApproval() {
        val answers = mutableListOf<Pair<AdminInvitationConsent, Boolean>>()
        compose.setContent { AdminInvitationControls(boxes(answers), PERSONA) { null } }
        assertEquals(emptyList<Any>(), answers)
        assertCompleteScope()
        compose.onNodeWithText("Refuse").performClick()
        assertEquals(listOf(CONSENT to false), answers)
    }

    @Test fun consentDisplaysCompleteScopeAndApprovesOnlyAfterExplicitAction() {
        val answers = mutableListOf<Pair<AdminInvitationConsent, Boolean>>()
        compose.setContent { AdminInvitationControls(boxes(answers), PERSONA) { null } }
        assertEquals(emptyList<Any>(), answers)
        assertCompleteScope()
        compose.onNodeWithText("Sign invitation").performClick()
        assertEquals(listOf(CONSENT to true), answers)
    }

    @Test fun completeScopeIsReadableAndReachableAtPhoneViewport() {
        val answers = mutableListOf<Pair<AdminInvitationConsent, Boolean>>()
        compose.setContent { AdminInvitationControls(boxes(answers), PERSONA) { null } }
        compose.onNodeWithText(FIELD_SCOPE).assertIsDisplayed()
        compose.onNodeWithText(FINAL_SCOPE).assertIsNotDisplayed()
        repeat(12) { compose.onNodeWithText(FIELD_SCOPE).onParent().performTouchInput { swipeUp() } }
        compose.onNodeWithText(FINAL_SCOPE).assertIsDisplayed()
        compose.onNodeWithText("Sign invitation").assertIsDisplayed()
        compose.onNodeWithText("Refuse").assertIsDisplayed()
        assertEquals(emptyList<Any>(), answers)
    }

    private fun assertCompleteScope() {
        compose.onNodeWithText("Sign this private invitation?").assertIsDisplayed()
        compose.onNodeWithText(FIELD_SCOPE).assertIsDisplayed()
        compose.onNodeWithText(FINAL_SCOPE).performScrollTo()
        compose.onNodeWithText(FINAL_SCOPE).assertIsDisplayed()
    }

    private fun boxes(answers: MutableList<Pair<AdminInvitationConsent, Boolean>>): VmlsBoxes {
        return Proxy.newProxyInstance(VmlsBoxes::class.java.classLoader, arrayOf(VmlsBoxes::class.java)) { proxy, method, args ->
            when (method.name) {
                "getState" -> MutableStateFlow(VmlsBoxesState())
                "getConsent" -> MutableStateFlow<ConsentScope?>(null)
                "getAdminInvitationConsent" -> MutableStateFlow(CONSENT)
                "answerAdminInvitationConsent" -> { answers += (args[0] as AdminInvitationConsent) to (args[1] as Boolean); Unit }
                "open", "answer" -> Unit
                "toString" -> "Admin2 consent fixture"
                "hashCode" -> 1
                "equals" -> proxy === args[0]
                else -> throw UnsupportedOperationException(method.name)
            }
        } as VmlsBoxes
    }

    companion object {
        private fun hex(value: Int) = value.toString(16).padStart(2, '0').repeat(32)
        private val PERSONA = hex(1)
        private val DEVICE = hex(2)
        private val CREDENTIAL = hex(3)
        private val DEADLINE = 1_899_999_900L
        private val CONTEXT = AdminInvitationSigningContext(
            invitationId = hex(4), inviterPersona = PERSONA, inviterDevice = DEVICE, inviterRendezvous = hex(5),
            joinerPersona = hex(6), joinerDevice = hex(7), joinerRendezvous = hex(8),
            group = hex(9), groupHome = hex(10), installation = hex(11), leafHome = hex(12),
            returnNode = null, expiresAt = 1_900_000_000L, adminSetCommitment = hex(13),
        )
        private val CONSENT = AdminInvitationConsent("test-app", PERSONA, DEVICE, CREDENTIAL, DEADLINE, CONTEXT)
        private val FIELD_SCOPE = "Profile: ${CONTEXT.profile}\nPolicy: ${CONTEXT.policy}\nAdmin set: ${CONTEXT.adminSetCommitment}\nInvitation: ${CONTEXT.invitationId}\nPersona: $PERSONA\nDevice: $DEVICE\nOriginal credential: $CREDENTIAL\nGroup: ${CONTEXT.group}\nGroup home: ${CONTEXT.groupHome}\nInstallation: ${CONTEXT.installation}\nInviter rendezvous: ${CONTEXT.inviterRendezvous}\nTarget persona: ${CONTEXT.joinerPersona}\nTarget device: ${CONTEXT.joinerDevice}\nTarget rendezvous: ${CONTEXT.joinerRendezvous}\nTarget leaf home: ${CONTEXT.leafHome}\nReturn: authenticated private OOB\nInvitation expires: ${CONTEXT.expiresAt}\nSigning deadline: $DEADLINE"
        private const val FINAL_SCOPE = "This approval applies only to this invitation."
    }
}
