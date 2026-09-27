package com.barton.dualscreenhost

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class InnerCurtainManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var curtainView: View? = null
    var isCurtainActive: Boolean = false
        private set

    var onCurtainStateChanged: ((Boolean) -> Unit)? = null

    fun toggleCurtain(): Boolean {
        if (isCurtainActive) dismissCurtain() else showCurtain()
        return isCurtainActive
    }

    fun showCurtain() {
        if (curtainView != null) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            screenBrightness = 0.0f // Force minimal AMOLED power draw
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val doubleTapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                dismissCurtain()
                return true
            }
        })

        val view = View(context).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, event ->
                doubleTapDetector.onTouchEvent(event)
                true
            }
        }

        try {
            windowManager.addView(view, layoutParams)
            curtainView = view
            isCurtainActive = true
            onCurtainStateChanged?.invoke(true)
        } catch (e: Exception) {
            Log.e("InnerCurtain", "Failed to add curtain overlay", e)
        }
    }

    fun dismissCurtain() {
        curtainView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e("InnerCurtain", "Failed to remove curtain overlay", e)
            }
            curtainView = null
            isCurtainActive = false
            onCurtainStateChanged?.invoke(false)
        }
    }
}
