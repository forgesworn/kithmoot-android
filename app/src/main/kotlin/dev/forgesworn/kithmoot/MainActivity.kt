package dev.forgesworn.kithmoot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.forgesworn.kithmoot.ui.KithMootApp
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The only activity.
 *
 * It is `singleTask` in the manifest, so a join link tapped while a room is
 * already open arrives at [onNewIntent] on the running instance rather than
 * standing up a second copy of the application on top of a live session.
 */
class MainActivity : ComponentActivity() {

    /** A link that has arrived and not yet been acted on. */
    private val incoming = MutableStateFlow<String?>(null)
    private val pictureInPicture = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = linkFrom(intent)

        setContent {
            KithMootTheme {
                val model: RoomViewModel = viewModel()
                val link by incoming.collectAsState()
                LaunchedEffect(link) {
                    val url = link ?: return@LaunchedEffect
                    incoming.value = null
                    model.onJoinUrlChanged(url)
                    model.joinFromUrl(url)
                }
                val inPip by pictureInPicture.collectAsState()
                KithMootApp(model, inPip, if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) ({
                    val opened = runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) }.getOrDefault(false)
                    if (!opened) model.showNotice("Picture-in-picture could not open. You can still zoom in fullscreen.")
                }) else null)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pictureInPicture.value = isInPictureInPictureMode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkFrom(intent)?.let { incoming.value = it }
    }

    /**
     * The link off an incoming VIEW intent.
     *
     * `dataString` is used rather than rebuilding from the `Uri`, because the
     * payload lives entirely in the fragment and a round trip through `Uri`
     * parts is a good way to lose it. A URL with no fragment carries no room and
     * is ignored.
     */
    private fun linkFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val raw = intent.dataString ?: return null
        return raw.takeIf { it.contains('#') }
    }
}
