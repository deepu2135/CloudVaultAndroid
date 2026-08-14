package com.cloudvault.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnSettings: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnTabPhotos: Button
    private lateinit var btnTabVideos: Button
    private lateinit var btnTabFiles: Button
    private lateinit var playerContainer: FrameLayout
    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvEmptyEmoji: TextView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var pbLoading: ProgressBar

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private lateinit var mediaAdapter: MediaGridAdapter
    private var currentCategory: MediaType = MediaType.PHOTO

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        btnSettings = findViewById(R.id.btnSettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnTabPhotos = findViewById(R.id.btnTabPhotos)
        btnTabVideos = findViewById(R.id.btnTabVideos)
        btnTabFiles = findViewById(R.id.btnTabFiles)
        playerContainer = findViewById(R.id.playerContainer)
        rvMediaGrid = findViewById(R.id.rvMediaGrid)
        layoutEmptyState = findViewById(R.id.layoutEmptyState)
        tvEmptyEmoji = findViewById(R.id.tvEmptyEmoji)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptySubtitle = findViewById(R.id.tvEmptySubtitle)
        pbLoading = findViewById(R.id.pbLoading)

        setupRecyclerView()

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
            pbLoading.visibility = View.VISIBLE
            lifecycleScope.launch {
                TelegramRepository.loadVaultItems()
                pbLoading.visibility = View.GONE
            }
        }

        btnTabPhotos.setOnClickListener { switchCategory(MediaType.PHOTO) }
        btnTabVideos.setOnClickListener { switchCategory(MediaType.VIDEO) }
        btnTabFiles.setOnClickListener { switchCategory(MediaType.DOCUMENT) }

        observeAuthState()
        observeVaultItems()

        // Set initial category tab
        switchCategory(MediaType.PHOTO)
    }

    private fun setupRecyclerView() {
        mediaAdapter = MediaGridAdapter(lifecycleScope) { item ->
            handleMediaItemClick(item)
        }
        rvMediaGrid.layoutManager = GridLayoutManager(this, 3)
        rvMediaGrid.adapter = mediaAdapter
    }

    private fun switchCategory(category: MediaType) {
        currentCategory = category

        // Update Tab Button Styles
        val activeBgColor = getColor(R.color.card_bg)
        btnTabPhotos.setBackgroundColor(if (category == MediaType.PHOTO) activeBgColor else 0)
        btnTabVideos.setBackgroundColor(if (category == MediaType.VIDEO) activeBgColor else 0)
        btnTabFiles.setBackgroundColor(if (category == MediaType.DOCUMENT) activeBgColor else 0)

        // Update Grid Span: 3 for photos, 2 for videos, 1 for files
        val spanCount = when (category) {
            MediaType.PHOTO -> 3
            MediaType.VIDEO -> 2
            MediaType.DOCUMENT -> 1
        }
        rvMediaGrid.layoutManager = GridLayoutManager(this, spanCount)

        updateDisplayedItems()
    }

    private fun updateDisplayedItems() {
        val items = when (currentCategory) {
            MediaType.PHOTO -> TelegramRepository.photos.value
            MediaType.VIDEO -> TelegramRepository.videos.value
            MediaType.DOCUMENT -> TelegramRepository.files.value
        }

        mediaAdapter.submitList(items)

        if (items.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvMediaGrid.visibility = View.GONE
            when (currentCategory) {
                MediaType.PHOTO -> {
                    tvEmptyEmoji.text = "📷"
                    tvEmptyTitle.text = "No Photos Found"
                    tvEmptySubtitle.text = "Photos sent to your Telegram Saved Messages will appear here in a grid."
                }
                MediaType.VIDEO -> {
                    tvEmptyEmoji.text = "🎬"
                    tvEmptyTitle.text = "No Videos Found"
                    tvEmptySubtitle.text = "Videos in your Telegram Cloud will appear here ready to stream."
                }
                MediaType.DOCUMENT -> {
                    tvEmptyEmoji.text = "📄"
                    tvEmptyTitle.text = "No Files Found"
                    tvEmptySubtitle.text = "Documents and files from your Telegram Vault will appear here."
                }
            }
        } else {
            layoutEmptyState.visibility = View.GONE
            rvMediaGrid.visibility = View.VISIBLE
        }
    }

    private fun handleMediaItemClick(item: VaultMediaItem) {
        when (item.type) {
            MediaType.PHOTO -> showPhotoViewerDialog(item)
            MediaType.VIDEO -> playVideoViaProxy(item.fileId, item.title)
            MediaType.DOCUMENT -> showFileDownloadPrompt(item)
        }
    }

    private fun showPhotoViewerDialog(item: VaultMediaItem) {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_photo_viewer, null)
        val ivFullPhoto: ImageView = dialogView.findViewById(R.id.ivFullPhoto)
        val pbFullPhotoLoading: ProgressBar = dialogView.findViewById(R.id.pbFullPhotoLoading)
        val tvViewerTitle: TextView = dialogView.findViewById(R.id.tvViewerTitle)
        val tvViewerSize: TextView = dialogView.findViewById(R.id.tvViewerSize)
        val btnDownloadPhoto: Button = dialogView.findViewById(R.id.btnDownloadPhoto)
        val btnCloseViewer: Button = dialogView.findViewById(R.id.btnCloseViewer)

        tvViewerTitle.text = item.title
        tvViewerSize.text = item.formattedSize

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(dialogView)
            .create()

        btnCloseViewer.setOnClickListener { dialog.dismiss() }
        btnDownloadPhoto.setOnClickListener {
            DownloadManager.startDownload(this, item)
            Toast.makeText(this, "Downloading ${item.title} to storage...", Toast.LENGTH_SHORT).show()
        }

        dialog.show()

        // Load full resolution photo in background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var tdFile = TelegramClient.sendRequest(TdApi.GetFile(item.fileId)) as TdApi.File
                if (!tdFile.local.isDownloadingCompleted || tdFile.local.path.isBlank() || !File(tdFile.local.path).exists()) {
                    TelegramClient.sendRequest(TdApi.DownloadFile(item.fileId, 32, 0L, 0L, false))
                    var attempts = 0
                    while (attempts < 40) {
                        delay(250)
                        tdFile = TelegramClient.sendRequest(TdApi.GetFile(item.fileId)) as TdApi.File
                        if (tdFile.local.isDownloadingCompleted && File(tdFile.local.path).exists()) {
                            break
                        }
                        attempts++
                    }
                }

                val path = tdFile.local.path
                if (path.isNotBlank() && File(path).exists()) {
                    val bitmap = BitmapFactory.decodeFile(path)
                    withContext(Dispatchers.Main) {
                        pbFullPhotoLoading.visibility = View.GONE
                        ivFullPhoto.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    pbFullPhotoLoading.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Failed to load full photo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showFileDownloadPrompt(item: VaultMediaItem) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setMessage("Size: ${item.formattedSize}\nType: ${item.mimeType}\n\nWould you like to download this file to your device?")
            .setPositiveButton("Download") { _, _ ->
                DownloadManager.startDownload(this, item)
                Toast.makeText(this, "Downloading ${item.title}...", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun playVideoViaProxy(fileId: Int, videoTitle: String) {
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
            Toast.makeText(this, "Streaming $videoTitle...", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to play video", e)
            Toast.makeText(this, "Playback Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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
                        tvStatus.text = "Status: Connected to Telegram Cloud ☁️"
                        pbLoading.visibility = View.VISIBLE
                        TelegramRepository.loadVaultItems()
                        pbLoading.visibility = View.GONE
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
            TelegramRepository.photos.collectLatest {
                updateDisplayedItems()
            }
        }
        lifecycleScope.launch {
            TelegramRepository.videos.collectLatest {
                updateDisplayedItems()
            }
        }
        lifecycleScope.launch {
            TelegramRepository.files.collectLatest {
                updateDisplayedItems()
            }
        }
    }

    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Telegram API Credentials")
        builder.setMessage("Get your free API ID & API Hash from https://my.telegram.org (API development tools).")

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
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

        val container = FrameLayout(this)
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

        val container = FrameLayout(this)
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

        val container = FrameLayout(this)
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
