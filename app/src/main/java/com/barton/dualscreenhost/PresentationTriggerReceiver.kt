package com.barton.dualscreenhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PresentationTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            "com.barton.dualscreenhost.ACTION_SHOW", "ACTION_START_COVER_DISPLAY" -> MainActivity.instance?.showPresentation()
            "com.barton.dualscreenhost.ACTION_HIDE", "ACTION_STOP_COVER_DISPLAY" -> MainActivity.instance?.dismissPresentation()
            "ACTION_UPDATE_COVER_SETTINGS" -> {
                val bVal = intent.getFloatExtra("brightness", -1f)
                val amoled = if (intent.hasExtra("amoled")) intent.getBooleanExtra("amoled", false) else null
                MainActivity.instance?.updateCoverPresentationSettings(bVal, amoled)
            }
        }
    }
}
