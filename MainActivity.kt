package com.example.sleepfade

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    private lateinit var tvVersion: TextView
    private lateinit var tvAccess: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etMinutes: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvVersion = findViewById(R.id.tvVersion)
        tvAccess = findViewById(R.id.tvAccess)
        tvStatus = findViewById(R.id.tvStatus)
        etMinutes = findViewById(R.id.etMinutes)

        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener { startTimer() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, SleepFadeService::class.java).setAction(SleepFadeService.ACTION_STOP))
        }
    }

    private fun startTimer() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val minutes = etMinutes.text.toString().toIntOrNull()?.coerceIn(1, 720) ?: 30
        val i = Intent(this, SleepFadeService::class.java)
            .setAction(SleepFadeService.ACTION_START)
            .putExtra(SleepFadeService.EXTRA_MINUTES, minutes)
        ContextCompat.startForegroundService(this, i)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val version = MusicController.vivoMusicVersion(this)
        tvVersion.text = when {
            version == null -> "Vivo Music: not found"
            version.startsWith("4.9.1.2") -> "Vivo Music $version (matches 4.9.1.2)"
            else -> "Vivo Music $version (built for 4.9.1.2, should still work)"
        }

        val access = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        tvAccess.text = if (access) "Notification access: ON" else "Notification access: OFF - tap the button below and enable Sleep Fade"

        val end = SleepFadeService.endAtMillis
        tvStatus.text = if (end != 0L) {
            val left = ((end - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
            val mm = left / 60
            val ss = left % 60
            val fade = if (left <= 300) "\nFading - volume ${SleepFadeService.volumePercent}%" else "\nFade starts in the last 5 min"
            "%d:%02d left%s".format(mm, ss, fade)
        } else {
            SleepFadeService.message.ifEmpty { "No timer running" }
        }
    }
}
