package dev.forgesworn.kithmoot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.theme.TextSize
import dev.forgesworn.kithmoot.ui.theme.TextSizeSetting
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.forgesworn.kithmoot.ui.KithMootApp
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.result.contract.ActivityResultContracts
import dev.forgesworn.kithmoot.account.Nip55Bridge
import dev.forgesworn.kithmoot.account.SignetSignIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** The browser coming back from Signet with a sign-in. */
    private val signetReturn = MutableStateFlow<String?>(null)
    private val pictureInPicture = MutableStateFlow(false)

    /**
     * Signer intents, one at a time. A NIP-55 signer app is another activity
     * started for a result, and only the activity can do that; the view model
     * asks through this and waits.
     */
    private var signerAnswer: CompletableDeferred<Intent?>? = null
    private val signerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        signerAnswer?.complete(if (result.resultCode == RESULT_OK) result.data ?: Intent() else null)
        signerAnswer = null
    }
    private val signerTurn = Mutex()
    private val signerBridge = Nip55Bridge { intent ->
        signerTurn.withLock {
            val answer = CompletableDeferred<Intent?>()
            signerAnswer = answer
            try { signerLauncher.launch(intent) } catch (e: Exception) {
                signerAnswer = null
                throw dev.forgesworn.kithmoot.account.SignerException("The signer app could not be opened: ${e.message}")
            }
            answer.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        signetFrom(intent)?.let { signetReturn.value = it } ?: run { incoming.value = linkFrom(intent) }

        setContent {
            var textSize by remember { mutableStateOf(TextSize.load(this)) }
            val textSetting = remember(textSize) { TextSizeSetting(textSize) { chosen -> TextSize.save(this, chosen); textSize = chosen } }
            KithMootTheme(textScale = textSize.scale) {
              CompositionLocalProvider(LocalTextSizeSetting provides textSetting) {
                val model: RoomViewModel = viewModel()
                model.signerBridge = signerBridge
                val link by incoming.collectAsState()
                LaunchedEffect(link) {
                    val url = link ?: return@LaunchedEffect
                    incoming.value = null
                    model.onJoinUrlChanged(url)
                    model.joinFromUrl(url)
                }
                val signet by signetReturn.collectAsState()
                LaunchedEffect(signet) {
                    val callback = signet ?: return@LaunchedEffect
                    signetReturn.value = null
                    model.completeSignetSignIn(callback)
                }
                LaunchedEffect(model) {
                    model.browser.collect { url ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        catch (e: Exception) { model.cancelSignIn(); model.showNotice("No browser could open the Signet sign-in.") }
                    }
                }
                val inPip by pictureInPicture.collectAsState()
                KithMootApp(model, inPip, if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) ({
                    val opened = runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) }.getOrDefault(false)
                    if (!opened) model.showNotice("Picture-in-picture could not open. You can still zoom in fullscreen.")
                }) else null)
              }
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
        signetFrom(intent)?.let { signetReturn.value = it; return }
        linkFrom(intent)?.let { incoming.value = it }
    }

    /** `kithmoot://signet?…`: Signet sending the browser back here with a sign-in. */
    private fun signetFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val raw = intent.dataString ?: return null
        return raw.takeIf { SignetSignIn.parse(it) != null }
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
