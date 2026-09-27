package com.barton.dualscreenhost

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.hypot

class AudioReactiveEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioReactiveEngine"
    }

    private var visualizer: Visualizer? = null
    private var isRunning = false
    private var smoothedBass = 0f

    var onPulseUpdate: ((bassIntensity: Float) -> Unit)? = null

    fun start() {
        if (isRunning) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start Visualizer: RECORD_AUDIO permission not granted")
            return
        }

        try {
            // Attach to audio session 0 (global audio output mix)
            visualizer = Visualizer(0).apply {
                enabled = false
                captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256)

                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {}

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            if (fft != null && isRunning) {
                                processFft(fft)
                            }
                        }
                    },
                    Visualizer.getMaxCaptureRate() / 2,
                    false,
                    true
                )

                enabled = true
            }
            isRunning = true
            Log.d(TAG, "AudioReactiveEngine Visualizer started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Visualizer: ${e.message}", e)
            stop()
        }
    }

    private fun processFft(fft: ByteArray) {
        if (fft.size < 10) return

        // Extract lower frequency sub-bass/bass energy (bins 1 to 4)
        var totalMag = 0f
        var count = 0

        for (i in 1..4) {
            val realIndex = 2 * i
            val imagIndex = 2 * i + 1

            if (imagIndex < fft.size) {
                val real = fft[realIndex].toFloat()
                val imag = fft[imagIndex].toFloat()
                val mag = hypot(real, imag)
                totalMag += mag
                count++
            }
        }

        val avgMag = if (count > 0) totalMag / count else 0f
        // Normalize magnitude to 0.0f..1.0f
        val rawBass = (avgMag / 80f).coerceIn(0f, 1f)

        // Asymmetric filter: Instant attack on peaks, exponential decay to prevent jitter
        smoothedBass = if (rawBass > smoothedBass) {
            rawBass
        } else {
            smoothedBass * 0.82f + rawBass * 0.18f
        }

        onPulseUpdate?.invoke(smoothedBass)
    }

    fun stop() {
        isRunning = false
        try {
            visualizer?.apply {
                enabled = false
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Visualizer: ${e.message}", e)
        }
        visualizer = null
        smoothedBass = 0f
        onPulseUpdate?.invoke(0f)
    }
}
