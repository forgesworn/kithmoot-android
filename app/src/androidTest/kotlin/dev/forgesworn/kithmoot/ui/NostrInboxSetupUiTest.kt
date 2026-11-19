package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.account.ContactInboxView
import dev.forgesworn.kithmoot.account.NostrPeopleView
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.start.PeopleActions
import dev.forgesworn.kithmoot.ui.start.PeopleDestination
import dev.forgesworn.kithmoot.ui.start.PeopleNostrDialog
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic Compose state; real relay receipt and hardware signers are separate checks. */
class NostrInboxSetupUiTest {
    @get:Rule val compose = createComposeRule()
    private val self = "11".repeat(32)
    private val peer = "22".repeat(32)
    private fun state() = StartState(account = AccountView(self, npubOf(self), shortNpub(self), "local"),
        dmRelays = emptyList(), nostrPeople = NostrPeopleView(enabled = true), contactInbox = ContactInboxView(ready = true))

    @Test fun setupFromTheComposerKeepsTheDraftAndPublishesOnlyOnRequest() {
        var state by mutableStateOf(state())
        var publications = 0
        var sent: Pair<String, String>? = null
        compose.setContent {
            KithMootTheme {
                PeopleNostrDialog(state, PeopleActions(
                    saveNostrRelays = { relays ->
                        publications++
                        state = state.copy(dmRelays = relays, profileMessage = "Your Nostr inbox relays were published.")
                    },
                    sendNostr = { target, text, _ -> sent = target to text },
                ), { _, _ -> }, initialPeer = peer, close = {})
            }
        }
        compose.onNodeWithText("Nostr message").performTextInput("Keep this message while I set up my inbox")
        compose.onNodeWithText("Inbox relays").performClick()
        compose.onNodeWithText("Published inbox relays").performTextInput("wss://inbox.example/")
        compose.runOnIdle { assertEquals(0, publications); assertEquals(null, sent) }
        compose.onNodeWithText("Publish relays").performClick()
        compose.onNodeWithText("Your Nostr inbox relays were published.").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Keep this message while I set up my inbox").assertExists()
        compose.onNodeWithText(npubOf(peer)).assertExists()
        compose.onNodeWithText("Send on Nostr").performClick()
        compose.runOnIdle { assertEquals(1, publications); assertEquals(peer to "Keep this message while I set up my inbox", sent) }
    }

    @Test fun anInvitationOffersInboxSetupBeforeItCallsTheSender() {
        var invites = 0
        val state = state().copy(follows = listOf(peer))
        compose.setContent {
            KithMootTheme {
                PeopleDestination(state, PeopleActions(invitePrivateChat = { _, _ -> invites++ }), initialSelected = peer)
            }
        }
        compose.onNodeWithText("Invite to KithMoot chat").performScrollTo().performClick()
        compose.onNodeWithText("Set up inbox relays").assertIsDisplayed().performClick()
        compose.onNodeWithText("Published inbox relays").assertIsDisplayed()
        compose.onNodeWithText("Publish relays").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, invites) }
    }

    @Test fun anUnknownInboxListCannotBeReplacedBeforeTheLookupFinishes() {
        compose.setContent {
            KithMootTheme {
                PeopleNostrDialog(state().copy(dmRelays = null, profileBusy = true), PeopleActions(), { _, _ -> },
                    initialPeer = peer, initialConfigure = true, close = {})
            }
        }
        compose.onNodeWithText("Published inbox relays").assertIsNotEnabled()
        compose.onNodeWithText("Publish relays").assertIsNotEnabled()
    }
}
