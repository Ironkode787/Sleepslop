package com.sleepslop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.sleepslop.audio.AudioEngine
import com.sleepslop.audio.WakeRoutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that keeps the synth engine alive with the screen off.
 *
 * The engine itself is a singleton; this service pins the process, holds a
 * partial wake lock while sound is playing, publishes a [MediaSessionCompat]
 * so the lock screen / headset buttons / watches can drive playback, and shows
 * the media-style notification.
 *
 * It stays alive while *either* playback is running *or* a wake-up routine is
 * armed; in the armed-but-silent state it shows a calm "Wake-up armed" note
 * and drops the wake lock.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var session: MediaSessionCompat

    private var noisyReceiver: BroadcastReceiver? = null
    private var startedForeground = false

    // ------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Sleep sound playback controls"
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        session = MediaSessionCompat(this, "SleepslopSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    AudioEngine.play()
                }

                override fun onPause() {
                    AudioEngine.pause()
                }

                override fun onStop() {
                    AudioEngine.pause()
                }
            })
            setSessionActivity(openAppIntent())
        }
        updateMetadata()
        updateSessionState(AudioEngine.isPlaying.value)
        session.isActive = AudioEngine.isPlaying.value

        registerNoisyReceiver()

        // Playback state -> session, wake lock, notification.
        scope.launch {
            AudioEngine.isPlaying.collect { playing ->
                session.isActive = playing
                updateSessionState(playing)
                if (playing) acquireWakeLock() else releaseWakeLock()
                refreshNotification()
            }
        }

        // Mix changes -> metadata + notification subtitle.
        scope.launch {
            AudioEngine.mix.collect {
                updateMetadata()
                if (AudioEngine.isPlaying.value) refreshNotification()
            }
        }

        // Wake time changes -> refresh the armed notification.
        scope.launch {
            WakeRoutine.wakeAtMillis.collect {
                if (!AudioEngine.isPlaying.value) refreshNotification()
            }
        }

        // Stay alive while playing OR armed; stop only when both are false.
        scope.launch {
            combine(AudioEngine.isPlaying, WakeRoutine.armed) { playing, armed ->
                playing || armed
            }.distinctUntilChanged().collect { keepAlive ->
                if (keepAlive) {
                    refreshNotification()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Hardware media buttons / watch transport controls arrive here.
        if (intent != null) {
            MediaButtonReceiver.handleIntent(session, intent)
        }

        when (intent?.action) {
            ACTION_TOGGLE -> AudioEngine.togglePlayback()
            ACTION_STOP -> AudioEngine.pause()
        }

        // Always honour the startForegroundService contract before deciding to
        // stop, so a stop request can never leave the service in limbo.
        startForegroundCompat()
        if (AudioEngine.isPlaying.value) acquireWakeLock() else releaseWakeLock()

        // Keep-alive rule: alive while playing OR while a wake-up is armed.
        if (!AudioEngine.isPlaying.value && !WakeRoutine.armed.value) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        unregisterNoisyReceiver()
        scope.cancel()
        if (this::session.isInitialized) {
            session.isActive = false
            session.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------- media session

    private fun updateSessionState(playing: Boolean) {
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f
            )
            .build()
        session.setPlaybackState(state)
    }

    private fun updateMetadata() {
        val subtitle = activeSounds()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, TITLE)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, TITLE)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .build()
        )
    }

    /** Active sound labels, e.g. "Rain · Brown noise". */
    private fun activeSounds(): String {
        val labels = AudioEngine.mix.value.keys.joinToString(" · ") { it.label }
        return labels.ifEmpty { FALLBACK_SUBTITLE }
    }

    // ---------------------------------------------------------- notification

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startedForeground = true
    }

    private fun refreshNotification() {
        if (!startedForeground) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val playing = AudioEngine.isPlaying.value
        return if (!playing && WakeRoutine.armed.value) {
            buildArmedNotification()
        } else {
            buildMediaNotification(playing)
        }
    }

    private fun buildMediaNotification(playing: Boolean): Notification {
        val toggle = PendingIntent.getService(
            this, REQ_TOGGLE,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, REQ_STOP,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(TITLE)
            .setContentText(activeSounds())
            .setContentIntent(openAppIntent())
            .setOngoing(playing)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                toggle
            )
            .addAction(R.drawable.ic_notification, "Stop", stop)
            .setDeleteIntent(stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stop)
            )
            .build()
    }

    private fun buildArmedNotification(): Notification {
        val wakeAt = WakeRoutine.wakeAtMillis.value
        val text = if (wakeAt != null) {
            "Wakes at ${formatClock(wakeAt)}"
        } else {
            "Waiting for the wake-up time"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ARMED_TITLE)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun formatClock(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, REQ_OPEN,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE
    )

    // -------------------------------------------------------- becoming noisy

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    AudioEngine.pause()
                }
            }
        }
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        noisyReceiver = receiver
    }

    private fun unregisterNoisyReceiver() {
        val receiver = noisyReceiver ?: return
        noisyReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    // -------------------------------------------------------------- wakelock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepslop:playback")
            .apply { acquire(12 * 60 * 60 * 1000L) } // safety cap: 12 h
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.sleepslop.STOP"
        private const val ACTION_TOGGLE = "com.sleepslop.TOGGLE"

        private const val TITLE = "Sleepslop"
        private const val ARMED_TITLE = "Wake-up armed"
        private const val FALLBACK_SUBTITLE = "Sleep sounds"

        private const val REQ_OPEN = 0
        private const val REQ_STOP = 1
        private const val REQ_TOGGLE = 2

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }
    }
}
