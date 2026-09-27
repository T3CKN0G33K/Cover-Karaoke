package com.barton.dualscreenhost

import android.animation.ValueAnimator
import android.app.Presentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.net.Uri
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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.widget.NestedScrollView
import androidx.palette.graphics.Palette
import coil.load
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

    // --- Root & Backdrop ---
    private lateinit var rootContainer: FrameLayout
    private lateinit var backdropImageView: ImageView
    private lateinit var gradientOverlayView: View

    // --- Vertical Feed Scroll Architecture ---
    private lateinit var mainNestedScrollView: NestedScrollView
    private lateinit var feedLayout: LinearLayout

    // --- Sticky Header (Pinned Top Bar on Scroll Down) ---
    private lateinit var stickyHeader: LinearLayout
    private lateinit var stickyTitleView: TextView
    private lateinit var stickyArtistView: TextView
    private lateinit var stickyHeartBtn: ImageButton
    private lateinit var stickyPlayPauseBtn: ImageButton
    private lateinit var stickyProgressLine: View
    private var isStickyHeaderVisible = false

    // --- Section A: Full Now Playing Screen ---
    private lateinit var sectionAPage: LinearLayout
    private lateinit var headerTimeView: TextView
    private lateinit var unifiedStatusView: UnifiedStatusView
    private lateinit var albumArtCard: CardView
    private lateinit var albumArtView: ImageView
    private lateinit var activeLyricPreviewTextView: TextView

    private lateinit var trackTitleView: TextView
    private lateinit var artistView: TextView
    private lateinit var heartBtn: ImageButton
    private lateinit var progressBar: SeekBar
    private lateinit var currentTimeView: TextView
    private lateinit var remainingTimeView: TextView

    private lateinit var playPauseBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton

    // --- Section B: Synced Lyrics Card ---
    private lateinit var sectionBCard: CardView
    private lateinit var lyricsScrollView: ScrollView
    private lateinit var lyricsListLayout: LinearLayout
    private lateinit var lyricsExpandBtn: ImageButton

    // --- Fullscreen Lyrics Overlay ---
    private lateinit var fullscreenLyricsContainer: FrameLayout
    private lateinit var fullscreenTitleView: TextView
    private lateinit var fullscreenArtistView: TextView
    private lateinit var fullscreenStatusView: UnifiedStatusView
    private lateinit var fullscreenLyricsScrollView: ScrollView
    private lateinit var fullscreenLyricsListLayout: LinearLayout
    private lateinit var fullscreenProgressBar: SeekBar
    private lateinit var fullscreenCurrentTimeView: TextView
    private lateinit var fullscreenRemainingTimeView: TextView
    private lateinit var fullscreenPlayPauseBtn: ImageButton
    private var fullscreenLyricViews = mutableListOf<TextView>()
    private var isFullScreenLyricsActive = false
    private var parsedLyrics = listOf<LyricLine>()
    private var lyricViews = mutableListOf<TextView>()
    private var currentLyricIndex = -1

    // --- Section C: Extra Info Feed Cards ---
    private lateinit var artistNotesTextView: TextView
    private lateinit var spotifyDockScrollView: HorizontalScrollView
    private lateinit var spotifyDockListLayout: LinearLayout

    // --- Engines & Managers ---
    private var audioReactiveEngine: AudioReactiveEngine? = null
    private var systemStatusManager: SystemStatusManager? = null
    private var spotifyManager: SpotifyManager? = null
    private var orientationListener: OrientationEventListener? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    // --- State & Utilities ---
    private var currentBitmap: Bitmap? = null
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var isUserScrubbing = false
    private var isUserScrubbingFullscreen = false
    private var isShuffleActive = false
    private var isRepeatActive = false
    private var isFavoriteActive = false

    private var lastPosition: Long = 0
    private var lastUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var trackDuration: Long = 0
    private var currentTrackName = ""
    private var currentArtistName = ""

    private var isAmoledMode = false
    private var coverBrightness = 0.9f
    private var isFlipped180 = false
    private var ambientGlowAnimator: ValueAnimator? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (isPlaying && trackDuration > 0) {
                val timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime
                val estimatedPos = (lastPosition + (timeDelta * playbackSpeed)).toLong().coerceIn(0, trackDuration)

                // Update Main Player Scrub Bar & Timestamps
                if (!isUserScrubbing && ::progressBar.isInitialized) {
                    progressBar.progress = estimatedPos.toInt()
                    currentTimeView.text = formatMs(estimatedPos)
                    remainingTimeView.text = formatRemainingMs(estimatedPos, trackDuration)
                    updateStickyProgressBar(estimatedPos, trackDuration)
                }

                // Update Fullscreen Lyrics Scrub Bar & Timestamps
                if (!isUserScrubbingFullscreen && ::fullscreenProgressBar.isInitialized && isFullScreenLyricsActive) {
                    fullscreenProgressBar.progress = estimatedPos.toInt()
                    fullscreenCurrentTimeView.text = formatMs(estimatedPos)
                    fullscreenRemainingTimeView.text = formatRemainingMs(estimatedPos, trackDuration)
                }

                syncKaraoke(estimatedPos)
                mainHandler.postDelayed(this, 300)
            }
        }
    }

    private val headerClockTicker = object : Runnable {
        override fun run() {
            if (::headerTimeView.isInitialized) {
                val sdfClock = SimpleDateFormat("h:mm a", Locale.getDefault())
                headerTimeView.text = sdfClock.format(Date())
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

        initOrientationListener()
        buildUI()
        initMediaManager()

        // Audio reactive engine for radial status dial pulse
        audioReactiveEngine = AudioReactiveEngine(context).apply {
            onPulseUpdate = { bassIntensity ->
                if (::unifiedStatusView.isInitialized) {
                    unifiedStatusView.setAudioBassIntensity(bassIntensity)
                }
            }
            start()
        }

        mainHandler.post(headerClockTicker)
    }

    private fun isDisplayLandscape(): Boolean {
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels > metrics.heightPixels
    }

    private fun buildUI() {
        rootContainer.removeAllViews()
        val dp = context.resources.displayMetrics.density

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

        // Background Layer 2: Palette Gradient Overlay
        gradientOverlayView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(gradientOverlayView)

        currentBitmap?.let { applyDynamicBackdrop(it) }

        // Root Vertical Scroll Feed Architecture
        mainNestedScrollView = NestedScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        feedLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Section A: Full Now Playing Screen
        buildSectionANowPlaying(dp)

        // Section B: Synced Karaoke Lyrics Card
        buildSectionBLyricsCard(dp)

        // Section C: Extra Info Feed Cards (About the Artist & Spotify Scrubber)
        buildSectionCExtraInfoCards(dp)

        mainNestedScrollView.addView(feedLayout)
        rootContainer.addView(mainNestedScrollView)

        // Pinned Sticky Mini-Player Header
        buildStickyHeader(dp)

        // Fullscreen Lyrics Expansion Overlay
        buildFullScreenLyricsOverlay(dp)

        // Monitor Scroll Y to Fade Sticky Header
        val displayHeight = context.resources.displayMetrics.heightPixels
        mainNestedScrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            val triggerThreshold = (displayHeight * 0.55f).toInt()
            if (scrollY > triggerThreshold && !isStickyHeaderVisible) {
                isStickyHeaderVisible = true
                stickyHeader.visibility = View.VISIBLE
                stickyHeader.animate().alpha(1.0f).setDuration(220).start()
            } else if (scrollY <= triggerThreshold && isStickyHeaderVisible) {
                isStickyHeaderVisible = false
                stickyHeader.animate().alpha(0f).setDuration(220).withEndAction {
                    stickyHeader.visibility = View.GONE
                }.start()
            }
        })
    }

    private fun buildSectionANowPlaying(dp: Float) {
        val landscape = isDisplayLandscape()
        val displayHeight = context.resources.displayMetrics.heightPixels

        sectionAPage = LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (landscape) ViewGroup.LayoutParams.WRAP_CONTENT else displayHeight
            )
        }

        // Section A Top Bar: Clock + Radial Dial
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        headerTimeView = TextView(context).apply {
            text = "10:42 PM"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        topBar.addView(headerTimeView)

        unifiedStatusView = UnifiedStatusView(context).apply {
            layoutParams = LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt())
        }
        topBar.addView(unifiedStatusView)

        systemStatusManager = SystemStatusManager(context, unifiedStatusView)
        systemStatusManager?.start()

        sectionAPage.addView(topBar)

        // Top Spacer 1 (weight 0.2f): Pulls album art down from camera cutout
        if (!landscape) {
            val topSpacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.2f)
            }
            sectionAPage.addView(topSpacer)
        }

        // 1:1 Square Album Art Container
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val artSize = if (landscape) {
            (160 * dp).toInt()
        } else {
            (screenWidthPx - (48 * dp).toInt()).coerceAtMost((280 * dp).toInt())
        }

        albumArtCard = CardView(context).apply {
            radius = 20 * dp
            cardElevation = 10 * dp
            setCardBackgroundColor(Color.parseColor("#181818"))
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply {
                if (landscape) {
                    marginEnd = (24 * dp).toInt()
                } else {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
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
        sectionAPage.addView(albumArtCard)

        // Middle Spacer 2 (weight 0.8f): Pushes controls down into lower third
        if (!landscape) {
            val midSpacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f)
            }
            sectionAPage.addView(midSpacer)
        }

        // Lower Third Section
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        // Active Lyric Snippet Preview Ticker
        activeLyricPreviewTextView = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (10 * dp).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { scrollToLyricsSection() }
        }
        infoCol.addView(activeLyricPreviewTextView)

        // Metadata Header Row (Title/Artist on Left, Favorite Heart on Right)
        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        trackTitleView = TextView(context).apply {
            text = if (currentTrackName.isNotEmpty()) currentTrackName else "No Media"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(trackTitleView)

        artistView = TextView(context).apply {
            text = if (currentArtistName.isNotEmpty()) currentArtistName else "---"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        textCol.addView(artistView)
        metaRow.addView(textCol)

        heartBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_heart)
            setColorFilter(if (isFavoriteActive) Color.parseColor("#1DB954") else Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener {
                isFavoriteActive = !isFavoriteActive
                setColorFilter(if (isFavoriteActive) Color.parseColor("#1DB954") else Color.WHITE)
                if (::stickyHeartBtn.isInitialized) {
                    stickyHeartBtn.setColorFilter(if (isFavoriteActive) Color.parseColor("#1DB954") else Color.WHITE)
                }
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        applyGlassPressAnimation(heartBtn)
        metaRow.addView(heartBtn)

        infoCol.addView(metaRow)

        // Interactive 3dp Scrub Bar
        progressBar = SeekBar(context).apply {
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            progressDrawable?.setTint(Color.parseColor("#1DB954"))
            thumb?.setTint(Color.WHITE)
            isEnabled = true
            max = trackDuration.toInt()
            progress = lastPosition.toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        currentTimeView.text = formatMs(progress.toLong())
                        remainingTimeView.text = formatRemainingMs(progress.toLong(), trackDuration)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserScrubbing = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserScrubbing = false
                    seekBar?.let {
                        activeController?.transportControls?.seekTo(it.progress.toLong())
                    }
                }
            })
        }
        infoCol.addView(progressBar)

        // Timestamps (Current on Left, Negative Remaining "-m:ss" on Right)
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        currentTimeView = TextView(context).apply {
            text = formatMs(lastPosition)
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        timeRow.addView(currentTimeView)

        remainingTimeView = TextView(context).apply {
            text = formatRemainingMs(lastPosition, trackDuration)
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            gravity = Gravity.END
        }
        timeRow.addView(remainingTimeView)
        infoCol.addView(timeRow)

        // Floating Glass Controls Row (NO capsule/pill bounding box!)
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Shuffle
        shuffleBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_shuffle)
            setColorFilter(if (isShuffleActive) Color.parseColor("#1DB954") else Color.parseColor("#B3FFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnClickListener {
                isShuffleActive = !isShuffleActive
                setColorFilter(if (isShuffleActive) Color.parseColor("#1DB954") else Color.parseColor("#B3FFFFFF"))
                try {
                    activeController?.transportControls?.sendCustomAction("ACTION_SHUFFLE", null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        applyGlassPressAnimation(shuffleBtn)
        controlsRow.addView(shuffleBtn)

        // Previous
        prevBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_media_previous)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnClickListener { activeController?.transportControls?.skipToPrevious() }
        }
        applyGlassPressAnimation(prevBtn)
        controlsRow.addView(prevBtn)

        // Standalone 64dp Circular Glass Play/Pause Button
        playPauseBtn = ImageButton(context).apply {
            setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.play_button_background)
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
                gravity = Gravity.CENTER
            }
            setOnClickListener {
                if (isPlaying) activeController?.transportControls?.pause() else activeController?.transportControls?.play()
            }
        }
        applyGlassPressAnimation(playPauseBtn)
        controlsRow.addView(playPauseBtn)

        // Next
        nextBtn = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_media_next)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnClickListener { activeController?.transportControls?.skipToNext() }
        }
        applyGlassPressAnimation(nextBtn)
        controlsRow.addView(nextBtn)

        // Repeat
        repeatBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_repeat)
            setColorFilter(if (isRepeatActive) Color.parseColor("#1DB954") else Color.parseColor("#B3FFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnClickListener {
                isRepeatActive = !isRepeatActive
                setColorFilter(if (isRepeatActive) Color.parseColor("#1DB954") else Color.parseColor("#B3FFFFFF"))
                try {
                    activeController?.transportControls?.sendCustomAction("ACTION_REPEAT", null)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        applyGlassPressAnimation(repeatBtn)
        controlsRow.addView(repeatBtn)

        infoCol.addView(controlsRow)

        // Peeking Bottom Lip Indicator
        val peekingLip = TextView(context).apply {
            text = "Scroll down for Lyrics & Extra Cards ▾"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { scrollToLyricsSection() }
        }
        infoCol.addView(peekingLip)

        sectionAPage.addView(infoCol)
        feedLayout.addView(sectionAPage)
    }

    private fun buildSectionBLyricsCard(dp: Float) {
        val landscape = isDisplayLandscape()

        sectionBCard = CardView(context).apply {
            radius = 20 * dp
            cardElevation = 12 * dp
            setCardBackgroundColor(Color.parseColor("#E61C1C24"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (420 * dp).toInt()
            ).apply {
                marginStart = (16 * dp).toInt()
                marginEnd = (16 * dp).toInt()
                topMargin = (16 * dp).toInt()
                bottomMargin = (16 * dp).toInt()
            }
        }

        val cardContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Section B Card Header
        val cardHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { toggleFullScreenLyrics(true) }
        }

        val headerText = TextView(context).apply {
            text = "Lyrics"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        cardHeader.addView(headerText)

        lyricsExpandBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_expand)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
            setOnClickListener { toggleFullScreenLyrics(true) }
        }
        applyGlassPressAnimation(lyricsExpandBtn)
        cardHeader.addView(lyricsExpandBtn)
        cardContent.addView(cardHeader)

        // Lyrics Scroll View
        lyricsScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val hPad = if (landscape) (32 * dp).toInt() else (20 * dp).toInt()
        lyricsListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(hPad, (24 * dp).toInt(), hPad, (40 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        lyricsScrollView.addView(lyricsListLayout)
        cardContent.addView(lyricsScrollView)

        sectionBCard.addView(cardContent)
        feedLayout.addView(sectionBCard)
    }

    private fun buildSectionCExtraInfoCards(dp: Float) {
        // Card C1: "About the Artist / Song"
        val artistInfoCard = CardView(context).apply {
            radius = 16 * dp
            cardElevation = 8 * dp
            setCardBackgroundColor(Color.parseColor("#E61E1E24"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (16 * dp).toInt()
                marginEnd = (16 * dp).toInt()
                bottomMargin = (16 * dp).toInt()
            }
        }

        val c1Col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt())
        }

        val c1Title = TextView(context).apply {
            text = "ABOUT THE ARTIST"
            setTextColor(Color.parseColor("#1DB954"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setPadding(0, 0, 0, (6 * dp).toInt())
        }
        c1Col.addView(c1Title)

        artistNotesTextView = TextView(context).apply {
            text = if (currentArtistName.isNotEmpty()) {
                "Playing \"$currentTrackName\" by $currentArtistName. Real-time lyrics and live status HUD powered by Cover Karaoke."
            } else {
                "Connect Spotify or YouTube to stream live track metadata and synced karaoke lyrics."
            }
            setTextColor(Color.parseColor("#E0E0E0"))
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(4 * dp, 1.0f)
        }
        c1Col.addView(artistNotesTextView)
        artistInfoCard.addView(c1Col)
        feedLayout.addView(artistInfoCard)

        // Card C2: "Playlists & Albums"
        val playlistsCard = CardView(context).apply {
            radius = 16 * dp
            cardElevation = 8 * dp
            setCardBackgroundColor(Color.parseColor("#E61E1E24"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (16 * dp).toInt()
                marginEnd = (16 * dp).toInt()
                bottomMargin = (32 * dp).toInt()
            }
        }

        val c2Col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * dp).toInt(), (16 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt())
        }

        val c2Title = TextView(context).apply {
            text = "MORE FROM SPOTIFY"
            setTextColor(Color.parseColor("#1DB954"))
            textSize = 12f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        c2Col.addView(c2Title)

        spotifyDockScrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        spotifyDockListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        spotifyDockScrollView.addView(spotifyDockListLayout)
        c2Col.addView(spotifyDockScrollView)

        playlistsCard.addView(c2Col)
        feedLayout.addView(playlistsCard)

        spotifyManager = SpotifyManager(context).apply {
            onPlaylistsLoaded = { playlists ->
                populateSpotifyPlaylists(playlists, dp)
            }
            fetchPlaylists()
        }
    }

    private fun buildStickyHeader(dp: Float) {
        stickyHeader = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E614141A"))
            elevation = 12 * dp
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        stickyTitleView = TextView(context).apply {
            text = if (currentTrackName.isNotEmpty()) currentTrackName else "Cover Karaoke"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(stickyTitleView)

        stickyArtistView = TextView(context).apply {
            text = if (currentArtistName.isNotEmpty()) currentArtistName else "---"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 12f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(stickyArtistView)
        topRow.addView(textCol)

        stickyHeartBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_heart)
            setColorFilter(if (isFavoriteActive) Color.parseColor("#1DB954") else Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener {
                isFavoriteActive = !isFavoriteActive
                setColorFilter(if (isFavoriteActive) Color.parseColor("#1DB954") else Color.WHITE)
                if (::heartBtn.isInitialized) {
                    heartBtn.setColorFilter(if (isFavoriteActive) Color.parseColor("#1DB954") else Color.WHITE)
                }
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        applyGlassPressAnimation(stickyHeartBtn)
        topRow.addView(stickyHeartBtn)

        stickyPlayPauseBtn = ImageButton(context).apply {
            setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.play_button_background)
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()).apply {
                marginStart = (8 * dp).toInt()
            }
            setOnClickListener {
                if (isPlaying) activeController?.transportControls?.pause() else activeController?.transportControls?.play()
            }
        }
        applyGlassPressAnimation(stickyPlayPauseBtn)
        topRow.addView(stickyPlayPauseBtn)

        stickyHeader.addView(topRow)

        // Thin 2dp Progress Indicator Line Pinned at Bottom of Sticky Header
        stickyProgressLine = View(context).apply {
            setBackgroundColor(Color.parseColor("#1DB954"))
            layoutParams = LinearLayout.LayoutParams(0, (2 * dp).toInt())
        }
        stickyHeader.addView(stickyProgressLine)

        rootContainer.addView(stickyHeader)
    }

    private fun buildFullScreenLyricsOverlay(dp: Float) {
        val landscape = isDisplayLandscape()

        fullscreenLyricsContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#E6121218"))
            elevation = 20 * dp
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val overlayContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top Bar Header (~56dp, padding horizontal 16dp)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            setBackgroundColor(Color.parseColor("#1C1C24"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val backBtn = ImageButton(context).apply {
            setImageResource(R.drawable.ic_collapse)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((28 * dp).toInt(), (28 * dp).toInt())
            setOnClickListener { toggleFullScreenLyrics(false) }
        }
        applyGlassPressAnimation(backBtn)
        topBar.addView(backBtn)

        val metaCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        fullscreenTitleView = TextView(context).apply {
            text = if (currentTrackName.isNotEmpty()) currentTrackName else "Cover Karaoke"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        metaCol.addView(fullscreenTitleView)

        fullscreenArtistView = TextView(context).apply {
            text = if (currentArtistName.isNotEmpty()) currentArtistName else "---"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        metaCol.addView(fullscreenArtistView)
        topBar.addView(metaCol)

        fullscreenStatusView = UnifiedStatusView(context).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
        }
        topBar.addView(fullscreenStatusView)

        overlayContent.addView(topBar)

        // Center Synced Typography Canvas
        fullscreenLyricsScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val hPad = if (landscape) (32 * dp).toInt() else (20 * dp).toInt()
        fullscreenLyricsListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
            setPadding(hPad, (40 * dp).toInt(), hPad, (80 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fullscreenLyricsScrollView.addView(fullscreenLyricsListLayout)
        overlayContent.addView(fullscreenLyricsScrollView)

        // Minimalist Bottom Controls (Anchored to bottom edge with 24dp bottom padding)
        val bottomDock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#E6121218"))
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fullscreenProgressBar = SeekBar(context).apply {
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            progressDrawable?.setTint(Color.parseColor("#1DB954"))
            thumb?.setTint(Color.WHITE)
            isEnabled = true
            max = trackDuration.toInt()
            progress = lastPosition.toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        fullscreenCurrentTimeView.text = formatMs(progress.toLong())
                        fullscreenRemainingTimeView.text = formatRemainingMs(progress.toLong(), trackDuration)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserScrubbingFullscreen = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserScrubbingFullscreen = false
                    seekBar?.let { activeController?.transportControls?.seekTo(it.progress.toLong()) }
                }
            })
        }
        bottomDock.addView(fullscreenProgressBar)

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fullscreenCurrentTimeView = TextView(context).apply {
            text = formatMs(lastPosition)
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        timeRow.addView(fullscreenCurrentTimeView)

        fullscreenRemainingTimeView = TextView(context).apply {
            text = formatRemainingMs(lastPosition, trackDuration)
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            gravity = Gravity.END
        }
        timeRow.addView(fullscreenRemainingTimeView)
        bottomDock.addView(timeRow)

        // Centered Standalone 64dp Circular Play/Pause Button (NO skip or shuffle buttons!)
        fullscreenPlayPauseBtn = ImageButton(context).apply {
            setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.play_button_background)
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                if (isPlaying) activeController?.transportControls?.pause() else activeController?.transportControls?.play()
            }
        }
        applyGlassPressAnimation(fullscreenPlayPauseBtn)
        bottomDock.addView(fullscreenPlayPauseBtn)

        overlayContent.addView(bottomDock)
        fullscreenLyricsContainer.addView(overlayContent)

        // Gesture detector for downward swipe to collapse fullscreen lyrics
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && velocityY > 800 && abs(velocityY) > abs(velocityX)) {
                    toggleFullScreenLyrics(false)
                    return true
                }
                return false
            }
        })

        fullscreenLyricsContainer.setOnTouchListener { v, event ->
            val handled = gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !handled) {
                v.performClick()
            }
            true
        }

        rootContainer.addView(fullscreenLyricsContainer)
    }

    private fun toggleFullScreenLyrics(expand: Boolean) {
        if (!::fullscreenLyricsContainer.isInitialized) return

        isFullScreenLyricsActive = expand

        if (expand) {
            // Re-anchor media session playback state & position immediately
            activeController?.playbackState?.let { state ->
                lastPosition = state.position
                lastUpdateTime = state.lastPositionUpdateTime
                playbackSpeed = state.playbackSpeed
                isPlaying = state.state == PlaybackState.STATE_PLAYING
            }

            val currentPos = activeController?.playbackState?.position ?: lastPosition
            val duration = trackDuration.takeIf { it > 0 } ?: 1L

            // 1. Bind Metadata to Fullscreen Header
            if (::fullscreenTitleView.isInitialized) {
                fullscreenTitleView.text = if (currentTrackName.isNotEmpty()) currentTrackName else "Cover Karaoke"
                fullscreenArtistView.text = if (currentArtistName.isNotEmpty()) currentArtistName else "---"
            }

            // 2. Bind Scrub Bar & Controls State
            if (::fullscreenProgressBar.isInitialized) {
                fullscreenProgressBar.max = duration.toInt()
                fullscreenProgressBar.progress = currentPos.toInt()
                fullscreenCurrentTimeView.text = formatMs(currentPos)
                fullscreenRemainingTimeView.text = formatRemainingMs(currentPos, duration)
                fullscreenPlayPauseBtn.setImageResource(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                )
                fullscreenPlayPauseBtn.setColorFilter(Color.WHITE)
            }

            // 3. Populate Lyrics List if empty
            if (fullscreenLyricViews.isEmpty() && parsedLyrics.isNotEmpty()) {
                populateFullscreenLyricsViews()
            }

            // 4. Animate Overlay to Visible
            mainNestedScrollView.visibility = View.GONE
            fullscreenLyricsContainer.visibility = View.VISIBLE
            fullscreenLyricsContainer.animate().alpha(1.0f).setDuration(250).start()
            
            // Re-start progress ticker loop immediately without delay
            mainHandler.removeCallbacks(progressTicker)
            if (isPlaying) {
                mainHandler.post(progressTicker)
            } else {
                syncKaraoke(currentPos)
            }
        } else {
            mainNestedScrollView.visibility = View.VISIBLE
            fullscreenLyricsContainer.animate().alpha(0f).setDuration(250).withEndAction {
                fullscreenLyricsContainer.visibility = View.GONE
            }.start()
        }
    }

    private fun updateStickyProgressBar(currentMs: Long, totalMs: Long) {
        if (!::stickyProgressLine.isInitialized || !::stickyHeader.isInitialized) return
        val totalWidth = stickyHeader.width
        if (totalWidth > 0 && totalMs > 0) {
            val progressWidth = ((currentMs.toFloat() / totalMs) * totalWidth).toInt().coerceIn(0, totalWidth)
            val lp = stickyProgressLine.layoutParams
            if (lp.width != progressWidth) {
                lp.width = progressWidth
                stickyProgressLine.layoutParams = lp
            }
        }
    }

    private fun scrollToLyricsSection() {
        if (::mainNestedScrollView.isInitialized && ::sectionBCard.isInitialized) {
            mainNestedScrollView.smoothScrollTo(0, sectionBCard.top - 20)
        }
    }

    private fun populateSpotifyPlaylists(playlists: List<SpotifyPlaylist>, dp: Float) {
        spotifyDockListLayout.removeAllViews()

        playlists.forEach { playlist ->
            val card = CardView(context).apply {
                radius = 12 * dp
                cardElevation = 4 * dp
                setCardBackgroundColor(Color.parseColor("#181818"))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams((64 * dp).toInt(), (64 * dp).toInt()).apply {
                    marginEnd = (12 * dp).toInt()
                }

                setOnClickListener { v ->
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    try {
                        activeController?.transportControls?.playFromUri(Uri.parse(playlist.uri), null)
                    } catch (e: Exception) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(playlist.uri)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
            }

            val img = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                if (playlist.imageUrl.isNotEmpty()) {
                    load(playlist.imageUrl)
                } else {
                    setImageResource(R.drawable.ic_fold_dual_screen)
                    setColorFilter(Color.parseColor("#1DB954"))
                }
            }
            card.addView(img)
            spotifyDockListLayout.addView(card)
        }
    }

    private fun applyGlassPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                }
            }
            false
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
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                    gravity = alignGravity
                    setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                }
                lyricsListLayout.addView(tv)
            }
        }
    }

    private fun parseLrc(lrc: String) {
        lyricsListLayout.removeAllViews()
        if (::fullscreenLyricsListLayout.isInitialized) {
            fullscreenLyricsListLayout.removeAllViews()
        }

        val dp = context.resources.displayMetrics.density
        parsedLyrics = EnhancedLrcParser.parse(lrc)
        lyricViews.clear()
        fullscreenLyricViews.clear()

        val landscape = isDisplayLandscape()
        val alignGravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL

        parsedLyrics.forEach { line ->
            // Section B Card Row
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

            // Fullscreen Overlay Row
            if (::fullscreenLyricsListLayout.isInitialized) {
                val ftv = TextView(context).apply {
                    text = line.text
                    textSize = 24f
                    setTextColor(Color.WHITE)
                    alpha = 0.45f
                    scaleX = 1.0f
                    scaleY = 1.0f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    gravity = alignGravity
                    setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
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
                fullscreenLyricViews.add(ftv)
                fullscreenLyricsListLayout.addView(ftv)
            }
        }
    }

    private fun populateFullscreenLyricsViews() {
        if (!::fullscreenLyricsListLayout.isInitialized || parsedLyrics.isEmpty()) return
        fullscreenLyricsListLayout.removeAllViews()
        fullscreenLyricViews.clear()

        val dp = context.resources.displayMetrics.density
        val landscape = isDisplayLandscape()
        val alignGravity = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL

        parsedLyrics.forEach { line ->
            val ftv = TextView(context).apply {
                text = line.text
                textSize = 24f
                setTextColor(Color.WHITE)
                alpha = 0.45f
                scaleX = 1.0f
                scaleY = 1.0f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = alignGravity
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
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
            fullscreenLyricViews.add(ftv)
            fullscreenLyricsListLayout.addView(ftv)
        }
    }

    private fun syncKaraoke(currentMs: Long) {
        if (parsedLyrics.isEmpty()) {
            if (::activeLyricPreviewTextView.isInitialized) {
                activeLyricPreviewTextView.text = ""
            }
            return
        }

        var activeIndex = -1
        for (i in parsedLyrics.indices) {
            if (currentMs >= parsedLyrics[i].timeMs) {
                activeIndex = i
            } else {
                break
            }
        }

        if (activeIndex in parsedLyrics.indices) {
            val activeLine = parsedLyrics[activeIndex]

            // 1. Update Single-Line Active Lyric Snippet Ticker
            if (::activeLyricPreviewTextView.isInitialized) {
                activeLyricPreviewTextView.text = activeLine.text
            }

            // 2. Update Full Karaoke Sheet List
            if (lyricViews.isNotEmpty() && activeIndex in lyricViews.indices) {
                val activeView = lyricViews[activeIndex]

                if (activeLine.words.isNotEmpty()) {
                    activeView.text = WordHighlightHelper.formatHighlightedWordText(activeLine, currentMs)
                }

                if (activeIndex != currentLyricIndex) {
                    currentLyricIndex = activeIndex
                    lyricViews.forEachIndexed { index, view ->
                        val isActive = (index == activeIndex)
                        animateLyricRow(view, isActive, isFullscreen = false)

                        if (isActive && ::lyricsScrollView.isInitialized) {
                            val targetY = view.top - (lyricsScrollView.height / 2) + (view.height / 2)
                            lyricsScrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                        }
                    }
                }
            }

            // 3. Update Fullscreen Overlay Lyrics List & Centering
            if (fullscreenLyricViews.isNotEmpty() && activeIndex in fullscreenLyricViews.indices) {
                val activeFullView = fullscreenLyricViews[activeIndex]

                if (activeLine.words.isNotEmpty()) {
                    activeFullView.text = WordHighlightHelper.formatHighlightedWordText(activeLine, currentMs)
                }

                fullscreenLyricViews.forEachIndexed { index, view ->
                    val isActive = (index == activeIndex)
                    animateLyricRow(view, isActive, isFullscreen = true)

                    if (isActive && ::fullscreenLyricsScrollView.isInitialized && isFullScreenLyricsActive) {
                        val targetY = view.top - (fullscreenLyricsScrollView.height / 2) + (view.height / 2)
                        fullscreenLyricsScrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                    }
                }
            }
        }
    }

    private fun animateLyricRow(view: TextView, isActive: Boolean, isFullscreen: Boolean = false) {
        val targetSize = if (isActive) 24f else 20f
        val targetAlpha = if (isActive) 1.0f else 0.45f

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
                if (::stickyTitleView.isInitialized) {
                    stickyTitleView.text = "Cover Karaoke"
                    stickyArtistView.text = "No Media"
                }
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
        remainingTimeView.text = formatRemainingMs(lastPosition, trackDuration)

        if (title != currentTrackName || artist != currentArtistName) {
            currentTrackName = title
            currentArtistName = artist
            fetchLyrics(title, artist)
        }

        trackTitleView.text = title
        artistView.text = artist

        if (::stickyTitleView.isInitialized) {
            stickyTitleView.text = title
            stickyArtistView.text = artist
        }

        if (::fullscreenTitleView.isInitialized) {
            fullscreenTitleView.text = title
            fullscreenArtistView.text = artist
            fullscreenProgressBar.max = trackDuration.toInt()
            fullscreenRemainingTimeView.text = formatRemainingMs(lastPosition, trackDuration)
        }

        if (::artistNotesTextView.isInitialized) {
            artistNotesTextView.text = "Playing \"$title\" by $artist. Live synced lyrics and telemetry by Cover Karaoke."
        }

        currentBitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        if (currentBitmap != null) {
            albumArtView.setImageBitmap(currentBitmap)
            applyDynamicBackdrop(currentBitmap!!)
        }
    }

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
            if (::fullscreenLyricsContainer.isInitialized) {
                fullscreenLyricsContainer.background = gradient
            }
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
        playPauseBtn.setColorFilter(Color.WHITE)

        if (::stickyPlayPauseBtn.isInitialized) {
            stickyPlayPauseBtn.setImageResource(iconRes)
            stickyPlayPauseBtn.setColorFilter(Color.WHITE)
        }

        if (isPlaying) {
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.post(progressTicker)
        } else {
            mainHandler.removeCallbacks(progressTicker)
            if (!isUserScrubbing) {
                progressBar.progress = lastPosition.toInt()
                currentTimeView.text = formatMs(lastPosition)
                remainingTimeView.text = formatRemainingMs(lastPosition, trackDuration)
                updateStickyProgressBar(lastPosition, trackDuration)
            }
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
                if (::stickyTitleView.isInitialized) {
                    stickyTitleView.text = "Cover Karaoke"
                    stickyArtistView.text = "No Media"
                }
            } else {
                updateMetadata(activeController?.metadata)
                updatePlaybackState(activeController?.playbackState)
            }
        }
    }

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%d:%02d", min, sec)
    }

    private fun formatRemainingMs(currentMs: Long, totalMs: Long): String {
        if (totalMs <= 0) return "-0:00"
        val remainingMs = (totalMs - currentMs).coerceAtLeast(0)
        val totalSec = remainingMs / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "-%d:%02d", min, sec)
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        buildUI()
        activeController?.metadata?.let { updateMetadata(it) }
        activeController?.playbackState?.let { updatePlaybackState(it) }
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.removeCallbacks(headerClockTicker)
        ambientGlowAnimator?.cancel()
        audioReactiveEngine?.stop()
        audioReactiveEngine = null
        systemStatusManager?.stop()
        orientationListener?.disable()
        activeController?.unregisterCallback(mediaCallback)
    }
}
