package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Test fun duration_sliders_preserve_days_hours_and_minutes_and_refuse_zero() {
        var seconds by mutableStateOf(7200)
        compose.setContent {
            KithMootTheme {
                dev.forgesworn.kithmoot.ui.start.NewRoomForm("Temporary", {}, false, {}, true, false, null, {},
                    conferenceLength = dev.forgesworn.kithmoot.session.ConferenceLength.CUSTOM,
                    onConferenceLengthChanged = {}, durationSeconds = seconds, onDurationChanged = { seconds = it })
            }
        }
        compose.onNodeWithContentDescription("Hours").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("Start a room").assertIsNotEnabled()
        compose.onNodeWithText("Choose at least one minute.").assertExists()
        compose.onNodeWithContentDescription("Days").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithContentDescription("Hours").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        compose.onNodeWithContentDescription("Minutes").performSemanticsAction(SemanticsActions.SetProgress) { it(5f) }
        compose.runOnIdle { assertEquals(86400 + 7200 + 300, seconds) }
        compose.onNodeWithText("Start a room").assertIsEnabled()
        compose.onNodeWithContentDescription("Days").performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        compose.runOnIdle { assertEquals(30 * 86400, seconds) }
        compose.onNodeWithContentDescription("Hours").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Minutes").assertIsNotEnabled()
    }

    private fun room(
        id: String, name: String, openedAt: Long = 0, project: String? = null, account: String? = null,
        anonymous: Boolean = false, secondary: Boolean = false, ended: Boolean = false, canShareInvite: Boolean = false,
        pinned: Boolean = false,
    ) = SavedRoomSummary(id, name, secondary, openedAt, project, account, anonymous, ended, canShareInvite, pinned = pinned)

    private fun setHome(
        state: StartState, widthDp: Int = 360, heightDp: Int = 640, fontScale: Float = 1f,
        callRoomId: String? = null, onReopen: (String) -> Unit = {}, onAnonymousModeChanged: (Boolean) -> Unit = {},
        onJoin: () -> Unit = {}, onForget: (String) -> Unit = {}, onOpenProjects: () -> Unit = {},
        onPin: (String, Boolean) -> Unit = { _, _ -> }, onSignIn: () -> Unit = {},
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
                            onProject = { _, _ -> }, onRetryStorage = {}, onResetStorage = {},
                            onPin = onPin, onSignIn = onSignIn,
                            callRoomId = callRoomId, onOpenProjects = onOpenProjects, accountRooms = accountRooms,
                        )
                    }
                }
            }
        }
    }

    // AC 13
    @Test fun cold_compact_shows_the_start_form_in_order_with_no_fab_or_tabs() {
        var openedProjects = false
        setHome(StartState(loadingRooms = false, savedRooms = emptyList()), onOpenProjects = { openedProjects = true })
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
        compose.onNodeWithText("Projects").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(openedProjects)
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
        // Renaming is shared and happens inside the room ("Rename for everyone").
        compose.onNodeWithText("Rename").assertDoesNotExist()
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
        compose.onNodeWithContentDescription("Search rooms").assertDoesNotExist()
    }

    @Test fun search_field_appears_at_eight_rooms_and_reports_no_matches() {
        val eight = (1..8).map { room("r$it", "Room $it") }
        setHome(StartState(loadingRooms = false, savedRooms = eight))
        // The search field opens in place from the search icon (room-list-sections.md section 4).
        compose.onNodeWithText("Find a room").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search rooms").performClick()
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

    // room-list-sections.md sections 1, 2 and 4
    private fun resetFolds() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE).edit()
            .remove("homeFoldOpen.older").remove("homeFoldOpen.ended").apply()
    }

    // The window is taller than the screen, so rows are checked to exist rather than to be on screen.
    private fun manyRooms(): List<SavedRoomSummary> {
        val now = System.currentTimeMillis() / 1000
        return (1..8).map { room("%02x".format(it) + "0".repeat(62), "Fresh $it", openedAt = now - it) } +
            room("a1" + "0".repeat(62), "Dusty one", openedAt = 1) + room("a2" + "0".repeat(62), "Dusty two", openedAt = 2) +
            room("b1" + "0".repeat(62), "Finished", openedAt = now, ended = true) +
            SavedRoomSummary("c1" + "0".repeat(62), "Starred", false, 3, pinned = true)
    }

    @Test fun past_eight_rooms_the_list_has_headings_with_older_and_ended_folded() {
        resetFolds()
        setHome(StartState(loadingRooms = false, savedRooms = manyRooms()), heightDp = 1600)
        compose.onNodeWithText("Pinned").assertExists()
        compose.onNodeWithText("Recent").assertExists()
        compose.onNodeWithText("Older · 2").assertExists()
        compose.onNodeWithText("Ended · 1").assertExists()
        compose.onNodeWithText("Dusty one").assertDoesNotExist()
        compose.onNodeWithText("Starred").assertExists()
        // Pinned sorts above the rest even though it is the oldest room.
        assertTrue(compose.onNodeWithText("Starred").getUnclippedBoundsInRoot().top < compose.onNodeWithText("Fresh 1").getUnclippedBoundsInRoot().top)
    }

    @Test fun the_older_fold_opens_and_closes() {
        resetFolds()
        setHome(StartState(loadingRooms = false, savedRooms = manyRooms()), heightDp = 1600)
        compose.onNodeWithText("Older · 2").performClick()
        compose.onNodeWithText("Older").assertExists()
        compose.onNodeWithText("Dusty one").assertExists()
        compose.onNodeWithText("Dusty two").assertExists()
        compose.onNodeWithText("Older").performClick()
        compose.onNodeWithText("Older · 2").assertExists()
        compose.onNodeWithText("Dusty one").assertDoesNotExist()
    }

    @Test fun a_search_lists_matches_from_closed_folds_without_headings() {
        resetFolds()
        setHome(StartState(loadingRooms = false, savedRooms = manyRooms()), heightDp = 1600)
        compose.onNodeWithContentDescription("Search rooms").performClick()
        compose.onNodeWithText("Find a room").performTextInput("Dusty")
        compose.onNodeWithText("Dusty one").assertExists()
        compose.onNodeWithText("Dusty two").assertExists()
        compose.onNodeWithText("Older · 2").assertDoesNotExist()
        compose.onNodeWithText("Recent").assertDoesNotExist()
    }

    @Test fun the_menu_offers_pin_or_unpin_first_and_asks_to_toggle() {
        var pinned: Pair<String, Boolean>? = null
        val id = "d1" + "0".repeat(62)
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room(id, "Garden group"))), onPin = { room, on -> pinned = room to on })
        compose.onNodeWithContentDescription("More options for Garden group").performClick()
        compose.onNodeWithText("Pin").performClick()
        assertEquals(id to true, pinned)
    }

    @Test fun overflow_holds_open_invite_link_and_sign_in() {
        var signedIn = false
        setHome(StartState(loadingRooms = false, savedRooms = listOf(room("g", "Garden group"))), onSignIn = { signedIn = true })
        compose.onNodeWithText("Open an invite link").assertDoesNotExist()
        compose.onNodeWithText("Already on Nostr? Sign in").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Open invite link").performClick()
        compose.onNodeWithText("Invite link").assertIsDisplayed()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
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
    @Test fun settings_signed_out_lists_its_pages_and_each_opens() {
        var page by mutableStateOf(dev.forgesworn.kithmoot.ui.settings.SettingsPage.ROOT)
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
                        page = page,
                        onPageChange = { page = it },
                        onBack = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Sign in with Nostr").assertIsDisplayed()
        compose.onNodeWithText("Notifications and calls").assertIsDisplayed().assertHeightIsAtLeast(56.dp)
        compose.onNodeWithText("Display").assertIsDisplayed()
        compose.onNodeWithText("Privacy").assertIsDisplayed()
        compose.onNodeWithText("Connections").assertIsDisplayed()
        compose.onNodeWithText("Display").performClick()
        compose.onNodeWithText("Text size").assertIsDisplayed()
        compose.onNodeWithText("Large").assertIsNotSelected()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Connections").performClick()
        compose.onNodeWithText("Relays").assertIsDisplayed()
        compose.onNodeWithText("KithMoot site").assertIsDisplayed()
        compose.onNodeWithText("Relays for private conversations").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
    }
}

private fun accountView(pubkey: String) = dev.forgesworn.kithmoot.ui.AccountView(
    pubkey = pubkey, npub = dev.forgesworn.kithmoot.account.npubOf(padKey(pubkey)), short = shortNpub(padKey(pubkey)), method = "local",
)

/** Test pubkeys are short labels; pad them out to a plausible 64 hex characters for bech32. */
private fun padKey(pubkey: String): String = pubkey.map { it.code.toString(16) }.joinToString("").padEnd(64, '0').take(64)
