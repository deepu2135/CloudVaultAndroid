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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaGridAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (VaultMediaItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_MEDIA = 1
        const val TYPE_FILE = 2

        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = maxMemory / 8
        val bitmapCache = object : LruCache<Int, Bitmap>(cacheSize) {
            override fun sizeOf(key: Int, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        private val dateFormat = SimpleDateFormat("dd MMM yyyy • hh:mm a", Locale.getDefault())
    }

    private var items: List<VaultMediaItem> = emptyList()

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

    override fun getItemViewType(position: Int): Int {
        return if (items[position].type == MediaType.DOCUMENT) TYPE_FILE else TYPE_MEDIA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_FILE) {
            val view = inflater.inflate(R.layout.item_file_card, parent, false)
            FileViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_media_card, parent, false)
            MediaViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is MediaViewHolder) {
            holder.bind(item)
        } else if (holder is FileViewHolder) {
            holder.bind(item)
        }
    }

    override fun getItemCount() = items.size

    inner class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvFileBadge: TextView = itemView.findViewById(R.id.tvFileBadge)
        private val tvFileSize: TextView = itemView.findViewById(R.id.tvFileSize)
        private val tvFileDate: TextView = itemView.findViewById(R.id.tvFileDate)
        private val btnFileMenu: TextView = itemView.findViewById(R.id.btnFileMenu)

        fun bind(item: VaultMediaItem) {
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

            itemView.setOnClickListener { onItemClick(item) }
            btnFileMenu.setOnClickListener { onItemClick(item) }
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
                    tvBadgeIcon.text = "📷"
                    badgeVideoOverlay.visibility = View.GONE
                }
                MediaType.VIDEO -> {
                    tvPlaceholderIcon.text = "🎬"
                    tvBadgeIcon.text = "🎬"
                    badgeVideoOverlay.visibility = View.VISIBLE
                }
                MediaType.DOCUMENT -> {
                    tvPlaceholderIcon.text = "📄"
                    tvBadgeIcon.text = "📄"
                    badgeVideoOverlay.visibility = View.GONE
                }
            }

            btnItemMenu.setOnClickListener {
                onItemClick(item)
            }

            val targetFileId = if (item.thumbnailFileId > 0) item.thumbnailFileId else item.fileId

            if (targetFileId > 0 && (item.type == MediaType.PHOTO || item.type == MediaType.VIDEO || item.thumbnailFileId > 0)) {
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

                if (item.type == MediaType.VIDEO && item.fileId > 0) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        val proxyUrl = "http://127.0.0.1:${TelegramStreamingProxy.port}/stream?file_id=${item.fileId}"
                        retriever.setDataSource(proxyUrl, HashMap())
                        val frame = retriever.getFrameAtTime(1000000) ?: retriever.frameAtTime
                        if (frame != null) return frame
                    } catch (e: Throwable) {
                        // ignore
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
