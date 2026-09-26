package com.barton.dualscreenhost

import android.animation.ValueAnimator
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.SensorManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.palette.graphics.Palette
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class CoverPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

    private lateinit var rootContainer: FrameLayout
    private lateinit var backdropImageView: ImageView
    private lateinit var gradientOverlayView: View
    private lateinit var playerContainer: LinearLayout
    private lateinit var lyricsContainer: FrameLayout
    private lateinit var lyricsScrollView: ScrollView
    private lateinit var lyricsListLayout: LinearLayout

    private lateinit var albumArtCard: CardView
    private lateinit var albumArtView: ImageView
    private lateinit var trackTitleView: TextView
    private lateinit var artistView: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var miniPlayBtn: ImageButton
    private lateinit var miniLyricsTitleView: TextView
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var lyricsToggleBtn: ImageButton
    private lateinit var progressBar: SeekBar

    // Ambient HUD Mode Views
    private lateinit var ambientHudContainer: FrameLayout
    private lateinit var unifiedStatusView: UnifiedStatusView
    private var systemStatusManager: SystemStatusManager? = null
    private lateinit var clockTextView: TextView
    private lateinit var dateTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var ambientMiniTitleView: TextView
    private lateinit var ambientMiniPlayBtn: ImageButton
    private var isAmbientHudMode = false

    // AMOLED & Settings State
    private var isAmoledMode = false
    private var coverBrightness = 0.9f

    // Tent Mode Auto-Rotation (180° Flip)
    private var orientationListener: OrientationEventListener? = null
    private var isFlipped180 = false

    private var currentBitmap: Bitmap? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()

    private var isLyricsMode = false
    private var parsedLyrics = listOf<LyricLine>()
    private var lyricViews = mutableListOf<TextView>()
    private var currentLyricIndex = -1
    private var currentTrackName = ""
    private var currentArtistName = ""

    private var isPlaying = false
    private var lastPosition: Long = 0
    private var lastUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var trackDuration: Long = 0

    private val progressTicker = object : Runnable {
        override fun run() {
            if (isPlaying && trackDuration > 0) {
                val timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime
                val estimatedPos = (lastPosition + (timeDelta * playbackSpeed)).toLong()
                progressBar.progress = estimatedPos.coerceIn(0, trackDuration).toInt()
                if (isLyricsMode) {
                    syncKaraoke(estimatedPos)
                }
                mainHandler.postDelayed(this, 300)
            }
        }
    }

    private val clockTicker = object : Runnable {
        override fun run() {
            if (isAmbientHudMode && ::clockTextView.isInitialized) {
                updateAmbientHudInfo()
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = context.getSharedPreferences("cover_karaoke_prefs", Context.MODE_PRIVATE)
        isAmoledMode = prefs.getBoolean("pref_amoled_mode", false)
        coverBrightness = prefs.getFloat("pref_cover_brightness", 0.9f)

        window?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            attributes = attributes.apply { screenBrightness = coverBrightness }
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        rootContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#0A0A0E"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(rootContainer)

        setupGestureDetector()
        initOrientationListener()
        buildUI()
        initMediaManager()
    }

    private fun setupGestureDetector() {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleAmbientHudMode()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && abs(velocityX) > 1200) {
                    toggleAmbientHudMode()
                    return true
                }
                return false
            }
        })

        rootContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun initOrientationListener() {
        orientationListener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val isUpsideDown = orientation in 135..225
                val isNormal = (orientation in 0..45) || (orientation in 315..360)

                if (isUpsideDown && !isFlipped180) {
                    isFlipped180 = true
                    rootContainer.animate().rotation(180f).setDuration(300).start()
                } else if (isNormal && isFlipped180) {
                    isFlipped180 = false
                    rootContainer.animate().rotation(0f).setDuration(300).start()
                }
            }
        }
        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        }
    }

    private fun isDisplayLandscape(): Boolean {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels > metrics.heightPixels
    }

    private fun buildUI() {
        rootContainer.removeAllViews()
        val dp = context.resources.displayMetrics.density
        val landscape = isDisplayLandscape()

        // Background Layer 1: Blurred Album Art
        backdropImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.50f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(backdropImageView)

        // Background Layer 2: Vibrant Palette Mesh Gradient Overlay
        gradientOverlayView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(gradientOverlayView)

        currentBitmap?.let { applyDynamicBackdrop(it) }

        // 1. Standard Player Layout
        playerContainer = LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val hPad = if (landscape) (24 * dp).toInt() else (32 * dp).toInt()
            val vPad = if (landscape) (16 * dp).toInt() else (48 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val artSize = if (landscape) (170 * dp).toInt() else (250 * dp).toInt()
        albumArtCard = CardView(context).apply {
            radius = 24 * dp
            cardElevation = 12 * dp
            setCardBackgroundColor(Color.parseColor("#1A1A1A"))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                if (landscape) {
                    marginEnd = (24 * dp).toInt()
                } else {
                    bottomMargin = (28 * dp).toInt()
                }
            }
            setOnClickListener { toggleLyricsMode() }
        }

        albumArtView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            currentBitmap?.let { setImageBitmap(it) }
        }
        albumArtCard.addView(albumArtView)
        playerContainer.addView(albumArtCard)

        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            layoutParams = if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        trackTitleView = TextView(context).apply {
            text = if (currentTrackName.isNotEmpty()) currentTrackName else "No Media"
            setTextColor(Color.WHITE)
            textSize = if (landscape) 22f else 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
        }
        infoCol.addView(trackTitleView)

        artistView = TextView(context).apply {
            text = if (currentArtistName.isNotEmpty()) currentArtistName else "---"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 15f
            setPadding(0, (2 * dp).toInt(), 0, if (landscape) (8 * dp).toInt() else (16 * dp).toInt())
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
        }
        infoCol.addView(artistView)

        progressBar = SeekBar(context).apply {
            setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), if (landscape) (8 * dp).toInt() else (14 * dp).toInt())
            progressDrawable?.setTint(Color.parseColor("#1DB954"))
            thumb?.setTint(Color.WHITE)
            isEnabled = false
            max = trackDuration.toInt()
            progress = lastPosition.toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        infoCol.addView(progressBar)

        val pillBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.pill_control_background)
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (!landscape) gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        prevBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        }
        pillBar.addView(prevBtn)

        playPauseBtn = ImageButton(context).apply {
            setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.BLACK)
            setBackgroundResource(R.drawable.play_button_background)
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            setOnClickListener {
                if (isPlaying) activeController?.transportControls?.pause() else activeController?.transportControls?.play()
            }
        }
        pillBar.addView(playPauseBtn)

        nextBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { activeController?.transportControls?.skipToNext() }
        }
        pillBar.addView(nextBtn)

        lyricsToggleBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_lyrics)
            setColorFilter(Color.parseColor("#CCFFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener { toggleLyricsMode() }
        }
        pillBar.addView(lyricsToggleBtn)

        infoCol.addView(pillBar)
        playerContainer.addView(infoCol)
        rootContainer.addView(playerContainer)

        buildLyricsView(dp)
        buildAmbientHudView(dp)
    }

    private fun buildLyricsView(dp: Float) {
        lyricsContainer = FrameLayout(context).apply {
            visibility = if (isLyricsMode) View.VISIBLE else View.GONE
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        lyricsScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val landscape = isDisplayLandscape()
        val hPad = if (landscape) (32 * dp).toInt() else (24 * dp).toInt()
        val topPad = (140 * dp).toInt()
        val bottomPad = (220 * dp).toInt()

        lyricsListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(hPad, topPad, hPad, bottomPad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        lyricsScrollView.addView(lyricsListLayout)
        lyricsContainer.addView(lyricsScrollView)

        // Apple Music Style Floating Bottom Mini Control Pill
        val bottomLyricsBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.pill_control_background)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (24 * dp).toInt()
            }
        }

        miniPlayBtn = ImageButton(context).apply {
            setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.BLACK)
            setBackgroundResource(R.drawable.play_button_background)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener {
                if (isPlaying) activeController?.transportControls?.pause() else activeController?.transportControls?.play()
            }
        }
        bottomLyricsBar.addView(miniPlayBtn)

        miniLyricsTitleView = TextView(context).apply {
            text = if (currentTrackName.isNotEmpty()) currentTrackName else "Lyrics"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        bottomLyricsBar.addView(miniLyricsTitleView)

        val closeLyricsBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener { toggleLyricsMode() }
        }
        bottomLyricsBar.addView(closeLyricsBtn)

        lyricsContainer.addView(bottomLyricsBar)
        rootContainer.addView(lyricsContainer)
    }

    private fun buildAmbientHudView(dp: Float) {
        ambientHudContainer = FrameLayout(context).apply {
            visibility = if (isAmbientHudMode) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val modeTip = TextView(context).apply {
            text = "Desk Ambient HUD  •  Double-tap to switch"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = (24 * dp).toInt()
            }
        }
        ambientHudContainer.addView(modeTip)

        val centerCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        clockTextView = TextView(context).apply {
            text = "10:42 PM"
            setTextColor(Color.WHITE)
            textSize = 56f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        centerCol.addView(clockTextView)

        dateTextView = TextView(context).apply {
            text = "Monday, October 24"
            setTextColor(Color.parseColor("#A7A7A7"))
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        }
        centerCol.addView(dateTextView)

        batteryTextView = TextView(context).apply {
            text = "⚡ 85% Battery"
            setTextColor(Color.parseColor("#1DB954"))
            textSize = 15f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        centerCol.addView(batteryTextView)

        ambientHudContainer.addView(centerCol)

        val bottomHudBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.pill_control_background)
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (24 * dp).toInt()
            }
        }

        ambientMiniPlayBtn = ImageButton(context).apply {
            setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.BLACK)
            setBackgroundResource(R.drawable.play_button_background)
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            setOnClickListener {
                if (isPlaying) activeController?.transportControls?.pause() else activeController?.transportControls?.play()
            }
        }
        bottomHudBar.addView(ambientMiniPlayBtn)

        ambientMiniTitleView = TextView(context).apply {
            text = if (currentTrackName.isNotEmpty()) "$currentTrackName • $currentArtistName" else "Cover Karaoke"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        }
        bottomHudBar.addView(ambientMiniTitleView)

        ambientHudContainer.addView(bottomHudBar)

        unifiedStatusView = UnifiedStatusView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                (52 * dp).toInt(),
                (52 * dp).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (20 * dp).toInt()
                marginEnd = (20 * dp).toInt()
            }
        }
        ambientHudContainer.addView(unifiedStatusView)
        systemStatusManager = SystemStatusManager(context, unifiedStatusView)

        rootContainer.addView(ambientHudContainer)

        updateAmbientHudInfo()
    }

    private fun toggleAmbientHudMode() {
        isAmbientHudMode = !isAmbientHudMode

        if (isAmbientHudMode) {
            playerContainer.visibility = View.GONE
            lyricsContainer.visibility = View.GONE
            ambientHudContainer.visibility = View.VISIBLE
            unifiedStatusView.startMorphAnimation()
            systemStatusManager?.start()
            mainHandler.removeCallbacks(clockTicker)
            mainHandler.post(clockTicker)
        } else {
            ambientHudContainer.visibility = View.GONE
            systemStatusManager?.stop()
            mainHandler.removeCallbacks(clockTicker)
            playerContainer.visibility = if (isLyricsMode) View.GONE else View.VISIBLE
            lyricsContainer.visibility = if (isLyricsMode) View.VISIBLE else View.GONE
        }
    }

    private fun updateAmbientHudInfo() {
        if (!::clockTextView.isInitialized) return

        val sdfClock = SimpleDateFormat("h:mm a", Locale.getDefault())
        val sdfDate = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        val now = Date()

        clockTextView.text = sdfClock.format(now)
        dateTextView.text = sdfDate.format(now)
        batteryTextView.text = getBatteryStatusString()
    }

    private fun getBatteryStatusString(): String {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
            val icon = if (isCharging) "⚡ " else "🔋 "
            "$icon$pct% Battery"
        } catch (e: Exception) {
            "🔋 85% Battery"
        }
    }

    private fun toggleLyricsMode() {
        isLyricsMode = !isLyricsMode
        playerContainer.visibility = if (isLyricsMode) View.GONE else View.VISIBLE
        lyricsContainer.visibility = if (isLyricsMode) View.VISIBLE else View.GONE
    }

    private fun sanitizeTitle(raw: String): String {
        return raw
            .replace(Regex("(?i)\\s*\\(?(feat\\.|ft\\.|featuring).*\\)?"), "")
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s*\\[[^]]*\\]"), "")
            .replace(Regex("(?i)\\s*-\\s*(remastered|radio edit|bonus track|extended|mix).*"), "")
            .trim()
    }

    private fun sanitizeArtist(raw: String): String {
        return raw.split(",", ";", "/", "feat.", "ft.").firstOrNull()?.trim() ?: raw.trim()
    }

    private fun fetchLyrics(rawTrack: String, rawArtist: String) {
        parsedLyrics = emptyList()
        lyricViews.clear()
        lyricsListLayout.removeAllViews()

        val cleanTrack = sanitizeTitle(rawTrack)
        val cleanArtist = sanitizeArtist(rawArtist)

        val loadingView = TextView(context).apply {
            text = "Fetching lyrics for:\n\"$cleanTrack\" by $cleanArtist..."
            setTextColor(Color.GRAY)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 48, 0, 48)
        }
        lyricsListLayout.addView(loadingView)

        val targetUrl = "https://lrclib.net/api/get?track_name=${URLEncoder.encode(cleanTrack, "UTF-8")}&artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}"

        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "FoldCoverDisplay/1.0 (Android)")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { 
                    loadingView.text = "Network error: Check internet connection" 
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    processLyricsJson(body, loadingView)
                } else {
                    querySearchFallback(cleanTrack, cleanArtist, loadingView)
                }
            }
        })
    }

    private fun querySearchFallback(track: String, artist: String, loadingView: TextView) {
        val query = "$track $artist".trim()
        val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(query, "UTF-8")}"

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "FoldCoverDisplay/1.0 (Android)")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { loadingView.text = "Lyrics not found for \"$track\"" }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                mainHandler.post {
                    try {
                        val array = JSONArray(body)
                        var foundSynced = ""
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            val synced = item.optString("syncedLyrics", "")
                            if (synced.isNotEmpty()) {
                                foundSynced = synced
                                break
                            }
                        }

                        if (foundSynced.isNotEmpty()) {
                            parseLrc(foundSynced)
                        } else {
                            loadingView.text = "Synced lyrics not available for this track"
                        }
                    } catch (e: Exception) {
                        loadingView.text = "Lyrics not available"
                    }
                }
            }
        })
    }

    private fun processLyricsJson(body: String, loadingView: TextView) {
        mainHandler.post {
            try {
                val json = JSONObject(body)
                val synced = json.optString("syncedLyrics", "")
                val plain = json.optString("plainLyrics", "")

                when {
                    synced.isNotEmpty() -> parseLrc(synced)
                    plain.isNotEmpty() -> parsePlainLyrics(plain)
                    else -> loadingView.text = "Instrumental or lyrics unavailable"
                }
            } catch (e: Exception) {
                loadingView.text = "Failed to parse lyrics response"
            }
        }
    }

    private fun parsePlainLyrics(plainText: String) {
        lyricsListLayout.removeAllViews()
        val dp = context.resources.displayMetrics.density

        val notice = TextView(context).apply {
            text = "(Static Lyrics - No Sync Available)\n"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (16 * dp).toInt())
        }
        lyricsListLayout.addView(notice)

        val landscape = isDisplayLandscape()
        val alignGravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL

        plainText.lines().forEach { line ->
            if (line.isNotBlank()) {
                val tv = TextView(context).apply {
                    text = line
                    textSize = 22f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    gravity = alignGravity
                    setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                }
                lyricsListLayout.addView(tv)
            }
        }
    }

    private var ambientGlowAnimator: ValueAnimator? = null

    private fun initAmbientGlowAnimation() {
        if (ambientGlowAnimator != null) return

        ambientGlowAnimator = ValueAnimator.ofFloat(0.65f, 0.85f).apply {
            duration = 3800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { anim ->
                if (!isAmoledMode && ::gradientOverlayView.isInitialized) {
                    gradientOverlayView.alpha = anim.animatedValue as Float
                }
            }
            start()
        }
    }

    private fun parseLrc(lrc: String) {
        lyricsListLayout.removeAllViews()
        val dp = context.resources.displayMetrics.density

        parsedLyrics = EnhancedLrcParser.parse(lrc)
        lyricViews.clear()

        val landscape = isDisplayLandscape()
        val alignGravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL

        parsedLyrics.forEach { line ->
            val tv = TextView(context).apply {
                text = line.text
                textSize = 20f
                setTextColor(Color.WHITE)
                alpha = 0.40f
                scaleX = 1.0f
                scaleY = 1.0f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = alignGravity
                setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRenderEffect(RenderEffect.createBlurEffect(3.5f, 3.5f, Shader.TileMode.CLAMP))
                }

                setOnClickListener {
                    activeController?.transportControls?.seekTo(line.timeMs)
                    currentLyricIndex = -1
                    syncKaraoke(line.timeMs)
                }
            }
            lyricViews.add(tv)
            lyricsListLayout.addView(tv)
        }
    }

    private fun syncKaraoke(currentMs: Long) {
        if (parsedLyrics.isEmpty() || lyricViews.isEmpty()) return

        var activeIndex = -1
        for (i in parsedLyrics.indices) {
            if (currentMs >= parsedLyrics[i].timeMs) {
                activeIndex = i
            } else {
                break
            }
        }

        if (activeIndex in lyricViews.indices) {
            val activeLine = parsedLyrics[activeIndex]
            val activeView = lyricViews[activeIndex]

            // Syllable / Word Wiping for active line
            if (activeLine.words.isNotEmpty()) {
                activeView.text = WordHighlightHelper.formatHighlightedWordText(activeLine, currentMs)
            }

            if (activeIndex != currentLyricIndex) {
                currentLyricIndex = activeIndex
                lyricViews.forEachIndexed { index, view ->
                    val isActive = (index == activeIndex)
                    animateLyricRow(view, isActive)

                    if (isActive) {
                        val targetY = view.top - (lyricsScrollView.height / 2) + (view.height / 2)
                        lyricsScrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                    }
                }
            }
        }
    }

    private fun animateLyricRow(view: TextView, isActive: Boolean) {
        val targetSize = if (isActive) 26f else 20f
        val targetAlpha = if (isActive) 1.0f else 0.40f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isActive) {
                view.setRenderEffect(null)
            } else {
                view.setRenderEffect(RenderEffect.createBlurEffect(3.5f, 3.5f, Shader.TileMode.CLAMP))
            }
        }

        view.typeface = if (isActive) Typeface.create("sans-serif-black", Typeface.BOLD)
                        else Typeface.create("sans-serif-medium", Typeface.NORMAL)

        val currentSizeSp = view.textSize / view.resources.displayMetrics.scaledDensity
        if (abs(currentSizeSp - targetSize) > 0.5f) {
            ValueAnimator.ofFloat(currentSizeSp, targetSize).apply {
                duration = 220
                addUpdateListener { anim ->
                    view.textSize = anim.animatedValue as Float
                }
                start()
            }
        } else {
            view.textSize = targetSize
        }

        view.animate()
            .alpha(targetAlpha)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(220)
            .start()
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        if (metadata == null) {
            if (activeController == null) {
                trackTitleView.text = "No Active Media"
                artistView.text = "Play music on Spotify or YouTube"
            }
            return
        }

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Track"
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: "Unknown Artist"
        trackDuration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        progressBar.max = trackDuration.toInt()

        if (title != currentTrackName || artist != currentArtistName) {
            currentTrackName = title
            currentArtistName = artist
            if (::miniLyricsTitleView.isInitialized) {
                miniLyricsTitleView.text = title
            }
            if (::ambientMiniTitleView.isInitialized) {
                ambientMiniTitleView.text = "$title • $artist"
            }
            fetchLyrics(title, artist)
        }

        trackTitleView.text = title
        artistView.text = artist

        currentBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (currentBitmap != null) {
            albumArtView.setImageBitmap(currentBitmap)
            applyDynamicBackdrop(currentBitmap!!)
        }
    }

    private fun applyDynamicBackdrop(bitmap: Bitmap) {
        if (!::backdropImageView.isInitialized || !::gradientOverlayView.isInitialized) return

        if (isAmoledMode) {
            ambientGlowAnimator?.cancel()
            backdropImageView.visibility = View.GONE
            gradientOverlayView.background = ColorDrawable(Color.BLACK)
            gradientOverlayView.alpha = 1.0f
            rootContainer.setBackgroundColor(Color.BLACK)
            return
        }

        backdropImageView.visibility = View.VISIBLE
        initAmbientGlowAnimation()

        try {
            val smallArt = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            backdropImageView.setImageBitmap(smallArt)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                backdropImageView.setRenderEffect(
                    RenderEffect.createBlurEffect(
                        50f, 50f, Shader.TileMode.CLAMP
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        Palette.from(bitmap).generate { palette ->
            val vibrant = palette?.getVibrantColor(Color.parseColor("#2A1836"))
                ?: palette?.getDominantColor(Color.parseColor("#1A2036"))
                ?: Color.parseColor("#1F1B2E")

            val darkVibrant = palette?.getDarkVibrantColor(Color.parseColor("#120F1C"))
                ?: palette?.getDarkMutedColor(Color.parseColor("#0F101A"))
                ?: Color.parseColor("#0F101A")

            val pitchDark = Color.parseColor("#0A0A0E")

            val gradient = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(vibrant, darkVibrant, pitchDark)
            )

            gradientOverlayView.background = gradient
            if (ambientGlowAnimator?.isStarted != true) {
                ambientGlowAnimator?.start()
            }
        }
    }

    fun updateSettings(brightness: Float, amoled: Boolean?) {
        if (brightness >= 0f) {
            coverBrightness = brightness
            window?.attributes = window?.attributes?.apply {
                screenBrightness = brightness
            }
        }

        if (amoled != null && amoled != isAmoledMode) {
            isAmoledMode = amoled
            val prefs = context.getSharedPreferences("cover_karaoke_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("pref_amoled_mode", amoled).apply()

            currentBitmap?.let { applyDynamicBackdrop(it) } ?: run {
                if (isAmoledMode) {
                    backdropImageView.visibility = View.GONE
                    gradientOverlayView.background = ColorDrawable(Color.BLACK)
                    rootContainer.setBackgroundColor(Color.BLACK)
                }
            }
        }
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        if (state == null) return

        isPlaying = state.state == PlaybackState.STATE_PLAYING
        lastPosition = state.position
        lastUpdateTime = state.lastPositionUpdateTime
        playbackSpeed = state.playbackSpeed

        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        playPauseBtn.setImageResource(iconRes)
        if (::miniPlayBtn.isInitialized) {
            miniPlayBtn.setImageResource(iconRes)
        }
        if (::ambientMiniPlayBtn.isInitialized) {
            ambientMiniPlayBtn.setImageResource(iconRes)
        }

        if (isPlaying) {
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.post(progressTicker)
        } else {
            mainHandler.removeCallbacks(progressTicker)
            progressBar.progress = lastPosition.toInt()
        }
    }

    private var mediaRebindAttempts = 0

    private fun initMediaManager() {
        mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val componentName = ComponentName(context, CoverNotificationListener::class.java)

        CoverNotificationListener.requestRebind(context)

        try {
            val controllers = if (CoverNotificationListener.isConnected && CoverNotificationListener.instance != null) {
                CoverNotificationListener.instance?.getActiveMediaControllers()
            } else {
                mediaSessionManager?.getActiveSessions(componentName)
            }

            val bestController = controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers?.firstOrNull()

            mediaRebindAttempts = 0
            attachActiveController(bestController)

            try {
                mediaSessionManager?.addOnActiveSessionsChangedListener({ newControllers ->
                    val newBest = newControllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                        ?: newControllers?.firstOrNull()
                    attachActiveController(newBest)
                }, componentName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } catch (e: SecurityException) {
            if (mediaRebindAttempts < 5) {
                mediaRebindAttempts++
                trackTitleView.text = "Connecting Listener..."
                artistView.text = "Binding notification access ($mediaRebindAttempts/5)..."
                mainHandler.postDelayed({ initMediaManager() }, 600)
            } else {
                trackTitleView.text = "Permission Missing"
                artistView.text = "Tap here to Grant Notification Access"
                rootContainer.setOnClickListener {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                }
            }
        }
    }

    private fun attachActiveController(controller: MediaController?) {
        activeController?.unregisterCallback(mediaCallback)
        activeController = controller
        activeController?.registerCallback(mediaCallback)

        mainHandler.post {
            if (activeController == null) {
                trackTitleView.text = "No Active Media"
                artistView.text = "Play music on Spotify or YouTube"
            } else {
                updateMetadata(activeController?.metadata)
                updatePlaybackState(activeController?.playbackState)
            }
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        buildUI()
        activeController?.metadata?.let { updateMetadata(it) }
        activeController?.playbackState?.let { updatePlaybackState(it) }
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.removeCallbacks(clockTicker)
        systemStatusManager?.stop()
        orientationListener?.disable()
        activeController?.unregisterCallback(mediaCallback)
    }
}
