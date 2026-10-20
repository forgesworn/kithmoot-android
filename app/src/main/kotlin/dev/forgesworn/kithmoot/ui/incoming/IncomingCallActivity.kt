package dev.forgesworn.kithmoot.ui.incoming

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.notifications.IncomingCallActionReceiver
import dev.forgesworn.kithmoot.notifications.IncomingCallRinger
import dev.forgesworn.kithmoot.ui.room.callerLabel
import dev.forgesworn.kithmoot.ui.theme.KithMootTheme

/**
 * The full-screen intent target for a ringing call: shown over the lock
 * screen where Android 14's `canUseFullScreenIntent()` allows it, and
 * reachable from the notification's own tap otherwise.
 *
 * Finishes itself the moment [IncomingCallRinger.active] no longer names
 * this call - answered, declined, timed out, or the call itself ended -
 * so nothing here needs its own copy of that decision.
 */
class IncomingCallActivity : ComponentActivity() {
    private var roomId = ""
    private var roomName = ""
    private var callId = ""
    private var caller = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        readExtras(intent)

        setContent {
            KithMootTheme {
                val active by IncomingCallRinger.active.collectAsState()
                LaunchedEffect(active) {
                    val current = active
                    if (current == null || current.roomId != roomId || current.callId != callId) finish()
                }
                IncomingCallScreen(
                    roomName = roomName.ifBlank { "KithMoot" },
                    callerLabel = callerLabel(caller),
                    onAnswer = { startActivity(IncomingCallActionReceiver.answerIntent(this, roomId, roomName, callId)); finish() },
                    onDecline = { sendAction(IncomingCallActionReceiver.ACTION_DECLINE); finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readExtras(intent)
    }

    private fun readExtras(intent: Intent) {
        roomId = intent.getStringExtra(IncomingCallRinger.EXTRA_ROOM_ID).orEmpty()
        roomName = intent.getStringExtra(IncomingCallRinger.EXTRA_ROOM_NAME).orEmpty()
        callId = intent.getStringExtra(IncomingCallRinger.EXTRA_CALL_ID).orEmpty()
        caller = intent.getStringExtra(IncomingCallRinger.EXTRA_CALLER).orEmpty()
    }

    private fun sendAction(action: String) {
        sendBroadcast(
            Intent(this, IncomingCallActionReceiver::class.java).setAction(action)
                .putExtra(IncomingCallRinger.EXTRA_ROOM_ID, roomId)
                .putExtra(IncomingCallRinger.EXTRA_ROOM_NAME, roomName)
                .putExtra(IncomingCallRinger.EXTRA_CALL_ID, callId)
                .putExtra(IncomingCallRinger.EXTRA_CALLER, caller),
        )
    }

}

/**
 * The ring screen (design-calm-shell 6.5): what is ringing, who, and where,
 * then Decline in red and Answer in green, 72 dp each. Answer goes straight
 * in with the microphone on and the camera off.
 */
@Composable
private fun IncomingCallScreen(roomName: String, callerLabel: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                // The ring does not say whether it is voice or video.
                Text("KithMoot call", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text(callerLabel, style = MaterialTheme.typography.headlineMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                // A private chat is named for the person calling; say what it is instead.
                Text(if (roomName.isBlank() || roomName == callerLabel) "Private chat" else roomName, style = MaterialTheme.typography.bodyLarge,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                RingButton("Decline", Icons.Filled.CallEnd, androidx.compose.ui.graphics.Color(0xFFC62828), onDecline)
                RingButton("Answer", Icons.Filled.Call, androidx.compose.ui.graphics.Color(0xFF2E7D32), onAnswer)
            }
        }
    }
}

@Composable
private fun RingButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, colour: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = colour,
                contentColor = androidx.compose.ui.graphics.Color.White),
        ) { androidx.compose.material3.Icon(icon, contentDescription = label, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
