package com.barton.dualscreenhost

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class UnifiedStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var batteryPct = 85
    private var isCharging = false
    private var wifiLevel = 3 // 0 to 3
    private var cellLevel = 4 // 0 to 4

    private var morphProgress = 1.0f
    private var chargingPulseAlpha = 1.0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ringBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#33FFFFFF")
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val rectF = RectF()
    private var pulseAnimator: ValueAnimator? = null

    init {
        val dp = resources.displayMetrics.density
        ringPaint.strokeWidth = 3.5f * dp
        ringBgPaint.strokeWidth = 3.5f * dp
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    fun setBatteryState(pct: Int, charging: Boolean) {
        batteryPct = pct.coerceIn(0, 100)
        isCharging = charging
        if (isCharging) {
            startChargingPulse()
        } else {
            pulseAnimator?.cancel()
            chargingPulseAlpha = 1.0f
        }
        invalidate()
    }

    fun setWifiLevel(level: Int) {
        wifiLevel = level.coerceIn(0, 3)
        invalidate()
    }

    fun setCellularLevel(level: Int) {
        cellLevel = level.coerceIn(0, 4)
        invalidate()
    }

    fun startMorphAnimation(onEnd: (() -> Unit)? = null) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                morphProgress = it.animatedValue as Float
                invalidate()
            }
        }
        animator.start()
    }

    private fun startChargingPulse() {
        if (pulseAnimator != null && pulseAnimator?.isStarted == true) return
        pulseAnimator = ValueAnimator.ofFloat(0.5f, 1.0f).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                chargingPulseAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val dp = resources.displayMetrics.density
        val radius = (min(w, h) / 2f) - (8f * dp)

        // 1. Draw Outer Battery Crescent Arc (Starts at 150°, sweeps 240° clockwise to 30°, leaving a clean 120° open bottom gap)
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, 150f, 240f, false, ringBgPaint)

        val batterySweep = (batteryPct / 100f) * 240f
        ringPaint.color = if (isCharging) Color.parseColor("#1DB954") else Color.WHITE
        ringPaint.alpha = (chargingPulseAlpha * 255).toInt()
        canvas.drawArc(rectF, 150f, batterySweep, false, ringPaint)

        // ----------------------------------------------------
        // 2. WI-FI ICON (Scaled down, centered, 2 bars + 1 pie wedge)
        // ----------------------------------------------------
        // Lowered anchor so the entire 3-tier icon centers in the upper bowl
        val wifiAnchorY = cy + dpToPx(0.5f)

        val wifiStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = dpToPx(1.8f) // Thin, sleek line weight
        }

        val wifiFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        // 1. Top Wave (Outer Stroke Arc)
        val r1 = dpToPx(10.5f)
        val rect1 = RectF(cx - r1, wifiAnchorY - r1, cx + r1, wifiAnchorY + r1)
        wifiStroke.alpha = if (wifiLevel >= 3) 255 else 60
        canvas.drawArc(rect1, 230f, 80f, false, wifiStroke)

        // 2. Middle Wave (Mid Stroke Arc)
        val r2 = dpToPx(7.0f)
        val rect2 = RectF(cx - r2, wifiAnchorY - r2, cx + r2, wifiAnchorY + r2)
        wifiStroke.alpha = if (wifiLevel >= 2) 255 else 60
        canvas.drawArc(rect2, 230f, 80f, false, wifiStroke)

        // 3. Bottom Base (Solid Pie Wedge, useCenter = true)
        val r3 = dpToPx(3.8f)
        val rect3 = RectF(cx - r3, wifiAnchorY - r3, cx + r3, wifiAnchorY + r3)
        wifiFill.alpha = if (wifiLevel >= 1) 255 else 60
        canvas.drawArc(rect3, 230f, 80f, true, wifiFill)

        // 3. Draw 4 Cellular Dots Along Concave Bottom Curve (Left-to-Right: 130° -> 50°)
        val cellularDotRadius = 2.4f * dp
        val dotArcRadius = radius - (1f * dp)
        val startAngle = 130.0
        val endAngle = 50.0

        for (i in 0 until 4) {
            val angleDeg = startAngle - (i * (startAngle - endAngle) / 3.0)
            val angleRad = Math.toRadians(angleDeg)
            val dotX = cx + (dotArcRadius * cos(angleRad)).toFloat()
            val dotY = cy + (dotArcRadius * sin(angleRad)).toFloat()

            dotPaint.alpha = if ((i + 1) <= cellLevel) 255 else 64
            canvas.drawCircle(dotX, dotY, cellularDotRadius, dotPaint)
        }
    }
}
