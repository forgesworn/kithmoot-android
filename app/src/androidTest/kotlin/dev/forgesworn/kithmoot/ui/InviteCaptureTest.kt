package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.ContactInboxView
import dev.forgesworn.kithmoot.account.ContactInvitation
import dev.forgesworn.kithmoot.account.SignetContact
import dev.forgesworn.kithmoot.account.SignetDirectoryView
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.session.ChatInvite
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.inviteText
import dev.forgesworn.kithmoot.session.roomInviteText
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.room.ChatPane
import dev.forgesworn.kithmoot.ui.room.ParticipantDetails
import dev.forgesworn.kithmoot.ui.start.Destination
import dev.forgesworn.kithmoot.ui.start.PeopleActions
import dev.forgesworn.kithmoot.ui.start.PeopleDestination
import dev.forgesworn.kithmoot.ui.start.ShellNavigationBar
import dev.forgesworn.kithmoot.ui.start.StartScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Screenshots of design-calm-shell stage 6 on Android for review: the
 * ask-first door, invitation cards in a chat and in Chats, a member's
 * Invite to a room, the picker, and People's Invite. Seeded data, no
 * network, every action a no-op; saved under `files/invite/` with the
 * theme and font scale in each name, as [ShellCaptureTest] does.
 */
class InviteCaptureTest {
    @get:Rule val ui = createEmptyComposeRule()

    private val me = "03".repeat(32)
    private val priya = "a2".repeat(32)
    private val rowan = "b4".repeat(32)
    private val stranger = "e9".repeat(32)
    private val now = System.currentTimeMillis() / 1000

    private val release = "1a".repeat(32)
    private val priyaChat = "2b".repeat(32)
    private val townHall = "3c".repeat(32)
    private val moot = "4d".repeat(32)

    private val account = AccountView(me, npubOf(me), "npub1ada…", "local", name = "Ada")
    private val rooms = listOf(
        SavedRoomSummary(release, "Release workshop", false, now - 60, account = me, canShareInvite = true),
        SavedRoomSummary(priyaChat, "Private with Priya Natarajan", false, now - 30, account = me, privatePeer = priya, canShareInvite = true),
        SavedRoomSummary(townHall, "Town hall", false, now - 600, account = me, canShareInvite = true),
        SavedRoomSummary(moot, "The moot", false, now - 9000, account = me, canShareInvite = true),
    )
    private val people = StartState(loadingRooms = false, account = account, publicProfiles = true, savedRooms = rooms,
        signetContacts = SignetDirectoryView("ready", listOf(SignetContact(priya, "Priya Natarajan", "Kith", true),
            SignetContact(rowan, "Rowan", "Ken", true))),
        contactInbox = ContactInboxView(ready = true))
    private val invited = people.copy(contactInbox = ContactInboxView(ready = true, invitations = listOf(
        ContactInvitation("i1", priya, me, "7c".repeat(32), "https://example.org/j/#r", now - 120),
        ContactInvitation("i2", stranger, me, "8d".repeat(32), "https://example.org/j/#s", now - 1500),
    )))

    private fun say(id: String, who: String, name: String?, text: String, ago: Long, invite: ChatInvite? = null) =
        ChatMessage(id, who, who.take(2).repeat(32), text, now - ago, name = name, invite = invite)

    // A private chat with Priya: she invites Ada to a room, Ada invites her back to Town hall.
    private val chat = listOf(
        say("m1", priya, "Priya Natarajan", "Come to the Saturday walk planning, it is in our room.", 400),
        say("m2", priya, "Priya Natarajan", roomInviteText(), 380, ChatInvite(me, "9e".repeat(32), "sealed")),
        say("m3", me, "Ada", "Thanks! And you should see Town hall.", 200),
        say("m4", me, "Ada", roomInviteText(), 190, ChatInvite(priya, townHall, "sealed")),
        say("m5", stranger, null, inviteText(), 100, ChatInvite(me, "af".repeat(32), "sealed")),
    )

    @Composable
    private fun Phone(current: Destination, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) { content() }
            ShellNavigationBar(Destination.entries, current, {}, mapOf(Destination.CHATS to 2))
        }
    }

    @Composable
    private fun Chats(state: StartState) = StartScreen(
        state = state, onRoomNameChanged = {}, onJoinUrlChanged = {}, onRelaysChanged = {}, onAnonymousModeChanged = {},
        onPersistentGroupChanged = {}, onStartRoom = {}, onJoin = {}, onReopen = {}, onForget = {}, onProject = { _, _ -> },
        onRetryStorage = {}, onResetStorage = {}, onMessageContact = {})

    @Test fun capture_invites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val out = File(context.getExternalFilesDir(null), "invite").apply { mkdirs() }
        val font = "%.1f".format(context.resources.configuration.fontScale)
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

            // The ask-first door, as a temporary room's link opens it.
            val gate = GuestAdmissionGate()
            gate.prepare("synthetic-invitation", "Release workshop", "", null)
            show {
                val view by gate.view.collectAsState()
                view?.let { GuestAdmissionDialog(it, false, devicesAvailable = true, onName = gate::editName, onRequest = {},
                    onCancel = {}, onClose = {}, onRetry = {}) }
            }
            ui.onNodeWithText("Ask to join Release workshop").assertExists()
            ui.onNodeWithText("What should we call you?").assertExists()
            shoot("door", theme)

            // Chats: invitations waiting; a nameless sender is Somebody, never a key.
            show { Phone(Destination.CHATS) { Chats(invited) } }
            ui.onNodeWithText("Somebody invited you to a private chat").assertExists()
            shoot("chats-invitations", theme)

            // Cards in a chat: received ones cannot name the room; your own can.
            show { Box(Modifier.fillMaxSize().statusBarsPadding()) {
                ChatPane(chat, me, { _, _ -> }, Modifier.fillMaxSize(), showTitle = false,
                    savedRoomName = { id -> rooms.firstOrNull { it.id == id }?.name })
            } }
            ui.onNodeWithText("Priya Natarajan invited you to a room").assertExists()
            ui.onNodeWithText("You invited Priya Natarajan to Town hall").assertExists()
            ui.onNodeWithText("Somebody invited you to a private chat").assertExists()
            shoot("chat-cards", theme)

            // A member's sheet in a room: Invite to a room beside Message privately.
            show { ParticipantDetails(priya, "Priya Natarajan", null, onClose = {}, onMessage = {}, onInvite = {}) }
            ui.onNodeWithText("Invite to a room").assertExists()
            shoot("member-invite", theme)

            // The picker from that sheet: the room on screen is where she already is.
            val fromRoom = RoomInvitePicker(priya, "Priya Natarajan", fromPeople = false)
            val choices = invitableRooms(rooms, me, current = release)
            show { Box(Modifier.fillMaxSize().statusBarsPadding()) { RoomInviteContent(fromRoom, choices, {}, {}) } }
            ui.onNodeWithText("Priya Natarajan is already here").assertExists()
            shoot("picker", theme)
            ui.onNode(hasText("Town hall") and hasClickAction()).performClick()
            shoot("picker-chosen", theme)
            show { Box(Modifier.fillMaxSize().statusBarsPadding()) {
                RoomInviteContent(fromRoom.copy(sending = true), choices, {}, {}, initialChoice = townHall)
            } }
            ui.onNodeWithText("Sending…").assertExists()
            shoot("picker-sending", theme)
            show { Box(Modifier.fillMaxSize().statusBarsPadding()) {
                RoomInviteContent(fromRoom.copy(error = "The relays refused the invitation; nobody was told its link."), choices, {}, {},
                    initialChoice = townHall)
            } }
            shoot("picker-error", theme)
            show { Box(Modifier.fillMaxSize().statusBarsPadding()) {
                RoomInviteContent(RoomInvitePicker(rowan, "Rowan", fromPeople = true), emptyList(), {}, {})
            } }
            ui.onNodeWithText("You have no rooms to invite Rowan to yet. Start a room from Chats, then invite Rowan.").assertExists()
            shoot("picker-empty", theme)

            // As a sheet over People, from Priya's Invite.
            show { Phone(Destination.PEOPLE) {
                PeopleDestination(people, PeopleActions(), initialSelected = priya)
                RoomInviteSheet(RoomInvitePicker(priya, "Priya Natarajan", fromPeople = true), invitableRooms(rooms, me, null), {}, {})
            } }
            ui.onNodeWithText("Invite Priya Natarajan to a room").assertExists()
            shoot("people-picker", theme)

            // People: Invite beside Message, Voice and Video.
            show { Phone(Destination.PEOPLE) { PeopleDestination(people, PeopleActions(), initialSelected = priya) } }
            ui.onNodeWithText("Invite").assertExists()
            shoot("people-invite", theme)
        }
    }
}
