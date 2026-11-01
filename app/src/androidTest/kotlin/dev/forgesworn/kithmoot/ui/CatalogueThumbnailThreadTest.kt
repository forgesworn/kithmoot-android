package dev.forgesworn.kithmoot.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ActivityScenario
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.session.searchMediaCatalogue
import dev.forgesworn.kithmoot.ui.room.CatalogueThumbnail
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The GIF thumbnail decodes off the main thread and then swaps a lifecycle observer. A real
 * [LifecycleRegistry] refuses that swap from any other thread, so any mount whose decoded drawable
 * is published, and recomposed, off the main thread crashes the composition.
 */
class CatalogueThumbnailThreadTest {
    @get:Rule val ui = createEmptyComposeRule()

    private class MainThreadOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun start() { registry.currentState = Lifecycle.State.STARTED }
        fun observers() = registry.observerCount
    }

    private fun hasDecodedImage(view: View): Boolean =
        (view is ImageView && view.drawable != null) || (view is ViewGroup && view.children.any(::hasDecodedImage))

    @Test fun decoded_gif_is_published_and_observer_swapped_on_the_main_thread_every_time() {
        val gif = searchMediaCatalogue("coffee", false).single()
        val owner = MainThreadOwner()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { owner.start() }
            var generation by mutableIntStateOf(0)
            scenario.onActivity { activity -> activity.setContent { KithMootTheme {
                key(generation) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner) { CatalogueThumbnail(gif, Modifier.size(100.dp)) }
                }
            } } }
            repeat(30) { round ->
                ui.waitUntil(10_000) {
                    var decoded = false
                    scenario.onActivity { decoded = hasDecodedImage(it.window.decorView) }
                    decoded
                }
                ui.waitForIdle()
                // Let any recomposition triggered by the decoded drawable run before the next mount.
                Thread.sleep(50)
                ui.waitForIdle()
                ui.runOnUiThread { generation = round + 1 }
            }
            ui.waitForIdle()
            // Every mount removed its observer again; the last mount still holds exactly one.
            var held = -1
            scenario.onActivity { held = owner.observers() }
            assertEquals(1, held)
        }
    }
}
