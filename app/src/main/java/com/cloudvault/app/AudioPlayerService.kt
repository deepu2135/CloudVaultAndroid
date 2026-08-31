package com.cloudvault.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioPlayerService : Service(), MediaPlayer.OnPreparedListener,
    MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener,
    MediaPlayer.OnBufferingUpdateListener {

    companion object {
        const val CHANNEL_ID = "cloudvault_audio_player_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_PLAY = "com.cloudvault.app.action.AUDIO_PLAY"
        const val ACTION_PAUSE = "com.cloudvault.app.action.AUDIO_PAUSE"
        const val ACTION_RESUME = "com.cloudvault.app.action.AUDIO_RESUME"
        const val ACTION_TOGGLE_PLAY = "com.cloudvault.app.action.AUDIO_TOGGLE_PLAY"
        const val ACTION_PREV = "com.cloudvault.app.action.AUDIO_PREV"
        const val ACTION_NEXT = "com.cloudvault.app.action.AUDIO_NEXT"
        const val ACTION_SEEK = "com.cloudvault.app.action.AUDIO_SEEK"
        const val ACTION_SEEK_RELATIVE = "com.cloudvault.app.action.AUDIO_SEEK_RELATIVE"
        const val ACTION_SET_SPEED = "com.cloudvault.app.action.AUDIO_SET_SPEED"
        const val ACTION_STOP = "com.cloudvault.app.action.AUDIO_STOP"

        const val EXTRA_FILE_ID = "extra_file_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_SIZE_BYTES = "extra_size_bytes"
        const val EXTRA_DURATION_SEC = "extra_duration_sec"
        const val EXTRA_SEEK_POSITION = "extra_seek_position"
        const val EXTRA_SEEK_OFFSET_MS = "extra_seek_offset_ms"
        const val EXTRA_SPEED = "extra_speed"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentFileId: Int = 0
    private var currentTitle: String = "Cloud Vault Audio"
    private var currentDurationMs: Long = 0L
    private var currentSpeed: Float = 1.0f
    private var currentThumbnailBmp: android.graphics.Bitmap? = null
    private var isPrepared = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (isPrepared) {
                    val pos = runCatching { player.currentPosition.toLong() }.getOrDefault(0L)
                    val dur = runCatching { player.duration.toLong() }.getOrDefault(currentDurationMs)
                    val playing = runCatching { player.isPlaying }.getOrDefault(false)
                    AudioPlayerManager.updateState(
                        playing = playing,
                        buffering = false,
                        positionMs = pos,
                        durationMs = if (dur > 0) dur else currentDurationMs
                    )
                    updatePlaybackState()
                }
            }
            handler.postDelayed(this, 800)
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent?.action) {
                pauseAudio()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(noisyReceiver, filter)
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "CloudVaultAudioSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumeAudio()
                }

                override fun onPause() {
                    pauseAudio()
                }

                override fun onSkipToNext() {
                    AudioPlayerManager.playNext(this@AudioPlayerService)
                }

                override fun onSkipToPrevious() {
                    AudioPlayerManager.playPrevious(this@AudioPlayerService)
                }

                override fun onSeekTo(pos: Long) {
                    seekAudio(pos)
                }

                override fun onStop() {
                    stopAudio()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val fileId = intent.getIntExtra(EXTRA_FILE_ID, 0)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Audio Track"
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
                val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
                val sizeBytes = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)
                val durationSec = intent.getIntExtra(EXTRA_DURATION_SEC, 0)
                playTrack(fileId, title, chatId, messageId, sizeBytes, durationSec)
            }
            ACTION_PAUSE -> pauseAudio()
            ACTION_RESUME -> resumeAudio()
            ACTION_TOGGLE_PLAY -> {
                if (mediaPlayer?.isPlaying == true) {
                    pauseAudio()
                } else {
                    resumeAudio()
                }
            }
            ACTION_PREV -> AudioPlayerManager.playPrevious(this)
            ACTION_NEXT -> AudioPlayerManager.playNext(this)
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                seekAudio(pos)
            }
            ACTION_SEEK_RELATIVE -> {
                val offset = intent.getLongExtra(EXTRA_SEEK_OFFSET_MS, 0L)
                val currentPos = mediaPlayer?.currentPosition?.toLong() ?: 0L
                val targetPos = (currentPos + offset).coerceIn(0L, currentDurationMs.coerceAtLeast(1000L))
                seekAudio(targetPos)
            }
            ACTION_SET_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                applySpeed(speed)
            }
            ACTION_STOP -> stopAudio()
        }
        return START_NOT_STICKY
    }

    private fun applySpeed(speed: Float) {
        currentSpeed = speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mediaPlayer?.let { player ->
                if (isPrepared) {
                    runCatching {
                        val params = player.playbackParams
                        params.speed = speed
                        player.playbackParams = params
                    }
                }
            }
        }
    }

    private fun playTrack(
        fileId: Int,
        title: String,
        chatId: Long,
        messageId: Long,
        sizeBytes: Long,
        durationSec: Int
    ) {
        currentFileId = fileId
        currentTitle = title
        currentDurationMs = (durationSec * 1000L).coerceAtLeast(0L)
        currentThumbnailBmp = null
        isPrepared = false

        val currentTrack = AudioPlayerManager.currentTrack.value
        if (currentTrack != null) {
            serviceScope.launch(Dispatchers.IO) {
                val bmp = AudioThumbnailHelper.getThumbnailBitmap(currentTrack)
                if (bmp != null && currentFileId == fileId) {
                    currentThumbnailBmp = bmp
                    withContext(Dispatchers.Main) {
                        updateMetadata()
                        if (isPrepared && mediaPlayer?.isPlaying == true) {
                            startForeground(NOTIFICATION_ID, buildNotification(isPlaying = true, isBuffering = false))
                        }
                    }
                }
            }
        }

        handler.removeCallbacks(progressRunnable)
        releasePlayer()

        if (!requestAudioFocus()) {
            AudioPlayerManager.updateState(playing = false, buffering = false, positionMs = 0L, durationMs = currentDurationMs)
            return
        }

        AudioPlayerManager.updateState(playing = true, buffering = true, positionMs = 0L, durationMs = currentDurationMs)
        startForeground(NOTIFICATION_ID, buildNotification(isPlaying = true, isBuffering = true))

        try {
            val streamUrl = TelegramStreamingProxy.getUrl(fileId, title, sizeBytes, chatId, messageId)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(streamUrl)
                setOnPreparedListener(this@AudioPlayerService)
                setOnCompletionListener(this@AudioPlayerService)
                setOnErrorListener(this@AudioPlayerService)
                setOnBufferingUpdateListener(this@AudioPlayerService)
                prepareAsync()
            }
        } catch (e: Throwable) {
            TeleflixLogger.log("AudioPlayerService", "Error setting up MediaPlayer: ${e.message}", isError = true)
            AudioPlayerManager.updateState(playing = false, buffering = false, positionMs = 0L, durationMs = currentDurationMs)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        isPrepared = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentSpeed != 1.0f) {
            runCatching {
                val params = mp?.playbackParams
                if (params != null) {
                    params.speed = currentSpeed
                    mp.playbackParams = params
                }
            }
        }
        mp?.start()
        val dur = mp?.duration?.toLong() ?: currentDurationMs
        if (dur > 0) currentDurationMs = dur

        AudioPlayerManager.updateState(playing = true, buffering = false, positionMs = 0L, durationMs = currentDurationMs)
        updateMetadata()
        updatePlaybackState()
        startForeground(NOTIFICATION_ID, buildNotification(isPlaying = true, isBuffering = false))
        handler.post(progressRunnable)
    }

    override fun onCompletion(mp: MediaPlayer?) {
        if (AudioPlayerManager.isRepeat.value) {
            seekAudio(0L)
            mp?.start()
        } else {
            AudioPlayerManager.playNext(this)
        }
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        TeleflixLogger.log("AudioPlayerService", "MediaPlayer error: what=$what extra=$extra", isError = true)
        AudioPlayerManager.updateState(playing = false, buffering = false, positionMs = 0L, durationMs = currentDurationMs)
        stopForeground(STOP_FOREGROUND_REMOVE)
        return true
    }

    override fun onBufferingUpdate(mp: MediaPlayer?, percent: Int) {
        // Buffering update
    }

    private fun pauseAudio() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            AudioPlayerManager.updateState(
                playing = false,
                buffering = false,
                positionMs = mediaPlayer?.currentPosition?.toLong() ?: 0L,
                durationMs = currentDurationMs
            )
            updatePlaybackState()
            startForeground(NOTIFICATION_ID, buildNotification(isPlaying = false, isBuffering = false))
        }
    }

    private fun resumeAudio() {
        if (requestAudioFocus()) {
            mediaPlayer?.start()
            AudioPlayerManager.updateState(
                playing = true,
                buffering = false,
                positionMs = mediaPlayer?.currentPosition?.toLong() ?: 0L,
                durationMs = currentDurationMs
            )
            updatePlaybackState()
            startForeground(NOTIFICATION_ID, buildNotification(isPlaying = true, isBuffering = false))
            handler.post(progressRunnable)
        }
    }

    private fun seekAudio(positionMs: Long) {
        if (isPrepared) {
            mediaPlayer?.seekTo(positionMs.toInt())
            AudioPlayerManager.updateState(
                playing = mediaPlayer?.isPlaying == true,
                buffering = false,
                positionMs = positionMs,
                durationMs = currentDurationMs
            )
            updatePlaybackState()
        }
    }

    private fun stopAudio() {
        handler.removeCallbacks(progressRunnable)
        releasePlayer()
        abandonAudioFocus()
        AudioPlayerManager.updateState(playing = false, buffering = false, positionMs = 0L, durationMs = 0L)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        isPrepared = false
        mediaPlayer?.apply {
            runCatching {
                if (isPlaying) stop()
                reset()
                release()
            }
        }
        mediaPlayer = null
    }

    private fun updateMetadata() {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "CloudVault Audio")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)

        currentThumbnailBmp?.let { bmp ->
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
        }
        mediaSession?.setMetadata(builder.build())
    }

    private fun updatePlaybackState() {
        val state = if (mediaPlayer?.isPlaying == true) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (!isPrepared && AudioPlayerManager.isBuffering.value) {
            PlaybackStateCompat.STATE_BUFFERING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val pos: Long = runCatching { mediaPlayer?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, pos, 1.0f)
            .build()

        mediaSession?.setPlaybackState(playbackState)
    }

    private fun buildNotification(isPlaying: Boolean, isBuffering: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val togglePlayIntent = PendingIntent.getService(
            this, 2,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_TOGGLE_PLAY },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this, 3,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 4,
            Intent(this, AudioPlayerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(currentTitle)
            .setContentText(if (isBuffering) "Buffering from Cloud..." else "CloudVault Music Player")
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, togglePlayIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
                    .setShowCancelButton(true)
                    .setCancelButtonIntent(stopIntent)
            )

        currentThumbnailBmp?.let { bmp ->
            builder.setLargeIcon(bmp)
        }

        return builder.build()
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attr)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pauseAudio()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseAudio()
                        AudioManager.AUDIOFOCUS_GAIN -> resumeAudio()
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pauseAudio()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseAudio()
                        AudioManager.AUDIOFOCUS_GAIN -> resumeAudio()
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active audio playback controls for CloudVault"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        handler.removeCallbacks(progressRunnable)
        releasePlayer()
        abandonAudioFocus()
        mediaSession?.release()
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
