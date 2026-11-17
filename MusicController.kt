package com.example.sleepfade

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent

object MusicController {

    /** Package names used by Vivo's stock music player. */
    val VIVO_PACKAGES = listOf("com.android.bbkmusic", "com.vivo.music")

    /** Returns the installed Vivo Music version name, or null if not found. */
    fun vivoMusicVersion(context: Context): String? {
        for (pkg in VIVO_PACKAGES) {
            try {
                return context.packageManager.getPackageInfo(pkg, 0).versionName
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Pauses the Vivo music session if it is playing (else any playing session).
     * Falls back to a MEDIA_PAUSE key event if notification access is not granted.
     * Returns true if a pause command was sent.
     */
    fun pause(context: Context): Boolean {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val listener = ComponentName(context, MediaListener::class.java)
            val sessions = msm.getActiveSessions(listener)
            fun playing(state: PlaybackState?) = state?.state == PlaybackState.STATE_PLAYING
            val target = sessions.firstOrNull { it.packageName in VIVO_PACKAGES && playing(it.playbackState) }
                ?: sessions.firstOrNull { playing(it.playbackState) }
            if (target != null) {
                target.transportControls.pause()
                return true
            }
            // Nothing is playing any more: treat as success.
            return true
        } catch (_: SecurityException) {
            // Notification access not granted -> use media key as a fallback.
        }
        return sendMediaPauseKey(context)
    }

    private fun sendMediaPauseKey(context: Context): Boolean {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            true
        } catch (_: Exception) {
            false
        }
    }
}
