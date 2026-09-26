package com.barton.dualscreenhost

import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.barton.dualscreenhost.services.FloatingBubbleService
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        var instance: MainActivity? = null
        const val SHIZUKU_REQ_CODE = 5001
    }

    private lateinit var displayManager: DisplayManager
    private var presentation: CoverPresentation? = null
    private lateinit var logView: TextView
    private var isDaemonRunning = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            logView.text = "Shizuku Binder Received. Checking permission..."
            checkAndRequestShizuku()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            logView.text = "Shizuku Binder disconnected."
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQ_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                logView.text = "Shizuku permission granted. Starting sensor daemon..."
                startHingeAngleDaemon()
            } else {
                logView.text = "Shizuku permission denied."
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager

        val dp = resources.displayMetrics.density

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
        }

        // Spotify Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (28 * dp).toInt())
        }

        val logoIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_fold_dual_screen)
            setColorFilter(Color.parseColor("#1DB954"))
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                marginEnd = (16 * dp).toInt()
            }
        }
        headerLayout.addView(logoIcon)

        val headerTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "Cover Host"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        headerTextCol.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Spotify Cover Display Controller"
            setTextColor(Color.parseColor("#A7A7A7"))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        headerTextCol.addView(subtitleView)
        headerLayout.addView(headerTextCol)

        rootLayout.addView(headerLayout)

        // Buttons Section
        val shizukuBtn = Button(this).apply {
            text = "Connect Shizuku Engine"
            setTextColor(Color.BLACK)
            textSize = 15f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setBackgroundResource(R.drawable.btn_spotify_primary)
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * dp).toInt()
            }
            setOnClickListener { checkAndRequestShizuku() }
        }
        rootLayout.addView(shizukuBtn)

        val notifAccessBtn = Button(this).apply {
            text = "Grant Notification Access"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setBackgroundResource(R.drawable.btn_spotify_secondary)
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * dp).toInt()
            }
            setOnClickListener { requestNotificationListenerPermission() }
        }
        rootLayout.addView(notifAccessBtn)

        val manualToggleBtn = Button(this).apply {
            text = "Toggle Cover Display Mode"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setBackgroundResource(R.drawable.btn_spotify_secondary)
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (28 * dp).toInt()
            }
            setOnClickListener {
                if (presentation == null) {
                    showPresentation()
                } else {
                    dismissPresentation()
                }
            }
        }
        rootLayout.addView(manualToggleBtn)

        // Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_spotify_background)
            setPadding((20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt(), (20 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cardTitle = TextView(this).apply {
            text = "SYSTEM STATUS"
            setTextColor(Color.parseColor("#1DB954"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setPadding(0, 0, 0, (8 * dp).toInt())
        }
        statusCard.addView(cardTitle)

        logView = TextView(this).apply {
            text = "Initializing status..."
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(4 * dp, 1.0f)
        }
        statusCard.addView(logView)

        rootLayout.addView(statusCard)
        setContentView(rootLayout)

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
            logView.text = "Shizuku listener registration error: ${e.message}"
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndServices()
    }

    private fun checkPermissionsAndServices() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotifAccess = isNotificationListenerGranted()

        if (!hasOverlay) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        } else {
            startService(Intent(this, FloatingBubbleService::class.java))
        }

        if (!hasNotifAccess) {
            logView.text = "WARNING: Notification Access missing! Tap 'Grant Notification Access' to allow media control."
        } else {
            CoverNotificationListener.requestRebind(this)
            logView.text = "All permissions granted! Waiting for Shizuku / Cover events..."
            checkAndRequestShizuku()
        }
    }

    private fun isNotificationListenerGranted(): Boolean {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val componentName = ComponentName(this, CoverNotificationListener::class.java)
        return notificationManager.isNotificationListenerAccessGranted(componentName)
    }

    private fun requestNotificationListenerPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
    }

    private fun checkAndRequestShizuku() {
        try {
            if (Shizuku.isPreV11()) {
                logView.text = "Unsupported Shizuku version (requires v11+)."
                return
            }

            val isAlive = Shizuku.pingBinder()
            if (!isAlive) {
                logView.text = "Pinging Shizuku service..."
            }

            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                logView.text = "Shizuku authorized! Sensor daemon active."
                startHingeAngleDaemon()
            } else {
                logView.text = "Requesting Shizuku authorization..."
                // This triggers the system dialog or registers the app in Shizuku's list
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            }
        } catch (e: Exception) {
            logView.text = "Shizuku check error: ${e.message}"
        }
    }

    private fun startHingeAngleDaemon() {
        if (isDaemonRunning) return
        isDaemonRunning = true

        thread(isDaemon = true) {
            var isCoverActive = false
            val targetAngle = 85.0

            while (!isFinishing && isDaemonRunning) {
                try {
                    if (ShizukuShell.isReady()) {
                        val raw = ShizukuShell.exec("cat /sys/devices/virtual/sensors/sub_accelerometer_sensor/read_angle_data 2>/dev/null")
                        val deg = raw.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0

                        if (deg >= targetAngle && !isCoverActive) {
                            isCoverActive = true
                            runOnUiThread {
                                logView.text = "Hinge: ${deg}° -> State 4 Active"
                                showPresentation()
                            }
                        } else if (deg < (targetAngle - 10) && isCoverActive) {
                            isCoverActive = false
                            runOnUiThread {
                                logView.text = "Hinge: ${deg}° -> State Reset"
                                dismissPresentation()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Thread.sleep(120)
            }
        }
    }

    fun showPresentation() {
        ShizukuShell.setCoverStateConcurrent()

        val cover = displayManager.displays.firstOrNull { it.displayId == 1 }
        if (cover == null) {
            logView.postDelayed({ showPresentation() }, 150)
            return
        }

        if (presentation == null) {
            presentation = CoverPresentation(this, cover).apply {
                setOnDismissListener { presentation = null }
                show()
            }
        }
    }

    fun updateCoverPresentationSettings(brightness: Float, amoled: Boolean?) {
        presentation?.updateSettings(brightness, amoled)
    }

    fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
        ShizukuShell.resetDeviceState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        presentation?.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
        isDaemonRunning = false
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        dismissPresentation()
        instance = null
    }
}
