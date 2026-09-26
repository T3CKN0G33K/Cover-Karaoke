package com.barton.dualscreenhost.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import com.barton.dualscreenhost.R
import kotlin.math.abs

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var dismissView: View? = null
    private var quickSettingsView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var dismissParams: WindowManager.LayoutParams
    private lateinit var quickSettingsParams: WindowManager.LayoutParams

    private var isCoverScreenActive = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLongPressed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setupDismissTarget()
        setupFloatingBubble()
    }

    private fun setupDismissTarget() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }

        dismissView = LayoutInflater.from(this).inflate(R.layout.layout_dismiss_target, null)
        windowManager.addView(dismissView, dismissParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingBubble() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        bubbleView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)
        val icon = bubbleView?.findViewById<ImageView>(R.id.ivBubbleIcon)

        val longPressRunnable = Runnable {
            isLongPressed = true
            showQuickSettings()
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private val CLICK_DRAG_TOLERANCE = 12f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isLongPressed = false
                        initialX = bubbleParams.x
                        initialY = bubbleParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        dismissView?.visibility = View.VISIBLE
                        mainHandler.postDelayed(longPressRunnable, 500)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (diffX > CLICK_DRAG_TOLERANCE || diffY > CLICK_DRAG_TOLERANCE) {
                            mainHandler.removeCallbacks(longPressRunnable)
                        }

                        bubbleParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        bubbleParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubbleView, bubbleParams)

                        if (quickSettingsView != null) {
                            updateQuickSettingsPosition()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        mainHandler.removeCallbacks(longPressRunnable)
                        val isDismissed = isOverlappingDismissArea(event.rawX, event.rawY)
                        dismissView?.visibility = View.GONE

                        // 1. Check if dropped in Dismiss Target area
                        if (isDismissed) {
                            dismissQuickSettings()
                            stopSelf() // Closes service and removes bubble
                            return true
                        }

                        // 2. Short Tap action
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (!isLongPressed && diffX < CLICK_DRAG_TOLERANCE && diffY < CLICK_DRAG_TOLERANCE) {
                            toggleCoverScreen(icon)
                        }
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(bubbleView, bubbleParams)
    }

    private fun isOverlappingDismissArea(rawX: Float, rawY: Float): Boolean {
        val target = dismissView?.findViewById<View>(R.id.ivDismissX) ?: return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val targetRect = Rect(
            location[0],
            location[1],
            location[0] + target.width,
            location[1] + target.height
        )
        return targetRect.contains(rawX.toInt(), rawY.toInt())
    }

    private fun toggleCoverScreen(icon: ImageView?) {
        isCoverScreenActive = !isCoverScreenActive

        if (isCoverScreenActive) {
            icon?.setBackgroundResource(R.drawable.bg_bubble_active)
            sendBroadcast(Intent("ACTION_START_COVER_DISPLAY").setPackage(packageName))
        } else {
            icon?.setBackgroundResource(R.drawable.bg_bubble_inactive)
            sendBroadcast(Intent("ACTION_STOP_COVER_DISPLAY").setPackage(packageName))
        }
    }

    private fun showQuickSettings() {
        if (quickSettingsView != null) return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        quickSettingsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (bubbleParams.x + 130).coerceAtMost(600)
            y = bubbleParams.y
        }

        quickSettingsView = LayoutInflater.from(this).inflate(R.layout.layout_bubble_quick_settings, null)

        val prefs = getSharedPreferences("cover_karaoke_prefs", MODE_PRIVATE)
        val isAmoled = prefs.getBoolean("pref_amoled_mode", false)
        val brightness = prefs.getFloat("pref_cover_brightness", 0.9f)

        val sbBrightness = quickSettingsView?.findViewById<SeekBar>(R.id.sbBrightness)
        val swAmoled = quickSettingsView?.findViewById<Switch>(R.id.swAmoled)
        val btnToggleDisplay = quickSettingsView?.findViewById<Button>(R.id.btnToggleDisplay)
        val ivClose = quickSettingsView?.findViewById<View>(R.id.ivCloseSettings)

        sbBrightness?.progress = (brightness * 100).toInt()
        swAmoled?.isChecked = isAmoled
        btnToggleDisplay?.text = if (isCoverScreenActive) "Sleep Display" else "Wake Display"

        sbBrightness?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val bVal = progress / 100f
                    prefs.edit().putFloat("pref_cover_brightness", bVal).apply()
                    val intent = Intent("ACTION_UPDATE_COVER_SETTINGS").apply {
                        setPackage(packageName)
                        putExtra("brightness", bVal)
                    }
                    sendBroadcast(intent)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        swAmoled?.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_amoled_mode", isChecked).apply()
            val intent = Intent("ACTION_UPDATE_COVER_SETTINGS").apply {
                setPackage(packageName)
                putExtra("amoled", isChecked)
            }
            sendBroadcast(intent)
        }

        btnToggleDisplay?.setOnClickListener {
            val icon = bubbleView?.findViewById<ImageView>(R.id.ivBubbleIcon)
            toggleCoverScreen(icon)
            btnToggleDisplay.text = if (isCoverScreenActive) "Sleep Display" else "Wake Display"
        }

        ivClose?.setOnClickListener {
            dismissQuickSettings()
        }

        windowManager.addView(quickSettingsView, quickSettingsParams)
    }

    private fun updateQuickSettingsPosition() {
        quickSettingsParams.x = (bubbleParams.x + 130).coerceAtMost(600)
        quickSettingsParams.y = bubbleParams.y
        try {
            quickSettingsView?.let { windowManager.updateViewLayout(it, quickSettingsParams) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissQuickSettings() {
        try {
            quickSettingsView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        quickSettingsView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissQuickSettings()
        try {
            bubbleView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            dismissView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
