package com.cloudvault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class VaultDisplayItem {
    data class Header(val dateTitle: String, val dateTimestamp: Long) : VaultDisplayItem()
    data class Media(val item: VaultMediaItem) : VaultDisplayItem()
}

class MediaGridAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (VaultMediaItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PHOTO = 1
        const val TYPE_VIDEO = 2
        const val TYPE_FILE = 3

        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = maxMemory / 8
        val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        private val dateFormat = SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.getDefault())
    }

    private var items: List<VaultDisplayItem> = emptyList()

    var isSelectionMode: Boolean = false
        private set

    private val selectedItemIds = mutableSetOf<String>()
    private val selectedItemsMap = mutableMapOf<String, VaultMediaItem>()

    var onItemMenuClick: ((VaultMediaItem, View) -> Unit)? = null
    var onSelectionModeChangeListener: ((Boolean) -> Unit)? = null
    var onSelectionChangedListener: ((Set<VaultMediaItem>) -> Unit)? = null

    fun enterSelectionMode() {
        isSelectionMode = true
        notifyDataSetChanged()
        onSelectionModeChangeListener?.invoke(true)
        onSelectionChangedListener?.invoke(getSelectedItems())
    }

    fun startSelection(initialItem: VaultMediaItem) {
        isSelectionMode = true
        selectedItemIds.clear()
        selectedItemsMap.clear()
        selectedItemIds.add(initialItem.id)
        selectedItemsMap[initialItem.id] = initialItem
        notifyDataSetChanged()
        onSelectionModeChangeListener?.invoke(true)
        onSelectionChangedListener?.invoke(getSelectedItems())
    }

    fun toggleSelection(item: VaultMediaItem) {
        if (selectedItemIds.contains(item.id)) {
            selectedItemIds.remove(item.id)
            selectedItemsMap.remove(item.id)
        } else {
            selectedItemIds.add(item.id)
            selectedItemsMap[item.id] = item
        }

        if (selectedItemIds.isEmpty()) {
            isSelectionMode = false
            notifyDataSetChanged()
            onSelectionModeChangeListener?.invoke(false)
            onSelectionChangedListener?.invoke(emptySet())
        } else {
            isSelectionMode = true
            notifyDataSetChanged()
            onSelectionChangedListener?.invoke(getSelectedItems())
        }
    }

    fun toggleDateGroupSelection(dateTitle: String) {
        val dateItems = getItemsForDateHeader(dateTitle)
        if (dateItems.isEmpty()) return

        val allSelected = dateItems.all { selectedItemIds.contains(it.id) }
        if (allSelected) {
            dateItems.forEach {
                selectedItemIds.remove(it.id)
                selectedItemsMap.remove(it.id)
            }
        } else {
            isSelectionMode = true
            dateItems.forEach {
                selectedItemIds.add(it.id)
                selectedItemsMap[it.id] = it
            }
        }

        if (selectedItemIds.isEmpty()) {
            isSelectionMode = false
            notifyDataSetChanged()
            onSelectionModeChangeListener?.invoke(false)
            onSelectionChangedListener?.invoke(emptySet())
        } else {
            isSelectionMode = true
            notifyDataSetChanged()
            onSelectionModeChangeListener?.invoke(true)
            onSelectionChangedListener?.invoke(getSelectedItems())
        }
    }

    fun getItemsForDateHeader(dateTitle: String): List<VaultMediaItem> {
        val result = mutableListOf<VaultMediaItem>()
        var foundHeader = false
        for (item in items) {
            if (item is VaultDisplayItem.Header) {
                if (item.dateTitle == dateTitle) {
                    foundHeader = true
                } else if (foundHeader) {
                    break
                }
            } else if (foundHeader && item is VaultDisplayItem.Media) {
                result.add(item.item)
            }
        }
        return result
    }

    fun selectAll(visibleMediaItems: List<VaultMediaItem>) {
        isSelectionMode = true
        selectedItemIds.clear()
        selectedItemsMap.clear()
        visibleMediaItems.forEach {
            selectedItemIds.add(it.id)
            selectedItemsMap[it.id] = it
        }
        notifyDataSetChanged()
        onSelectionModeChangeListener?.invoke(true)
        onSelectionChangedListener?.invoke(getSelectedItems())
    }

    fun clearSelection() {
        isSelectionMode = false
        selectedItemIds.clear()
        selectedItemsMap.clear()
        notifyDataSetChanged()
        onSelectionModeChangeListener?.invoke(false)
        onSelectionChangedListener?.invoke(emptySet())
    }

    fun getSelectedItems(): Set<VaultMediaItem> {
        val result = mutableSetOf<VaultMediaItem>()
        result.addAll(selectedItemsMap.values)
        val mediaMap = items.filterIsInstance<VaultDisplayItem.Media>().map { it.item }.associateBy { it.id }
        for (id in selectedItemIds) {
            val found = mediaMap[id]
            if (found != null) {
                result.add(found)
            }
        }
        return result
    }

    fun getSelectedCount(): Int = selectedItemIds.size

    fun submitList(newItems: List<VaultDisplayItem>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = newItems[newItemPosition]
                return when {
                    old is VaultDisplayItem.Header && new is VaultDisplayItem.Header -> old.dateTitle == new.dateTitle
                    old is VaultDisplayItem.Media && new is VaultDisplayItem.Media -> old.item.id == new.item.id
                    else -> false
                }
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newItems[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is VaultDisplayItem.Header -> TYPE_HEADER
            is VaultDisplayItem.Media -> when (item.item.type) {
                MediaType.PHOTO, MediaType.VIDEO -> TYPE_PHOTO
                MediaType.DOCUMENT -> TYPE_FILE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.item_date_header, parent, false)
                HeaderViewHolder(view)
            }
            TYPE_PHOTO -> {
                val view = inflater.inflate(R.layout.item_photo_square, parent, false)
                PhotoSquareViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_file_card, parent, false)
                FileViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is VaultDisplayItem.Header -> (holder as? HeaderViewHolder)?.bind(item)
            is VaultDisplayItem.Media -> {
                when (holder) {
                    is PhotoSquareViewHolder -> holder.bind(item.item)
                    is FileViewHolder -> holder.bind(item.item)
                }
            }
        }
    }

    override fun getItemCount() = items.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)
        private val layoutDateSelectionCheck: FrameLayout? = itemView.findViewById(R.id.layoutDateSelectionCheck)
        private val viewDateCheckUnselected: View? = itemView.findViewById(R.id.viewDateCheckUnselected)
        private val viewDateCheckSelected: FrameLayout? = itemView.findViewById(R.id.viewDateCheckSelected)

        fun bind(header: VaultDisplayItem.Header) {
            tvDateHeader.text = header.dateTitle

            val dateItems = getItemsForDateHeader(header.dateTitle)
            val isAllDateItemsSelected = dateItems.isNotEmpty() && dateItems.all { selectedItemIds.contains(it.id) }

            layoutDateSelectionCheck?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            viewDateCheckSelected?.visibility = if (isAllDateItemsSelected) View.VISIBLE else View.GONE
            viewDateCheckUnselected?.visibility = if (!isAllDateItemsSelected) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleDateGroupSelection(header.dateTitle)
                }
            }

            itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toggleDateGroupSelection(header.dateTitle)
                true
            }

            layoutDateSelectionCheck?.setOnClickListener {
                toggleDateGroupSelection(header.dateTitle)
            }
        }
    }

    inner class PhotoSquareViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvPlaceholderIcon: TextView = itemView.findViewById(R.id.tvPlaceholderIcon)
        private val pbThumbLoading: ProgressBar = itemView.findViewById(R.id.pbThumbLoading)
        private val selectionDimOverlay: View? = itemView.findViewById(R.id.selectionDimOverlay)
        private val layoutSelectionCheck: FrameLayout? = itemView.findViewById(R.id.layoutSelectionCheck)
        private val viewCheckUnselected: View? = itemView.findViewById(R.id.viewCheckUnselected)
        private val viewCheckSelected: FrameLayout? = itemView.findViewById(R.id.viewCheckSelected)

        private val badgeVideoOverlay: FrameLayout? = itemView.findViewById(R.id.badgeVideoOverlay)

        private var loadJob: Job? = null

        fun bind(item: VaultMediaItem) {
            loadJob?.cancel()

            val isSelected = selectedItemIds.contains(item.id)

            selectionDimOverlay?.visibility = if (isSelected) View.VISIBLE else View.GONE
            layoutSelectionCheck?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            viewCheckUnselected?.visibility = if (!isSelected) View.VISIBLE else View.GONE
            viewCheckSelected?.visibility = if (isSelected) View.VISIBLE else View.GONE

            badgeVideoOverlay?.visibility = if (item.type == MediaType.VIDEO) View.VISIBLE else View.GONE
            tvPlaceholderIcon.text = if (item.type == MediaType.VIDEO) "🎬" else "📷"

            ivThumbnail.setImageDrawable(null)
            ivThumbnail.visibility = View.GONE
            tvPlaceholderIcon.visibility = View.VISIBLE
            pbThumbLoading.visibility = View.GONE

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onItemClick(item)
                }
            }

            itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!isSelectionMode) {
                    startSelection(item)
                } else {
                    toggleSelection(item)
                }
                true
            }

            layoutSelectionCheck?.setOnClickListener {
                if (!isSelectionMode) {
                    startSelection(item)
                } else {
                    toggleSelection(item)
                }
            }

            val targetFileId = if (item.thumbnailFileId > 0) {
                item.thumbnailFileId
            } else if (item.type == MediaType.PHOTO) {
                item.fileId
            } else {
                0
            }

            if (targetFileId > 0) {
                val cached = bitmapCache.get(targetFileId)
                    ?: (if (item.fileId > 0) bitmapCache.get(item.fileId) else null)

                if (cached != null) {
                    ivThumbnail.setImageBitmap(cached)
                    ivThumbnail.visibility = View.VISIBLE
                    tvPlaceholderIcon.visibility = View.GONE
                    pbThumbLoading.visibility = View.GONE
                } else {
                    pbThumbLoading.visibility = View.VISIBLE
                    loadJob = scope.launch(Dispatchers.IO) {
                        val bitmap = loadOrDownloadThumbnail(targetFileId, item)
                        withContext(Dispatchers.Main) {
                            pbThumbLoading.visibility = View.GONE
                            if (bitmap != null) {
                                bitmapCache.put(targetFileId, bitmap)
                                if (item.fileId > 0) bitmapCache.put(item.fileId, bitmap)
                                ivThumbnail.setImageBitmap(bitmap)
                                ivThumbnail.visibility = View.VISIBLE
                                tvPlaceholderIcon.visibility = View.GONE
                            } else {
                                ivThumbnail.visibility = View.GONE
                                tvPlaceholderIcon.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            } else {
                pbThumbLoading.visibility = View.GONE
                ivThumbnail.visibility = View.GONE
                tvPlaceholderIcon.visibility = View.VISIBLE
            }
        }
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvPlaceholderIcon: TextView = itemView.findViewById(R.id.tvPlaceholderIcon)
        private val tvBadgeIcon: TextView = itemView.findViewById(R.id.tvBadgeIcon)
        private val badgeVideoOverlay: FrameLayout = itemView.findViewById(R.id.badgeVideoOverlay)
        private val pbThumbLoading: ProgressBar = itemView.findViewById(R.id.pbThumbLoading)
        private val tvMediaTitle: TextView = itemView.findViewById(R.id.tvMediaTitle)
        private val tvMediaSize: TextView = itemView.findViewById(R.id.tvMediaSize)
        private val btnItemMenu: TextView = itemView.findViewById(R.id.btnItemMenu)
        private val selectionDimOverlay: View? = itemView.findViewById(R.id.selectionDimOverlay)
        private val layoutSelectionCheck: FrameLayout? = itemView.findViewById(R.id.layoutSelectionCheck)
        private val viewCheckUnselected: View? = itemView.findViewById(R.id.viewCheckUnselected)
        private val viewCheckSelected: FrameLayout? = itemView.findViewById(R.id.viewCheckSelected)

        private var loadJob: Job? = null

        fun bind(item: VaultMediaItem) {
            loadJob?.cancel()

            val isSelected = selectedItemIds.contains(item.id)

            selectionDimOverlay?.visibility = if (isSelected) View.VISIBLE else View.GONE
            layoutSelectionCheck?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            viewCheckUnselected?.visibility = if (!isSelected) View.VISIBLE else View.GONE
            viewCheckSelected?.visibility = if (isSelected) View.VISIBLE else View.GONE

            tvMediaTitle.text = item.title
            tvMediaSize.text = item.formattedSize

            // Reset views
            ivThumbnail.setImageDrawable(null)
            ivThumbnail.visibility = View.GONE
            tvPlaceholderIcon.visibility = View.VISIBLE
            pbThumbLoading.visibility = View.GONE

            tvPlaceholderIcon.text = "🎬"
            tvBadgeIcon.text = "🎬"
            badgeVideoOverlay.visibility = View.VISIBLE

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onItemClick(item)
                }
            }

            itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!isSelectionMode) {
                    startSelection(item)
                } else {
                    toggleSelection(item)
                }
                true
            }

            layoutSelectionCheck?.setOnClickListener {
                if (!isSelectionMode) {
                    startSelection(item)
                } else {
                    toggleSelection(item)
                }
            }

            btnItemMenu.setOnClickListener { view ->
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onItemMenuClick?.invoke(item, view) ?: onItemClick(item)
                }
            }

            val targetFileId = if (item.thumbnailFileId > 0) {
                item.thumbnailFileId
            } else if (item.type == MediaType.PHOTO) {
                item.fileId
            } else {
                0
            }

            if (targetFileId > 0) {
                val cached = bitmapCache.get(targetFileId)
                    ?: if (item.fileId > 0) bitmapCache.get(item.fileId) else null

                if (cached != null) {
                    ivThumbnail.setImageBitmap(cached)
                    ivThumbnail.visibility = View.VISIBLE
                    tvPlaceholderIcon.visibility = View.GONE
                    pbThumbLoading.visibility = View.GONE
                } else {
                    pbThumbLoading.visibility = View.VISIBLE
                    loadJob = scope.launch(Dispatchers.IO) {
                        val bitmap = loadOrDownloadThumbnail(targetFileId, item)
                        withContext(Dispatchers.Main) {
                            pbThumbLoading.visibility = View.GONE
                            if (bitmap != null) {
                                bitmapCache.put(targetFileId, bitmap)
                                if (item.fileId > 0) bitmapCache.put(item.fileId, bitmap)
                                ivThumbnail.setImageBitmap(bitmap)
                                ivThumbnail.visibility = View.VISIBLE
                                tvPlaceholderIcon.visibility = View.GONE
                            } else {
                                ivThumbnail.visibility = View.GONE
                                tvPlaceholderIcon.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            } else {
                pbThumbLoading.visibility = View.GONE
                ivThumbnail.visibility = View.GONE
                tvPlaceholderIcon.visibility = View.VISIBLE
            }
        }
    }

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileBadge: TextView = itemView.findViewById(R.id.tvFileBadge)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val tvFileDate: TextView = itemView.findViewById(R.id.tvFileDate)
        private val btnFileMenu: TextView = itemView.findViewById(R.id.btnFileMenu)
        private val selectionDimOverlay: View? = itemView.findViewById(R.id.selectionDimOverlay)
        private val layoutSelectionCheck: FrameLayout? = itemView.findViewById(R.id.layoutSelectionCheck)
        private val viewCheckUnselected: View? = itemView.findViewById(R.id.viewCheckUnselected)
        private val viewCheckSelected: FrameLayout? = itemView.findViewById(R.id.viewCheckSelected)

        fun bind(item: VaultMediaItem) {
            val isSelected = selectedItemIds.contains(item.id)

            selectionDimOverlay?.visibility = if (isSelected) View.VISIBLE else View.GONE
            btnFileMenu.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            layoutSelectionCheck?.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
            viewCheckUnselected?.visibility = if (!isSelected) View.VISIBLE else View.GONE
            viewCheckSelected?.visibility = if (isSelected) View.VISIBLE else View.GONE

            tvFileName.text = item.title
            tvFileSize.text = item.formattedSize

            val ext = item.title.substringAfterLast('.', "").uppercase().ifBlank { "FILE" }
            tvFileBadge.text = ext

            val formattedDate = if (item.dateAdded > 0) {
                dateFormat.format(Date(item.dateAdded * 1000L))
            } else {
                "Recent"
            }
            tvFileDate.text = formattedDate

            itemView.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onItemClick(item)
                }
            }

            itemView.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!isSelectionMode) {
                    startSelection(item)
                } else {
                    toggleSelection(item)
                }
                true
            }

            layoutSelectionCheck?.setOnClickListener {
                if (!isSelectionMode) {
                    startSelection(item)
                } else {
                    toggleSelection(item)
                }
            }

            btnFileMenu.setOnClickListener { view ->
                if (isSelectionMode) {
                    toggleSelection(item)
                } else {
                    onItemMenuClick?.invoke(item, view) ?: onItemClick(item)
                }
            }
        }
    }

    private val thumbnailSemaphore = kotlinx.coroutines.sync.Semaphore(6)

    private suspend fun loadOrDownloadThumbnail(targetFileId: Int, item: VaultMediaItem): Bitmap? {
        if (targetFileId <= 0) return null
        return withContext(Dispatchers.IO) {
            thumbnailSemaphore.withPermit {
                try {
                    // 1. Try targetFileId (fast lightweight thumbnail)
                    val tdFile = TelegramClient.downloadFileAndWait(targetFileId, priority = 32, timeoutMs = 8000L)
                    if (tdFile != null && tdFile.local.path.isNotBlank() && File(tdFile.local.path).exists()) {
                        val bmp = ImageUtils.decodeOrientedBitmap(tdFile.local.path, maxDimension = 400)
                        if (bmp != null) return@withPermit bmp
                    }

                    // 2. Only for small photos: fallback to main file if <= 5MB
                    if (item.type == MediaType.PHOTO && targetFileId != item.fileId && item.fileId > 0 && item.sizeBytes in 1..5_000_000L) {
                        val mainFile = TelegramClient.downloadFileAndWait(item.fileId, priority = 24, timeoutMs = 10000L)
                        if (mainFile != null && mainFile.local.path.isNotBlank() && File(mainFile.local.path).exists()) {
                            val bmp = ImageUtils.decodeOrientedBitmap(mainFile.local.path, maxDimension = 400)
                            if (bmp != null) return@withPermit bmp
                        }
                    }

                    null
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}
