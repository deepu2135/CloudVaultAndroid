package com.cloudvault.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class VlcPlayerActivity : AppCompatActivity() {

    private lateinit var vlcRoot: FrameLayout
    private lateinit var vlcVideoLayout: VLCVideoLayout
    private lateinit var pbVlcBuffering: ProgressBar
    private lateinit var layoutControlsOverlay: FrameLayout

    private lateinit var layoutTopBar: FrameLayout
    private lateinit var btnVlcBack: ImageView
    private lateinit var tvVlcTitle: TextView
    private lateinit var tvVlcSubtitle: TextView

    private lateinit var btnVlcFloatingLock: FrameLayout
    private lateinit var ivFloatingLockIcon: ImageView

    private lateinit var layoutGestureHud: LinearLayout
    private lateinit var tvGestureHudIcon: TextView
    private lateinit var pbGestureHud: ProgressBar
    private lateinit var tvGestureHudText: TextView

    private lateinit var layoutBottomBar: LinearLayout
    private lateinit var tvVlcCurrentTime: TextView
    private lateinit var sbVlcProgress: SeekBar
    private lateinit var tvVlcTotalDuration: TextView

    private lateinit var btnVlcSubtitles: ImageView
    private lateinit var btnVlcAspect: ImageView
    private lateinit var btnVlcLock: ImageView
    private lateinit var btnVlcRewind: ImageView
    private lateinit var btnVlcPlayPause: FrameLayout
    private lateinit var ivPlayPauseIcon: ImageView
    private lateinit var btnVlcForward: ImageView
    private lateinit var btnVlcPip: ImageView
    private lateinit var btnVlcRotate: ImageView
    private lateinit var btnVlcMore: ImageView

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var explicitDurationMs: Long = 0L
    private var currentFileId: Int = 0
    private var hasResumedPosition = false
    private var lastPositionSaveTime = 0L

    private var isUserTracking = false
    private var isLocked = false
    private var showRemainingTime = true
    private var currentSpeedIndex = 1
    private val speedOptions = arrayOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")

    private var currentAspectIndex = 0
    private val aspectModes = arrayOf("Best Fit", "Zoom to Fill", "Stretch (Full)", "16:9", "4:3")

    private var currentOrientationIndex = 0
    private val orientationModes = arrayOf(
        Pair("Landscape 🖥️", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
        Pair("Portrait 📱", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        Pair("Reverse Landscape 🔄", ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        Pair("Auto Rotate 🌐", ActivityInfo.SCREEN_ORIENTATION_SENSOR)
    )

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        if (!isLocked) {
            layoutControlsOverlay.visibility = View.GONE
        } else {
            btnVlcFloatingLock.visibility = View.GONE
        }
    }

    private val hideHudRunnable = Runnable {
        layoutGestureHud.visibility = View.GONE
    }

    private lateinit var audioManager: AudioManager
    private var maxVolume: Int = 15
    private var currentBrightness: Float = -1f

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vlc_player)

        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("VlcPlayer", "Window cutout setup error", e)
        }

        window.decorView.post { hideSystemUI() }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val fileId = intent.getIntExtra("FILE_ID", 0)
        currentFileId = fileId
        val title = intent.getStringExtra("TITLE") ?: "Video"
        val chatId = intent.getLongExtra("CHAT_ID", 0L)
        val messageId = intent.getLongExtra("MESSAGE_ID", 0L)
        val durSec = intent.getIntExtra("DURATION_SECONDS", 0)
        explicitDurationMs = if (durSec > 0) durSec * 1000L else intent.getLongExtra("DURATION_MS", 0L)

        if (fileId != 0 && chatId != 0L && messageId != 0L) {
            TelegramStreamingProxy.registerFileMessage(fileId, chatId, messageId)
        }

        bindViews()
        setupListeners(fileId, title)
        setupGestures()
        setupSeekBar()
        initVlcAndPlay(fileId, title)
        scheduleControlsAutoHide()
    }

    private fun bindViews() {
        vlcRoot = findViewById(R.id.vlcRoot)
        vlcVideoLayout = findViewById(R.id.vlcVideoLayout)
        pbVlcBuffering = findViewById(R.id.pbVlcBuffering)
        layoutControlsOverlay = findViewById(R.id.layoutControlsOverlay)

        layoutTopBar = findViewById(R.id.layoutTopBar)
        btnVlcBack = findViewById(R.id.btnVlcBack)
        tvVlcTitle = findViewById(R.id.tvVlcTitle)
        tvVlcSubtitle = findViewById(R.id.tvVlcSubtitle)

        btnVlcFloatingLock = findViewById(R.id.btnVlcFloatingLock)
        ivFloatingLockIcon = findViewById(R.id.ivFloatingLockIcon)

        layoutGestureHud = findViewById(R.id.layoutGestureHud)
        tvGestureHudIcon = findViewById(R.id.tvGestureHudIcon)
        pbGestureHud = findViewById(R.id.pbGestureHud)
        tvGestureHudText = findViewById(R.id.tvGestureHudText)

        layoutBottomBar = findViewById(R.id.layoutBottomBar)
        tvVlcCurrentTime = findViewById(R.id.tvVlcCurrentTime)
        sbVlcProgress = findViewById(R.id.sbVlcProgress)
        tvVlcTotalDuration = findViewById(R.id.tvVlcTotalDuration)

        btnVlcSubtitles = findViewById(R.id.btnVlcSubtitles)
        btnVlcAspect = findViewById(R.id.btnVlcAspect)
        btnVlcLock = findViewById(R.id.btnVlcLock)
        btnVlcRewind = findViewById(R.id.btnVlcRewind)
        btnVlcPlayPause = findViewById(R.id.btnVlcPlayPause)
        ivPlayPauseIcon = findViewById(R.id.ivPlayPauseIcon)
        btnVlcForward = findViewById(R.id.btnVlcForward)
        btnVlcPip = findViewById(R.id.btnVlcPip)
        btnVlcRotate = findViewById(R.id.btnVlcRotate)
        btnVlcMore = findViewById(R.id.btnVlcMore)
    }

    private fun setupListeners(fileId: Int, title: String) {
        tvVlcTitle.text = title
        tvVlcSubtitle.text = "CLOUD VAULT STREAM • VLC ENGINE"

        if (explicitDurationMs > 0L) {
            updateTimeViews(0L, explicitDurationMs)
        }

        btnVlcBack.setOnClickListener { finish() }

        btnVlcPlayPause.setOnClickListener {
            togglePlayPause()
            scheduleControlsAutoHide()
        }

        btnVlcRewind.setOnClickListener {
            seekRelative(-10000L)
            scheduleControlsAutoHide()
        }

        btnVlcForward.setOnClickListener {
            seekRelative(10000L)
            scheduleControlsAutoHide()
        }

        btnVlcSubtitles.setOnClickListener {
            showTrackSelectionDialog("Subtitles / Audio Tracks")
            scheduleControlsAutoHide()
        }

        btnVlcAspect.setOnClickListener {
            cycleAspectRatio()
            scheduleControlsAutoHide()
        }

        btnVlcLock.setOnClickListener {
            toggleLock()
        }

        btnVlcFloatingLock.setOnClickListener {
            toggleLock()
        }

        btnVlcPip.setOnClickListener {
            enterPipMode()
        }

        btnVlcRotate.setOnClickListener {
            cycleScreenRotation()
            scheduleControlsAutoHide()
        }

        btnVlcMore.setOnClickListener {
            showMoreOptionsDialog()
            scheduleControlsAutoHide()
        }

        tvVlcTotalDuration.setOnClickListener {
            showRemainingTime = !showRemainingTime
            val time = mediaPlayer?.time ?: 0L
            val duration = getEffectiveDuration()
            updateTimeViews(time, duration)
            scheduleControlsAutoHide()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                layoutControlsOverlay.visibility = View.GONE
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Throwable) {
                Toast.makeText(this, "PIP not supported: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Picture-in-Picture requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            layoutControlsOverlay.visibility = View.GONE
        } else {
            layoutControlsOverlay.visibility = View.VISIBLE
            scheduleControlsAutoHide()
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    if (btnVlcFloatingLock.visibility == View.VISIBLE) {
                        btnVlcFloatingLock.visibility = View.GONE
                    } else {
                        btnVlcFloatingLock.visibility = View.VISIBLE
                        scheduleControlsAutoHide()
                    }
                } else {
                    if (layoutControlsOverlay.visibility == View.VISIBLE) {
                        layoutControlsOverlay.visibility = View.GONE
                    } else {
                        layoutControlsOverlay.visibility = View.VISIBLE
                        scheduleControlsAutoHide()
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return false
                val screenWidth = vlcRoot.width.toFloat()
                if (e.x < screenWidth / 2f) {
                    seekRelative(-10000L)
                    showGestureHud("⏱️", "-10s", 0)
                } else {
                    seekRelative(10000L)
                    showGestureHud("⏱️", "+10s", 0)
                }
                scheduleControlsAutoHide()
                return true
            }
        })

        var initialX = 0f
        var initialY = 0f
        var isDraggingVertical = false
        var isDraggingHorizontal = false
        var initialVolume = 0
        var initialSeekTime = 0L

        vlcRoot.setOnTouchListener { _, event ->
            if (gestureDetector.onTouchEvent(event)) {
                return@setOnTouchListener true
            }

            if (isLocked) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.x
                    initialY = event.y
                    isDraggingVertical = false
                    isDraggingHorizontal = false
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    initialSeekTime = mediaPlayer?.time ?: 0L
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - initialX
                    val deltaY = event.y - initialY
                    val screenWidth = vlcRoot.width.toFloat()
                    val screenHeight = vlcRoot.height.toFloat()

                    if (!isDraggingVertical && !isDraggingHorizontal) {
                        if (abs(deltaY) > 40f && abs(deltaY) > abs(deltaX)) {
                            isDraggingVertical = true
                        } else if (abs(deltaX) > 40f && abs(deltaX) > abs(deltaY)) {
                            isDraggingHorizontal = true
                        }
                    }

                    if (isDraggingVertical) {
                        val fraction = -deltaY / screenHeight
                        if (initialX < screenWidth / 2f) {
                            // Left side: Brightness
                            val lp = window.attributes
                            val current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                            val newBrightness = (current + fraction * 0.1f).coerceIn(0.01f, 1.0f)
                            lp.screenBrightness = newBrightness
                            window.attributes = lp
                            val pct = (newBrightness * 100).roundToInt()
                            showGestureHud("☀️", "$pct%", pct)
                        } else {
                            // Right side: Volume
                            val volumeDelta = (fraction * maxVolume * 0.8f).roundToInt()
                            val newVolume = (initialVolume + volumeDelta).coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                            val pct = ((newVolume.toFloat() / maxVolume.toFloat()) * 100).roundToInt()
                            showGestureHud("🔊", "$pct%", pct)
                        }
                    } else if (isDraggingHorizontal) {
                        // Horizontal Scrub / Seek
                        val duration = getEffectiveDuration()
                        if (duration > 0L) {
                            val seekFraction = deltaX / screenWidth
                            val seekDeltaMs = (seekFraction * 90000L).toLong() // +/- 90 seconds max per drag
                            val targetTime = (initialSeekTime + seekDeltaMs).coerceIn(0L, duration)
                            val sign = if (seekDeltaMs >= 0) "+" else ""
                            showGestureHud("⏱️", "$sign${formatTime(seekDeltaMs)} (${formatTime(targetTime)})", ((targetTime.toFloat() / duration.toFloat()) * 100).roundToInt())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingHorizontal) {
                        val duration = getEffectiveDuration()
                        val deltaX = event.x - initialX
                        val screenWidth = vlcRoot.width.toFloat()
                        if (duration > 0L) {
                            val seekFraction = deltaX / screenWidth
                            val seekDeltaMs = (seekFraction * 90000L).toLong()
                            val targetTime = (initialSeekTime + seekDeltaMs).coerceIn(0L, duration)
                            mediaPlayer?.time = targetTime
                        }
                    }
                    hideHandler.removeCallbacks(hideHudRunnable)
                    hideHandler.postDelayed(hideHudRunnable, 1200L)
                    scheduleControlsAutoHide()
                    true
                }
                else -> false
            }
        }
    }

    private fun showGestureHud(icon: String, text: String, progress: Int) {
        tvGestureHudIcon.text = icon
        tvGestureHudText.text = text
        if (progress in 0..100) {
            pbGestureHud.visibility = View.VISIBLE
            pbGestureHud.progress = progress
        } else {
            pbGestureHud.visibility = View.GONE
        }
        layoutGestureHud.visibility = View.VISIBLE
        hideHandler.removeCallbacks(hideHudRunnable)
    }

    private fun updateTimeViews(currentTimeMs: Long, totalDurationMs: Long) {
        tvVlcCurrentTime.text = formatTime(currentTimeMs)
        if (totalDurationMs > 0L) {
            if (showRemainingTime) {
                val remaining = (totalDurationMs - currentTimeMs).coerceAtLeast(0L)
                tvVlcTotalDuration.text = "- ${formatTime(remaining)}"
            } else {
                tvVlcTotalDuration.text = formatTime(totalDurationMs)
            }
        }
    }

    private fun getEffectiveDuration(): Long {
        val vlcLength = mediaPlayer?.length ?: 0L
        if (explicitDurationMs > 0L) {
            if (vlcLength <= 0L || vlcLength > explicitDurationMs * 2.5 || vlcLength < explicitDurationMs * 0.3) {
                return explicitDurationMs
            }
        }
        return if (vlcLength > 0L) vlcLength else explicitDurationMs
    }

    private fun setupSeekBar() {
        sbVlcProgress.max = 1000
        sbVlcProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = getEffectiveDuration()
                    if (duration > 0L) {
                        val newTime = (progress.toFloat() / 1000f * duration).toLong()
                        updateTimeViews(newTime, duration)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTracking = true
                hideHandler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTracking = false
                val progress = seekBar?.progress ?: 0
                val duration = getEffectiveDuration()
                if (duration > 0L) {
                    val seekTime = (progress.toFloat() / 1000f * duration).toLong()
                    mediaPlayer?.time = seekTime
                }
                scheduleControlsAutoHide()
            }
        })
    }

    private fun initVlcAndPlay(fileId: Int, videoTitle: String) {
        try {
            val options = ArrayList<String>().apply {
                add("--http-reconnect")
                add("--network-caching=2000")
                add("--avcodec-skiploopfilter=1")
                add("--avcodec-hw=any")
                add("--aout=opensles")
                add("--audio-time-stretch")
                add("-vv")
            }

            val vlc = LibVLC(this, options)
            libVLC = vlc

            val player = MediaPlayer(vlc)
            mediaPlayer = player

            try {
                player.attachViews(vlcVideoLayout, null, true, false)
            } catch (e: Throwable) {
                android.util.Log.w("VlcPlayer", "SurfaceView attach failed, fallback to TextureView", e)
                try {
                    player.attachViews(vlcVideoLayout, null, true, true)
                } catch (e2: Throwable) {
                    android.util.Log.e("VlcPlayer", "attachViews fallback failed", e2)
                }
            }

            val rawStreamUrl = intent.getStringExtra("STREAM_URL")
            val proxyUrl = if (!rawStreamUrl.isNullOrBlank()) rawStreamUrl else TelegramStreamingProxy.getUrl(fileId, videoTitle)

            TeleflixLogger.log("VlcPlayer", "Initializing VLC playback for '$videoTitle' (fileId=$fileId) url=$proxyUrl")

            val isAudio = videoTitle.endsWith(".m4a", ignoreCase = true) ||
                          videoTitle.endsWith(".mp3", ignoreCase = true) ||
                          videoTitle.endsWith(".ogg", ignoreCase = true) ||
                          videoTitle.endsWith(".flac", ignoreCase = true) ||
                          videoTitle.endsWith(".wav", ignoreCase = true) ||
                          videoTitle.endsWith(".aac", ignoreCase = true) ||
                          videoTitle.endsWith(".opus", ignoreCase = true)

            hasResumedPosition = false
            val bufferMb = PlayerPreferences.getBufferSizeMb(this)
            TelegramStreamingProxy.setPrefetchMb(bufferMb.toLong())
            val cachingMs = PlayerPreferences.getNetworkCachingMs(this)

            val media = Media(vlc, Uri.parse(proxyUrl)).apply {
                if (!isAudio) {
                    setHWDecoderEnabled(true, false)
                }
                addOption(":network-caching=$cachingMs")
                addOption(":file-caching=$cachingMs")
                addOption(":live-caching=$cachingMs")
                addOption(":clock-jitter=0")
                addOption(":clock-synchro=0")
            }

            player.setEventListener { event ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    when (event.type) {
                        MediaPlayer.Event.Buffering -> {
                            if (event.buffering < 100f) {
                                pbVlcBuffering.visibility = View.VISIBLE
                            } else {
                                pbVlcBuffering.visibility = View.GONE
                            }
                        }
                        MediaPlayer.Event.Playing -> {
                            pbVlcBuffering.visibility = View.GONE
                            ivPlayPauseIcon.setImageResource(R.drawable.ic_vlc_pause)
                            TeleflixLogger.log("VlcPlayer", "Playback state: PLAYING")

                            if (!hasResumedPosition && currentFileId > 0) {
                                hasResumedPosition = true
                                val savedPos = PlayerPreferences.getSavedPlaybackPosition(this@VlcPlayerActivity, currentFileId)
                                if (savedPos > 3000L) {
                                    player.time = savedPos
                                    val formatted = formatTime(savedPos)
                                    showGestureHud("⏱️", "Resumed from $formatted", -1)
                                    Toast.makeText(this@VlcPlayerActivity, "Resumed playback from $formatted", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        MediaPlayer.Event.Paused -> {
                            ivPlayPauseIcon.setImageResource(R.drawable.ic_vlc_play)
                            TeleflixLogger.log("VlcPlayer", "Playback state: PAUSED")
                            saveCurrentPlaybackPosition()
                        }
                        MediaPlayer.Event.EndReached -> {
                            ivPlayPauseIcon.setImageResource(R.drawable.ic_vlc_play)
                            pbVlcBuffering.visibility = View.GONE
                            TeleflixLogger.log("VlcPlayer", "Playback state: END REACHED")
                            if (currentFileId > 0) {
                                PlayerPreferences.clearPlaybackPosition(this@VlcPlayerActivity, currentFileId)
                            }
                        }
                        MediaPlayer.Event.TimeChanged -> {
                            if (!isUserTracking) {
                                val time = player.time
                                val length = getEffectiveDuration()
                                if (length > 0L) {
                                    val clampedTime = time.coerceIn(0L, length)
                                    val progress = ((clampedTime.toFloat() / length.toFloat()) * 1000).toInt().coerceIn(0, 1000)
                                    sbVlcProgress.progress = progress
                                    updateTimeViews(clampedTime, length)
                                } else if (time > 0L) {
                                    tvVlcCurrentTime.text = formatTime(time)
                                }

                                val now = System.currentTimeMillis()
                                if (now - lastPositionSaveTime > 5000L && currentFileId > 0) {
                                    lastPositionSaveTime = now
                                    saveCurrentPlaybackPosition()
                                }
                            }
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            pbVlcBuffering.visibility = View.GONE
                            TeleflixLogger.log("VlcPlayer", "Playback error encountered in VLC engine", isError = true)
                            Toast.makeText(this@VlcPlayerActivity, "VLC encountered a playback error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            player.media = media
            media.release()
            player.play()

        } catch (e: Throwable) {
            TeleflixLogger.log("VlcPlayer", "LibVLC init exception: ${e.message}", isError = true)
            android.util.Log.e("VlcPlayerActivity", "LibVLC init exception", e)
            Toast.makeText(this, "LibVLC error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                ivPlayPauseIcon.setImageResource(R.drawable.ic_vlc_play)
            } else {
                player.play()
                ivPlayPauseIcon.setImageResource(R.drawable.ic_vlc_pause)
            }
        }
    }

    private fun seekRelative(deltaMs: Long) {
        mediaPlayer?.let { player ->
            val curr = player.time
            val dur = getEffectiveDuration()
            val target = (curr + deltaMs).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
            player.time = target
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            ivFloatingLockIcon.setImageResource(R.drawable.ic_vlc_lock)
            btnVlcLock.setImageResource(R.drawable.ic_vlc_lock)
            layoutTopBar.visibility = View.GONE
            layoutBottomBar.visibility = View.GONE
            btnVlcFloatingLock.visibility = View.VISIBLE
            Toast.makeText(this, "Screen locked 🔒", Toast.LENGTH_SHORT).show()
        } else {
            ivFloatingLockIcon.setImageResource(R.drawable.ic_vlc_unlock)
            btnVlcLock.setImageResource(R.drawable.ic_vlc_unlock)
            layoutTopBar.visibility = View.VISIBLE
            layoutBottomBar.visibility = View.VISIBLE
            btnVlcFloatingLock.visibility = View.VISIBLE
            Toast.makeText(this, "Screen unlocked 🔓", Toast.LENGTH_SHORT).show()
        }
        scheduleControlsAutoHide()
    }

    private fun cycleAspectRatio() {
        val player = mediaPlayer ?: return
        currentAspectIndex = (currentAspectIndex + 1) % aspectModes.size
        val mode = aspectModes[currentAspectIndex]

        val rootW = if (vlcRoot.width > 0) vlcRoot.width else resources.displayMetrics.widthPixels
        val rootH = if (vlcRoot.height > 0) vlcRoot.height else resources.displayMetrics.heightPixels

        when (currentAspectIndex) {
            0 -> {
                // Best Fit (letterboxed, original proportions)
                player.aspectRatio = null
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
            1 -> {
                // Zoom to Fill (crops without stretch)
                player.aspectRatio = null
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            }
            2 -> {
                // Stretch Fullscreen
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
                player.aspectRatio = "$rootW:$rootH"
            }
            3 -> {
                // 16:9
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                player.aspectRatio = "16:9"
            }
            4 -> {
                // 4:3
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                player.aspectRatio = "4:3"
            }
        }
        vlcVideoLayout.requestLayout()
        Toast.makeText(this, "Aspect: $mode", Toast.LENGTH_SHORT).show()
    }

    private fun showTrackSelectionDialog(title: String) {
        val player = mediaPlayer ?: return
        val audioTracks = player.audioTracks
        val spuTracks = player.spuTracks

        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        audioTracks?.forEach { track ->
            items.add("🎵 Audio: ${track.name}")
            actions.add { player.setAudioTrack(track.id) }
        }

        items.add("🚫 Disable Subtitles")
        actions.add { player.setSpuTrack(-1) }

        spuTracks?.forEach { track ->
            items.add("💬 Subtitle: ${track.name}")
            actions.add { player.setSpuTrack(track.id) }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .show()
    }

    private fun showMoreOptionsDialog() {
        val options = arrayOf(
            "⏱️ Playback Speed (${speedLabels[currentSpeedIndex]})",
            "🔄 Screen Rotation (${orientationModes[currentOrientationIndex].first})",
            "💬 Subtitles / Audio Tracks",
            "📐 Aspect Ratio (${aspectModes[currentAspectIndex]})",
            "⏩ Jump to Time",
            "⏲️ Sleep Timer",
            "🔊 Audio Boost (Up to 200%)",
            "🖼️ Picture-in-Picture"
        )

        AlertDialog.Builder(this)
            .setTitle("VLC Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSpeedDialog()
                    1 -> showRotationDialog()
                    2 -> showTrackSelectionDialog("Subtitles & Audio Tracks")
                    3 -> cycleAspectRatio()
                    4 -> showJumpToTimeDialog()
                    5 -> showSleepTimerDialog()
                    6 -> showAudioBoostDialog()
                    7 -> enterPipMode()
                }
            }
            .show()
    }

    private fun cycleScreenRotation() {
        currentOrientationIndex = (currentOrientationIndex + 1) % orientationModes.size
        val (label, orientation) = orientationModes[currentOrientationIndex]
        requestedOrientation = orientation
        showGestureHud("🔄", label, -1)
        Toast.makeText(this, "Orientation: $label", Toast.LENGTH_SHORT).show()
    }

    private fun showRotationDialog() {
        val labels = orientationModes.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Screen Orientation / Rotation")
            .setItems(labels) { _, which ->
                currentOrientationIndex = which
                val (label, orientation) = orientationModes[which]
                requestedOrientation = orientation
                showGestureHud("🔄", label, -1)
                Toast.makeText(this, "Orientation: $label", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSpeedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setItems(speedLabels) { _, which ->
                currentSpeedIndex = which
                val speed = speedOptions[which]
                mediaPlayer?.rate = speed
                Toast.makeText(this, "Speed set to ${speedLabels[which]}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showJumpToTimeDialog() {
        val input = EditText(this).apply {
            hint = "e.g. 12:30 or 75"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        AlertDialog.Builder(this)
            .setTitle("Jump to Time")
            .setMessage("Enter time (MM:SS or minutes):")
            .setView(input)
            .setPositiveButton("Jump") { _, _ ->
                val text = input.text.toString().trim()
                val targetMs = parseTimeToMs(text)
                if (targetMs >= 0L) {
                    val dur = getEffectiveDuration()
                    mediaPlayer?.time = targetMs.coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
                } else {
                    Toast.makeText(this, "Invalid time format", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseTimeToMs(text: String): Long {
        if (text.isBlank()) return -1L
        return try {
            if (text.contains(":")) {
                val parts = text.split(":")
                if (parts.size == 2) {
                    val m = parts[0].toLong()
                    val s = parts[1].toLong()
                    (m * 60 + s) * 1000L
                } else if (parts.size == 3) {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val s = parts[2].toLong()
                    (h * 3600 + m * 60 + s) * 1000L
                } else -1L
            } else {
                val num = text.toLong()
                num * 60 * 1000L
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("15 Minutes", "30 Minutes", "45 Minutes", "60 Minutes", "Cancel Timer")
        AlertDialog.Builder(this)
            .setTitle("Sleep Timer")
            .setItems(options) { _, which ->
                if (which < 4) {
                    val minutes = arrayOf(15, 30, 45, 60)[which]
                    hideHandler.postDelayed({
                        try {
                            mediaPlayer?.pause()
                            finish()
                        } catch (_: Throwable) {}
                    }, minutes * 60 * 1000L)
                    Toast.makeText(this, "Sleep timer set for $minutes min", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Sleep timer canceled", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showAudioBoostDialog() {
        val levels = arrayOf("100% (Normal)", "125%", "150%", "175%", "200% (Max Boost)")
        val volInts = arrayOf(100, 125, 150, 175, 200)
        AlertDialog.Builder(this)
            .setTitle("Audio Boost")
            .setItems(levels) { _, which ->
                try {
                    mediaPlayer?.volume = volInts[which]
                    Toast.makeText(this, "Audio Volume: ${levels[which]}", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this, "Audio boost not supported: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun scheduleControlsAutoHide() {
        hideHandler.removeCallbacks(hideControlsRunnable)
        hideHandler.postDelayed(hideControlsRunnable, 4000L)
    }

    private fun formatTime(millis: Long): String {
        val totalSecs = millis / 1000
        val hrs = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    private fun hideSystemUI() {
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        } catch (e: Throwable) {
            try {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            } catch (_: Throwable) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    private fun saveCurrentPlaybackPosition() {
        val player = mediaPlayer ?: return
        val time = player.time
        val duration = getEffectiveDuration()
        if (time > 0L && currentFileId > 0) {
            PlayerPreferences.savePlaybackPosition(this, currentFileId, time, duration)
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPlaybackPosition()
        try {
            mediaPlayer?.pause()
        } catch (_: Throwable) {}
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPlaybackPosition()
        try {
            mediaPlayer?.pause()
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPlaybackPosition()
        hideHandler.removeCallbacksAndMessages(null)
        try {
            mediaPlayer?.stop()
            mediaPlayer?.detachViews()
            mediaPlayer?.release()
        } catch (e: Throwable) {
            android.util.Log.e("VlcPlayerActivity", "Error releasing MediaPlayer", e)
        }
        try {
            libVLC?.release()
        } catch (e: Throwable) {
            android.util.Log.e("VlcPlayerActivity", "Error releasing LibVLC", e)
        }
        mediaPlayer = null
        libVLC = null

        // Auto-clear cache for streamed audio & video after exiting the player
        if (currentFileId != 0) {
            TelegramStreamingProxy.clearStreamCache(currentFileId)
        }
    }
}
