package com.barton.dualscreenhost

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService

class CoverNotificationListener : NotificationListenerService() {

    companion object {
        var instance: CoverNotificationListener? = null
        var isConnected: Boolean = false
            private set

        fun requestRebind(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    val componentName = ComponentName(context, CoverNotificationListener::class.java)
                    requestRebind(componentName)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
        isConnected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        isConnected = false
    }

    fun getActiveMediaControllers(): List<MediaController> {
        return try {
            val mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, CoverNotificationListener::class.java)
            mediaSessionManager.getActiveSessions(componentName)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
