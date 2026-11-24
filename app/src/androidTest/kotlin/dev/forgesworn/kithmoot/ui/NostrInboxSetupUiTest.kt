package dev.forgesworn.kithmoot.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forgesworn.kithmoot.ui.settings.DmRelaysPage
import dev.forgesworn.kithmoot.ui.start.AccountSettingsActions
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

    @Test fun publicProfilesCanBeTurnedOffAndBackOnInsidePeople() {
        var state by mutableStateOf(state())
        var changes = 0
        compose.setContent {
            KithMootTheme {
                PeopleDestination(state, PeopleActions(setPublicProfiles = {
                    changes++
                    state = state.copy(publicProfiles = it)
                }))
            }
        }
        compose.onNodeWithContentDescription("Public profiles").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Public profiles").assertIsOff()
        compose.onNodeWithText("Public profiles are off. Turn them on above to see who you follow.").assertExists()
        compose.onNodeWithContentDescription("Public profiles").performClick().assertIsOn()
        compose.onNodeWithText("Public profiles are off. Turn them on above to see who you follow.").assertDoesNotExist()
        assertEquals(2, changes)
        compose.runOnIdle {
            state = state.copy(publicProfiles = false,
                accountNetworkMode = dev.forgesworn.kithmoot.account.AccountNetworkMode.PRIVATE_INVITATIONS)
        }
        compose.onNodeWithContentDescription("Public profiles").assertDoesNotExist()
        compose.onNodeWithText("Public profiles are paused while Nostr networking is off.").assertExists()
    }

    @Test fun setupFromTheComposerKeepsTheDraftAndPublishesOnlyOnRequest() {
        var state by mutableStateOf(state())
        var publications = 0
        var sent: Pair<String, String>? = null
        compose.setContent {
            KithMootTheme {
                PeopleNostrDialog(state, PeopleActions(
                    saveNostrRelays = { relays ->
                        assertEquals(listOf("wss://inbox.example/"), relays)
                        publications++
                        state = state.copy(dmRelays = relays, profileMessage = "Your Nostr inbox relays were published.")
                    },
                    sendNostr = { target, text, _ -> sent = target to text },
                ), { _, _ -> }, initialPeer = peer, close = {})
            }
        }
        compose.onNodeWithText("Nostr message").performTextInput("Keep this message while I set up my inbox")
        compose.onNodeWithText("Inbox relays").performScrollTo().assertIsDisplayed().performClick()
        val relays = compose.onNodeWithText("Published inbox relays").performScrollTo()
        relays.assertTextContains("wss://relay.primal.net/\nwss://nos.lol/\nwss://relay.ditto.pub/")
        relays.performTextReplacement("wss://inbox.example/")
        compose.runOnIdle { assertEquals(0, publications); assertEquals(null, sent) }
        compose.onNodeWithText("Publish relays").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Your Nostr inbox relays were published.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Keep this message while I set up my inbox").assertExists()
        compose.onNodeWithText(npubOf(peer)).assertExists()
        compose.onNodeWithText("Send on Nostr").performScrollTo().assertIsDisplayed().performClick()
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
        compose.onNodeWithText("Publish relays").assertIsEnabled()
        compose.onNodeWithText("Published inbox relays").assertTextContains("wss://relay.primal.net/\nwss://nos.lol/\nwss://relay.ditto.pub/")
        compose.runOnIdle { assertEquals(0, invites) }
    }

    @Test fun settingsCanPublishDefaultsAndPreservesAnExistingCustomInbox() {
        var state by mutableStateOf(state())
        var published: List<String>? = null
        compose.setContent {
            KithMootTheme { androidx.compose.foundation.layout.Column { DmRelaysPage(state, AccountSettingsActions(publishDmRelays = { published = it })) } }
        }
        compose.onNodeWithText("Relays, one per line").assertTextContains("wss://relay.primal.net/\nwss://nos.lol/\nwss://relay.ditto.pub/")
        compose.runOnIdle { assertEquals(null, published) }
        compose.onNodeWithText("Save to your Nostr account").performClick()
        compose.runOnIdle {
            assertEquals(listOf("wss://relay.primal.net/", "wss://nos.lol/", "wss://relay.ditto.pub/"), published)
            state = state.copy(dmRelays = listOf("wss://my-inbox.example/"))
        }
        compose.onNodeWithText("Relays, one per line").assertTextContains("wss://my-inbox.example/")
        compose.onNodeWithText("Relays, one per line").assertTextEquals("Relays, one per line", "wss://my-inbox.example/")
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
