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
        return when (items[position].type) {
            MediaType.PHOTO -> TYPE_PHOTO
            MediaType.VIDEO -> TYPE_VIDEO
            MediaType.DOCUMENT -> TYPE_FILE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PHOTO -> {
                val view = inflater.inflate(R.layout.item_photo_square, parent, false)
                PhotoSquareViewHolder(view)
            }
            TYPE_VIDEO -> {
                val view = inflater.inflate(R.layout.item_video_square, parent, false)
                VideoSquareViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_file_card, parent, false)
                FileViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is PhotoSquareViewHolder -> holder.bind(item)
            is VideoSquareViewHolder -> holder.bind(item)
            is FileViewHolder -> holder.bind(item)
        }
    }

    override fun getItemCount() = items.size

    inner class PhotoSquareViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvPlaceholderIcon: TextView = itemView.findViewById(R.id.tvPlaceholderIcon)
        private val pbThumbLoading: ProgressBar = itemView.findViewById(R.id.pbThumbLoading)
        private var loadJob: Job? = null

        fun bind(item: VaultMediaItem) {
            loadJob?.cancel()

            ivThumbnail.setImageDrawable(null)
            ivThumbnail.visibility = View.GONE
            tvPlaceholderIcon.visibility = View.VISIBLE
            pbThumbLoading.visibility = View.GONE

            itemView.setOnClickListener { onItemClick(item) }

            val targetFileId = if (item.thumbnailFileId > 0) item.thumbnailFileId else item.fileId
            if (targetFileId > 0) {
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
        }
    }

    inner class VideoSquareViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val tvPlaceholderIcon: TextView = itemView.findViewById(R.id.tvPlaceholderIcon)
        private val pbThumbLoading: ProgressBar = itemView.findViewById(R.id.pbThumbLoading)
        private var loadJob: Job? = null

        fun bind(item: VaultMediaItem) {
            loadJob?.cancel()

            ivThumbnail.setImageDrawable(null)
            ivThumbnail.visibility = View.GONE
            tvPlaceholderIcon.visibility = View.VISIBLE
            pbThumbLoading.visibility = View.GONE

            itemView.setOnClickListener { onItemClick(item) }

            val targetFileId = if (item.thumbnailFileId > 0) item.thumbnailFileId else item.fileId
            if (targetFileId > 0) {
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
        }
    }

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

    private suspend fun loadOrDownloadThumbnail(targetFileId: Int, item: VaultMediaItem): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                var tdFile = TelegramClient.sendRequest(TdApi.GetFile(targetFileId)) as TdApi.File

                if (tdFile.local.isDownloadingCompleted && tdFile.local.path.isNotBlank() && File(tdFile.local.path).exists()) {
                    return@withContext decodeSampledBitmap(tdFile.local.path, 300, 300)
                }

                TelegramClient.sendRequest(
                    TdApi.DownloadFile(
                        targetFileId,
                        32,
                        0L,
                        0L,
                        false
                    )
                )

                var attempts = 0
                while (attempts < 25) {
                    delay(200)
                    tdFile = TelegramClient.sendRequest(TdApi.GetFile(targetFileId)) as TdApi.File
                    if (tdFile.local.isDownloadingCompleted && File(tdFile.local.path).exists()) {
                        return@withContext decodeSampledBitmap(tdFile.local.path, 300, 300)
                    }
                    attempts++
                }

                if (item.type == MediaType.VIDEO && TelegramStreamingProxy.port > 0) {
                    return@withContext extractVideoFrameFromProxy(item.fileId, item.title)
                }

                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun extractVideoFrameFromProxy(fileId: Int, title: String): Bitmap? {
        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever()
            val streamUrl = TelegramStreamingProxy.getUrl(fileId, title)
            retriever.setDataSource(streamUrl, HashMap())
            retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever?.release()
            } catch (_: Throwable) {}
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
