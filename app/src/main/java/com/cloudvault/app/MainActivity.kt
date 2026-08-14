package com.cloudvault.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnTabPhotos: Button
    private lateinit var btnTabVideos: Button
    private lateinit var btnTabFiles: Button
    private lateinit var tvContentSummary: TextView
    private lateinit var playerContainer: FrameLayout

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnSettings = findViewById(R.id.btnSettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnTabPhotos = findViewById(R.id.btnTabPhotos)
        btnTabVideos = findViewById(R.id.btnTabVideos)
        btnTabFiles = findViewById(R.id.btnTabFiles)
        tvContentSummary = findViewById(R.id.tvContentSummary)
        playerContainer = findViewById(R.id.playerContainer)

        try {
            // Start Local Range Streaming Proxy on background thread
            TelegramStreamingProxy.start()

            // Initialize TDLib
            TelegramClient.initialize(applicationContext)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "App init exception", e)
            tvStatus.text = "Init Warning: ${e.message}"
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnRefresh.setOnClickListener {
            lifecycleScope.launch {
                TelegramRepository.loadVaultItems()
            }
        }

        btnTabPhotos.setOnClickListener { showPhotos() }
        btnTabVideos.setOnClickListener { showVideos() }
        btnTabFiles.setOnClickListener { showFiles() }

        observeAuthState()
        observeVaultItems()
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            TelegramClient.authState.collectLatest { state ->
                when (state) {
                    is TelegramAuthState.Idle -> tvStatus.text = "Status: Idle"
                    is TelegramAuthState.Initializing -> tvStatus.text = "Status: Initializing TDLib..."
                    is TelegramAuthState.WaitTdlibParameters -> tvStatus.text = "Status: Setup API Credentials in Settings"
                    is TelegramAuthState.WaitPhoneNumber -> showPhoneInputDialog()
                    is TelegramAuthState.WaitCode -> showCodeInputDialog()
                    is TelegramAuthState.WaitPassword -> showPasswordInputDialog()
                    is TelegramAuthState.Ready -> {
                        tvStatus.text = "Status: Connected to Telegram Cloud"
                        TelegramRepository.loadVaultItems()
                    }
                    is TelegramAuthState.Error -> {
                        tvStatus.text = "Status: Error (${state.message})"
                    }
                }
            }
        }
    }

    private fun observeVaultItems() {
        lifecycleScope.launch {
            TelegramRepository.photos.collectLatest { photos ->
                if (photos.isNotEmpty()) {
                    tvContentSummary.text = "Loaded ${photos.size} Photos, ${TelegramRepository.videos.value.size} Videos, ${TelegramRepository.files.value.size} Files"
                }
            }
        }
    }

    private fun showPhotos() {
        val photos = TelegramRepository.photos.value
        val text = if (photos.isEmpty()) "No photos found in Vault." else photos.joinToString("\n") {
            "📷 ${it.title} (${it.formattedSize})"
        }
        tvContentSummary.text = "--- PHOTOS ---\n$text"
    }

    private fun showVideos() {
        val videos = TelegramRepository.videos.value
        if (videos.isEmpty()) {
            tvContentSummary.text = "No videos found in Vault."
            return
        }
        val firstVideo = videos.first()
        tvContentSummary.text = "--- VIDEOS ---\nStreaming ${firstVideo.title} via Proxy port ${TelegramStreamingProxy.port}..."
        playVideoViaProxy(firstVideo.fileId)
    }

    private fun showFiles() {
        val files = TelegramRepository.files.value
        val text = if (files.isEmpty()) "No files found in Vault." else files.joinToString("\n") {
            "📄 ${it.title} (${it.formattedSize})"
        }
        tvContentSummary.text = "--- FILES & DOCUMENTS ---\n$text"
    }

    private fun playVideoViaProxy(fileId: Int) {
        try {
            if (playerView == null) {
                val pv = PlayerView(this)
                playerView = pv
                playerContainer.addView(pv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
            if (exoPlayer == null) {
                exoPlayer = ExoPlayer.Builder(this).build()
                playerView?.player = exoPlayer
            }
            playerContainer.visibility = View.VISIBLE
            val proxyUrl = "http://127.0.0.1:${TelegramStreamingProxy.port}/stream?file_id=$fileId"
            val mediaItem = MediaItem.fromUri(proxyUrl)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to play video", e)
            tvContentSummary.text = "Playback Error: ${e.message}"
        }
    }

    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("TDLib API Credentials")

        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(40, 20, 40, 20)

        val etApiId = EditText(this)
        etApiId.hint = "API ID (e.g., 123456)"
        etApiId.setText(TdlibManager.getApiId(this).let { if (it > 0) it.toString() else "" })
        layout.addView(etApiId)

        val etApiHash = EditText(this)
        etApiHash.hint = "API Hash (e.g., a1b2c3d4...)"
        etApiHash.setText(TdlibManager.getApiHash(this))
        layout.addView(etApiHash)

        builder.setView(layout)
        builder.setPositiveButton("Save Credentials") { _, _ ->
            val idStr = etApiId.text.toString().trim()
            val hashStr = etApiHash.text.toString().trim()
            val apiId = idStr.toIntOrNull() ?: 0
            if (apiId > 0 && hashStr.isNotBlank()) {
                TdlibManager.saveApiId(this, apiId)
                TdlibManager.saveApiHash(this, hashStr)
                Toast.makeText(this, "Credentials saved! Reloading TDLib...", Toast.LENGTH_SHORT).show()
                TelegramClient.initialize(applicationContext)
            } else {
                Toast.makeText(this, "Please enter valid API ID and API Hash", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showPhoneInputDialog() {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Connect Telegram Account")
        builder.setMessage("Enter your phone number with country code (e.g., +14155552671):")

        val input = EditText(this)
        builder.setView(input)

        builder.setPositiveButton("Send Code") { _, _ ->
            val phone = input.text.toString().trim()
            if (phone.isNotBlank()) {
                TelegramClient.setPhoneNumber(phone)
            }
        }
        builder.show()
    }

    private fun showCodeInputDialog() {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Telegram Verification Code")
        builder.setMessage("Enter the code sent to your Telegram app:")

        val input = EditText(this)
        builder.setView(input)

        builder.setPositiveButton("Submit") { _, _ ->
            val code = input.text.toString().trim()
            if (code.isNotBlank()) {
                TelegramClient.checkCode(code)
            }
        }
        builder.show()
    }

    private fun showPasswordInputDialog() {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Two-Step Verification (2FA)")
        builder.setMessage("Enter your 2FA password:")

        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        builder.setView(input)

        builder.setPositiveButton("Submit") { _, _ ->
            val pass = input.text.toString().trim()
            if (pass.isNotBlank()) {
                TelegramClient.checkPassword(pass)
            }
        }
        builder.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        TelegramStreamingProxy.stop()
    }
}
