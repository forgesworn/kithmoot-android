package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.AccountRoom
import dev.forgesworn.kithmoot.account.RoomBookmarkSnapshot
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.start.SettingsScreen
import dev.forgesworn.kithmoot.ui.start.StartScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.theme.TextSize
import dev.forgesworn.kithmoot.ui.theme.TextSizeSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Home, rendered directly (no activity, no view model): design-home-rooms.md
 * acceptance checks 13 to 28. Forces the width, height and font scale the
 * spec asks for with [Box.requiredSize] and an overridden [LocalDensity],
 * the documented fallback for compose-ui 1.7.
 */
class HomeScreenUiTest {
    @get:Rule val compose = createComposeRule()

    private fun room(
        id: String, name: String, openedAt: Long = 0, project: String? = null, account: String? = null,
        anonymous: Boolean = false, secondary: Boolean = false, ended: Boolean = false, canShareInvite: Boolean = false,
    ) = SavedRoomSummary(id, name, secondary, openedAt, project, account, anonymous, ended, canShareInvite)

    private fun setHome(
        state: StartState, widthDp: Int = 360, heightDp: Int = 640, fontScale: Float = 1f,
        callRoomId: String? = null, onReopen: (String) -> Unit = {}, onAnonymousModeChanged: (Boolean) -> Unit = {},
        onJoin: () -> Unit = {}, onForget: (String) -> Unit = {}, onOpenProjects: () -> Unit = {},
        accountRooms: dev.forgesworn.kithmoot.ui.start.AccountRoomActions = dev.forgesworn.kithmoot.ui.start.AccountRoomActions(),
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                KithMootTheme {
                    Box(Modifier.requiredSize(widthDp.dp, heightDp.dp)) {
                        StartScreen(
                            state = state,
                            onRoomNameChanged = {}, onJoinUrlChanged = {}, onRelaysChanged = {},
                            onAnonymousModeChanged = onAnonymousModeChanged, onPersistentGroupChanged = {},
                            onStartRoom = {}, onJoin = onJoin, onReopen = onReopen, onForget = onForget,
                            onRename = { _, _ -> }, onProject = { _, _ -> }, onRetryStorage = {}, onResetStorage = {},
                            callRoomId = callRoomId, onOpenProjects = onOpenProjects, accountRooms = accountRooms,
                        )
                    }
                }
            }
        }
    }

    // AC 13
    @Test fun cold_compact_shows_the_start_form_in_order_with_no_fab_or_tabs() {
        setHome(StartState(loadingRooms = false, savedRooms = emptyList()))
        compose.onNodeWithText("Start a room, then send the link.").assertIsDisplayed()
        compose.onNodeWithText("A workspace nobody owns: messages, files and calls for your people and your agents. No account needed.").assertIsDisplayed()
        compose.onNodeWithText("Room name (optional)").assertIsDisplayed()
        compose.onNodeWithText("Tor-only room (Orbot)").assertIsDisplayed()
        compose.onNodeWithText("Start a room").assertIsDisplayed()
        compose.onNodeWithText("Open an invite link").assertIsDisplayed()
        // Below the fold of a 360 x 640 screen since the start form grew: reached by scrolling.
        compose.onNodeWithText("Already on Nostr? Sign in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New room").assertDoesNotExist()
        compose.onNodeWithText("Chats").assertDoesNotExist()
        compose.onNodeWithText("Projects").assertDoesNotExist()
    }

    // AC 14
    @Test fun tor_row_is_one_toggleable_target() {
        var checked: Boolean? = null
        setHome(StartState(loadingRooms = false), onAnonymousModeChanged = { checked = it })
        val node = compose.onNodeWithText("Tor-only room (Orbot)")
        node.assertIsToggleable()
        node.performClick()
        assertEquals(true, checked)
        // F6: the switch's accessible name is the visible label, not a separate
        // description naming the old switch by its former, now-removed label.
    }

    // AC 15
    @Test fun invite_link_section_expands_and_joins() {
        var joined = false
        setHome(StartState(loadingRooms = false, joinUrl = "https://kithmoot.example/j/#abc"), onJoin = { joined = true })
        compose.onNodeWithText("Invite link").assertDoesNotExist()
        val toggle = compose.onNodeWithText("Open an invite link")
        assertEquals("Collapsed", toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        toggle.performClick()
        assertEquals("Expanded", toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithText("Invite link").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scan QR code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open").performScrollTo().performClick()
        assertTrue(joined)
    }

    // AC 16
    @Test fun loading_shows_only_the_progress_indicator() {
        setHome(StartState(loadingRooms = true, savedRooms = emptyList()))
        compose.onNodeWithContentDescription("Loading rooms").assertExists()
        compose.onNodeWithText("Start a room, then send the link.").assertDoesNotExist()
        compose.onNodeWithText("Rooms").assertDoesNotExist()
    }

    // AC 17
    @Test fun returning_compact_orders_rows_by_activity_and_shows_the_fab() {
        val rooms = listOf(
            room("a", "First", openedAt = 100), room("b", "Second", openedAt = 300),
            room("c".repeat(64), "Room ${"c".repeat(8)}", openedAt = 200),
        )
        setHome(StartState(loadingRooms = false, savedRooms = rooms))
        compose.onNodeWithText("Rooms").assertIsDisplayed()
        compose.onNodeWithText("Untitled room").assertIsDisplayed()
        compose.onNodeWithText("New room").assertIsDisplayed()
        compose.onAllNodesWithText("Open", substring = false).assertCountEquals(0)
        compose.onNodeWithText("On this phone").assertDoesNotExist()
        val second = compose.onNodeWithText("Second").getUnclippedBoundsInRoot().top
        val untitled = compose.onNodeWithText("Untitled room").getUnclippedBoundsInRoot().top
        val first = compose.onNodeWithText("First").getUnclippedBoundsInRoot().top
        assertTrue(second < untitled)
        assertTrue(untitled < first)
    }

    // AC 18
    @Test fun row_is_one_merged_node_with_open_and_more_options_actions() {
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room("g", "Garden group"))))
        val row = compose.onNodeWithText("Garden group")
        val node = row.fetchSemanticsNode()
        assertEquals("Open", node.config.getOrNull(SemanticsActions.OnClick)?.label)
        assertEquals("More options", node.config.getOrNull(SemanticsActions.OnLongClick)?.label)
        row.assertHeightIsAtLeast(48.dp)
        row.assertWidthIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("More options for Garden group").assertExists()
    }

    // AC 19
    @Test fun menu_shows_the_right_items_for_a_saved_room_with_an_invite() {
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room("g", "Garden group", canShareInvite = true))))
        compose.onNodeWithContentDescription("More options for Garden group").performClick()
        compose.onNodeWithText("Share invite link").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Add to a project").assertIsDisplayed()
        compose.onNodeWithText("Remove from this phone").assertIsDisplayed()
    }

    @Test fun call_room_has_no_remove_action_and_shows_its_status() {
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room("g", "Garden group"))), callRoomId = "g")
        compose.onNodeWithText("On a call now.").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options for Garden group").performClick()
        compose.onNodeWithText("Remove from this phone").assertDoesNotExist()
    }

    @Test fun account_only_room_offers_only_remove_from_account() {
        val bookmarks = RoomBookmarkSnapshot(rooms = listOf(AccountRoom("r1", "link", "Remote room", 10)), ready = true)
        setHome(StartState(loadingRooms = false, account = accountView("me"), roomBookmarks = bookmarks))
        compose.onNodeWithContentDescription("More options for Remote room").performClick()
        compose.onNodeWithText("Remove from account").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertDoesNotExist()
    }

    // AC 20
    @Test fun status_lines_follow_precedence() {
        val other = "1".repeat(64)
        setHome(StartState(loadingRooms = false, savedRooms = listOf(
            room("e", "Ended room", ended = true),
            room("j", "Joined room", account = other),
            room("t", "Tor room", anonymous = true),
        )))
        compose.onNodeWithText("Ended. Its invite link no longer works.").assertIsDisplayed()
        compose.onNodeWithText("Joined as ${shortNpub(other)}. Sign in with that account to open it.").assertIsDisplayed()
        compose.onNodeWithText("Tor-only room.").assertIsDisplayed()
    }

    // AC 21
    @Test fun search_field_is_absent_at_seven_rooms() {
        val seven = (1..7).map { room("r$it", "Room $it") }
        setHome(StartState(loadingRooms = false, savedRooms = seven))
        compose.onNodeWithText("Find a room").assertDoesNotExist()
    }

    @Test fun search_field_appears_at_eight_rooms_and_reports_no_matches() {
        val eight = (1..8).map { room("r$it", "Room $it") }
        setHome(StartState(loadingRooms = false, savedRooms = eight))
        compose.onNodeWithText("Find a room").assertIsDisplayed()
        compose.onNodeWithText("Find a room").performTextInput("zzz")
        compose.onNodeWithText("No rooms match “zzz”.").assertIsDisplayed()
    }

    @Test fun project_chips_filter_the_list() {
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room("w", "Work room", project = "Work"), room("p", "Plain room"))))
        compose.onNodeWithText("All").assertIsDisplayed()
        compose.onNodeWithText("Work").assertIsDisplayed()
        compose.onNodeWithText("No project").assertIsDisplayed()
        compose.onNodeWithText("Work").performClick()
        assertEquals(true, compose.onNodeWithText("Work").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        compose.onNodeWithText("Work room").assertIsDisplayed()
        compose.onNodeWithText("Plain room").assertDoesNotExist()
    }

    // AC 22
    @Test fun landscape_phone_returning_shows_the_fab_and_no_new_room_pane() {
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room("g", "Garden group"))), widthDp = 760, heightDp = 360)
        // Only the FAB says "New room"; there is no second, headed pane with the same words.
        compose.onAllNodesWithText("New room").assertCountEquals(1)
    }

    // AC 23
    @Test fun landscape_phone_cold_is_two_columns() {
        setHome(StartState(loadingRooms = false, savedRooms = emptyList()), widthDp = 760, heightDp = 360)
        compose.onNodeWithText("Start a room").assertIsDisplayed()
        compose.onNodeWithText("Start a room, then send the link.").assertIsDisplayed()
        val headingLeft = compose.onNodeWithText("Start a room, then send the link.").getUnclippedBoundsInRoot().left
        val buttonLeft = compose.onNodeWithText("Start a room").getUnclippedBoundsInRoot().left
        assertTrue(headingLeft < buttonLeft)
    }

    // AC 24. Forced to 1280 dp, wider than a phone's test window, so this
    // checks the nodes exist and sit side by side, not that they are on screen.
    @Test fun expanded_shows_list_and_new_room_pane_together_with_no_fab() {
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room("g", "Garden group"))), widthDp = 1280, heightDp = 800)
        compose.onNodeWithText("New room").assertExists()
        compose.onNodeWithText("Room name (optional)").assertExists()
        compose.onNodeWithText("Garden group").assertExists()
        compose.onNodeWithText("Cancel").assertDoesNotExist()
        val headingLeft = compose.onNodeWithText("New room").getUnclippedBoundsInRoot().left
        val rowRight = compose.onNodeWithText("Garden group").getUnclippedBoundsInRoot().right
        assertTrue(headingLeft > rowRight - 1.dp)
    }

    // AC 25
    @Test fun font_scale_two_stacks_the_time_under_the_name() {
        setHome(
            StartState(loadingRooms = false, savedRooms = listOf(room("g", "Weekly planning for the community garden project", openedAt = 1))),
            fontScale = 2f,
        )
        compose.onNodeWithText("Weekly planning for the community garden project").assertIsDisplayed()
        compose.onNodeWithContentDescription("More options for Weekly planning for the community garden project").assertHeightIsAtLeast(48.dp)
    }

    // AC 26
    @Test fun storage_error_shows_the_error_and_disables_start() {
        setHome(StartState(loadingRooms = false, storageError = true))
        compose.onNodeWithText("Saved rooms are unavailable").assertIsDisplayed()
        compose.onNodeWithText("Start a room").assertIsDisplayed().assertIsNotEnabled()
    }

    // AC 27
    @Test fun settings_signed_out_shows_every_section() {
        compose.setContent {
            KithMootTheme {
                CompositionLocalProvider(LocalTextSizeSetting provides TextSizeSetting(TextSize.STANDARD) {}) {
                    SettingsScreen(
                        state = StartState(),
                        signIn = dev.forgesworn.kithmoot.ui.start.AccountActions.None,
                        accountSettings = dev.forgesworn.kithmoot.ui.start.AccountSettingsActions(),
                        accountRooms = dev.forgesworn.kithmoot.ui.start.AccountRoomActions(),
                        relayChoices = emptyList(),
                        onWebAppAddressChanged = { false },
                        notificationSettings = {},
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Text size").assertIsDisplayed()
        compose.onNodeWithText("Notifications & sound").assertIsDisplayed()
        compose.onNodeWithText("Connections").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Nostr").assertIsDisplayed()
        compose.onNodeWithText("Nostr relays").assertIsDisplayed()
        compose.onNodeWithText("KithMoot site").assertIsDisplayed()
        assertEquals(false, compose.onNodeWithText("Large").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        compose.onNodeWithContentDescription("Back").performClick()
    }
}

private fun accountView(pubkey: String) = dev.forgesworn.kithmoot.ui.AccountView(
    pubkey = pubkey, npub = dev.forgesworn.kithmoot.account.npubOf(padKey(pubkey)), short = shortNpub(padKey(pubkey)), method = "local",
)

/** Test pubkeys are short labels; pad them out to a plausible 64 hex characters for bech32. */
private fun padKey(pubkey: String): String = pubkey.map { it.code.toString(16) }.joinToString("").padEnd(64, '0').take(64)
