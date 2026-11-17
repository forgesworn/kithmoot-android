package dev.forgesworn.kithmoot.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.SignetContact
import dev.forgesworn.kithmoot.account.SignetDirectoryView
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.ui.start.PeopleActions
import dev.forgesworn.kithmoot.ui.start.PeopleDestination
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PeopleProfileCacheDeviceTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun encrypted_profile_cache_restores_contact_name_and_picture_url_for_people() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "a7".repeat(32)
        val otherAccount = "b7".repeat(32)
        val now = System.currentTimeMillis() / 1000
        val event = Events.sign(ByteArray(32) { 71 }, 0, now, emptyList(),
            """{"display_name":"Rowan","picture":"https://example.invalid/rowan.png"}""")
        val cache = PeopleProfileCache.onDevice(context, account)
        val other = PeopleProfileCache.onDevice(context, otherAccount)
        cache.clear(); other.clear()
        try {
            cache.put(listOf(event))
            val restored = PeopleProfileCache.onDevice(context, account).read(listOf(event.pubkey))
            assertEquals("Rowan", restored.profiles[event.pubkey]?.name)
            assertEquals("https://example.invalid/rowan.png", restored.profiles[event.pubkey]?.picture)
            assertTrue(other.read(listOf(event.pubkey)).profiles.isEmpty())
            val vault = java.io.File(context.noBackupFilesDir, "kithmoot.people-profiles.v1.$account.vault")
            assertTrue(vault.isFile)
            assertFalse(vault.readBytes().decodeToString().contains("Rowan"))
            // Exercise restored metadata in the actual People UI. Image loading
            // is omitted so this fixture never contacts an external image host.
            var state by mutableStateOf(StartState(
                signetContacts = SignetDirectoryView("ready", listOf(SignetContact(event.pubkey, npubOf(event.pubkey)))),
                followProfiles = restored.profiles.mapValues { it.value.copy(picture = null) },
            ))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent { KithMootTheme { PeopleDestination(state, PeopleActions()) } } }
                ui.onNodeWithText("Rowan").assertIsDisplayed()
                ui.onNodeWithText(npubOf(event.pubkey)).assertDoesNotExist()
                val image = ui.onRoot().captureToImage().asAndroidBitmap()
                java.io.File(context.cacheDir, "people-cached-profile.png").outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                ui.runOnIdle { state = state.copy(publicProfiles = false, followProfiles = emptyMap()) }
                ui.onNodeWithText("Rowan").assertDoesNotExist()
                ui.onNodeWithText(shortNpub(event.pubkey)).assertIsDisplayed()
            }
            cache.clear()
            assertTrue(PeopleProfileCache.onDevice(context, account).read(listOf(event.pubkey)).profiles.isEmpty())
        } finally { cache.clear(); other.clear() }
    }
}
