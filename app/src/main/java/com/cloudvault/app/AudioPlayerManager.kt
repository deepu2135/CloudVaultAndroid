package com.cloudvault.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AudioPlayerManager {

    private val _currentTrack = MutableStateFlow<VaultMediaItem?>(null)
    val currentTrack: StateFlow<VaultMediaItem?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playlist = MutableStateFlow<List<VaultMediaItem>>(emptyList())
    val playlist: StateFlow<List<VaultMediaItem>> = _playlist.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _isRepeat = MutableStateFlow(false)
    val isRepeat: StateFlow<Boolean> = _isRepeat.asStateFlow()

    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun play(context: Context, item: VaultMediaItem, queue: List<VaultMediaItem> = listOf(item)) {
        _currentTrack.value = item
        _playlist.value = queue
        val idx = queue.indexOfFirst { it.fileId == item.fileId }
        _currentIndex.value = if (idx >= 0) idx else 0
        _isBuffering.value = true
        _isPlaying.value = true
        _currentPositionMs.value = 0L
        _durationMs.value = (item.durationSeconds * 1000L).coerceAtLeast(0L)

        val intent = Intent(context, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_PLAY
            putExtra(AudioPlayerService.EXTRA_FILE_ID, item.fileId)
            putExtra(AudioPlayerService.EXTRA_TITLE, item.title)
            putExtra(AudioPlayerService.EXTRA_CHAT_ID, item.chatId)
            putExtra(AudioPlayerService.EXTRA_MESSAGE_ID, item.messageId)
            putExtra(AudioPlayerService.EXTRA_SIZE_BYTES, item.sizeBytes)
            putExtra(AudioPlayerService.EXTRA_DURATION_SEC, item.durationSeconds)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun togglePlayPause(context: Context) {
        val intent = Intent(context, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_TOGGLE_PLAY
        }
        context.startService(intent)
    }

    fun pause(context: Context) {
        val intent = Intent(context, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resume(context: Context) {
        val intent = Intent(context, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun seekTo(context: Context, positionMs: Long) {
        _currentPositionMs.value = positionMs
        val intent = Intent(context, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_SEEK
            putExtra(AudioPlayerService.EXTRA_SEEK_POSITION, positionMs)
        }
        context.startService(intent)
    }

    fun playNext(context: Context) {
        val queue = _playlist.value
        if (queue.isEmpty()) return

        var nextIndex = _currentIndex.value + 1
        if (_isShuffle.value && queue.size > 1) {
            nextIndex = (queue.indices).filter { it != _currentIndex.value }.random()
        } else if (nextIndex >= queue.size) {
            nextIndex = 0
        }

        if (nextIndex in queue.indices) {
            play(context, queue[nextIndex], queue)
        }
    }

    fun playPrevious(context: Context) {
        val queue = _playlist.value
        if (queue.isEmpty()) return

        // If played more than 3 seconds, restart current track
        if (_currentPositionMs.value > 3000L) {
            seekTo(context, 0L)
            return
        }

        var prevIndex = _currentIndex.value - 1
        if (prevIndex < 0) {
            prevIndex = queue.size - 1
        }

        if (prevIndex in queue.indices) {
            play(context, queue[prevIndex], queue)
        }
    }

    fun stop(context: Context) {
        _currentTrack.value = null
        _isPlaying.value = false
        _isBuffering.value = false
        _currentPositionMs.value = 0L
        progressJob?.cancel()

        val intent = Intent(context, AudioPlayerService::class.java).apply {
            action = AudioPlayerService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun toggleRepeat() {
        _isRepeat.value = !_isRepeat.value
    }

    // Internal updates from Service
    internal fun updateState(
        playing: Boolean,
        buffering: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        _isPlaying.value = playing
        _isBuffering.value = buffering
        _currentPositionMs.value = positionMs
        if (durationMs > 0L) {
            _durationMs.value = durationMs
        }
    }
}
