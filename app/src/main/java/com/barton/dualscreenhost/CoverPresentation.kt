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

    // --- Root & Layout Containers ---
    private lateinit var rootContainer: FrameLayout
    private lateinit var backdropImageView: ImageView
    private lateinit var gradientOverlayView: View
    private lateinit var contentContainer: FrameLayout

    // --- Top Header ---
    private lateinit var headerTimeView: TextView
    private lateinit var unifiedStatusView: UnifiedStatusView

    // --- Bottom Liquid Glass Tab Bar ---
    private lateinit var tabBarContainer: LinearLayout
    private val tabIcons = mutableListOf<ImageButton>()
    private var selectedTabIndex = 0 // 0 = Now Playing, 1 = Lyrics, 2 = Library

    // --- Page 0: Now Playing ---
    private lateinit var nowPlayingPage: LinearLayout
    private lateinit var albumArtCard: CardView
    private lateinit var albumArtView: ImageView
    private lateinit var trackTitleView: TextView
    private lateinit var artistView: TextView
    private lateinit var heartBtn: ImageButton
    private lateinit var progressBar: SeekBar
    private lateinit var currentTimeView: TextView
    private lateinit var totalDurationView: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton

    private var isShuffleActive = false
    private var isRepeatActive = false
    private var isFavoriteActive = false

    // --- Page 1: Lyrics ---
    private lateinit var lyricsPage: FrameLayout
    private lateinit var lyricsScrollView: ScrollView
    private lateinit var lyricsListLayout: LinearLayout
    private var parsedLyrics = listOf<LyricLine>()
    private var lyricViews = mutableListOf<TextView>()
    private var currentLyricIndex = -1

    // --- Page 2: Library ---
    private lateinit var libraryPage: FrameLayout
    private lateinit var libraryScrollView: ScrollView
    private lateinit var libraryListLayout: LinearLayout

    // --- Engines & Managers ---
    private var audioReactiveEngine: AudioReactiveEngine? = null
    private var systemStatusManager: SystemStatusManager? = null
    private var spotifyManager: SpotifyManager? = null
    private var orientationListener: OrientationEventListener? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    // --- State & Utility ---
    private var currentBitmap: Bitmap? = null
    private val httpClient = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPlaying = false
    private var isUserScrubbing = false
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
                }

                if (selectedTabIndex == 1) { // If Lyrics tab is open
                    syncKaraoke(estimatedPos)
                }

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

        // Audio reactive engine for status dial pulse
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

        // Content Area (Pages 0, 1, 2)
        contentContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootContainer.addView(contentContainer)

        // Top Bar (Clock + Dial)
        buildTopHeader(dp)

        // Build Pages
        buildNowPlayingPage(dp)
        buildLyricsPage(dp)
        buildLibraryPage(dp)

        // Bottom Liquid Glass Tab Bar
        buildBottomTabBar(dp)

        // Set Default Tab
        selectTab(0, animate = false)
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
            layoutParams = LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt())
        }
        topBar.addView(unifiedStatusView)

        systemStatusManager = SystemStatusManager(context, unifiedStatusView)
        systemStatusManager?.start()

        rootContainer.addView(topBar)
    }

    private fun buildNowPlayingPage(dp: Float) {
        val landscape = isDisplayLandscape()

        nowPlayingPage = LinearLayout(context).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((20 * dp).toInt(), (68 * dp).toInt(), (20 * dp).toInt(), (80 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Expanded Edge-to-Edge Album Art Container (Anchored in upper portion)
        val artWidth = if (landscape) (160 * dp).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        val artHeight = if (landscape) (160 * dp).toInt() else (270 * dp).toInt()

        albumArtCard = CardView(context).apply {
            radius = 20 * dp
            cardElevation = 10 * dp
            setCardBackgroundColor(Color.parseColor("#181818"))
            layoutParams = LinearLayout.LayoutParams(artWidth, artHeight).apply {
                if (landscape) {
                    marginEnd = (24 * dp).toInt()
                } else {
                    marginStart = (4 * dp).toInt()
                    marginEnd = (4 * dp).toInt()
                    bottomMargin = (16 * dp).toInt()
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
        nowPlayingPage.addView(albumArtCard)

        // 2. Lower Third Section (Shifted DOWN into bottom third)
        val infoCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        // Metadata Header Row (Title/Artist on Left, Favorite Heart on Right)
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
            textSize = 22f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(trackTitleView)

        artistView = TextView(context).apply {
            text = if (currentArtistName.isNotEmpty()) currentArtistName else "---"
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 15f
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

        // 3. Polished Interactive Scrub Bar
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

        // Timestamps (0:00 / 3:45)
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((4 * dp).toInt(), (2 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt())
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

        totalDurationView = TextView(context).apply {
            text = formatMs(trackDuration)
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 12f
            gravity = Gravity.END
        }
        timeRow.addView(totalDurationView)
        infoCol.addView(timeRow)

        // 4. Floating Glass Controls Row (NO capsule/pill background surrounding the whole row!)
        val controlsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (4 * dp).toInt()
            }
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

        // Standalone 64dp Circular Glass Play/Pause
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
        nowPlayingPage.addView(infoCol)
        contentContainer.addView(nowPlayingPage)
    }

    private fun buildLyricsPage(dp: Float) {
        lyricsPage = FrameLayout(context).apply {
            visibility = View.GONE
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
        val topPad = (100 * dp).toInt()
        val bottomPad = (160 * dp).toInt()

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
        lyricsPage.addView(lyricsScrollView)
        contentContainer.addView(lyricsPage)
    }

    private fun buildLibraryPage(dp: Float) {
        libraryPage = FrameLayout(context).apply {
            visibility = View.GONE
            setPadding((20 * dp).toInt(), (72 * dp).toInt(), (20 * dp).toInt(), (90 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        libraryScrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        libraryListLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        libraryScrollView.addView(libraryListLayout)
        libraryPage.addView(libraryScrollView)
        contentContainer.addView(libraryPage)

        spotifyManager = SpotifyManager(context).apply {
            onPlaylistsLoaded = { playlists ->
                populateLibraryPage(playlists, dp)
            }
            fetchPlaylists()
        }
    }

    private fun buildBottomTabBar(dp: Float) {
        tabBarContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.pill_control_background)
            elevation = 12 * dp
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (56 * dp).toInt()
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * dp).toInt()
                marginStart = (24 * dp).toInt()
                marginEnd = (24 * dp).toInt()
            }
        }

        tabIcons.clear()

        // Tab 0: Now Playing
        val tab0 = ImageButton(context).apply {
            setImageResource(R.drawable.ic_now_playing)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            setOnClickListener { selectTab(0) }
        }
        applyGlassPressAnimation(tab0)
        tabIcons.add(tab0)
        tabBarContainer.addView(tab0)

        // Tab 1: Lyrics
        val tab1 = ImageButton(context).apply {
            setImageResource(R.drawable.ic_lyrics)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            setOnClickListener { selectTab(1) }
        }
        applyGlassPressAnimation(tab1)
        tabIcons.add(tab1)
        tabBarContainer.addView(tab1)

        // Tab 2: Library
        val tab2 = ImageButton(context).apply {
            setImageResource(R.drawable.ic_library)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            setOnClickListener { selectTab(2) }
        }
        applyGlassPressAnimation(tab2)
        tabIcons.add(tab2)
        tabBarContainer.addView(tab2)

        rootContainer.addView(tabBarContainer)
    }

    private fun selectTab(index: Int, animate: Boolean = true) {
        selectedTabIndex = index.coerceIn(0, 2)

        tabIcons.forEachIndexed { i, btn ->
            if (i == selectedTabIndex) {
                btn.alpha = 1.0f
                if (animate) btn.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
            } else {
                btn.alpha = 0.40f
                if (animate) btn.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }

        val pages = listOf(nowPlayingPage, lyricsPage, libraryPage)
        pages.forEachIndexed { i, page ->
            if (i == selectedTabIndex) {
                page.visibility = View.VISIBLE
                if (animate) {
                    page.alpha = 0f
                    page.animate().alpha(1.0f).setDuration(150).start()
                } else {
                    page.alpha = 1.0f
                }
            } else {
                if (animate) {
                    page.animate().alpha(0f).setDuration(150).withEndAction {
                        page.visibility = View.GONE
                    }.start()
                } else {
                    page.visibility = View.GONE
                    page.alpha = 0f
                }
            }
        }

        if (selectedTabIndex == 1 && parsedLyrics.isNotEmpty()) {
            syncKaraoke(lastPosition)
        }
    }

    private fun populateLibraryPage(playlists: List<SpotifyPlaylist>, dp: Float) {
        libraryListLayout.removeAllViews()

        playlists.forEach { playlist ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card_spotify_background)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * dp).toInt()
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
                    selectTab(0) // Switch to Now Playing tab
                }
            }

            val card = CardView(context).apply {
                radius = 12 * dp
                cardElevation = 4 * dp
                setCardBackgroundColor(Color.parseColor("#181818"))
                layoutParams = LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()).apply {
                    marginEnd = (14 * dp).toInt()
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
            row.addView(card)

            val title = TextView(context).apply {
                text = playlist.name
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            row.addView(title)

            libraryListLayout.addView(row)
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
        totalDurationView.text = formatMs(trackDuration)

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
