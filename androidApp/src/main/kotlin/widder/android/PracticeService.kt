package widder.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import widder.ui.BackgroundAudio

/**
 * Keeps Widder alive while drums, drone or loop play and the app is in the background or the
 * screen is off: a foreground service with a notification to get back or to stop. The sound
 * itself stays in the view model's stream; this only holds the process and the CPU awake.
 * While the looper records, the service is of the microphone type too: without it Android
 * gives an app in the background only silence.
 */
class PracticeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The view model stops the sound and in turn this service.
            Handler(Looper.getMainLooper()).post { MainActivity.stopPracticeSound() }
            return START_NOT_STICKY
        }
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Üben"
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && intent?.getBooleanExtra(EXTRA_MICROPHONE, false) == true) {
            val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            // Refused when the microphone permission is missing: then at least the sound goes on.
            runCatching { startForeground(ID, notification, both) }
                .onFailure { startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, notification)
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "widder:practice").apply { acquire(3 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY
    }

    /** Swiped away from the recent apps: the sound goes too. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Handler(Looper.getMainLooper()).post { MainActivity.stopPracticeSound() }
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Üben im Hintergrund", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PracticeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Widder übt")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stopp", stop).build())
            .build()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "practice"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_MICROPHONE = "microphone"
        private const val ACTION_STOP = "widder.STOP_PRACTICE"

        /** Starts or updates the service for [audio]; null stops it. */
        fun update(context: Context, audio: BackgroundAudio?) {
            val intent = Intent(context, PracticeService::class.java)
            if (audio == null) {
                context.stopService(intent)
                return
            }
            intent.putExtra(EXTRA_TEXT, audio.text)
            intent.putExtra(EXTRA_MICROPHONE, audio.microphone)
            // Only ever called while the app is in front (a tap on play) or the service already runs.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }
        }
    }
}
