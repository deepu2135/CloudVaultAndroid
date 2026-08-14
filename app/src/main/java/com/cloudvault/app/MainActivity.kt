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
                    is TelegramAuthState.WaitTdlibParameters -> {
                        tvStatus.text = "Status: Tap ⚙️ Settings to enter API ID & Hash"
                        if (!TdlibManager.isCredentialsConfigured(this@MainActivity)) {
                            showSettingsDialog()
                        }
                    }
                    is TelegramAuthState.WaitPhoneNumber -> {
                        tvStatus.text = "Status: Waiting for phone number..."
                        showPhoneInputDialog()
                    }
                    is TelegramAuthState.WaitCode -> {
                        tvStatus.text = "Status: Waiting for verification code..."
                        showCodeInputDialog()
                    }
                    is TelegramAuthState.WaitPassword -> {
                        tvStatus.text = "Status: Waiting for 2FA password..."
                        showPasswordInputDialog()
                    }
                    is TelegramAuthState.Ready -> {
                        tvStatus.text = "Status: Connected to Telegram Cloud"
                        TelegramRepository.loadVaultItems()
                    }
                    is TelegramAuthState.Error -> {
                        tvStatus.text = "Status: Error (${state.message})"
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                        if (state.message.contains("Phone error", ignoreCase = true) ||
                            state.message.contains("PHONE_NUMBER", ignoreCase = true) ||
                            state.message.contains("API_ID", ignoreCase = true)) {
                            showErrorDialog(state.message)
                        }
                    }
                }
            }
        }
    }

    private fun showErrorDialog(errorMessage: String) {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Connection / Auth Notice")
        builder.setMessage(errorMessage + "\n\nTip: Double-check that your phone number has the country code (e.g. +91..., +1...) and that your API ID and Hash are correct from my.telegram.org.")
        builder.setPositiveButton("Retry Phone") { _, _ -> showPhoneInputDialog() }
        builder.setNeutralButton("Edit Settings") { _, _ -> showSettingsDialog() }
        builder.setNegativeButton("Dismiss", null)
        builder.show()
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
        builder.setTitle("Telegram API Credentials")
        builder.setMessage("Get your free API ID & API Hash from https://my.telegram.org (API development tools).")

        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(50, 20, 50, 20)

        val etApiId = EditText(this)
        etApiId.hint = "API ID (e.g. 12345678)"
        etApiId.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        etApiId.setText(TdlibManager.getApiId(this).let { if (it > 0) it.toString() else "" })
        layout.addView(etApiId)

        val etApiHash = EditText(this)
        etApiHash.hint = "API Hash (e.g. 0123456789abcdef0123456789abcdef)"
        etApiHash.setText(TdlibManager.getApiHash(this))
        layout.addView(etApiHash)

        builder.setView(layout)
        builder.setPositiveButton("Save & Connect") { _, _ ->
            val idStr = etApiId.text.toString().trim()
            val hashStr = etApiHash.text.toString().trim()
            val apiId = idStr.toIntOrNull() ?: 0
            if (apiId > 0 && hashStr.isNotBlank()) {
                TdlibManager.saveApiId(this, apiId)
                TdlibManager.saveApiHash(this, hashStr)
                Toast.makeText(this, "Credentials saved! Connecting...", Toast.LENGTH_SHORT).show()
                TelegramClient.sendTdlibParameters(applicationContext)
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
        builder.setMessage("Enter your phone number with country code (e.g., +919876543210 or +14155552671):")

        val container = android.widget.FrameLayout(this)
        container.setPadding(50, 20, 50, 20)
        val input = EditText(this)
        input.hint = "+1234567890"
        input.inputType = android.text.InputType.TYPE_CLASS_PHONE
        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Send Code") { _, _ ->
            val phone = input.text.toString().trim()
            if (phone.isNotBlank()) {
                Toast.makeText(this, "Requesting code for $phone...", Toast.LENGTH_SHORT).show()
                TelegramClient.setPhoneNumber(phone)
            } else {
                Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    private fun showCodeInputDialog() {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Telegram Verification Code")
        builder.setMessage("Enter the login code sent to your active Telegram app (chats list on your phone/desktop):\n\n(Note: Telegram sends the code in-app, not via SMS).")

        val container = android.widget.FrameLayout(this)
        container.setPadding(50, 20, 50, 20)
        val input = EditText(this)
        input.hint = "12345"
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Submit Code") { _, _ ->
            val code = input.text.toString().trim()
            if (code.isNotBlank()) {
                Toast.makeText(this, "Verifying code...", Toast.LENGTH_SHORT).show()
                TelegramClient.checkCode(code)
            }
        }
        builder.setNeutralButton("Resend Code") { _, _ ->
            Toast.makeText(this, "Requesting new code...", Toast.LENGTH_SHORT).show()
            TelegramClient.resendCode()
        }
        builder.setNegativeButton("Change Phone") { _, _ ->
            showPhoneInputDialog()
        }
        builder.show()
    }

    private fun showPasswordInputDialog() {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Two-Step Verification (2FA)")
        builder.setMessage("Your account has 2FA enabled. Enter your Cloud password:")

        val container = android.widget.FrameLayout(this)
        container.setPadding(50, 20, 50, 20)
        val input = EditText(this)
        input.hint = "Password"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        container.addView(input)
        builder.setView(container)

        builder.setPositiveButton("Submit Password") { _, _ ->
            val pass = input.text.toString().trim()
            if (pass.isNotBlank()) {
                Toast.makeText(this, "Verifying password...", Toast.LENGTH_SHORT).show()
                TelegramClient.checkPassword(pass)
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        TelegramStreamingProxy.stop()
    }
}
