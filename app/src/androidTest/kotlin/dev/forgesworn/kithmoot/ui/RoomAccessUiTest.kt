package dev.forgesworn.kithmoot.ui

import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.Nip55
import dev.forgesworn.kithmoot.account.Nip55Bridge
import dev.forgesworn.kithmoot.account.Nip55Signer
import dev.forgesworn.kithmoot.account.SignerTimeoutException
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.g5.FixtureSignerActivity
import dev.forgesworn.kithmoot.projects.ProjectTestRelay
import dev.forgesworn.kithmoot.protocol.KIND_DEVICE_CREDENTIAL
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeJoinUrl
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.storage.SavedRoom
import dev.forgesworn.kithmoot.ui.room.ROOM_TITLE_TAG
import dev.forgesworn.kithmoot.ui.room.RoomScreen
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class RoomAccessUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun saved_room_opens_without_signer_and_renews_early_from_room_info() {
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val fixture = InstrumentationRegistry.getInstrumentation().context
        val packageName = "dev.forgesworn.kithmoot.roomsigner.fixture"
        val answer = Nip55Bridge { request ->
            FixtureSignerActivity.answer(fixture, request).putExtra("package", packageName)
        }
        val available = AtomicBoolean(false)
        val approvals = AtomicInteger()
        val bridge = Nip55Bridge { request ->
            if (request.getStringExtra("type") == Nip55.TYPE_SIGN_EVENT) {
                val event = Json.parseToJsonElement(requireNotNull(request.data?.schemeSpecificPart)).jsonObject
                if (event.getValue("kind").jsonPrimitive.int == KIND_DEVICE_CREDENTIAL) {
                    approvals.incrementAndGet()
                    if (!available.get()) throw SignerTimeoutException("Fixture signer is away.")
                }
            }
            answer.request(request)
        }
        val owner = runBlocking { Nip55Signer.connect(app, answer, packageName) }
        val previousAccount = app.accounts.load()
        val models = ViewModelStore()
        lateinit var model: RoomViewModel
        ProjectTestRelay().use { relay ->
            val secret = Entropy.bytes(32)
            val room = deriveRoom(secret)
            val now = System.currentTimeMillis() / 1000
            val expiry = now + 60 * 60
            val identity = runBlocking { PrimaryIdentity.createWith(owner, room.roomId, expiry, expiry - 24 * 60 * 60) }
            val link = encodeJoinUrl("https://kithmoot.example/j/", secret, listOf(relay.url))
            val saved = SavedRoom.create(secret, identity, link, listOf(relay.url), "Travel chat", now, null, null)
            app.savedRooms.save(saved)
            try {
                ui.runOnUiThread {
                    model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[RoomViewModel::class.java]
                    model.signerBridge = bridge
                    model.onRelaysChanged(relay.url)
                }
                ui.waitUntil(20_000) { !model.start.value.loadingRooms }
                ui.runOnUiThread { model.signInWithSignerApp(packageName) }
                ui.waitUntil(20_000) { model.start.value.account?.pubkey == owner.pubkey && !model.start.value.signingIn }
                ui.runOnUiThread {
                    model.setAppVisible(true)
                    model.reopenRoom(saved.id)
                }
                ui.waitUntil(20_000) { model.stage.value == Stage.ROOM && model.room.value.roomId == saved.id }
                assertEquals("Reopening must not ask for approval", 0, approvals.get())
                assertEquals(expiry, model.room.value.roomAccessExpiresAt)
                ui.setContent {
                    val state by model.room.collectAsState()
                    KithMootTheme {
                        RoomScreen(
                            state = state,
                            videos = emptyMap(),
                            eglBase = null,
                            onToggleMic = {},
                            onToggleAgentsMayHear = {},
                            onToggleCamera = {},
                            onSwitchCamera = {},
                            onToggleScreenShare = {},
                            onAddDevice = {},
                            onRotateInvitation = {},
                            onLeave = model::leave,
                            onRenewRoomAccess = model::renewRoomAccess,
                            chat = {},
                        )
                    }
                }
                ui.onNodeWithTag(ROOM_TITLE_TAG).performClick()
                ui.onNodeWithText("Access on this phone").performScrollTo().assertIsDisplayed()
                val deadline = ui.onNodeWithText("Valid until", substring = true)
                val before = deadline.performScrollTo().assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text]

                ui.onNodeWithText("Renew access").performScrollTo().performClick()
                ui.waitUntil(20_000) { approvals.get() == 1 && !model.room.value.renewingRoomAccess }
                assertEquals(expiry, app.savedRooms.get(saved.id)?.keptCredentialExpiry(now))

                available.set(true)
                ui.onNodeWithText("Renew access").performScrollTo().performClick()
                ui.waitUntil(20_000) { (model.room.value.roomAccessExpiresAt ?: 0) > expiry && !model.room.value.renewingRoomAccess }
                assertEquals(2, approvals.get())
                assertEquals(model.room.value.roomAccessExpiresAt, app.savedRooms.get(saved.id)?.keptCredentialExpiry(now))
                assertTrue(before != deadline.fetchSemanticsNode().config[SemanticsProperties.Text])
            } finally {
                ui.runOnUiThread { models.clear() }
                app.savedRooms.forget(saved.id)
                if (app.accounts.load()?.pubkey == owner.pubkey) {
                    app.accounts.clear()
                    previousAccount?.let { app.accounts.save(it) }
                }
            }
        }
    }
}
