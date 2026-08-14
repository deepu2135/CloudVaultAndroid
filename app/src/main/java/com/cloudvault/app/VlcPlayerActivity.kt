package com.cloudvault.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

class VlcPlayerActivity : AppCompatActivity() {

    private lateinit var vlcVideoLayout: VLCVideoLayout
    private lateinit var layoutControlsOverlay: View
    private lateinit var pbVlcBuffering: ProgressBar
    private lateinit var btnVlcBack: ImageButton
    private lateinit var tvVlcTitle: TextView
    private lateinit var btnVlcPlayPause: MaterialButton
    private lateinit var btnVlcRewind: MaterialButton
    private lateinit var btnVlcForward: MaterialButton
    private lateinit var sbVlcProgress: SeekBar
    private lateinit var tvVlcCurrentTime: TextView
    private lateinit var tvVlcTotalDuration: TextView

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isUserSeeking = false
    private var controlsVisible = true

    private val hideControlsRunnable = Runnable {
        hideControls()
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (!isUserSeeking && player.isPlaying) {
                    val time = player.time
                    val length = player.length
                    if (length > 0) {
                        val progress = ((time.toDouble() / length) * 1000).toInt()
                        sbVlcProgress.progress = progress
                        tvVlcCurrentTime.text = formatTime(time)
                        tvVlcTotalDuration.text = formatTime(length)
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vlc_player)

        val fileId = intent.getIntExtra("FILE_ID", 0)
        val title = intent.getStringExtra("TITLE") ?: "Video"

        vlcVideoLayout = findViewById(R.id.vlcVideoLayout)
        layoutControlsOverlay = findViewById(R.id.layoutControlsOverlay)
        pbVlcBuffering = findViewById(R.id.pbVlcBuffering)
        btnVlcBack = findViewById(R.id.btnVlcBack)
        tvVlcTitle = findViewById(R.id.tvVlcTitle)
        btnVlcPlayPause = findViewById(R.id.btnVlcPlayPause)
        btnVlcRewind = findViewById(R.id.btnVlcRewind)
        btnVlcForward = findViewById(R.id.btnVlcForward)
        sbVlcProgress = findViewById(R.id.sbVlcProgress)
        tvVlcCurrentTime = findViewById(R.id.tvVlcCurrentTime)
        tvVlcTotalDuration = findViewById(R.id.tvVlcTotalDuration)

        tvVlcTitle.text = title
        sbVlcProgress.max = 1000

        btnVlcBack.setOnClickListener { finish() }

        btnVlcPlayPause.setOnClickListener {
            togglePlayPause()
            resetControlsHideTimer()
        }

        btnVlcRewind.setOnClickListener {
            seekRelative(-10000)
            resetControlsHideTimer()
        }

        btnVlcForward.setOnClickListener {
            seekRelative(10000)
            resetControlsHideTimer()
        }

        sbVlcProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val length = mediaPlayer?.length ?: 0L
                    if (length > 0) {
                        val targetTime = (length * (progress / 1000.0)).toLong()
                        tvVlcCurrentTime.text = formatTime(targetTime)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val length = mediaPlayer?.length ?: 0L
                if (length > 0) {
                    val targetTime = (length * ((seekBar?.progress ?: 0) / 1000.0)).toLong()
                    mediaPlayer?.time = targetTime
                }
                resetControlsHideTimer()
            }
        })

        findViewById<View>(R.id.vlcRoot).setOnClickListener {
            if (controlsVisible) hideControls() else showControls()
        }

        initVlcAndPlay(fileId)
    }

    private fun initVlcAndPlay(fileId: Int) {
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
            player.attachViews(vlcVideoLayout, null, false, false)

            val proxyUrl = "http://127.0.0.1:${TelegramStreamingProxy.port}/stream?file_id=$fileId"
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
                        btnVlcPlayPause.text = "⏸"
                    }
                    MediaPlayer.Event.Paused -> {
                        btnVlcPlayPause.text = "▶"
                    }
                    MediaPlayer.Event.EndReached -> {
                        btnVlcPlayPause.text = "▶"
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        pbVlcBuffering.visibility = View.GONE
                        Toast.makeText(this, "VLC Playback Error", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            player.media = media
            media.release()
            player.play()

            handler.post(updateProgressRunnable)
            resetControlsHideTimer()

        } catch (e: Throwable) {
            android.util.Log.e("VlcPlayerActivity", "Failed to start LibVLC", e)
            Toast.makeText(this, "VLC Init Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlayPause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                btnVlcPlayPause.text = "▶"
            } else {
                player.play()
                btnVlcPlayPause.text = "⏸"
            }
        }
    }

    private fun seekRelative(offsetMs: Long) {
        mediaPlayer?.let { player ->
            val currentTime = player.time
            val length = player.length
            val newTime = (currentTime + offsetMs).coerceIn(0L, length)
            player.time = newTime
        }
    }

    private fun showControls() {
        controlsVisible = true
        layoutControlsOverlay.visibility = View.VISIBLE
        resetControlsHideTimer()
    }

    private fun hideControls() {
        controlsVisible = false
        layoutControlsOverlay.visibility = View.GONE
    }

    private fun resetControlsHideTimer() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        mediaPlayer?.let { player ->
            player.stop()
            player.detachViews()
            player.release()
        }
        libVLC?.release()
        mediaPlayer = null
        libVLC = null
    }
}
