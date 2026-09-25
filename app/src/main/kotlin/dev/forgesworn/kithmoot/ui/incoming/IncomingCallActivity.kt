package dev.forgesworn.kithmoot.ui.incoming

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
import dev.forgesworn.kithmoot.ui.room.shortId
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
                    callerLabel = shortId(caller),
                    onAnswer = { sendAction(IncomingCallActionReceiver.ACTION_ANSWER); finish() },
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

@Composable
private fun IncomingCallScreen(roomName: String, callerLabel: String, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Incoming call", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text(callerLabel, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text("is calling in $roomName", style = MaterialTheme.typography.bodyLarge)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                OutlinedButton(onClick = onDecline, modifier = Modifier.size(width = 140.dp, height = 56.dp)) { Text("Decline") }
                Button(onClick = onAnswer, modifier = Modifier.size(width = 140.dp, height = 56.dp)) { Text("Answer") }
            }
        }
    }
}
