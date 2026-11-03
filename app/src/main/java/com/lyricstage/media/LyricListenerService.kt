package com.lyricstage.media

import android.service.notification.NotificationListenerService

/**
 * Granting this listener notification access is what lets the app call
 * MediaSessionManager.getActiveSessions(). It ignores every notification.
 */
class LyricListenerService : NotificationListenerService()
