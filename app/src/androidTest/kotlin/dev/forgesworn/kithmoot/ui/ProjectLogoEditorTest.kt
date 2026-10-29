package dev.forgesworn.kithmoot.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.ui.room.*
import dev.forgesworn.kithmoot.ui.start.*
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import java.io.File
import kotlinx.serialization.json.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProjectLogoEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture() = InstrumentationRegistry.getInstrumentation().context.assets.open("logo-images-v1.json")
        .bufferedReader().use { Json.parseToJsonElement(it.readText()) }.jsonObject.getValue("vectors").jsonArray
        .mapNotNull { LogoImages.read(it.jsonObject["payload"]) }.first()

    @Test fun removalCanResolveWithheldForkArtworkWithoutShowingAnUnverifiedImage() {
        var removed = false; var closed = false
        val heads = ProjectLogoEditHeads(listOf("reviewed")); var actualHeads = listOf("reviewed")
        val edit = RoomLogoEditScope("Project with conflicting logos", null, { heads.matches(actualHeads) }, canRemove = true) { image, allowed ->
            assertTrue(allowed()); assertNull(image)
            heads.accepted("own-durable-removal"); actualHeads = listOf("own-durable-removal")
            delay(500) // At least one real editor validity poll sees the accepted head before return.
            assertTrue(allowed()); heads.completed("own-durable-removal"); removed = true
        }
        compose.setContent { KithMootTheme { ProjectLogoEditor(edit) { closed = true } } }
        compose.onNodeWithText("Project logo").assertIsDisplayed()
        compose.onNodeWithText("Remove logo for everyone").performScrollTo().performClick()
        compose.waitUntil(5_000) { removed && closed }
    }

    @Test fun cancelledPreviewDoesNotShareOrRemoveAnything() {
        var closed = false
        val edit = RoomLogoEditScope("Project", fixture(), { true }) { _, _ -> fail("Cancelled project preview shared artwork") }
        compose.setContent { KithMootTheme { ProjectLogoEditor(edit) { closed = true } } }
        compose.onNodeWithText("Share logo with project").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun photoPickerUsesRealLocalPixelsCropAndProjectShareAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("project-logo-picker-", ".png", File(context.cacheDir, "opened-images").apply { mkdirs() })
        val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED); setPixels(IntArray(200 * 200) { Color.BLUE }, 0, 200, 200, 0, 200, 200)
        }
        var closed = false
        try {
            file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.images", file)
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
                }
            }
            val picker = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
            val edit = RoomLogoEditScope("Workshop project", null, { true }) { image, allowed ->
                assertTrue(allowed()); val pixels = requireNotNull(decodePrivateLogo(requireNotNull(image)))
                try { assertTrue(Color.blue(pixels.getPixel(128, 128)) > 240) } finally { pixels.recycle() }
            }
            compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                KithMootTheme { ProjectLogoEditor(edit) { closed = true } }
            } }
            compose.onNodeWithText("Choose image").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Project logo crop preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Horizontal position").performScrollTo()
                .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            compose.onNodeWithText("Share logo with project").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) { closed }
        } finally { source.recycle(); file.delete() }
    }

    @Test fun projectOrAccountChangeDisablesSharingAndKeepsCancelReachable() {
        var valid = true; var closed = false; var changed = false
        val entered = CompletableDeferred<Unit>(); val answer = CompletableDeferred<Unit>()
        val edit = RoomLogoEditScope("Project", fixture(), { valid }) { _, allowed ->
            entered.complete(Unit); answer.await()
            check(allowed()) { "The reviewed project or account changed" }
            changed = true
        }
        compose.setContent { KithMootTheme { ProjectLogoEditor(edit) { closed = true } } }
        compose.onNodeWithText("Remove logo for everyone").performScrollTo().performClick()
        compose.waitUntil(5_000) { entered.isCompleted }
        compose.runOnIdle { valid = false }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("This project or account changed. Close this preview and open the editor again.").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { valid = true; answer.complete(Unit) }
        compose.onNodeWithText("Remove logo for everyone").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertFalse(changed); assertFalse(closed) }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun largeTextProjectEditorKeepsActionsReachableInLightAndDark() {
        val dark = mutableStateOf(false)
        val edit = RoomLogoEditScope("Shared workshop with a long project name", fixture(), { true }) { _, _ -> }
        compose.setContent { KithMootTheme(darkTheme = dark.value, textScale = 1.5f) { ProjectLogoEditor(edit) {} } }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { dark.value = theme == "dark" }
            compose.onNodeWithText("Choose image").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Remove logo for everyone").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Project logo").performScrollTo()
            val picture = compose.onNodeWithText("Project logo").onParent().captureToImage().asAndroidBitmap()
            try {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                File(context.getExternalFilesDir("ui-proof"), "project-logo-editor-large-$theme.png").outputStream().use {
                    assertTrue(picture.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { picture.recycle() }
        }
    }

    @Test fun projectCardOffersLogoEditingOnlyToItsOwner() {
        val owner = LocalSigner(ByteArray(32) { 1 }); val member = LocalSigner(ByteArray(32) { 2 })
        val ref = ProjectReference(owner.pubkey, "ab".repeat(32))
        val definition = buildJsonObject {
            put("name", "Workshop"); put("archived", false); put("authorityRevision", 1); put("rooms", JsonArray(emptyList()))
            put("members", JsonArray(listOf(owner, member).map { buildJsonObject { put("pubkey", it.pubkey); put("kind", "person"); put("epoch", 1) } }))
        }
        val project = SharedProject(ref, listOf("cd".repeat(32)), 1, definition, Projects.authority(ref, definition),
            true, false, false, logo = fixture())
        fun account(signer: LocalSigner) = AccountView(signer.pubkey, npubOf(signer.pubkey), "Owner", "local")
        val state = mutableStateOf(StartState(account = account(owner), projects = ProjectAccountSnapshot(listOf(project), ready = true)))
        var selected = false
        val actions = ProjectActions(editLogo = { selected = true; RoomLogoEditScope("Workshop", it.logo, { true }) { _, _ -> } })
        compose.setContent { KithMootTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ProjectsPanel(state.value, actions)
        } } }
        compose.onNodeWithContentDescription("Edit logo for Workshop").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(selected) }
        compose.onNodeWithText("Project logo").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(account = account(member)) }
        compose.onNodeWithContentDescription("Edit logo for Workshop").assertDoesNotExist()
        compose.onNodeWithText("Workshop").assertExists()
    }
}
