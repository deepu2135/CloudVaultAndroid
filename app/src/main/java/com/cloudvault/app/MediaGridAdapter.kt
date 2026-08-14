package com.cloudvault.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
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

class MediaGridAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (VaultMediaItem) -> Unit
) : RecyclerView.Adapter<MediaGridAdapter.MediaViewHolder>() {

    private var items: List<VaultMediaItem> = emptyList()

    // In-memory LRU cache for downloaded thumbnails & video posters
    companion object {
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = maxMemory / 8
        val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    fun submitList(newItems: List<VaultMediaItem>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].id == newItems[newItemPosition].id
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newItems[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_card, parent, false)
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount() = items.size

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvPlaceholderIcon: TextView = itemView.findViewById(R.id.tvPlaceholderIcon)
        private val badgeVideoOverlay: FrameLayout = itemView.findViewById(R.id.badgeVideoOverlay)
        private val pbThumbLoading: ProgressBar = itemView.findViewById(R.id.pbThumbLoading)
        private val tvMediaTitle: TextView = itemView.findViewById(R.id.tvMediaTitle)
        private val tvMediaSize: TextView = itemView.findViewById(R.id.tvMediaSize)

        private var loadJob: Job? = null

        fun bind(item: VaultMediaItem) {
            loadJob?.cancel()

            tvMediaTitle.text = item.title
            tvMediaSize.text = item.formattedSize

            // Reset views
            ivThumbnail.setImageDrawable(null)
            ivThumbnail.visibility = View.GONE
            tvPlaceholderIcon.visibility = View.VISIBLE
            pbThumbLoading.visibility = View.GONE

            when (item.type) {
                MediaType.PHOTO -> {
                    tvPlaceholderIcon.text = "📷"
                    badgeVideoOverlay.visibility = View.GONE
                }
                MediaType.VIDEO -> {
                    tvPlaceholderIcon.text = "🎬"
                    badgeVideoOverlay.visibility = View.VISIBLE
                }
                MediaType.DOCUMENT -> {
                    tvPlaceholderIcon.text = "📄"
                    badgeVideoOverlay.visibility = View.GONE
                }
            }

            val targetFileId = if (item.thumbnailFileId > 0) item.thumbnailFileId else item.fileId

            if (targetFileId > 0 && (item.type == MediaType.PHOTO || item.type == MediaType.VIDEO || item.thumbnailFileId > 0)) {
                // Check memory cache first
                val cached = bitmapCache.get(targetFileId)
                if (cached != null) {
                    ivThumbnail.setImageBitmap(cached)
                    ivThumbnail.visibility = View.VISIBLE
                    tvPlaceholderIcon.visibility = View.GONE
                } else {
                    pbThumbLoading.visibility = View.VISIBLE
                    loadJob = scope.launch(Dispatchers.IO) {
                        val bitmap = loadOrDownloadThumbnail(targetFileId, item)
                        withContext(Dispatchers.Main) {
                            pbThumbLoading.visibility = View.GONE
                            if (bitmap != null) {
                                bitmapCache.put(targetFileId, bitmap)
                                ivThumbnail.setImageBitmap(bitmap)
                                ivThumbnail.visibility = View.VISIBLE
                                tvPlaceholderIcon.visibility = View.GONE
                            }
                        }
                    }
                }
            }

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        private suspend fun loadOrDownloadThumbnail(fileId: Int, item: VaultMediaItem): Bitmap? {
            return try {
                // 1. If TDLib thumbnail / photo file ID is available, download and decode it
                if (item.thumbnailFileId > 0 || item.type == MediaType.PHOTO) {
                    var tdFile = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as TdApi.File
                    if (!tdFile.local.isDownloadingCompleted || tdFile.local.path.isBlank() || !File(tdFile.local.path).exists()) {
                        TelegramClient.sendRequest(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
                        var attempts = 0
                        while (attempts < 25) {
                            delay(200)
                            tdFile = TelegramClient.sendRequest(TdApi.GetFile(fileId)) as TdApi.File
                            if (tdFile.local.isDownloadingCompleted && File(tdFile.local.path).exists()) {
                                break
                            }
                            attempts++
                        }
                    }

                    val path = tdFile.local.path
                    if (path.isNotBlank() && File(path).exists()) {
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = 2
                        }
                        val decoded = BitmapFactory.decodeFile(path, options)
                        if (decoded != null) return decoded
                    }
                }

                // 2. For video without pre-built thumbnail, extract a frame via the streaming proxy
                if (item.type == MediaType.VIDEO && item.fileId > 0) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        val proxyUrl = "http://127.0.0.1:${TelegramStreamingProxy.port}/stream?file_id=${item.fileId}"
                        retriever.setDataSource(proxyUrl, HashMap())
                        val frame = retriever.getFrameAtTime(1000000) ?: retriever.frameAtTime
                        if (frame != null) return frame
                    } catch (e: Throwable) {
                        // ignore and return null
                    } finally {
                        try { retriever.release() } catch (e: Throwable) {}
                    }
                }

                null
            } catch (e: Throwable) {
                null
            }
        }
    }
}
