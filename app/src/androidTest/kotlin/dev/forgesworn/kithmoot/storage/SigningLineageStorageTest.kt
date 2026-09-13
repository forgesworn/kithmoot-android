package dev.forgesworn.kithmoot.storage

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.AccountStore
import dev.forgesworn.kithmoot.account.NostrAccount
import dev.forgesworn.kithmoot.account.npubOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val encryptedFixtures = linkedMapOf(
    "kithmoot.rooms.v1" to "rooms survive certificate rotation",
    "kithmoot.contacts.v1" to "contacts survive certificate rotation",
    "kithmoot.link-transport.v1" to "link credentials survive certificate rotation",
    "kithmoot.link-consent.v1" to "link consent survives certificate rotation",
)
private val rollbackFixtures = linkedMapOf(
    "kithmoot.cadence.v1" to "cadence ownership survives certificate rotation",
    "kithmoot.epoch.v1" to "room epochs survive certificate rotation",
)
private const val ACCOUNT_PUBKEY = "11e32e46bf7b127c65d22f2d3c14f2b58100e4494c8ec38d52933fd0eb06566f"

/** Seeds the production vault aliases before the package certificate changes. */
@RunWith(AndroidJUnit4::class)
class SigningLineageSeedTest {
    @Test fun seed_production_vaults() {
        val context: Context = ApplicationProvider.getApplicationContext()
        encryptedFixtures.forEach { (alias, value) ->
            EncryptedRoomStorage(context, alias).apply { reset(); write(value.encodeToByteArray()) }
        }
        rollbackFixtures.forEach { (alias, value) ->
            RollbackResistantRoomStorage(context, alias).apply { reset(); write(value.encodeToByteArray()) }
        }
        AccountStore(EncryptedRoomStorage(context, "kithmoot.account.v1")).save(
            NostrAccount(ACCOUNT_PUBKEY, "local", secretKey = ByteArray(32) { 7 }, signedInAt = 1_800_000_000),
        )
    }
}

/** Reads the same ciphertext through the same AndroidKeyStore aliases after rotation. */
@RunWith(AndroidJUnit4::class)
class SigningLineageVerifyTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun production_vaults_and_legacy_account_survive_rotation() {
        val context: Context = ApplicationProvider.getApplicationContext()
        encryptedFixtures.forEach { (alias, value) ->
            assertArrayEquals(value.encodeToByteArray(), EncryptedRoomStorage(context, alias).read())
        }
        rollbackFixtures.forEach { (alias, value) ->
            assertArrayEquals(value.encodeToByteArray(), RollbackResistantRoomStorage(context, alias).read())
        }
        val account = AccountStore(EncryptedRoomStorage(context, "kithmoot.account.v1")).load()
        assertEquals(ACCOUNT_PUBKEY, account?.pubkey)
        assertEquals("local", account?.method)
    }

    @Test fun release_shows_the_retained_preview_identity_without_deleting_it() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ui.waitUntil(10_000) {
                runCatching { ui.onNodeWithText("Keep your preview account").fetchSemanticsNode() }.isSuccess
            }
            ui.onNodeWithText("Keep your preview account").assertExists()
            ui.onNodeWithText(npubOf(ACCOUNT_PUBKEY)).assertExists()
            ui.onNodeWithText("A different account is refused", substring = true).assertExists()
        }
    }
}
