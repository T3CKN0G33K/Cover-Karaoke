package com.barton.dualscreenhost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

class SystemStatusManager(
    private val context: Context,
    private val statusView: UnifiedStatusView
) {

    private var batteryReceiver: BroadcastReceiver? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var telephonyManager: TelephonyManager? = null
    private var telephonyCallback: Any? = null

    fun start() {
        registerBatteryReceiver()
        registerWifiCallback()
        registerCellularCallback()
    }

    fun stop() {
        try {
            batteryReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback is TelephonyCallback) {
                telephonyManager?.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 85
                statusView.setBatteryState(pct, isCharging)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
    }

    private fun registerWifiCallback() {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val wifiInfo = caps.transportInfo as? WifiInfo
                        wifiInfo?.rssi ?: -60
                    } else {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                        @Suppress("DEPRECATION")
                        wifiManager?.connectionInfo?.rssi ?: -60
                    }
                    @Suppress("DEPRECATION")
                    val level = WifiManager.calculateSignalLevel(rssi, 4)
                    statusView.post { statusView.setWifiLevel(level) }
                }
            }

            override fun onLost(network: Network) {
                statusView.post { statusView.setWifiLevel(0) }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun registerCellularCallback() {
        telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    val level = signalStrength.level
                    statusView.post { statusView.setCellularLevel(level) }
                }
            }
            telephonyCallback = callback
            try {
                telephonyManager?.registerTelephonyCallback(context.mainExecutor, callback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            statusView.setCellularLevel(4)
        }
    }
}
