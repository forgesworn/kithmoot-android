package dev.forgesworn.kithmoot.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps a screen share alive.
 *
 * Android will not let an application capture the screen from the background,
 * and since Android 14 it will not create the projection at all unless a
 * foreground service of type `mediaProjection` is already running. The
 * notification is not decoration either: it is the platform's guarantee to the
 * user that something is watching their screen, and it cannot be suppressed.
 *
 * [running] is what the caller waits on. `startForegroundService` returns before
 * the service has actually gone foreground, and starting the capture inside that
 * window is exactly the race that produces a `SecurityException` on some devices
 * and silence on others.
 */
class ScreenShareService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), types())
        _running.value = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    /**
     * What this service tells the platform it is holding.
     *
     * `mediaProjection` is the one the screen capture cannot start without.
     * `camera` and `microphone` are there because sharing a screen is precisely
     * when the user leaves this application to go and show something, and from
     * Android 14 a backgrounded process keeps neither device unless its
     * foreground service claims it - the camera is revoked mid-capture with
     * `ERROR_CAMERA_DISABLED`, reported as a device policy failure.
     *
     * Each type is claimed only when its runtime permission is actually held.
     * Naming a type the application has no permission for is a `SecurityException`
     * on Android 14 and later, and a share with no camera is perfectly ordinary.
     */
    private fun types(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (held(Manifest.permission.CAMERA)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (held(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun held(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing your screen")
            .setContentText("Everyone in the room can see this device's screen.")
            .setSmallIcon(R.drawable.ic_screen_share)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown for as long as this device is sharing its screen."
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "kithmoot.screenshare"
        private const val NOTIFICATION_ID = 4601

        private val _running = MutableStateFlow(false)

        /** True once the service is genuinely in the foreground. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScreenShareService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenShareService::class.java))
            _running.value = false
        }
    }
}
