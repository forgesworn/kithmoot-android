package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.ContactInboxView
import dev.forgesworn.kithmoot.account.ContactInvitation
import dev.forgesworn.kithmoot.account.SignetContact
import dev.forgesworn.kithmoot.account.SignetDirectoryView
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.ChatReaction
import dev.forgesworn.kithmoot.session.AssignmentSnapshot
import dev.forgesworn.kithmoot.session.SharedAssignment
import dev.forgesworn.kithmoot.ui.room.ParticipantTile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.start.Destination
import dev.forgesworn.kithmoot.ui.start.PeopleActions
import dev.forgesworn.kithmoot.ui.start.PeopleDestination
import dev.forgesworn.kithmoot.ui.start.ShellAccountButton
import dev.forgesworn.kithmoot.ui.start.ShellNavigationBar
import dev.forgesworn.kithmoot.ui.start.ShellNavigationRail
import dev.forgesworn.kithmoot.ui.start.StartScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Renders the calm shell's screens (design-calm-shell stage 2 and People)
 * over fixed, seeded data and saves real screenshots, status bar included,
 * under the app's external files directory (`files/shell/`) for review. The
 * theme follows the device's night mode and the font scale is the device's,
 * both in each file name, so one run per mode and scale covers the matrix.
 * Tablet frames are taken only when the window is 840 dp wide or more (a
 * `wm size` override). No network: every action is a no-op.
 */
class ShellCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val me = "03".repeat(32)
    private val priya = "a2".repeat(32)
    private val rowan = "b4".repeat(32)
    private val sam = "c6".repeat(32)
    private val morwenna = "d8".repeat(32)
    private val tally = "05".repeat(32)
    private val now = System.currentTimeMillis() / 1000

    private val release = "1a".repeat(32)
    private val priyaChat = "2b".repeat(32)
    private val townHall = "3c".repeat(32)
    private val bothy = "4d".repeat(32)
    private val garden = "5e".repeat(32)
    private val books = "6f".repeat(32)

    private val account = AccountView(me, npubOf(me), "npub1ada…", "local", name = "Ada")

    private val chats = StartState(
        loadingRooms = false, account = account, publicProfiles = true,
        savedRooms = listOf(
            SavedRoomSummary(release, "Release workshop", false, now, account = me, pinned = true, canShareInvite = true),
            SavedRoomSummary(priyaChat, "Private with Priya Natarajan", false, now, account = me, privatePeer = priya),
            SavedRoomSummary(townHall, "Town hall", false, now, account = me),
            SavedRoomSummary(bothy, "Bothy and Wildbloom", false, now, project = "Wildbloom", account = me),
            SavedRoomSummary(garden, "Garden group", false, now, account = me),
            SavedRoomSummary(books, "Book club", false, now, account = me),
        ),
        latestMessageTimes = mapOf(release to now - 60, priyaChat to now - 7 * 60, townHall to now - 26 * 60,
            bothy to now - 26 * 3600, garden to now - 3 * 86400, books to now - 5 * 86400),
        unreadCounts = mapOf(priyaChat to 2, bothy to 1),
        mentionedRooms = setOf(bothy),
    )

    private val people = chats.copy(
        // As a Signet grant that shares tiers and checks shows them.
        signetContacts = SignetDirectoryView("ready", listOf(SignetContact(priya, "Priya Natarajan", "Kith", true),
            SignetContact(rowan, "Rowan", "Ken", true), SignetContact(sam, "Sam Okafor", "Ken"))),
        contactCards = listOf(ContactRow(morwenna, npubOf(morwenna), "Morwenna", emptyList(), now + 86400)),
        contactInbox = ContactInboxView(ready = true),
        follows = listOf(rowan, "e1".repeat(32), "e2".repeat(32), "e3".repeat(32), "e4".repeat(32)),
        followProfiles = mapOf(
            "e1".repeat(32) to PublicProfile("Elena Marsh", null, 1, "a", nip05 = "elena@example.org"),
            "e2".repeat(32) to PublicProfile("Tomasz Wilk", null, 1, "b"),
            "e4".repeat(32) to PublicProfile("Wren Abara", null, 1, "c"),
        ),
    )

    // Two waiting: Priya, a Signet contact, and somebody this account does not know.
    private val stranger = "e9".repeat(32)
    private val invited = people.copy(contactInbox = ContactInboxView(ready = true, invitations = listOf(
        ContactInvitation("i1", priya, me, "7c".repeat(32), "https://example.org/j/#r", now - 120),
        ContactInvitation("i2", stranger, me, "8d".repeat(32), "https://example.org/j/#s", now - 1500),
    )))

    // Signed in with nothing linked yet: People's empty state offers Link Signet contacts.
    private val unlinked = chats.copy(contactInbox = ContactInboxView(ready = true))

    private fun say(id: String, who: String, name: String?, text: String, ago: Long) =
        ChatMessage(id, who, who.take(2).repeat(32), text, now - ago, name = name)

    private val conversation = listOf(
        say("m1", priya, "Priya Natarajan", "Small thing: the invite link on Android opens the door twice. Screenshot in a minute.", 300),
        say("m2", rowan, "Rowan", "Seen that before. It goes away if the app is already open.", 240),
        say("m3", tally, "Tally", "Summary so far: build 54 is good, two flaky specs on the board, one Android cold-start bug to write up.", 180),
        say("m4", rowan, "Rowan", "Perfect. Call at 3?", 120),
        ChatMessage("r1", priya, priya.take(2).repeat(32), "", now - 110, reaction = ChatReaction("m4", rowan, "👍", true, 1)),
        say("m5", me, "You", "Yes, 3 works. I will start it from here.", 60),
    )

    private fun here(id: String, name: String) = ParticipantTile(id, id == me, 1, emptyList(), null, false, name = name)

    // A task Tally is waiting on Ada to answer, so the Board tab shows with "1 needs you".
    private val waiting = SharedAssignment(buildJsonObject {
        put("id", "7a".repeat(32)); put("head", "7b".repeat(32)); put("creator", me); put("owner", tally)
        put("objective", "Write up the Android cold-start bug"); put("criteria", "Steps for the Pixel and the A32")
        put("status", "blocked"); put("next", "Waiting for an answer"); put("progress", ""); put("question", "Which build shows it?")
    })

    private val room = RoomState(roomId = release, selfParticipant = me, name = "Release workshop", relaysUp = 3, relaysTotal = 3,
        joinUrl = "https://example.org/j/#release",
        tiles = listOf(here(me, "Ada"), here(priya, "Priya Natarajan"), here(rowan, "Rowan"), here(tally, "Tally")),
        agentParticipants = setOf(tally), work = AssignmentSnapshot(listOf(waiting), ready = true))

    @Composable
    private fun Account() = ShellAccountButton(account, showPicture = true, busy = false, onSettings = {}, onSignIn = {}, onSignOut = {})

    @Composable
    private fun Chats(selected: String? = null, accountButton: Boolean = true, modifier: Modifier = Modifier, state: StartState = chats) {
        StartScreen(
            state = state, onRoomNameChanged = {}, onJoinUrlChanged = {}, onRelaysChanged = {}, onAnonymousModeChanged = {},
            onPersistentGroupChanged = {}, onStartRoom = {}, onJoin = {}, onReopen = {}, onForget = {}, onProject = { _, _ -> },
            onRetryStorage = {}, onResetStorage = {}, modifier = modifier, selectedRoomId = selected,
            accountButton = if (accountButton) ({ Account() }) else null, onMessageContact = {},
        )
    }

    @Composable
    private fun Room(showBack: Boolean = true, modifier: Modifier = Modifier, state: RoomState = room, chat: List<ChatMessage> = conversation) {
        Box(modifier) {
            RoomScreen(state, emptyMap(), null, {}, {}, {}, {}, {}, {}, {}, {}, showBack = showBack, modifier = Modifier.fillMaxSize(),
                pinned = true, onAddToProject = {},
                chat = { ChatPane(chat, me, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false, agentParticipants = setOf(tally)) })
        }
    }

    /** A destination over the bottom bar, as the shell lays a phone out. */
    @Composable
    private fun Phone(current: Destination, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) { content() }
            ShellNavigationBar(Destination.entries, current, {}, mapOf(Destination.CHATS to 2))
        }
    }

    @Test fun capture_shell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "shell").apply { mkdirs() }
        val font = "%.1f".format(context.resources.configuration.fontScale)
        val widthDp = context.resources.configuration.screenWidthDp
        fun shoot(name: String, theme: String) {
            ui.waitForIdle()
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(out, "$name-$theme-font$font.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var theme = "light"
            fun show(content: @Composable () -> Unit) = scenario.onActivity { activity -> activity.setContent {
                val dark = isSystemInDarkTheme()
                theme = if (dark) "dark" else "light"
                KithMootTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                }
            } }
            if (widthDp < 840) {
                show { Phone(Destination.CHATS) { Chats() } }
                ui.onNodeWithText("Pinned").assertExists()
                shoot("chats", theme)

                show { Phone(Destination.CHATS) { Chats(state = invited) } }
                ui.onNodeWithText("Invitations").assertExists()
                ui.onAllNodesWithText("Accept", useUnmergedTree = true).assertCountEquals(2)
                shoot("invite", theme)
                // The row's ⋮: Decline and block, on this phone only.
                ui.onAllNodesWithContentDescription("More for the invitation from", substring = true)[1].performClick()
                ui.onNodeWithText("Decline and block", substring = true).assertExists()
                shoot("invite-block", theme)
                androidx.test.espresso.Espresso.pressBack()

                // Settings › Privacy: who is blocked on this phone, with Unblock.
                show { androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    dev.forgesworn.kithmoot.ui.settings.PrivacyPage(true, {},
                        blocked = listOf(stranger to dev.forgesworn.kithmoot.account.shortNpub(stranger), priya to "Priya Natarajan"))
                } }
                ui.onNodeWithText("Blocked on this phone").assertExists()
                ui.onNodeWithContentDescription("Unblock Priya Natarajan").performScrollTo()
                shoot("privacy-blocked", theme)

                show { Phone(Destination.PEOPLE) { PeopleDestination(unlinked, PeopleActions(), accountButton = { Account() }) } }
                ui.onNode(hasText("Link Signet contacts") and hasClickAction()).performClick()
                ui.onNodeWithText("Request check status").assertExists()
                shoot("signet-link", theme)

                show { Phone(Destination.CHATS) { Chats() } }
                ui.onNode(hasText("New room") and hasClickAction()).performClick()
                ui.onNodeWithText("Open an invite link…").assertExists()
                shoot("new-sheet", theme)

                show { Phone(Destination.PEOPLE) { PeopleDestination(people, PeopleActions(), accountButton = { Account() }, initialSelected = priya) } }
                ui.onNodeWithText("Message").assertExists()
                ui.onNodeWithText("Block Priya", substring = true).assertExists()
                shoot("people", theme)

                show { Room() }
                // The room follows the newest message once it has measured; take it there if the first frame did not.
                ui.onAllNodesWithContentDescription("Jump to the latest message").fetchSemanticsNodes().firstOrNull()
                    ?.let { ui.onNodeWithContentDescription("Jump to the latest message").performClick(); ui.waitForIdle() }
                // Stage 2: the room has no Inbox / All work row.
                ui.onNode(hasText("Inbox") and hasClickAction()).assertDoesNotExist()
                ui.onNode(hasText("All work") and hasClickAction()).assertDoesNotExist()
                shoot("room", theme)

                // Stage 3: ⋮ holds what the room's bar does not.
                ui.onNodeWithContentDescription("More for this room").performClick()
                ui.onNodeWithText("Room info").assertExists()
                ui.onNodeWithText("Leave room…").assertExists()
                shoot("room-menu", theme)

                // A call is on and Ada is not on it: the banner, in place of a Call tab.
                show { Room(state = room.copy(callOtherDevices = 2, currentCall = CallSummary(rowan, 2, now - 180, listOf(rowan, priya)))) }
                ui.onNodeWithText("Call on · Rowan and Priya Natarajan").assertExists()
                shoot("room-call-banner", theme)

                // On the call and reading the chat.
                show { Room(state = room.copy(onCall = true, micOn = true, callOtherDevices = 2, currentCall = CallSummary(rowan, 3, now - 723))) }
                ui.onNodeWithText("Return").assertExists()
                shoot("room-on-call", theme)

                // Nobody else here yet, and nothing said.
                show { Room(state = room.copy(tiles = listOf(here(me, "Ada")), agentParticipants = emptySet(), work = AssignmentSnapshot(ready = true)), chat = emptyList()) }
                ui.onNodeWithText("Nobody else is here yet.").assertExists()
                shoot("room-empty", theme)

                // Stage 5: the call itself, its More, and the agents question on first unmute.
                val onCall = room.copy(onCall = true, callOtherDevices = 2, agentCount = 1,
                    currentCall = CallSummary(rowan, 3, now - 723, listOf(me, rowan, priya)))
                show { Room(state = onCall) }
                ui.onNodeWithText("Return").performClick()
                ui.onNodeWithText("More").assertExists()
                shoot("call", theme)
                ui.onNodeWithText("More").performClick()
                ui.onNodeWithText("Effects…").assertExists()
                shoot("call-more", theme)
                ui.onNodeWithText("Effects…").performClick()
                ui.onNodeWithText("Hide your face").assertExists()
                shoot("call-effects", theme)
                androidx.test.espresso.Espresso.pressBack()
                ui.onNodeWithText("Unmute").performClick()
                ui.onNodeWithText("Let agents hear me").assertExists()
                shoot("call-agents-ask", theme)
                show { Phone(Destination.CHATS) { Box(Modifier.fillMaxSize()) {
                    dev.forgesworn.kithmoot.notifications.IncomingCallBanner("Priya Natarajan", {}, {}, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                } } }
                ui.onNodeWithText("Priya Natarajan is calling").assertExists()
                shoot("ring-banner", theme)

                // Stage 4: Room info, a full screen with its sections and rows.
                show { Room() }
                ui.onNodeWithTag(dev.forgesworn.kithmoot.ui.room.ROOM_TITLE_TAG).performClick()
                ui.onNodeWithText("Members").assertExists()
                shoot("room-info", theme)
            } else {
                show {
                    Row(Modifier.fillMaxSize()) {
                        ShellNavigationRail(Destination.entries, Destination.CHATS, {}, settingsSelected = false, onSettings = {},
                            badges = mapOf(Destination.CHATS to 2), account = { Account() })
                        Chats(selected = release, accountButton = false, modifier = Modifier.width(360.dp).fillMaxHeight())
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Room(showBack = false, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
                ui.onAllNodesWithContentDescription("Jump to the latest message").fetchSemanticsNodes().firstOrNull()
                    ?.let { ui.onNodeWithContentDescription("Jump to the latest message").performClick(); ui.waitForIdle() }
                ui.onNode(hasText("Inbox") and hasClickAction()).assertDoesNotExist()
                shoot("tablet-two-pane", theme)
            }
        }
    }
}
