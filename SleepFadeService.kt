package com.example.sleepfade

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.roundToInt

/**
 * Sleep timer. Runs for N minutes. In the last 5 minutes it lowers the music volume
 * once per minute (80% -> 60% -> 40% -> 20% -> 10% of the starting volume),
 * then pauses the music app and restores the original volume.
 */
class SleepFadeService : Service() {

    companion object {
        const val ACTION_START = "com.example.sleepfade.START"
        const val ACTION_STOP = "com.example.sleepfade.STOP"
        const val EXTRA_MINUTES = "minutes"

        private const val CHANNEL_ID = "sleep_fade"
        private const val NOTIF_ID = 1
        private const val FADE_MINUTES = 5

        /** Share of the starting volume kept when 5, 4, 3, 2, 1 minutes are left. */
        private val LEVELS = floatArrayOf(0.8f, 0.6f, 0.4f, 0.2f, 0.1f)

        /** Read by the UI. Uses SystemClock.elapsedRealtime() as its clock. 0 = no timer. */
        @Volatile var endAtMillis = 0L
        @Volatile var volumePercent = 100
        @Volatile var message = ""
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audio: AudioManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var fading = false
    private var startVolume = 0
    private var lastMinute = -1

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (endAtMillis != 0L) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val channel = NotificationChannel(CHANNEL_ID, "Sleep timer", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> cancelTimer()
            ACTION_START -> begin(intent.getIntExtra(EXTRA_MINUTES, 30).coerceIn(1, 720))
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(minutes: Int) {
        // Restart cleanly if a timer was already running.
        handler.removeCallbacksAndMessages(null)
        if (fading) restoreVolume()
        fading = false
        lastMinute = -1
        message = ""

        val durationMs = minutes * 60_000L
        endAtMillis = SystemClock.elapsedRealtime() + durationMs
        volumePercent = 100

        wakeLock?.let { if (it.isHeld) it.release() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepfade:timer")
            .apply { acquire(durationMs + 60_000L) }

        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Sleep timer: $minutes min"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        handler.post(ticker)
    }

    private fun tick() {
        val remaining = endAtMillis - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            finish()
            return
        }
        val minutesLeft = ((remaining + 59_999) / 60_000).toInt()
        if (minutesLeft <= FADE_MINUTES && minutesLeft != lastMinute) {
            if (!fading) {
                fading = true
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            lastMinute = minutesLeft
            applyLevel(LEVELS[FADE_MINUTES - minutesLeft])
            updateNotification("Fading: $minutesLeft min left, volume $volumePercent%")
        }
    }

    private fun applyLevel(fraction: Float) {
        if (startVolume > 0) {
            val target = (startVolume * fraction).roundToInt().coerceAtLeast(1)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        volumePercent = (fraction * 100).roundToInt()
    }

    private fun finish() {
        handler.removeCallbacks(ticker)
        volumePercent = 0
        if (fading && startVolume > 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        val paused = MusicController.pause(this)
        message = if (paused) "Music paused. Good night!" else "Could not pause - grant Notification access."
        handler.postDelayed({
            // Only bring the volume back if the music really stopped.
            if (paused) restoreVolume()
            endAtMillis = 0L
            volumePercent = 100
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 2000)
    }

    private fun cancelTimer() {
        handler.removeCallbacksAndMessages(null)
        if (fading) restoreVolume()
        fading = false
        endAtMillis = 0L
        volumePercent = 100
        message = "Timer cancelled."
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun restoreVolume() {
        if (startVolume > 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, startVolume, 0)
    }

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, SleepFadeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Sleep Fade")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Cancel", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
