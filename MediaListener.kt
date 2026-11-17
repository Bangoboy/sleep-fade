package com.example.sleepfade

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Having this listener enabled (Settings > Notification access)
 * is what allows MediaSessionManager.getActiveSessions() to return the music app's session.
 */
class MediaListener : NotificationListenerService()
