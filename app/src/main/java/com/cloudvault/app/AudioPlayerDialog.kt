package com.cloudvault.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

object AudioPlayerDialog {

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val currentTrack = AudioPlayerManager.currentTrack.value ?: return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_audio_player, null)
        val btnCloseAudioPlayer: TextView = view.findViewById(R.id.btnCloseAudioPlayer)
        val btnAudioQueue: TextView = view.findViewById(R.id.btnAudioQueue)
        val tvFullAudioTitle: TextView = view.findViewById(R.id.tvFullAudioTitle)
        val tvFullAudioSubtitle: TextView = view.findViewById(R.id.tvFullAudioSubtitle)
        val sbAudioProgress: SeekBar = view.findViewById(R.id.sbAudioProgress)
        val tvAudioCurrentTime: TextView = view.findViewById(R.id.tvAudioCurrentTime)
        val tvAudioTotalTime: TextView = view.findViewById(R.id.tvAudioTotalTime)
        val btnAudioShuffle: TextView = view.findViewById(R.id.btnAudioShuffle)
        val btnAudioPrevTrack: TextView = view.findViewById(R.id.btnAudioPrevTrack)
        val cardAudioPlayPause: MaterialCardView = view.findViewById(R.id.cardAudioPlayPause)
        val tvAudioPlayPauseIcon: TextView = view.findViewById(R.id.tvAudioPlayPauseIcon)
        val btnAudioNextTrack: TextView = view.findViewById(R.id.btnAudioNextTrack)
        val btnAudioRepeat: TextView = view.findViewById(R.id.btnAudioRepeat)

        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(view)

        btnCloseAudioPlayer.setOnClickListener { dialog.dismiss() }

        var isUserSeeking = false

        fun formatTime(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val min = totalSec / 60L
            val sec = totalSec % 60L
            return String.format(Locale.getDefault(), "%d:%02d", min, sec)
        }

        btnAudioShuffle.setOnClickListener {
            AudioPlayerManager.toggleShuffle()
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
                }
            }
        }

        lifecycleOwner?.lifecycleScope?.launch {
            AudioPlayerManager.isPlaying.collectLatest { playing ->
                tvAudioPlayPauseIcon.text = if (playing) "⏸" else "▶"
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

    private fun showQueueDialog(activity: Activity) {
        val queue = AudioPlayerManager.playlist.value
        if (queue.isEmpty()) return

        val titles = queue.mapIndexed { idx, item ->
            val isCurrent = item.fileId == AudioPlayerManager.currentTrack.value?.fileId
            if (isCurrent) "▶  ${item.title}" else "    ${item.title}"
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Playlist Queue (${queue.size} Tracks)")
            .setItems(titles) { _, which ->
                if (which in queue.indices) {
                    AudioPlayerManager.play(activity, queue[which], queue)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
