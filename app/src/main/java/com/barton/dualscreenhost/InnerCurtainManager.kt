package com.barton.dualscreenhost

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

class InnerCurtainManager(private val context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var curtainView: View? = null
    private var defaultWindowManager: WindowManager? = null

    var isCurtainActive: Boolean = false
        private set

    var onCurtainStateChanged: ((Boolean) -> Unit)? = null

    fun toggleCurtain(): Boolean {
        if (isCurtainActive) dismissCurtain() else showCurtain()
        return isCurtainActive
    }

    fun showCurtain() {
        if (curtainView != null) return

        try {
            // Target the default primary inner display (ID 0)
            val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return

            val targetContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.createDisplayContext(defaultDisplay)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                @Suppress("DEPRECATION")
                context.createDisplayContext(defaultDisplay)
            }

            defaultWindowManager = targetContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            ).apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            val doubleTapDetector = GestureDetector(targetContext, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    dismissCurtain()
                    return true
                }
            })

            val blackView = View(targetContext).apply {
                setBackgroundColor(Color.BLACK)
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            v.windowInsetsController?.apply {
                                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            v.systemUiVisibility = (
                                View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            )
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })

                setOnTouchListener { _, event ->
                    doubleTapDetector.onTouchEvent(event)
                    true
                }
            }

            defaultWindowManager?.addView(blackView, params)
            curtainView = blackView
            isCurtainActive = true
            onCurtainStateChanged?.invoke(true)
            Log.d("InnerCurtain", "Inner display curtain attached successfully")
        } catch (e: Exception) {
            Log.e("InnerCurtain", "Failed to deploy curtain on inner display", e)
        }
    }

    fun dismissCurtain() {
        curtainView?.let {
            try {
                defaultWindowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e("InnerCurtain", "Failed to remove curtain", e)
            }
            curtainView = null
            isCurtainActive = false
            onCurtainStateChanged?.invoke(false)
        }
    }
}
