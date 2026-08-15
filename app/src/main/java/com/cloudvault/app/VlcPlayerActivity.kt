package com.cloudvault.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

class VlcPlayerActivity : AppCompatActivity() {

    private lateinit var vlcRoot: FrameLayout
    private lateinit var vlcVideoLayout: VLCVideoLayout
    private lateinit var pbVlcBuffering: ProgressBar
    private lateinit var layoutControlsOverlay: FrameLayout

    private lateinit var btnVlcBack: FrameLayout
    private lateinit var tvVlcTitle: TextView
    private lateinit var btnVlcSubtitles: TextView
    private lateinit var btnVlcSettings: TextView

    private lateinit var btnVlcRewind: FrameLayout
    private lateinit var btnVlcPlayPause: FrameLayout
    private lateinit var tvPlayPauseIcon: TextView
    private lateinit var btnVlcForward: FrameLayout

    private lateinit var tvVlcCurrentTime: TextView
    private lateinit var sbVlcProgress: SeekBar
    private lateinit var tvVlcTotalDuration: TextView

    private lateinit var btnVlcLock: LinearLayout
    private lateinit var tvLockIcon: TextView
    private lateinit var btnVlcSpeed: LinearLayout
    private lateinit var tvSpeedLabel: TextView
    private lateinit var btnVlcPrev: TextView
    private lateinit var btnVlcNext: TextView
    private lateinit var btnVlcFullscreen: LinearLayout
    private lateinit var tvFullscreenLabel: TextView

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null

    private var isUserTracking = false
    private var isLocked = false
    private var currentSpeedIndex = 1
    private val speedOptions = arrayOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = arrayOf("0.5x", "1.0x", "1.25x", "1.5x", "2.0x")

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable {
        if (!isLocked) {
            layoutControlsOverlay.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemUI()

        setContentView(R.layout.activity_vlc_player)

        val fileId = intent.getIntExtra("FILE_ID", 0)
        val title = intent.getStringExtra("TITLE") ?: "Video"
        val chatId = intent.getLongExtra("CHAT_ID", 0L)
        val messageId = intent.getLongExtra("MESSAGE_ID", 0L)

        if (fileId != 0 && chatId != 0L && messageId != 0L) {
            TelegramStreamingProxy.registerFileMessage(fileId, chatId, messageId)
        }

        vlcRoot = findViewById(R.id.vlcRoot)
        vlcVideoLayout = findViewById(R.id.vlcVideoLayout)
        pbVlcBuffering = findViewById(R.id.pbVlcBuffering)
        layoutControlsOverlay = findViewById(R.id.layoutControlsOverlay)

        btnVlcBack = findViewById(R.id.btnVlcBack)
        tvVlcTitle = findViewById(R.id.tvVlcTitle)
        btnVlcSubtitles = findViewById(R.id.btnVlcSubtitles)
        btnVlcSettings = findViewById(R.id.btnVlcSettings)

        btnVlcRewind = findViewById(R.id.btnVlcRewind)
        btnVlcPlayPause = findViewById(R.id.btnVlcPlayPause)
        tvPlayPauseIcon = findViewById(R.id.tvPlayPauseIcon)
        btnVlcForward = findViewById(R.id.btnVlcForward)

        tvVlcCurrentTime = findViewById(R.id.tvVlcCurrentTime)
        sbVlcProgress = findViewById(R.id.sbVlcProgress)
        tvVlcTotalDuration = findViewById(R.id.tvVlcTotalDuration)

        btnVlcLock = findViewById(R.id.btnVlcLock)
        tvLockIcon = findViewById(R.id.tvLockIcon)
        btnVlcSpeed = findViewById(R.id.btnVlcSpeed)
        tvSpeedLabel = findViewById(R.id.tvSpeedLabel)
        btnVlcPrev = findViewById(R.id.btnVlcPrev)
        btnVlcNext = findViewById(R.id.btnVlcNext)
        btnVlcFullscreen = findViewById(R.id.btnVlcFullscreen)
        tvFullscreenLabel = findViewById(R.id.tvFullscreenLabel)

        tvVlcTitle.text = title

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

        btnVlcSettings.setOnClickListener {
            showSpeedDialog()
            scheduleControlsAutoHide()
        }

        btnVlcSpeed.setOnClickListener {
            cycleSpeed()
            scheduleControlsAutoHide()
        }

        btnVlcLock.setOnClickListener {
            toggleLock()
        }

        btnVlcPrev.setOnClickListener {
            seekRelative(-30000L)
            scheduleControlsAutoHide()
        }

        btnVlcNext.setOnClickListener {
            seekRelative(30000L)
            scheduleControlsAutoHide()
        }

        btnVlcFullscreen.setOnClickListener {
            cycleAspectRatio()
            scheduleControlsAutoHide()
        }

        vlcRoot.setOnClickListener {
            if (isLocked) {
                // Show only unlock button briefly
                layoutControlsOverlay.visibility = View.VISIBLE
                scheduleControlsAutoHide()
            } else {
                if (layoutControlsOverlay.visibility == View.VISIBLE) {
                    layoutControlsOverlay.visibility = View.GONE
                } else {
                    layoutControlsOverlay.visibility = View.VISIBLE
                    scheduleControlsAutoHide()
                }
            }
        }

        setupSeekBar()
        initVlcAndPlay(fileId, title)
        scheduleControlsAutoHide()
    }

    private fun setupSeekBar() {
        sbVlcProgress.max = 1000
        sbVlcProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = mediaPlayer?.length ?: 0L
                    if (duration > 0L) {
                        val newTime = (progress.toFloat() / 1000f * duration).toLong()
                        tvVlcCurrentTime.text = formatTime(newTime)
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
                val duration = mediaPlayer?.length ?: 0L
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
                add("--no-drop-late-frames")
                add("--no-skip-frames")
                add("--http-reconnect")
                add("-vvv")
            }

            val vlc = LibVLC(this, options)
            libVLC = vlc

            val player = MediaPlayer(vlc)
            mediaPlayer = player
            player.attachViews(vlcVideoLayout, null, true, true)

            val rawStreamUrl = intent.getStringExtra("STREAM_URL")
            val proxyUrl = if (!rawStreamUrl.isNullOrBlank()) rawStreamUrl else TelegramStreamingProxy.getUrl(fileId, videoTitle)

            val media = Media(vlc, Uri.parse(proxyUrl)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=1500")
            }

            player.setEventListener { event ->
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
                        tvPlayPauseIcon.text = "⏸"
                    }
                    MediaPlayer.Event.Paused -> {
                        tvPlayPauseIcon.text = "▶"
                    }
                    MediaPlayer.Event.EndReached -> {
                        tvPlayPauseIcon.text = "▶"
                        pbVlcBuffering.visibility = View.GONE
                    }
                    MediaPlayer.Event.TimeChanged -> {
                        if (!isUserTracking) {
                            val time = player.time
                            val length = player.length
                            if (length > 0L) {
                                val progress = ((time.toFloat() / length.toFloat()) * 1000).toInt()
                                sbVlcProgress.progress = progress
                                tvVlcCurrentTime.text = formatTime(time)
                                tvVlcTotalDuration.text = formatTime(length)
                            }
                        }
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        pbVlcBuffering.visibility = View.GONE
                        Toast.makeText(this, "VLC encountered a playback error", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            player.media = media
            media.release()
            player.play()

        } catch (e: Throwable) {
            android.util.Log.e("VlcPlayerActivity", "LibVLC init exception", e)
            Toast.makeText(this, "LibVLC error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                tvPlayPauseIcon.text = "▶"
            } else {
                player.play()
                tvPlayPauseIcon.text = "⏸"
            }
        }
    }

    private fun seekRelative(deltaMs: Long) {
        mediaPlayer?.let { player ->
            val curr = player.time
            val dur = player.length
            val target = (curr + deltaMs).coerceIn(0L, if (dur > 0L) dur else Long.MAX_VALUE)
            player.time = target
        }
    }

    private fun toggleLock() {
        isLocked = !isLocked
        tvLockIcon.text = if (isLocked) "🔓" else "🔒"
        Toast.makeText(this, if (isLocked) "Screen locked" else "Screen unlocked", Toast.LENGTH_SHORT).show()
        scheduleControlsAutoHide()
    }

    private fun cycleSpeed() {
        currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
        val speed = speedOptions[currentSpeedIndex]
        mediaPlayer?.rate = speed
        tvSpeedLabel.text = speedLabels[currentSpeedIndex]
        Toast.makeText(this, "Speed: ${speedLabels[currentSpeedIndex]}", Toast.LENGTH_SHORT).show()
    }

    private fun showSpeedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setItems(speedLabels) { _, which ->
                currentSpeedIndex = which
                val speed = speedOptions[which]
                mediaPlayer?.rate = speed
                tvSpeedLabel.text = speedLabels[which]
            }
            .show()
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

    private var currentAspectIndex = 0
    private val aspectModes = arrayOf("Best Fit", "Zoom to Fill", "Stretch (Full)")

    private fun cycleAspectRatio() {
        val player = mediaPlayer ?: return
        currentAspectIndex = (currentAspectIndex + 1) % aspectModes.size
        val mode = aspectModes[currentAspectIndex]

        val rootW = if (vlcRoot.width > 0) vlcRoot.width else resources.displayMetrics.widthPixels
        val rootH = if (vlcRoot.height > 0) vlcRoot.height else resources.displayMetrics.heightPixels

        when (currentAspectIndex) {
            0 -> {
                // 1. Best Fit (Entire video visible, original aspect ratio with letterboxing)
                player.aspectRatio = null
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
            1 -> {
                // 2. Zoom to Fill (Crops top/bottom or sides without distortion to remove black bars)
                player.aspectRatio = null
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            }
            2 -> {
                // 3. Stretch (Full screen edge-to-edge stretch)
                player.scale = 0f
                player.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
                player.aspectRatio = "$rootW:$rootH"
            }
        }
        vlcVideoLayout.requestLayout()
        tvFullscreenLabel.text = mode
        Toast.makeText(this, "Screen: $mode", Toast.LENGTH_SHORT).show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onPause() {
        super.onPause()
        mediaPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        mediaPlayer?.release()
        libVLC?.release()
    }
}
