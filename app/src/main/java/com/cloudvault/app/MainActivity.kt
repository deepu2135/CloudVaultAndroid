package com.cloudvault.app

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.provider.OpenableColumns
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
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

enum class GridZoomLevel(val spanCount: Int, val label: String) {
    DAY(3, "📅 Day"),
    MONTH(5, "📆 Month"),
    YEAR(9, "🗓️ Year")
}

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
    private lateinit var etSearch: android.widget.EditText
    private lateinit var btnSettings: MaterialButton
    private lateinit var fabUpload: MaterialButton

    private lateinit var layoutNormalTopBar: LinearLayout
    private lateinit var layoutSelectionTopBar: LinearLayout
    private lateinit var btnCloseSelection: TextView
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnSelectAll: MaterialButton
    private lateinit var layoutSelectionBottomBar: MaterialCardView
    private lateinit var btnDownloadSelected: MaterialButton
    private lateinit var btnDeleteSelected: MaterialButton

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
    private lateinit var btnStartSelect: MaterialButton
    private lateinit var btnGridToggle: TextView

    private lateinit var rvMediaGrid: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvEmptyEmoji: TextView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var pbLoading: ProgressBar

    private lateinit var mediaAdapter: MediaGridAdapter
    private var currentCategory: MediaType = MediaType.PHOTO
    private var currentZoomLevel: GridZoomLevel = GridZoomLevel.DAY
    private var lastScaleTime = 0L

    private var currentSortOrder: VaultSortOrder = VaultSortOrder.NEWEST
    private var searchQuery: String = ""

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
        etSearch = findViewById(R.id.etSearch)
        btnSettings = findViewById(R.id.btnSettings)
        fabUpload = findViewById(R.id.fabUpload)

        layoutNormalTopBar = findViewById(R.id.layoutNormalTopBar)
        layoutSelectionTopBar = findViewById(R.id.layoutSelectionTopBar)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        tvSelectionCount = findViewById(R.id.tvSelectionCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        layoutSelectionBottomBar = findViewById(R.id.layoutSelectionBottomBar)
        btnDownloadSelected = findViewById(R.id.btnDownloadSelected)
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected)

        btnCloseSelection.setOnClickListener {
            mediaAdapter.clearSelection()
        }

        btnSelectAll.setOnClickListener {
            val visible = getCurrentVisibleMediaItems()
            if (mediaAdapter.getSelectedCount() >= visible.size && visible.isNotEmpty()) {
                mediaAdapter.clearSelection()
            } else {
                mediaAdapter.selectAll(visible)
            }
        }

        btnDownloadSelected.setOnClickListener {
            val selected = mediaAdapter.getSelectedItems().toList()
            if (selected.isEmpty()) return@setOnClickListener
            val count = selected.size
            Toast.makeText(this, "Downloading $count item(s) to Downloads/CloudVault... ⬇️", Toast.LENGTH_LONG).show()
            mediaAdapter.clearSelection()

            DownloadManager.startBatchDownload(
                this,
                selected,
                onComplete = { successCount, total ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Saved $successCount of $total item(s) to Downloads/CloudVault! 📁",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        btnDeleteSelected.setOnClickListener {
            val selected = mediaAdapter.getSelectedItems().toList()
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this)
                .setTitle("Delete ${selected.size} item(s)?")
                .setMessage("Are you sure you want to permanently delete the selected ${selected.size} item(s) from your Cloud Vault and Telegram Saved Messages?")
                .setPositiveButton("Delete") { _, _ ->
                    pbLoading.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val success = TelegramRepository.deleteMediaItems(selected)
                        pbLoading.visibility = View.GONE
                        mediaAdapter.clearSelection()
                        if (success) {
                            Toast.makeText(this@MainActivity, "Deleted ${selected.size} item(s) 🗑️", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to delete some items", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

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
        btnStartSelect = findViewById(R.id.btnStartSelect)
        btnGridToggle = findViewById(R.id.btnGridToggle)

        btnStartSelect.setOnClickListener {
            mediaAdapter.enterSelectionMode()
        }

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

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                updateDisplayedItems()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
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

        btnGridToggle.text = currentZoomLevel.label
        btnGridToggle.setOnClickListener {
            toggleGridZoom()
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

        mediaAdapter.onItemMenuClick = { item, anchorView ->
            showItemPopupMenu(item, anchorView)
        }

        mediaAdapter.onSelectionModeChangeListener = { isSelection ->
            layoutNormalTopBar.visibility = if (isSelection) View.GONE else View.VISIBLE
            layoutSelectionTopBar.visibility = if (isSelection) View.VISIBLE else View.GONE
            layoutSelectionBottomBar.visibility = if (isSelection) View.VISIBLE else View.GONE
            fabUpload.visibility = if (isSelection) View.GONE else View.VISIBLE
        }

        mediaAdapter.onSelectionChangedListener = { selected ->
            val count = selected.size
            tvSelectionCount.text = "$count item${if (count != 1) "s" else ""} selected"
            btnDownloadSelected.text = "⬇ Download ($count)"
            btnDeleteSelected.text = "🗑 Delete ($count)"
            val visible = getCurrentVisibleMediaItems()
            btnSelectAll.text = if (count >= visible.size && visible.isNotEmpty()) "Deselect All" else "Select All"
        }

        val spanCount = currentZoomLevel.spanCount
        val gridLayoutManager = GridLayoutManager(this, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (mediaAdapter.getItemViewType(position)) {
                        MediaGridAdapter.TYPE_HEADER, MediaGridAdapter.TYPE_FILE -> currentZoomLevel.spanCount
                        else -> 1
                    }
                }
            }
        }
        rvMediaGrid.layoutManager = gridLayoutManager
        rvMediaGrid.adapter = mediaAdapter

        setupPinchToZoom()
    }

    private fun setupPinchToZoom() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var accumulatedScale = 1.0f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulatedScale = 1.0f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                accumulatedScale *= detector.scaleFactor
                val now = System.currentTimeMillis()
                if (now - lastScaleTime < 350) return true

                if (accumulatedScale > 1.25f) {
                    // Zoom In: Pinch open (Year -> Month -> Day)
                    if (zoomIn()) {
                        lastScaleTime = now
                        accumulatedScale = 1.0f
                        rvMediaGrid.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                } else if (accumulatedScale < 0.80f) {
                    // Zoom Out: Pinch close (Day -> Month -> Year)
                    if (zoomOut()) {
                        lastScaleTime = now
                        accumulatedScale = 1.0f
                        rvMediaGrid.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                }
                return true
            }
        })

        rvMediaGrid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                scaleDetector.onTouchEvent(e)
                return e.pointerCount >= 2
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                scaleDetector.onTouchEvent(e)
            }
        })
    }

    private fun toggleGridZoom() {
        val nextLevel = when (currentZoomLevel) {
            GridZoomLevel.DAY -> GridZoomLevel.MONTH
            GridZoomLevel.MONTH -> GridZoomLevel.YEAR
            GridZoomLevel.YEAR -> GridZoomLevel.DAY
        }
        setZoomLevel(nextLevel)
    }

    private fun zoomIn(): Boolean {
        return when (currentZoomLevel) {
            GridZoomLevel.YEAR -> {
                setZoomLevel(GridZoomLevel.MONTH)
                true
            }
            GridZoomLevel.MONTH -> {
                setZoomLevel(GridZoomLevel.DAY)
                true
            }
            GridZoomLevel.DAY -> false
        }
    }

    private fun zoomOut(): Boolean {
        return when (currentZoomLevel) {
            GridZoomLevel.DAY -> {
                setZoomLevel(GridZoomLevel.MONTH)
                true
            }
            GridZoomLevel.MONTH -> {
                setZoomLevel(GridZoomLevel.YEAR)
                true
            }
            GridZoomLevel.YEAR -> false
        }
    }

    private fun setZoomLevel(level: GridZoomLevel) {
        if (currentZoomLevel == level) return
        currentZoomLevel = level
        applyZoomLevel()
    }

    private fun applyZoomLevel() {
        val spanCount = currentZoomLevel.spanCount
        btnGridToggle.text = currentZoomLevel.label

        val layoutManager = GridLayoutManager(this, spanCount).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return when (mediaAdapter.getItemViewType(position)) {
                        MediaGridAdapter.TYPE_HEADER, MediaGridAdapter.TYPE_FILE -> currentZoomLevel.spanCount
                        else -> 1
                    }
                }
            }
        }
        rvMediaGrid.layoutManager = layoutManager
        updateDisplayedItems()
    }

    private fun getCurrentVisibleMediaItems(): List<VaultMediaItem> {
        val rawItems = when (currentCategory) {
            MediaType.PHOTO -> TelegramRepository.photos.value
            MediaType.VIDEO -> TelegramRepository.videos.value
            MediaType.DOCUMENT -> TelegramRepository.files.value
        }
        return when (currentSortOrder) {
            VaultSortOrder.NEWEST -> rawItems.sortedByDescending { it.dateAdded }
            VaultSortOrder.OLDEST -> rawItems.sortedBy { it.dateAdded }
            VaultSortOrder.NAME_ASC -> rawItems.sortedBy { it.title.lowercase() }
            VaultSortOrder.NAME_DESC -> rawItems.sortedByDescending { it.title.lowercase() }
            VaultSortOrder.SIZE_DESC -> rawItems.sortedByDescending { it.sizeBytes }
            VaultSortOrder.SIZE_ASC -> rawItems.sortedBy { it.sizeBytes }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mediaAdapter.isSelectionMode) {
            mediaAdapter.clearSelection()
            return
        }
        super.onBackPressed()
    }

    private val sectionDateHeaderDayFormat = SimpleDateFormat("MMM d", Locale.getDefault())
    private val sectionDateHeaderDayYearFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    private val sectionDateHeaderMonthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val sectionDateHeaderYearFormat = SimpleDateFormat("yyyy", Locale.getDefault())

    private fun formatDateHeader(timestampSecs: Long, zoomLevel: GridZoomLevel): String {
        if (timestampSecs <= 0L) return "Earlier"
        val date = Date(timestampSecs * 1000L)
        return when (zoomLevel) {
            GridZoomLevel.YEAR -> sectionDateHeaderYearFormat.format(date)
            GridZoomLevel.MONTH -> sectionDateHeaderMonthFormat.format(date)
            GridZoomLevel.DAY -> {
                val fileCal = Calendar.getInstance().apply { timeInMillis = timestampSecs * 1000L }
                val nowCal = Calendar.getInstance()

                val isSameYear = fileCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
                val isSameDay = isSameYear && fileCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

                val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val isYesterday = yesterdayCal.get(Calendar.YEAR) == fileCal.get(Calendar.YEAR) &&
                        yesterdayCal.get(Calendar.DAY_OF_YEAR) == fileCal.get(Calendar.DAY_OF_YEAR)

                when {
                    isSameDay -> "Today"
                    isYesterday -> "Yesterday"
                    isSameYear -> sectionDateHeaderDayFormat.format(date)
                    else -> sectionDateHeaderDayYearFormat.format(date)
                }
            }
        }
    }

    private fun switchCategory(category: MediaType) {
        if (mediaAdapter.isSelectionMode) {
            mediaAdapter.clearSelection()
        }
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

        val filteredItems = if (searchQuery.isNotBlank()) {
            val query = searchQuery.lowercase()
            rawItems.filter { it.title.lowercase().contains(query) }
        } else {
            rawItems
        }

        val items = when (currentSortOrder) {
            VaultSortOrder.NEWEST -> filteredItems.sortedByDescending { it.dateAdded }
            VaultSortOrder.OLDEST -> filteredItems.sortedBy { it.dateAdded }
            VaultSortOrder.NAME_ASC -> filteredItems.sortedBy { it.title.lowercase() }
            VaultSortOrder.NAME_DESC -> filteredItems.sortedByDescending { it.title.lowercase() }
            VaultSortOrder.SIZE_DESC -> filteredItems.sortedByDescending { it.sizeBytes }
            VaultSortOrder.SIZE_ASC -> filteredItems.sortedBy { it.sizeBytes }
        }

        val displayItems = if (currentCategory == MediaType.PHOTO || currentCategory == MediaType.VIDEO) {
            val list = mutableListOf<VaultDisplayItem>()
            val grouped = items.groupBy { formatDateHeader(it.dateAdded, currentZoomLevel) }
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
            if (searchQuery.isNotBlank()) {
                tvEmptyEmoji.text = "🔍"
                tvEmptyTitle.text = "No Results Found"
                tvEmptySubtitle.text = "No items matched your search \"$searchQuery\"."
            } else {
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

                UploadNotificationManager.showProgress(applicationContext, current, total, displayName, percent = 0)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Uploading ($current/$total): $displayName...", Toast.LENGTH_SHORT).show()
                }

                var lastProgressUpdate = 0L
                try {
                    val success = TelegramRepository.uploadFile(
                        localPath = tempFile.absolutePath,
                        mediaType = mediaType,
                        captionText = displayName,
                        onProgress = { uploaded, totalBytes ->
                            val now = System.currentTimeMillis()
                            if (now - lastProgressUpdate > 500L || uploaded == totalBytes) {
                                lastProgressUpdate = now
                                val pct = if (totalBytes > 0) ((uploaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
                                val progressText = if (totalBytes > 0) {
                                    "${CacheManager.formatBytes(uploaded)} of ${CacheManager.formatBytes(totalBytes)} ($pct%)"
                                } else {
                                    "${CacheManager.formatBytes(uploaded)} uploaded"
                                }
                                UploadNotificationManager.showProgress(
                                    applicationContext,
                                    current,
                                    total,
                                    displayName,
                                    percent = pct,
                                    statusText = progressText
                                )
                            }
                        }
                    )
                    if (success) {
                        successCount++
                    }
                } finally {
                    runCatching { tempFile.delete() }
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
            TeleflixLogger.log("MainActivity", "copyUriToTempFile failed for uri $uri: ${e.message}", isError = true)
            null
        }
    }

    private fun showItemPopupMenu(item: VaultMediaItem, anchorView: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchorView)
        popup.menu.add(0, 1, 0, "⬇ Download")
        popup.menu.add(0, 2, 1, "🗑 Delete")
        popup.menu.add(0, 3, 2, "☑ Select")
        if (item.type == MediaType.VIDEO) {
            popup.menu.add(0, 4, 3, "▶ Play Video")
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    Toast.makeText(this, "Downloading ${item.title} to Downloads/CloudVault...", Toast.LENGTH_SHORT).show()
                    DownloadManager.startDownload(this, item)
                    true
                }
                2 -> {
                    AlertDialog.Builder(this)
                        .setTitle("Delete ${item.title}?")
                        .setMessage("Are you sure you want to permanently delete this item from your Cloud Vault and Telegram?")
                        .setPositiveButton("Delete") { _, _ ->
                            lifecycleScope.launch {
                                val success = TelegramRepository.deleteMediaItems(listOf(item))
                                if (success) {
                                    Toast.makeText(this@MainActivity, "Deleted 🗑️", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "Failed to delete item", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                3 -> {
                    mediaAdapter.startSelection(item)
                    true
                }
                4 -> {
                    playVideoViaProxy(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
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
        val btnDeletePhoto: FrameLayout? = dialogView.findViewById(R.id.btnDeletePhoto)
        val btnDownloadPhoto: FrameLayout = dialogView.findViewById(R.id.btnDownloadPhoto)
        val btnCloseViewer: FrameLayout = dialogView.findViewById(R.id.btnCloseViewer)

        tvViewerTitle.text = item.title
        tvViewerSize.text = item.formattedSize

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogView)

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

        btnDeletePhoto?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete Photo?")
                .setMessage("Are you sure you want to delete ${item.title} from Cloud Vault and Telegram?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        val success = TelegramRepository.deleteMediaItems(listOf(item))
                        dialog.dismiss()
                        if (success) {
                            Toast.makeText(this@MainActivity, "Deleted photo 🗑️", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "Failed to delete photo", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnDownloadPhoto.setOnClickListener {
            DownloadManager.startDownload(this, item)
            Toast.makeText(this, "Downloading ${item.title} to Downloads/CloudVault...", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

        // 1. Instantly display cached thumbnail if available so user sees image immediately
        val cachedThumb = MediaGridAdapter.bitmapCache.get(item.thumbnailFileId)
            ?: (if (item.fileId > 0) MediaGridAdapter.bitmapCache.get(item.fileId) else null)

        if (cachedThumb != null) {
            ivFullPhoto.setImageBitmap(cachedThumb)
            ivFullPhoto.post { ivFullPhoto.fitToScreen() }
        }

        // 2. Load full-resolution photo in background with EXIF orientation correction
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tdFile = TelegramClient.downloadFileAndWait(item.fileId, priority = 32, timeoutMs = 60000L)
                val path = tdFile?.local?.path.orEmpty()
                if (path.isNotBlank() && File(path).exists()) {
                    val bitmap = ImageUtils.decodeOrientedBitmap(path, maxDimension = 4096)
                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            ivFullPhoto.setImageBitmap(bitmap)
                            ivFullPhoto.post { ivFullPhoto.fitToScreen() }
                        } else if (cachedThumb == null) {
                            Toast.makeText(this@MainActivity, "Could not decode photo", Toast.LENGTH_SHORT).show()
                        }
                        Unit
                    }
                } else if (cachedThumb == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Could not load full photo from cloud", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    if (cachedThumb == null) {
                        Toast.makeText(this@MainActivity, "Failed to load full photo: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    Unit
                }
            } finally {
                withContext(Dispatchers.Main) {
                    pbFullPhotoLoading.visibility = View.GONE
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
                putExtra("DURATION_SECONDS", item.durationSeconds)
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

        val cardOptionCacheManager: MaterialCardView = dialogView.findViewById(R.id.cardOptionCacheManager)
        val tvSettingsCacheSizeBadge: TextView = dialogView.findViewById(R.id.tvSettingsCacheSizeBadge)

        val cardOptionAppLogs: MaterialCardView? = dialogView.findViewById(R.id.cardOptionAppLogs)
        val tvSettingsLogsCountBadge: TextView? = dialogView.findViewById(R.id.tvSettingsLogsCountBadge)

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

        fun updateCacheSummary() {
            lifecycleScope.launch {
                val stats = CacheManager.calculateCacheStats(this@MainActivity)
                tvSettingsCacheSizeBadge.text = CacheManager.formatBytes(stats.totalBytes)
            }
        }

        fun updateLogsSummary() {
            val count = TeleflixLogger.getLogCount()
            tvSettingsLogsCountBadge?.text = "$count LOGS"
        }

        updateBackupSummary()
        updateCacheSummary()
        updateLogsSummary()

        cardOptionAutoBackup.setOnClickListener {
            showAutoBackupSettingsDialog {
                updateBackupSummary()
            }
        }

        cardOptionCacheManager.setOnClickListener {
            showCacheManagerDialog {
                updateCacheSummary()
            }
        }

        cardOptionAppLogs?.setOnClickListener {
            showAppLogsDialog()
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

    private fun showAppLogsDialog() {
        if (isFinishing || isDestroyed) return

        try {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_logs, null)
            val btnLogsBack: TextView = dialogView.findViewById(R.id.btnLogsBack)
            val btnLogsClose: TextView = dialogView.findViewById(R.id.btnLogsClose)
            val tvLogsContent: TextView = dialogView.findViewById(R.id.tvLogsContent)
            val tvLogsCount: TextView = dialogView.findViewById(R.id.tvLogsCount)
            val tvEmptyLogs: TextView = dialogView.findViewById(R.id.tvEmptyLogs)
            val etLogSearch: EditText = dialogView.findViewById(R.id.etLogSearch)
            val btnClearFilter: TextView = dialogView.findViewById(R.id.btnClearFilter)
            val btnCopyLogs: MaterialButton = dialogView.findViewById(R.id.btnCopyLogs)
            val btnRefreshLogs: MaterialButton = dialogView.findViewById(R.id.btnRefreshLogs)
            val btnClearLogs: MaterialButton = dialogView.findViewById(R.id.btnClearLogs)
            val scrollLogs: ScrollView = dialogView.findViewById(R.id.scrollLogs)

            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            dialog.setContentView(dialogView)
            dialog.window?.apply {
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(Color.parseColor("#0A0F1D")))
            }

            fun refreshLogDisplay() {
                val query = etLogSearch.text?.toString()?.trim() ?: ""
                val logs = if (query.isNotBlank()) {
                    TeleflixLogger.getFilteredLogs(query)
                } else {
                    TeleflixLogger.getFormattedLogs()
                }

                val totalCount = TeleflixLogger.getLogCount()
                tvLogsCount.text = if (query.isNotBlank()) {
                    "Filtering by \"$query\" • Total: $totalCount"
                } else {
                    "$totalCount log entries recorded"
                }

                if (logs.isBlank() || logs == "--- No Diagnostic Logs Recorded Yet ---") {
                    tvLogsContent.text = ""
                    tvEmptyLogs.visibility = View.VISIBLE
                } else {
                    tvEmptyLogs.visibility = View.GONE
                    tvLogsContent.text = logs
                }

                scrollLogs.post {
                    scrollLogs.fullScroll(View.FOCUS_DOWN)
                }
            }

            refreshLogDisplay()

            etLogSearch.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    btnClearFilter.visibility = if (!s.isNullOrBlank()) View.VISIBLE else View.GONE
                    refreshLogDisplay()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })

            btnClearFilter.setOnClickListener {
                etLogSearch.setText("")
            }

            btnCopyLogs.setOnClickListener {
                val query = etLogSearch.text?.toString()?.trim() ?: ""
                val logsToCopy = if (query.isNotBlank()) {
                    TeleflixLogger.getFilteredLogs(query)
                } else {
                    TeleflixLogger.getFormattedLogs()
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("CloudVault Diagnostic Logs", logsToCopy)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, "Logs copied to clipboard 📋", Toast.LENGTH_SHORT).show()
            }

            btnRefreshLogs.setOnClickListener {
                refreshLogDisplay()
                Toast.makeText(this, "Logs refreshed 🔄", Toast.LENGTH_SHORT).show()
            }

            btnClearLogs.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear App Logs?")
                    .setMessage("Are you sure you want to clear all diagnostic logs?")
                    .setPositiveButton("Clear") { _, _ ->
                        TeleflixLogger.clearLogs()
                        refreshLogDisplay()
                        Toast.makeText(this, "Logs cleared 🗑️", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            btnLogsBack.setOnClickListener { dialog.dismiss() }
            btnLogsClose.setOnClickListener { dialog.dismiss() }

            dialog.show()
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to show logs dialog", e)
            Toast.makeText(this, "Error opening logs: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCacheManagerDialog(onDismissCallback: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_cache_manager, null)
        val btnCacheBack: TextView = dialogView.findViewById(R.id.btnCacheBack)
        val btnCacheClose: TextView = dialogView.findViewById(R.id.btnCacheClose)
        val donutStorageChart: StorageDonutChartView = dialogView.findViewById(R.id.donutStorageChart)
        val tvDeviceStoragePercent: TextView = dialogView.findViewById(R.id.tvDeviceStoragePercent)
        val pbCacheLoading: ProgressBar = dialogView.findViewById(R.id.pbCacheLoading)

        val rowCacheVideos: LinearLayout = dialogView.findViewById(R.id.rowCacheVideos)
        val cbVideos: CheckBox = dialogView.findViewById(R.id.cbVideos)
        val tvVideosPercent: TextView = dialogView.findViewById(R.id.tvVideosPercent)
        val tvVideosSize: TextView = dialogView.findViewById(R.id.tvVideosSize)

        val rowCacheDocs: LinearLayout = dialogView.findViewById(R.id.rowCacheDocs)
        val cbDocs: CheckBox = dialogView.findViewById(R.id.cbDocs)
        val tvDocsPercent: TextView = dialogView.findViewById(R.id.tvDocsPercent)
        val tvDocsSize: TextView = dialogView.findViewById(R.id.tvDocsSize)

        val rowCachePhotos: LinearLayout = dialogView.findViewById(R.id.rowCachePhotos)
        val cbPhotos: CheckBox = dialogView.findViewById(R.id.cbPhotos)
        val tvPhotosPercent: TextView = dialogView.findViewById(R.id.tvPhotosPercent)
        val tvPhotosSize: TextView = dialogView.findViewById(R.id.tvPhotosSize)

        val rowCacheOther: LinearLayout = dialogView.findViewById(R.id.rowCacheOther)
        val cbOther: CheckBox = dialogView.findViewById(R.id.cbOther)
        val tvOtherPercent: TextView = dialogView.findViewById(R.id.tvOtherPercent)
        val tvOtherSize: TextView = dialogView.findViewById(R.id.tvOtherSize)

        val btnClearSelectedCache: MaterialButton = dialogView.findViewById(R.id.btnClearSelectedCache)

        val rowKeepMedia: LinearLayout = dialogView.findViewById(R.id.rowKeepMedia)
        val tvKeepMediaValue: TextView = dialogView.findViewById(R.id.tvKeepMediaValue)
        val rowMaxCacheSize: LinearLayout = dialogView.findViewById(R.id.rowMaxCacheSize)
        val tvMaxCacheValue: TextView = dialogView.findViewById(R.id.tvMaxCacheValue)

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#0A0F1D")))
        }

        btnCacheBack.setOnClickListener { dialog.dismiss() }
        btnCacheClose.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { onDismissCallback?.invoke() }

        fun updateRetentionLabels() {
            val keepDays = CacheManager.getKeepMediaDays(this)
            tvKeepMediaValue.text = when (keepDays) {
                3 -> "3 Days"
                7 -> "1 Week"
                30 -> "1 Month"
                else -> "Forever"
            }

            val maxMb = CacheManager.getMaxCacheSizeMb(this)
            tvMaxCacheValue.text = when (maxMb) {
                500L -> "500 MB"
                2048L -> "2 GB"
                5120L -> "5 GB"
                else -> "No Limit"
            }
        }
        updateRetentionLabels()

        rowKeepMedia.setOnClickListener {
            val options = arrayOf("3 Days", "1 Week", "1 Month", "Forever (Default)")
            val daysValues = arrayOf(3, 7, 30, -1)
            AlertDialog.Builder(this)
                .setTitle("Keep Media")
                .setItems(options) { _, which ->
                    CacheManager.setKeepMediaDays(this, daysValues[which])
                    updateRetentionLabels()
                }
                .show()
        }

        rowMaxCacheSize.setOnClickListener {
            val options = arrayOf("500 MB", "2 GB", "5 GB", "No Limit (Default)")
            val mbValues = arrayOf(500L, 2048L, 5120L, 0L)
            AlertDialog.Builder(this)
                .setTitle("Maximum Cache Size")
                .setItems(options) { _, which ->
                    CacheManager.setMaxCacheSizeMb(this, mbValues[which])
                    updateRetentionLabels()
                }
                .show()
        }

        fun renderStats(stats: CacheManager.CacheStats) {
            val total = stats.totalBytes

            fun calcPercent(size: Long): String {
                if (total <= 0L || size <= 0L) return "<1%"
                val pct = ((size.toDouble() / total.toDouble()) * 100).toInt()
                return if (pct < 1) "<1%" else "$pct%"
            }

            tvVideosSize.text = CacheManager.formatBytes(stats.videoBytes)
            tvVideosPercent.text = calcPercent(stats.videoBytes)

            tvDocsSize.text = CacheManager.formatBytes(stats.documentBytes)
            tvDocsPercent.text = calcPercent(stats.documentBytes)

            tvPhotosSize.text = CacheManager.formatBytes(stats.photoBytes)
            tvPhotosPercent.text = calcPercent(stats.photoBytes)

            tvOtherSize.text = CacheManager.formatBytes(stats.otherBytes)
            tvOtherPercent.text = calcPercent(stats.otherBytes)

            tvDeviceStoragePercent.text = "CloudVault uses ${stats.deviceUsagePercent}% of your device storage."

            fun updateChartAndButton() {
                var selectedBytes = 0L
                if (cbVideos.isChecked) selectedBytes += stats.videoBytes
                if (cbDocs.isChecked) selectedBytes += stats.documentBytes
                if (cbPhotos.isChecked) selectedBytes += stats.photoBytes
                if (cbOther.isChecked) selectedBytes += stats.otherBytes

                val segments = listOf(
                    StorageDonutChartView.Segment("videos", stats.videoBytes, Color.parseColor("#3B82F6"), cbVideos.isChecked),
                    StorageDonutChartView.Segment("docs", stats.documentBytes, Color.parseColor("#10B981"), cbDocs.isChecked),
                    StorageDonutChartView.Segment("photos", stats.photoBytes, Color.parseColor("#F59E0B"), cbPhotos.isChecked),
                    StorageDonutChartView.Segment("other", stats.otherBytes, Color.parseColor("#EAB308"), cbOther.isChecked)
                )

                donutStorageChart.setData(segments, CacheManager.formatBytes(selectedBytes))
                btnClearSelectedCache.text = "Clear Cache ${CacheManager.formatBytes(selectedBytes)}"
                btnClearSelectedCache.isEnabled = selectedBytes > 0L
                btnClearSelectedCache.alpha = if (selectedBytes > 0L) 1.0f else 0.5f
            }

            cbVideos.setOnCheckedChangeListener { _, _ -> updateChartAndButton() }
            cbDocs.setOnCheckedChangeListener { _, _ -> updateChartAndButton() }
            cbPhotos.setOnCheckedChangeListener { _, _ -> updateChartAndButton() }
            cbOther.setOnCheckedChangeListener { _, _ -> updateChartAndButton() }

            rowCacheVideos.setOnClickListener { cbVideos.toggle() }
            rowCacheDocs.setOnClickListener { cbDocs.toggle() }
            rowCachePhotos.setOnClickListener { cbPhotos.toggle() }
            rowCacheOther.setOnClickListener { cbOther.toggle() }

            updateChartAndButton()
        }

        fun loadCacheData() {
            pbCacheLoading.visibility = View.VISIBLE
            lifecycleScope.launch {
                val stats = CacheManager.calculateCacheStats(this@MainActivity)
                pbCacheLoading.visibility = View.GONE
                renderStats(stats)
            }
        }
        loadCacheData()

        btnClearSelectedCache.setOnClickListener {
            pbCacheLoading.visibility = View.VISIBLE
            btnClearSelectedCache.isEnabled = false
            lifecycleScope.launch {
                val updated = CacheManager.clearSelectedCache(
                    this@MainActivity,
                    cbVideos.isChecked,
                    cbDocs.isChecked,
                    cbPhotos.isChecked,
                    cbOther.isChecked
                )
                pbCacheLoading.visibility = View.GONE
                renderStats(updated)
                Toast.makeText(this@MainActivity, "Cache cleared successfully! 🧹", Toast.LENGTH_SHORT).show()
            }
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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("To automatically discover and back up your Document files (like PDFs), CloudVault needs 'All Files Access'. Please grant this permission in the settings screen.")
                        .setPositiveButton("Grant") { _, _ ->
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                intent.data = android.net.Uri.parse("package:$packageName")
                                startActivity(intent)
                            } catch (e: Exception) {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                startActivity(intent)
                            }
                        }
                        .setNegativeButton("Not Now") { _, _ ->
                            showFolderSelectionDialog()
                        }
                        .show()
                    return@setOnClickListener
                }
            }
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



            withContext(Dispatchers.Main) {
                val dialog = android.app.Dialog(this@MainActivity)
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                dialog.setContentView(R.layout.dialog_folder_selection)
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                dialog.window?.setLayout(
                    (resources.displayMetrics.widthPixels * 0.95).toInt(),
                    (resources.displayMetrics.heightPixels * 0.85).toInt()
                )

                val etFolderSearch = dialog.findViewById<android.widget.EditText>(R.id.etFolderSearch)
                val rvFolders = dialog.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvFolders)
                val btnCancel = dialog.findViewById<android.view.View>(R.id.btnCancelSelection)
                val btnSave = dialog.findViewById<android.view.View>(R.id.btnSaveSelection)

                rvFolders.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
                val adapter = FolderSelectionAdapter(folders) { _ -> }
                rvFolders.adapter = adapter

                etFolderSearch.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        val query = s?.toString()?.lowercase() ?: ""
                        val filtered = if (query.isBlank()) {
                            folders
                        } else {
                            folders.filter { it.bucketName.lowercase().contains(query) }
                        }
                        adapter.updateData(filtered)
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })

                btnCancel.setOnClickListener {
                    dialog.dismiss()
                }

                btnSave.setOnClickListener {
                    val selectedBucketIds = folders.filter { it.isSelected }.map { it.bucketId }.toSet()
                    AutoBackupPreferences.setSelectedBucketIds(this@MainActivity, selectedBucketIds)
                    Toast.makeText(this@MainActivity, "Backup folders updated (${selectedBucketIds.size} folders)", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }

                dialog.show()
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
