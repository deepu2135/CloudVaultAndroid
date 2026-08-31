package com.cloudvault.app

import android.app.Activity
import android.app.Dialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

object AudioPlayerDialog {

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val currentTrack = AudioPlayerManager.currentTrack.value ?: return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_audio_player, null)
        val btnCloseAudioPlayer: TextView = view.findViewById(R.id.btnCloseAudioPlayer)
        val btnAudioQueue: TextView = view.findViewById(R.id.btnAudioQueue)
        val tvAudioHeaderTrackIndex: TextView = view.findViewById(R.id.tvAudioHeaderTrackIndex)
        val layoutAudioPlaceholderArt: FrameLayout = view.findViewById(R.id.layoutAudioPlaceholderArt)
        val tvAudioVisualizerBars: TextView = view.findViewById(R.id.tvAudioVisualizerBars)
        val ivAudioArtwork: ImageView = view.findViewById(R.id.ivAudioArtwork)
        val tvFullAudioTitle: TextView = view.findViewById(R.id.tvFullAudioTitle)
        val tvFullAudioSubtitle: TextView = view.findViewById(R.id.tvFullAudioSubtitle)
        val sbAudioProgress: SeekBar = view.findViewById(R.id.sbAudioProgress)
        val tvAudioCurrentTime: TextView = view.findViewById(R.id.tvAudioCurrentTime)
        val tvAudioTotalTime: TextView = view.findViewById(R.id.tvAudioTotalTime)
        val btnAudioShuffle: TextView = view.findViewById(R.id.btnAudioShuffle)
        val btnAudioRewind10: TextView = view.findViewById(R.id.btnAudioRewind10)
        val btnAudioPrevTrack: TextView = view.findViewById(R.id.btnAudioPrevTrack)
        val cardAudioPlayPause: MaterialCardView = view.findViewById(R.id.cardAudioPlayPause)
        val tvAudioPlayPauseIcon: TextView = view.findViewById(R.id.tvAudioPlayPauseIcon)
        val btnAudioNextTrack: TextView = view.findViewById(R.id.btnAudioNextTrack)
        val btnAudioForward10: TextView = view.findViewById(R.id.btnAudioForward10)
        val btnAudioRepeat: TextView = view.findViewById(R.id.btnAudioRepeat)

        val btnAudioSpeedPill: MaterialButton = view.findViewById(R.id.btnAudioSpeedPill)
        val btnAudioSleepPill: MaterialButton = view.findViewById(R.id.btnAudioSleepPill)
        val btnAudioQueuePill: MaterialButton = view.findViewById(R.id.btnAudioQueuePill)

        val dialog = Dialog(activity, R.style.Theme_CloudVault_Dialog_Fullscreen)
        dialog.setContentView(view)

        btnCloseAudioPlayer.setOnClickListener { dialog.dismiss() }

        var isUserSeeking = false

        fun formatTime(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val hrs = totalSec / 3600L
            val min = (totalSec % 3600L) / 60L
            val sec = totalSec % 60L
            return if (hrs > 0) {
                String.format(Locale.getDefault(), "%d:%02d:%02d", hrs, min, sec)
            } else {
                String.format(Locale.getDefault(), "%d:%02d", min, sec)
            }
        }

        btnAudioShuffle.setOnClickListener {
            AudioPlayerManager.toggleShuffle()
        }

        btnAudioRewind10.setOnClickListener {
            AudioPlayerManager.seekRelative(activity, -10000L)
        }

        btnAudioForward10.setOnClickListener {
            AudioPlayerManager.seekRelative(activity, 10000L)
        }

        btnAudioRepeat.setOnClickListener {
            AudioPlayerManager.toggleRepeat()
        }

        btnAudioPrevTrack.setOnClickListener {
            AudioPlayerManager.playPrevious(activity)
        }

        btnAudioNextTrack.setOnClickListener {
            AudioPlayerManager.playNext(activity)
        }

        cardAudioPlayPause.setOnClickListener {
            AudioPlayerManager.togglePlayPause(activity)
        }

        btnAudioQueue.setOnClickListener {
            showQueueDialog(activity)
        }

        btnAudioQueuePill.setOnClickListener {
            showQueueDialog(activity)
        }

        // Speed cycling
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        btnAudioSpeedPill.setOnClickListener {
            val current = AudioPlayerManager.playbackSpeed.value
            val currentIdx = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.05f }
            val nextIdx = if (currentIdx >= 0) (currentIdx + 1) % speeds.size else 1
            val nextSpeed = speeds[nextIdx]
            AudioPlayerManager.setPlaybackSpeed(activity, nextSpeed)
        }

        // Sleep Timer
        btnAudioSleepPill.setOnClickListener {
            showSleepTimerDialog(activity)
        }

        sbAudioProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = AudioPlayerManager.durationMs.value
                    if (duration > 0L) {
                        val seekPos = (progress.toLong() * duration) / 1000L
                        tvAudioCurrentTime.text = formatTime(seekPos)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                val progress = seekBar?.progress ?: 0
                val duration = AudioPlayerManager.durationMs.value
                if (duration > 0L) {
                    val seekPos = (progress.toLong() * duration) / 1000L
                    AudioPlayerManager.seekTo(activity, seekPos)
                }
            }
        })

        val lifecycleOwner = activity as? LifecycleOwner
        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.currentTrack.collectLatest { track ->
                if (track == null) {
                    dialog.dismiss()
                } else {
                    tvFullAudioTitle.text = track.title
                    tvFullAudioSubtitle.text = "${track.mimeType} • ${track.formattedSize}"
                    val queue = AudioPlayerManager.playlist.value
                    val idx = queue.indexOfFirst { it.fileId == track.fileId }
                    if (idx >= 0 && queue.isNotEmpty()) {
                        tvAudioHeaderTrackIndex.text = "Track ${idx + 1} of ${queue.size}"
                        btnAudioQueuePill.text = "📋 Queue (${queue.size})"
                    }

                    // Load Thumbnail / Album Artwork if available
                    if (track.thumbnailFileId > 0) {
                        val cached = MediaGridAdapter.bitmapCache.get(track.thumbnailFileId)
                        if (cached != null) {
                            ivAudioArtwork.setImageBitmap(cached)
                            ivAudioArtwork.visibility = View.VISIBLE
                            layoutAudioPlaceholderArt.visibility = View.GONE
                        } else {
                            ivAudioArtwork.visibility = View.GONE
                            layoutAudioPlaceholderArt.visibility = View.VISIBLE
                            launch(Dispatchers.IO) {
                                val thumbBmp = AudioThumbnailHelper.getThumbnailBitmap(track)
                                withContext(Dispatchers.Main) {
                                    if (thumbBmp != null && AudioPlayerManager.currentTrack.value?.fileId == track.fileId) {
                                        ivAudioArtwork.setImageBitmap(thumbBmp)
                                        ivAudioArtwork.visibility = View.VISIBLE
                                        layoutAudioPlaceholderArt.visibility = View.GONE
                                    }
                                }
                            }
                        }
                    } else {
                        ivAudioArtwork.visibility = View.GONE
                        layoutAudioPlaceholderArt.visibility = View.VISIBLE
                    }
                }
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.isPlaying.collectLatest { playing ->
                tvAudioPlayPauseIcon.text = if (playing) "⏸" else "▶"
                tvAudioVisualizerBars.text = if (playing) "ılı.lıllılı.ıllı" else "— — — — —"
                tvAudioVisualizerBars.alpha = if (playing) 1.0f else 0.4f
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.isShuffle.collectLatest { shuffle ->
                btnAudioShuffle.alpha = if (shuffle) 1.0f else 0.4f
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.isRepeat.collectLatest { repeat ->
                btnAudioRepeat.alpha = if (repeat) 1.0f else 0.4f
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.playbackSpeed.collectLatest { speed ->
                btnAudioSpeedPill.text = "⚡ ${speed}x"
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.sleepTimerMinutes.collectLatest { minutes ->
                if (minutes != null && minutes > 0) {
                    btnAudioSleepPill.text = "🌙 ${minutes}m"
                } else {
                    btnAudioSleepPill.text = "🌙 Sleep"
                }
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.currentPositionMs.collectLatest { pos ->
                if (!isUserSeeking) {
                    tvAudioCurrentTime.text = formatTime(pos)
                    val dur = AudioPlayerManager.durationMs.value
                    if (dur > 0L) {
                        sbAudioProgress.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
                    }
                }
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.durationMs.collectLatest { dur ->
                tvAudioTotalTime.text = formatTime(dur)
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun showSleepTimerDialog(activity: Activity) {
        val options = arrayOf("Turn Off", "15 Minutes", "30 Minutes", "45 Minutes", "60 Minutes")
        val minutes = arrayOf(null, 15, 30, 45, 60)

        AlertDialog.Builder(activity)
            .setTitle("🌙 Sleep Timer")
            .setItems(options) { _, which ->
                AudioPlayerManager.setSleepTimer(activity, minutes[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQueueDialog(activity: Activity) {
        val queue = AudioPlayerManager.playlist.value
        if (queue.isEmpty()) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_audio_queue, null)
        val tvQueueCount: TextView = view.findViewById(R.id.tvQueueCount)
        val btnQueueShuffleAll: MaterialButton = view.findViewById(R.id.btnQueueShuffleAll)
        val btnCloseQueue: TextView = view.findViewById(R.id.btnCloseQueue)
        val etQueueSearch: EditText = view.findViewById(R.id.etQueueSearch)
        val rvQueueTracks: RecyclerView = view.findViewById(R.id.rvQueueTracks)

        val queueDialog = Dialog(activity, R.style.Theme_CloudVault_Dialog_Fullscreen)
        queueDialog.setContentView(view)

        btnCloseQueue.setOnClickListener { queueDialog.dismiss() }

        var displayList = queue.toList()

        tvQueueCount.text = "${queue.size} tracks • Tap any track to play"

        val adapter = QueueTrackAdapter(
            items = displayList,
            currentTrackId = AudioPlayerManager.currentTrack.value?.fileId,
            scope = (activity as? LifecycleOwner)?.lifecycleScope ?: CoroutineScope(Dispatchers.Main),
            onTrackClick = { clickedItem ->
                AudioPlayerManager.play(activity, clickedItem, queue)
                queueDialog.dismiss()
            }
        )

        rvQueueTracks.layoutManager = LinearLayoutManager(activity)
        rvQueueTracks.adapter = adapter

        btnQueueShuffleAll.setOnClickListener {
            AudioPlayerManager.shuffleQueue(activity)
            val updated = AudioPlayerManager.playlist.value
            displayList = updated
            adapter.updateItems(updated, AudioPlayerManager.currentTrack.value?.fileId)
            tvQueueCount.text = "${updated.size} tracks • Shuffled"
        }

        etQueueSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                val currentQueue = AudioPlayerManager.playlist.value
                val filtered = if (query.isBlank()) {
                    currentQueue
                } else {
                    currentQueue.filter { it.title.contains(query, ignoreCase = true) }
                }
                adapter.updateItems(filtered, AudioPlayerManager.currentTrack.value?.fileId)
                tvQueueCount.text = if (query.isNotBlank()) {
                    "${filtered.size} of ${currentQueue.size} matching"
                } else {
                    "${currentQueue.size} tracks • Tap any track to play"
                }
            }
        })

        queueDialog.show()
        queueDialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private class QueueTrackAdapter(
        private var items: List<VaultMediaItem>,
        private var currentTrackId: Int?,
        private val scope: CoroutineScope,
        private val onTrackClick: (VaultMediaItem) -> Unit
    ) : RecyclerView.Adapter<QueueTrackAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardQueueItem: MaterialCardView = view.findViewById(R.id.cardQueueItem)
            val tvTrackIndex: TextView = view.findViewById(R.id.tvTrackIndex)
            val tvPlayingIndicator: TextView = view.findViewById(R.id.tvPlayingIndicator)
            val ivQueueTrackThumb: ImageView = view.findViewById(R.id.ivQueueTrackThumb)
            val tvQueueTrackTitle: TextView = view.findViewById(R.id.tvQueueTrackTitle)
            val tvQueueTrackSubtitle: TextView = view.findViewById(R.id.tvQueueTrackSubtitle)
            val tvNowPlayingTag: TextView = view.findViewById(R.id.tvNowPlayingTag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_track, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isPlaying = item.fileId == currentTrackId

            holder.tvTrackIndex.text = "${position + 1}"
            holder.tvTrackIndex.visibility = if (isPlaying) View.GONE else View.VISIBLE
            holder.tvPlayingIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE
            holder.tvNowPlayingTag.visibility = if (isPlaying) View.VISIBLE else View.GONE

            holder.tvQueueTrackTitle.text = item.title
            val durStr = if (item.durationSeconds > 0) {
                val min = item.durationSeconds / 60
                val sec = item.durationSeconds % 60
                String.format(Locale.getDefault(), " • %d:%02d", min, sec)
            } else ""
            holder.tvQueueTrackSubtitle.text = "${item.mimeType} • ${item.formattedSize}$durStr"

            // Thumbnail in queue row
            if (item.thumbnailFileId > 0) {
                val cached = MediaGridAdapter.bitmapCache.get(item.thumbnailFileId)
                if (cached != null) {
                    holder.ivQueueTrackThumb.setImageBitmap(cached)
                    holder.ivQueueTrackThumb.visibility = View.VISIBLE
                    holder.tvTrackIndex.visibility = View.GONE
                } else {
                    holder.ivQueueTrackThumb.visibility = View.GONE
                    if (!isPlaying) holder.tvTrackIndex.visibility = View.VISIBLE
                    scope.launch(Dispatchers.IO) {
                        val thumb = AudioThumbnailHelper.getThumbnailBitmap(item)
                        withContext(Dispatchers.Main) {
                            if (thumb != null && holder.adapterPosition == position) {
                                holder.ivQueueTrackThumb.setImageBitmap(thumb)
                                holder.ivQueueTrackThumb.visibility = View.VISIBLE
                                holder.tvTrackIndex.visibility = View.GONE
                            }
                        }
                    }
                }
            } else {
                holder.ivQueueTrackThumb.visibility = View.GONE
                if (!isPlaying) holder.tvTrackIndex.visibility = View.VISIBLE
            }

            val context = holder.itemView.context
            if (isPlaying) {
                holder.cardQueueItem.strokeColor = context.getColor(R.color.accent_cyan)
                holder.cardQueueItem.setCardBackgroundColor(context.getColor(R.color.status_pill_bg))
                holder.tvQueueTrackTitle.setTextColor(context.getColor(R.color.accent_cyan_bright))
            } else {
                holder.cardQueueItem.strokeColor = context.getColor(R.color.card_border)
                holder.cardQueueItem.setCardBackgroundColor(context.getColor(R.color.card_bg))
                holder.tvQueueTrackTitle.setTextColor(context.getColor(R.color.text_primary))
            }

            holder.cardQueueItem.setOnClickListener {
                onTrackClick(item)
            }
        }

        override fun getItemCount(): Int = items.size

        fun updateItems(newItems: List<VaultMediaItem>, newCurrentTrackId: Int?) {
            items = newItems
            currentTrackId = newCurrentTrackId
            notifyDataSetChanged()
        }
    }
}
