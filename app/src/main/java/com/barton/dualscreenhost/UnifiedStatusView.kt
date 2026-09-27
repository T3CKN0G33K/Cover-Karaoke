package com.barton.dualscreenhost

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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

    private var pulseAnimator: ValueAnimator? = null

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
        val minDim = min(w, h)
        val scale = minDim / 400f

        canvas.save()
        canvas.translate((w - 400f * scale) / 2f, (h - 400f * scale) / 2f)
        canvas.scale(scale, scale)

        val cx = 200f
        val cy = 200f

        val primaryColor = Color.WHITE
        val trackColor = Color.argb(55, 255, 255, 255)

        val isLowBattery = batteryPct <= 20
        val activeArcColor = when {
            isCharging -> Color.parseColor("#1DB954")
            isLowBattery -> Color.parseColor("#FF3B30")
            else -> primaryColor
        }

        // --- 1. EXACT DUO WIDGET BATTERY ARC ---
        val arcRadius = 140f
        val arcOval = RectF(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 20f
            strokeCap = Paint.Cap.ROUND
            color = trackColor
        }

        val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 20f
            strokeCap = Paint.Cap.ROUND
            color = activeArcColor
            alpha = (chargingPulseAlpha * 255).toInt()
        }

        val clampedPct = (batteryPct / 100f).coerceIn(0f, 1f)

        // Continuous Arc: Starts at 142° and sweeps 256°
        val startAngle = 142f
        val totalSweep = 256f
        canvas.drawArc(arcOval, startAngle, totalSweep, false, trackPaint)

        val activeSweep = totalSweep * clampedPct
        if (activeSweep > 0f) {
            canvas.drawArc(arcOval, startAngle, activeSweep, false, activePaint)
        }

        // --- 2. EXACT DUO WIDGET WI-FI GLYPH ---
        val clampedWifi = wifiLevel.coerceIn(0, 3)
        val wifiMatrix = Matrix()
        val wifiScale = 3.6f
        wifiMatrix.setScale(wifiScale, wifiScale)
        wifiMatrix.postTranslate(cx - (18f * wifiScale), (cy - (15f * wifiScale)) + 12f)

        val wifiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        // 1. Top Wave (Bold Cubic Path)
        val topPath = Path().apply {
            moveTo(2.0f, 6.5f)
            cubicTo(10.5f, -2.0f, 25.5f, -2.0f, 34.0f, 6.5f)
            cubicTo(34.8f, 7.3f, 34.8f, 8.5f, 34.0f, 9.3f)
            cubicTo(33.2f, 10.1f, 32.0f, 10.1f, 31.2f, 9.3f)
            cubicTo(23.8f, 1.8f, 12.2f, 1.8f, 4.8f, 9.3f)
            cubicTo(4.0f, 10.1f, 2.8f, 10.1f, 2.0f, 9.3f)
            cubicTo(1.2f, 8.5f, 1.2f, 7.3f, 2.0f, 6.5f)
            close()
            transform(wifiMatrix)
        }
        wifiPaint.color = if (clampedWifi >= 3) primaryColor else trackColor
        canvas.drawPath(topPath, wifiPaint)

        // 2. Middle Wave (Bold Cubic Path)
        val midPath = Path().apply {
            moveTo(7.0f, 12.5f)
            cubicTo(13.0f, 6.8f, 23.0f, 6.8f, 29.0f, 12.5f)
            cubicTo(29.8f, 13.3f, 29.8f, 14.5f, 29.0f, 15.3f)
            cubicTo(28.2f, 16.1f, 27.0f, 16.1f, 26.2f, 15.3f)
            cubicTo(21.2f, 10.5f, 14.8f, 10.5f, 9.8f, 15.3f)
            cubicTo(9.0f, 16.1f, 7.8f, 16.1f, 7.0f, 15.3f)
            cubicTo(6.2f, 14.5f, 6.2f, 13.3f, 7.0f, 12.5f)
            close()
            transform(wifiMatrix)
        }
        wifiPaint.color = if (clampedWifi >= 2) primaryColor else trackColor
        canvas.drawPath(midPath, wifiPaint)

        // 3. Bottom Rounded Wedge (Cubic Path)
        val bottomPath = Path().apply {
            moveTo(12.5f, 19.0f)
            cubicTo(15.5f, 16.0f, 20.5f, 16.0f, 23.5f, 19.0f)
            cubicTo(24.3f, 19.8f, 24.3f, 21.0f, 23.5f, 21.8f)
            cubicTo(20.8f, 24.5f, 19.0f, 26.5f, 18.0f, 26.5f)
            cubicTo(17.0f, 26.5f, 15.2f, 24.5f, 12.5f, 21.8f)
            cubicTo(11.7f, 21.0f, 11.7f, 19.8f, 12.5f, 19.0f)
            close()
            transform(wifiMatrix)
        }
        wifiPaint.color = if (clampedWifi >= 1) primaryColor else trackColor
        canvas.drawPath(bottomPath, wifiPaint)

        // --- 3. EXACT DUO WIDGET CELLULAR INDICATOR DOTS ---
        val dotOrbitRadius = arcRadius // 140f
        val angles = listOf(122.0, 101.0, 79.0, 58.0)

        angles.forEachIndexed { index, angleDeg ->
            val rad = Math.toRadians(angleDeg)
            val dotX = (cx + (dotOrbitRadius * cos(rad))).toFloat()
            val dotY = (cy + (dotOrbitRadius * sin(rad))).toFloat()

            val isDotActive = (index + 1) <= cellLevel
            val cellDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (isDotActive) primaryColor else trackColor
            }
            canvas.drawCircle(dotX, dotY, 12f, cellDotPaint)
        }

        canvas.restore()
    }
}
