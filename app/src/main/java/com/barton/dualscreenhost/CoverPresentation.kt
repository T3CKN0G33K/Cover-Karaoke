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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.cardview.widget.CardView
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
    private lateinit var contentContainer: FrameLayout

    // --- Top Header ---
    private lateinit var headerTimeView: TextView
    private lateinit var unifiedStatusView: UnifiedStatusView

    // --- Now Playing Main View ---
    private lateinit var nowPlayingScrollView: ScrollView
    private lateinit var nowPlayingContent: LinearLayout
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

    // --- Lyrics Bottom Sheet Card ---
    private lateinit var lyricsBottomSheetCard: CardView
    private lateinit var lyricsSheetHeader: LinearLayout
    private lateinit var lyricsHeaderIcon: ImageView
    private lateinit var lyricsScrollView: ScrollView
    private lateinit var lyricsListLayout: LinearLayout
    private var isLyricsSheetExpanded = false

    private var parsedLyrics = listOf<LyricLine>()
    private var lyricViews = mutableListOf<TextView>()
    private var currentLyricIndex = -1

    // --- Engines & Managers ---
    private var audioReactiveEngine: AudioReactiveEngine? = null
    private var systemStatusManager: SystemStatusManager? = null
    private var orientationListener: OrientationEventListener? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    // --- State & Utilities ---
    private var currentBitmap: Bitmap? = null
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var isUserScrubbing = false
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

                if (!isUserScrubbing) {
                    progressBar.progress = estimatedPos.toInt()
                    currentTimeView.text = formatMs(estimatedPos)
                    remainingTimeView.text = formatRemainingMs(estimatedPos, trackDuration)
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

        // Audio reactive engine for radial dial pulse
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

        // Background Layer 2: Mesh Gradient Overlay
        gradientOverlayView = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(gradientOverlayView)

        currentBitmap?.let { applyDynamicBackdrop(it) }

        // Content Area
        contentContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(contentContainer)

        // Top Header Bar
        buildTopHeader(dp)

        // Main Spotify Now Playing Layout
        buildNowPlayingView(dp)

        // Swipe-Up Lyrics Bottom Sheet Card
        buildLyricsBottomSheetCard(dp)
    }

    private fun buildTopHeader(dp: Float) {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), 0)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
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
            layoutParams = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt())
        }
        topBar.addView(unifiedStatusView)

        systemStatusManager = SystemStatusManager(context, unifiedStatusView)
        systemStatusManager?.start()

        rootContainer.addView(topBar)
    }

    private fun buildNowPlayingView(dp: Float) {
        val landscape = isDisplayLandscape()

        nowPlayingScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        nowPlayingContent = LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * dp).toInt(), (68 * dp).toInt(), (20 * dp).toInt(), (96 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 1. Strict 1:1 Square Album Art Card
        val screenWidthPx = context.resources.displayMetrics.widthPixels
        val artSize = if (landscape) {
            (160 * dp).toInt()
        } else {
            (screenWidthPx - (40 * dp).toInt()).coerceAtMost((300 * dp).toInt())
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
                    bottomMargin = (12 * dp).toInt()
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
        nowPlayingContent.addView(albumArtCard)

        // Lower Section
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        // 2. Active Lyric Snippet Ticker (Single-Line Live Preview directly below album art)
        activeLyricPreviewTextView = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (14 * dp).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleLyricsSheet() }
        }
        infoCol.addView(activeLyricPreviewTextView)

        // 3. Metadata Header Row (Title/Artist on Left, Favorite Heart on Right)
        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), (10 * dp).toInt())
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
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
        applyGlassPressAnimation(heartBtn)
        metaRow.addView(heartBtn)

        infoCol.addView(metaRow)

        // 4. Interactive 3dp Scrub Bar
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
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (14 * dp).toInt())
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

        // 5. Floating Glass Controls Row (NO capsule/pill bounding box!)
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

        // Standalone 60dp Circular Glass Play/Pause Button
        playPauseBtn = ImageButton(context).apply {
            setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.play_button_background)
            setPadding((14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams((60 * dp).toInt(), (60 * dp).toInt()).apply {
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
        nowPlayingContent.addView(infoCol)
        nowPlayingScrollView.addView(nowPlayingContent)
        contentContainer.addView(nowPlayingScrollView)
    }

    private fun buildLyricsBottomSheetCard(dp: Float) {
        val landscape = isDisplayLandscape()

        lyricsBottomSheetCard = CardView(context).apply {
            radius = 20 * dp
            cardElevation = 16 * dp
            setCardBackgroundColor(Color.parseColor("#E61C1C22"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM
            ).apply {
                topMargin = (72 * dp).toInt()
            }
        }

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header Bar (Docked / Peeking)
        lyricsSheetHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * dp).toInt(), (14 * dp).toInt(), (20 * dp).toInt(), (14 * dp).toInt())
            isClickable = true
            isFocusable = true
            setBackgroundColor(Color.parseColor("#1AFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { toggleLyricsSheet() }
        }

        val headerText = TextView(context).apply {
            text = "Lyrics"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        lyricsSheetHeader.addView(headerText)

        lyricsHeaderIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_lyrics)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt())
        }
        lyricsSheetHeader.addView(lyricsHeaderIcon)
        cardLayout.addView(lyricsSheetHeader)

        // Lyrics Scrollable Content
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
            setPadding(hPad, (40 * dp).toInt(), hPad, (120 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        lyricsScrollView.addView(lyricsListLayout)
        cardLayout.addView(lyricsScrollView)

        lyricsBottomSheetCard.addView(cardLayout)

        // Swipe up/down gesture detector for lyrics bottom sheet
        val sheetGestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && abs(velocityY) > 800) {
                    if (velocityY < 0 && !isLyricsSheetExpanded) { // Swipe up
                        expandLyricsSheet()
                        return true
                    } else if (velocityY > 0 && isLyricsSheetExpanded) { // Swipe down
                        collapseLyricsSheet()
                        return true
                    }
                }
                return false
            }
        })

        lyricsBottomSheetCard.setOnTouchListener { v, event ->
            val handled = sheetGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !handled) {
                v.performClick()
            }
            true
        }

        contentContainer.addView(lyricsBottomSheetCard)

        // Start collapsed
        mainHandler.post { collapseLyricsSheet(animate = false) }
    }

    private fun toggleLyricsSheet() {
        if (isLyricsSheetExpanded) {
            collapseLyricsSheet()
        } else {
            expandLyricsSheet()
        }
    }

    private fun expandLyricsSheet() {
        isLyricsSheetExpanded = true
        lyricsBottomSheetCard.animate()
            .translationY(0f)
            .setDuration(280)
            .start()
        if (parsedLyrics.isNotEmpty()) {
            syncKaraoke(lastPosition)
        }
    }

    private fun collapseLyricsSheet(animate: Boolean = true) {
        isLyricsSheetExpanded = false
        val targetY = (lyricsBottomSheetCard.height - lyricsSheetHeader.height).toFloat().coerceAtLeast(0f)
        if (animate) {
            lyricsBottomSheetCard.animate()
                .translationY(targetY)
                .setDuration(280)
                .start()
        } else {
            lyricsBottomSheetCard.translationY = targetY
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
                        animateLyricRow(view, isActive)

                        if (isActive && isLyricsSheetExpanded) {
                            val targetY = view.top - (lyricsScrollView.height / 2) + (view.height / 2)
                            lyricsScrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                        }
                    }
                }
            }
        }
    }

    private fun animateLyricRow(view: TextView, isActive: Boolean) {
        val targetSize = if (isActive) 24f else 20f
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
        remainingTimeView.text = formatRemainingMs(lastPosition, trackDuration)

        if (title != currentTrackName || artist != currentArtistName) {
            currentTrackName = title
            currentArtistName = artist
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

        if (isPlaying) {
            mainHandler.removeCallbacks(progressTicker)
            mainHandler.post(progressTicker)
        } else {
            mainHandler.removeCallbacks(progressTicker)
            if (!isUserScrubbing) {
                progressBar.progress = lastPosition.toInt()
                currentTimeView.text = formatMs(lastPosition)
                remainingTimeView.text = formatRemainingMs(lastPosition, trackDuration)
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
