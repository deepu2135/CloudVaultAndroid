package com.cloudvault.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class VaultSortOrder(val label: String) {
    NEWEST("Newest ⌵"),
    OLDEST("Oldest ⌵"),
    NAME_ASC("Name (A-Z) ⌵"),
    NAME_DESC("Name (Z-A) ⌵"),
    SIZE_DESC("Largest ⌵"),
    SIZE_ASC("Smallest ⌵")
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var cardStatusBanner: MaterialCardView
    private lateinit var btnTopUpload: MaterialButton
    private lateinit var btnQuickAdd: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var fabUpload: MaterialButton

    private lateinit var tabPhotosContainer: LinearLayout
    private lateinit var tvTabPhotosLabel: TextView
    private lateinit var tabPhotosIndicator: View

    private lateinit var tabVideosContainer: LinearLayout
    private lateinit var tvTabVideosLabel: TextView
    private lateinit var tabVideosIndicator: View

    private lateinit var tabFilesContainer: LinearLayout
    private lateinit var tvTabFilesLabel: TextView
    private lateinit var tabFilesIndicator: View

    private lateinit var tvSectionTitle: TextView
    private lateinit var btnSortFilter: MaterialButton
    private lateinit var btnGridToggle: TextView

    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvEmptyEmoji: TextView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var pbLoading: ProgressBar

    private lateinit var mediaAdapter: MediaGridAdapter
    private var currentCategory: MediaType = MediaType.PHOTO
    private var isGrid3Col = true

    private var currentSortOrder: VaultSortOrder = VaultSortOrder.NEWEST

    // Activity Result Launchers for picking single or multiple media items
    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            handleBatchMediaUpload(uris, MediaType.PHOTO)
        }
    }

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            handleBatchMediaUpload(uris, MediaType.VIDEO)
        }
    }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            handleBatchMediaUpload(uris, MediaType.DOCUMENT)
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        tvStatus = findViewById(R.id.tvStatus)
        cardStatusBanner = findViewById(R.id.cardStatusBanner)
        btnTopUpload = findViewById(R.id.btnTopUpload)
        btnQuickAdd = findViewById(R.id.btnQuickAdd)
        btnSettings = findViewById(R.id.btnSettings)
        fabUpload = findViewById(R.id.fabUpload)

        tabPhotosContainer = findViewById(R.id.tabPhotosContainer)
        tvTabPhotosLabel = findViewById(R.id.tvTabPhotosLabel)
        tabPhotosIndicator = findViewById(R.id.tabPhotosIndicator)

        tabVideosContainer = findViewById(R.id.tabVideosContainer)
        tvTabVideosLabel = findViewById(R.id.tvTabVideosLabel)
        tabVideosIndicator = findViewById(R.id.tabVideosIndicator)

        tabFilesContainer = findViewById(R.id.tabFilesContainer)
        tvTabFilesLabel = findViewById(R.id.tvTabFilesLabel)
        tabFilesIndicator = findViewById(R.id.tabFilesIndicator)

        tvSectionTitle = findViewById(R.id.tvSectionTitle)
        btnSortFilter = findViewById(R.id.btnSortFilter)
        btnGridToggle = findViewById(R.id.btnGridToggle)

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

            // Initialize Auto Backup (Background Worker + Realtime Media Observer)
            AutoBackupManager.initialize(applicationContext)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "App init exception", e)
            tvStatus.text = "Init Warning: ${e.message}"
        }

        btnTopUpload.setOnClickListener { showUploadChoiceDialog() }
        btnQuickAdd.setOnClickListener { showUploadChoiceDialog() }
        fabUpload.setOnClickListener { showUploadChoiceDialog() }

        btnSettings.setOnClickListener { showSettingsDialog() }
        cardStatusBanner.setOnClickListener {
            if (TelegramClient.authState.value !is TelegramAuthState.Ready) {
                showSettingsDialog()
            } else {
                Toast.makeText(this, "Refreshing Vault...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    TelegramRepository.loadVaultItems()
                }
            }
        }

        tabPhotosContainer.setOnClickListener { switchCategory(MediaType.PHOTO) }
        tabVideosContainer.setOnClickListener { switchCategory(MediaType.VIDEO) }
        tabFilesContainer.setOnClickListener { switchCategory(MediaType.DOCUMENT) }

        btnGridToggle.setOnClickListener {
            isGrid3Col = !isGrid3Col
            val spanCount = if (isGrid3Col) 3 else 2
            rvMediaGrid.layoutManager = GridLayoutManager(this, spanCount)
        }

        btnSortFilter.setOnClickListener {
            showSortDialog()
        }

        observeAuthState()
        observeVaultItems()

        // Initial tab
        switchCategory(MediaType.PHOTO)
    }

    private fun showSortDialog() {
        val options = arrayOf(
            "🕒 Newest First (Default)",
            "⏳ Oldest First",
            "🔤 Name (A to Z)",
            "🔠 Name (Z to A)",
            "📊 Size (Largest First)",
            "📉 Size (Smallest First)"
        )
        val sortOrders = arrayOf(
            VaultSortOrder.NEWEST,
            VaultSortOrder.OLDEST,
            VaultSortOrder.NAME_ASC,
            VaultSortOrder.NAME_DESC,
            VaultSortOrder.SIZE_DESC,
            VaultSortOrder.SIZE_ASC
        )

        AlertDialog.Builder(this)
            .setTitle("Sort Items By")
            .setItems(options) { _, which ->
                currentSortOrder = sortOrders[which]
                btnSortFilter.text = currentSortOrder.label
                updateDisplayedItems()
            }
            .show()
    }

    private fun setupRecyclerView() {
        mediaAdapter = MediaGridAdapter(lifecycleScope) { item ->
            handleMediaItemClick(item)
        }
        val gridLayoutManager = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (mediaAdapter.getItemViewType(position)) {
                        MediaGridAdapter.TYPE_HEADER, MediaGridAdapter.TYPE_FILE -> 3
                        else -> 1
                    }
                }
            }
        }
        rvMediaGrid.layoutManager = gridLayoutManager
        rvMediaGrid.adapter = mediaAdapter
    }

    private val sectionDateHeaderFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    private val sectionDateHeaderYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    private fun formatDateHeader(timestampSecs: Long): String {
        if (timestampSecs <= 0L) return "Earlier"
        val fileCal = Calendar.getInstance().apply { timeInMillis = timestampSecs * 1000L }
        val nowCal = Calendar.getInstance()

        val isSameYear = fileCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val isSameDay = isSameYear && fileCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

        val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterdayCal.get(Calendar.YEAR) == fileCal.get(Calendar.YEAR) &&
                yesterdayCal.get(Calendar.DAY_OF_YEAR) == fileCal.get(Calendar.DAY_OF_YEAR)

        return when {
            isSameDay -> "Today"
            isYesterday -> "Yesterday"
            isSameYear -> sectionDateHeaderFormat.format(Date(timestampSecs * 1000L))
            else -> sectionDateHeaderYearFormat.format(Date(timestampSecs * 1000L))
        }
    }

    private fun switchCategory(category: MediaType) {
        currentCategory = category

        val cyanBright = getColor(R.color.accent_cyan_bright)
        val tabInactive = getColor(R.color.tab_inactive)
        val cyanColor = getColor(R.color.accent_cyan)
        val transparent = getColor(android.R.color.transparent)

        // Reset Tab Styles
        tvTabPhotosLabel.setTextColor(if (category == MediaType.PHOTO) cyanBright else tabInactive)
        tabPhotosIndicator.setBackgroundColor(if (category == MediaType.PHOTO) cyanColor else transparent)

        tvTabVideosLabel.setTextColor(if (category == MediaType.VIDEO) cyanBright else tabInactive)
        tabVideosIndicator.setBackgroundColor(if (category == MediaType.VIDEO) cyanColor else transparent)

        tvTabFilesLabel.setTextColor(if (category == MediaType.DOCUMENT) cyanBright else tabInactive)
        tabFilesIndicator.setBackgroundColor(if (category == MediaType.DOCUMENT) cyanColor else transparent)

        tvSectionTitle.text = when (category) {
            MediaType.PHOTO -> "Photos"
            MediaType.VIDEO -> "Videos"
            MediaType.DOCUMENT -> "Files & Documents"
        }

        updateDisplayedItems()
    }

    private fun updateDisplayedItems() {
        val rawItems = when (currentCategory) {
            MediaType.PHOTO -> TelegramRepository.photos.value
            MediaType.VIDEO -> TelegramRepository.videos.value
            MediaType.DOCUMENT -> TelegramRepository.files.value
        }

        val items = when (currentSortOrder) {
            VaultSortOrder.NEWEST -> rawItems.sortedByDescending { it.dateAdded }
            VaultSortOrder.OLDEST -> rawItems.sortedBy { it.dateAdded }
            VaultSortOrder.NAME_ASC -> rawItems.sortedBy { it.title.lowercase() }
            VaultSortOrder.NAME_DESC -> rawItems.sortedByDescending { it.title.lowercase() }
            VaultSortOrder.SIZE_DESC -> rawItems.sortedByDescending { it.sizeBytes }
            VaultSortOrder.SIZE_ASC -> rawItems.sortedBy { it.sizeBytes }
        }

        val displayItems = if (currentCategory == MediaType.PHOTO) {
            val list = mutableListOf<VaultDisplayItem>()
            val grouped = items.groupBy { formatDateHeader(it.dateAdded) }
            for ((dateHeader, mediaList) in grouped) {
                list.add(VaultDisplayItem.Header(dateHeader, mediaList.firstOrNull()?.dateAdded ?: 0L))
                mediaList.forEach { list.add(VaultDisplayItem.Media(it)) }
            }
            list
        } else {
            items.map { VaultDisplayItem.Media(it) }
        }

        mediaAdapter.submitList(displayItems)

        if (items.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvMediaGrid.visibility = View.GONE
            when (currentCategory) {
                MediaType.PHOTO -> {
                    tvEmptyEmoji.text = "📷"
                    tvEmptyTitle.text = "No Photos Found"
                    tvEmptySubtitle.text = "Photos sent to your Telegram Saved Messages will appear here."
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

    private fun showUploadChoiceDialog() {
        val options = arrayOf("📷 Upload Photo", "🎬 Upload Video", "📄 Upload File / Document")
        AlertDialog.Builder(this)
            .setTitle("Upload to Cloud Vault")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickPhotoLauncher.launch("image/*")
                    1 -> pickVideoLauncher.launch("video/*")
                    2 -> pickFileLauncher.launch("*/*")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleBatchMediaUpload(uris: List<Uri>, mediaType: MediaType) {
        if (uris.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val total = uris.size
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Preparing to upload $total item(s)...", Toast.LENGTH_SHORT).show()
            }

            var successCount = 0
            for ((index, uri) in uris.withIndex()) {
                val current = index + 1
                val (tempFile, displayName) = copyUriToTempFile(uri) ?: continue

                UploadNotificationManager.showProgress(applicationContext, current, total, displayName)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Uploading ($current/$total): $displayName...", Toast.LENGTH_SHORT).show()
                }

                val success = TelegramRepository.uploadFile(tempFile.absolutePath, mediaType, displayName)
                if (success) {
                    successCount++
                }
            }

            if (successCount > 0) {
                UploadNotificationManager.showComplete(applicationContext, successCount, total)
            } else {
                UploadNotificationManager.showError(applicationContext, "Failed to upload selected item(s)")
            }

            withContext(Dispatchers.Main) {
                if (successCount > 0) {
                    Toast.makeText(this@MainActivity, "Uploaded $successCount of $total item(s) to Telegram Cloud! ☁️", Toast.LENGTH_LONG).show()
                    switchCategory(mediaType)
                } else {
                    Toast.makeText(this@MainActivity, "Failed to upload selected item(s)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleMediaUpload(uri: Uri, mediaType: MediaType) {
        handleBatchMediaUpload(listOf(uri), mediaType)
    }

    private fun copyUriToTempFile(uri: Uri): Pair<File, String>? {
        return try {
            var fileName = "upload_${System.currentTimeMillis()}"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val name = cursor.getString(nameIdx)
                        if (!name.isNullOrBlank()) fileName = name
                    }
                }
            }

            val uploadDir = File(cacheDir, "uploads").apply { if (!exists()) mkdirs() }
            val tempFile = File(uploadDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            Pair(tempFile, fileName)
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "copyUriToTempFile failed", e)
            null
        }
    }

    private fun handleMediaItemClick(item: VaultMediaItem) {
        when (item.type) {
            MediaType.PHOTO -> showPhotoViewerDialog(item)
            MediaType.VIDEO -> playVideoViaProxy(item)
            MediaType.DOCUMENT -> showFileDownloadPrompt(item)
        }
    }

    private fun showPhotoViewerDialog(item: VaultMediaItem) {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_photo_viewer, null)
        val ivFullPhoto: ZoomableImageView = dialogView.findViewById(R.id.ivFullPhoto)
        val pbFullPhotoLoading: ProgressBar = dialogView.findViewById(R.id.pbFullPhotoLoading)
        val tvViewerTitle: TextView = dialogView.findViewById(R.id.tvViewerTitle)
        val tvViewerSize: TextView = dialogView.findViewById(R.id.tvViewerSize)
        val btnDownloadPhoto: FrameLayout = dialogView.findViewById(R.id.btnDownloadPhoto)
        val btnCloseViewer: FrameLayout = dialogView.findViewById(R.id.btnCloseViewer)

        tvViewerTitle.text = item.title
        tvViewerSize.text = item.formattedSize

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(dialogView)
            .create()

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        btnCloseViewer.setOnClickListener { dialog.dismiss() }
        btnDownloadPhoto.setOnClickListener {
            DownloadManager.startDownload(this, item)
            Toast.makeText(this, "Downloading ${item.title} to storage...", Toast.LENGTH_SHORT).show()
        }

        dialog.show()

        // Load full resolution photo in background with EXIF orientation correction
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
                    val bitmap = ImageUtils.decodeOrientedBitmap(path)
                    withContext(Dispatchers.Main) {
                        pbFullPhotoLoading.visibility = View.GONE
                        if (bitmap != null) {
                            ivFullPhoto.setImageBitmap(bitmap)
                        }
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

    private fun playVideoViaProxy(item: VaultMediaItem) {
        try {
            if (item.chatId != 0L && item.messageId != 0L) {
                TelegramStreamingProxy.registerFileMessage(item.fileId, item.chatId, item.messageId)
            }

            val streamUrl = TelegramStreamingProxy.getUrl(item.fileId, item.title, item.sizeBytes, item.chatId, item.messageId)

            val intent = Intent(this, VlcPlayerActivity::class.java).apply {
                putExtra("FILE_ID", item.fileId)
                putExtra("TITLE", item.title)
                putExtra("STREAM_URL", streamUrl)
                putExtra("CHAT_ID", item.chatId)
                putExtra("MESSAGE_ID", item.messageId)
                putExtra("SIZE_BYTES", item.sizeBytes)
            }
            startActivity(intent)

        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to launch in-app VLC player", e)
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
                        tvStatus.text = "Status: Tap to enter Telegram API ID & Hash"
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
        lifecycleScope.launch {
            TelegramRepository.isLoadingVault.collectLatest { loading ->
                val hasAnyItems = TelegramRepository.photos.value.isNotEmpty() ||
                        TelegramRepository.videos.value.isNotEmpty() ||
                        TelegramRepository.files.value.isNotEmpty()
                pbLoading.visibility = if (loading && !hasAnyItems) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showSettingsDialog() {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val cardOptionAutoBackup: MaterialCardView = dialogView.findViewById(R.id.cardOptionAutoBackup)
        val tvSettingsAutoBackupSummary: TextView = dialogView.findViewById(R.id.tvSettingsAutoBackupSummary)
        val tvSettingsAutoBackupBadge: TextView = dialogView.findViewById(R.id.tvSettingsAutoBackupBadge)
        val layoutToggleApiSettings: LinearLayout = dialogView.findViewById(R.id.layoutToggleApiSettings)
        val layoutApiFields: LinearLayout = dialogView.findViewById(R.id.layoutApiFields)
        val tvApiExpandIcon: TextView = dialogView.findViewById(R.id.tvApiExpandIcon)
        val etSettingsApiId: EditText = dialogView.findViewById(R.id.etSettingsApiId)
        val etSettingsApiHash: EditText = dialogView.findViewById(R.id.etSettingsApiHash)
        val btnSaveCredentials: MaterialButton = dialogView.findViewById(R.id.btnSaveCredentials)
        val btnSettingsLogout: MaterialButton = dialogView.findViewById(R.id.btnSettingsLogout)
        val btnCloseSettings: TextView = dialogView.findViewById(R.id.btnCloseSettings)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        fun updateBackupSummary() {
            val isBackupOn = AutoBackupPreferences.isEnabled(this)
            tvSettingsAutoBackupBadge.text = if (isBackupOn) "ON" else "OFF"
            tvSettingsAutoBackupBadge.setTextColor(if (isBackupOn) Color.parseColor("#00E5FF") else Color.parseColor("#94A3B8"))
            val selectedCount = AutoBackupPreferences.getSelectedBucketIds(this)?.size
            tvSettingsAutoBackupSummary.text = if (isBackupOn) {
                if (selectedCount != null) "$selectedCount folder(s) syncing ☁️" else "All device media syncing ☁️"
            } else {
                "Disabled • Tap to configure"
            }
        }
        updateBackupSummary()

        cardOptionAutoBackup.setOnClickListener {
            showAutoBackupSettingsDialog {
                updateBackupSummary()
            }
        }

        // Toggle collapsible API settings
        var isApiExpanded = false
        layoutToggleApiSettings.setOnClickListener {
            isApiExpanded = !isApiExpanded
            layoutApiFields.visibility = if (isApiExpanded) View.VISIBLE else View.GONE
            tvApiExpandIcon.text = if (isApiExpanded) "▲" else "▼"
        }

        val currentApiId = TdlibManager.getApiId(this)
        if (currentApiId > 0) etSettingsApiId.setText(currentApiId.toString())
        etSettingsApiHash.setText(TdlibManager.getApiHash(this))

        btnSaveCredentials.setOnClickListener {
            val idStr = etSettingsApiId.text.toString().trim()
            val hashStr = etSettingsApiHash.text.toString().trim()
            val apiId = idStr.toIntOrNull() ?: 0
            if (apiId > 0 && hashStr.isNotBlank()) {
                TdlibManager.saveApiId(this, apiId)
                TdlibManager.saveApiHash(this, hashStr)
                Toast.makeText(this, "Credentials saved! Connecting...", Toast.LENGTH_SHORT).show()
                TelegramClient.sendTdlibParameters(applicationContext)
                dialog.dismiss()
            } else {
                Toast.makeText(this, "Please enter valid API ID and API Hash", Toast.LENGTH_SHORT).show()
            }
        }

        btnSettingsLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out of CloudVault?")
                .setPositiveButton("Log Out") { _, _ ->
                    TelegramClient.logOut()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnCloseSettings.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAutoBackupSettingsDialog(onDismissCallback: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_autobackup_settings, null)
        val switchAutoBackup: SwitchMaterial = dialogView.findViewById(R.id.switchAutoBackup)
        val switchWifiOnly: SwitchMaterial = dialogView.findViewById(R.id.switchWifiOnly)
        val tvBackupStatus: TextView = dialogView.findViewById(R.id.tvBackupStatus)
        val btnSelectFolders: MaterialButton = dialogView.findViewById(R.id.btnSelectFolders)
        val btnBackupNow: MaterialButton = dialogView.findViewById(R.id.btnBackupNow)
        val btnBackFromAutoBackup: TextView = dialogView.findViewById(R.id.btnBackFromAutoBackup)

        val isBackupOn = AutoBackupPreferences.isEnabled(this)
        switchAutoBackup.isChecked = isBackupOn
        switchWifiOnly.isChecked = AutoBackupPreferences.isWifiOnly(this)
        tvBackupStatus.text = if (isBackupOn) {
            "Auto Backup is ON • Real-time observer & background sync active ☁️"
        } else {
            "Auto Backup is OFF • Tap switch to automatically sync photos & videos"
        }

        switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            AutoBackupManager.enableAutoBackup(this, isChecked)
            tvBackupStatus.text = if (isChecked) {
                "Auto Backup is ON • Real-time observer & background sync active ☁️"
            } else {
                "Auto Backup is OFF • Tap switch to automatically sync photos & videos"
            }
            Toast.makeText(this, if (isChecked) "Auto Backup enabled!" else "Auto Backup disabled", Toast.LENGTH_SHORT).show()
        }

        switchWifiOnly.setOnCheckedChangeListener { _, isChecked ->
            AutoBackupPreferences.setWifiOnly(this, isChecked)
            Toast.makeText(this, if (isChecked) "Backup set to Wi-Fi only" else "Backup set to Wi-Fi + Mobile Data", Toast.LENGTH_SHORT).show()
        }

        btnSelectFolders.setOnClickListener {
            showFolderSelectionDialog()
        }

        btnBackupNow.setOnClickListener {
            Toast.makeText(this, "Scanning device for unbacked photos & videos...", Toast.LENGTH_SHORT).show()
            AutoBackupManager.triggerImmediateSync(this)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            onDismissCallback?.invoke()
        }

        btnBackFromAutoBackup.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showFolderSelectionDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val folders = AutoBackupManager.scanAvailableFolders(this@MainActivity)
            if (folders.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "No media folders found on device", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val items = folders.map { "${it.bucketName} (${it.totalCount} items)" }.toTypedArray()
            val checkedItems = folders.map { it.isSelected }.toBooleanArray()

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Select Folders to Back Up")
                    .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                        checkedItems[which] = isChecked
                    }
                    .setPositiveButton("Save Selection") { _, _ ->
                        val selectedBucketIds = mutableSetOf<String>()
                        for (i in folders.indices) {
                            if (checkedItems[i]) {
                                selectedBucketIds.add(folders[i].bucketId)
                            }
                        }
                        AutoBackupPreferences.setSelectedBucketIds(this@MainActivity, selectedBucketIds)
                        Toast.makeText(this@MainActivity, "Backup folders updated (${selectedBucketIds.size} folders)", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
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
        TelegramStreamingProxy.stop()
    }
}
