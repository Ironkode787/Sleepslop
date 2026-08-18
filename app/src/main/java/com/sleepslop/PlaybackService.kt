package com.sleepslop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sleepslop.audio.AudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the synth engine alive with the screen off.
 * The engine itself is a singleton; this service just pins the process,
 * holds a partial wake lock, and shows the playback notification.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Sleep sound playback controls" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        scope.launch {
            AudioEngine.isPlaying.collect { playing ->
                if (!playing) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        scope.launch {
            AudioEngine.mix.collect {
                if (AudioEngine.isPlaying.value) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification())
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AudioEngine.pause()
                return START_NOT_STICKY
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val sounds = AudioEngine.mix.value.keys.joinToString(" · ") { it.label }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sleepslop")
            .setContentText(sounds.ifEmpty { "Playing sleep sounds" })
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepslop:playback")
            .apply { acquire(12 * 60 * 60 * 1000L) } // safety cap: 12 h
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.sleepslop.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }
}
