package com.soundbite.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * State representing audio playback status.
 */
enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED
}

/**
 * High-level playback coordinator built on top of Media3 / ExoPlayer.
 *
 * Provides reactive [StateFlow] streams for playback position, duration, and status,
 * enabling synchronized UI scrubber updates and instant timestamp seeks.
 */
class AudioPlaybackManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {

    companion object {
        private const val TAG = "AudioPlaybackManager"
        private const val POSITION_UPDATE_INTERVAL_MS = 50L
    }

    private var exoPlayer: ExoPlayer? = null
    private var progressJob: Job? = null

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.IDLE)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentFilePath = MutableStateFlow<String?>(null)
    val currentFilePath: StateFlow<String?> = _currentFilePath.asStateFlow()

    init {
        initPlayer()
    }

    private fun initPlayer() {
        if (exoPlayer != null) return

        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_IDLE -> _playbackStatus.value = PlaybackStatus.IDLE
                        Player.STATE_BUFFERING -> _playbackStatus.value = PlaybackStatus.BUFFERING
                        Player.STATE_READY -> {
                            _durationMs.value = duration.coerceAtLeast(0L)
                            _playbackStatus.value = if (playWhenReady) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
                        }
                        Player.STATE_ENDED -> {
                            _playbackStatus.value = PlaybackStatus.ENDED
                            stopProgressPolling()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playbackStatus.value = if (isPlaying) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
                    if (isPlaying) {
                        startProgressPolling()
                    } else {
                        stopProgressPolling()
                    }
                }
            })
        }
    }

    /**
     * Loads an audio file from local storage and starts playback from [startPositionMs].
     */
    fun loadAndPlay(filePath: String, startPositionMs: Long = 0L) {
        val player = exoPlayer ?: return
        val file = File(filePath)
        if (!file.exists()) {
            Log.e(TAG, "Cannot play audio: file does not exist at $filePath")
            return
        }

        val uri = Uri.fromFile(file)
        val mediaItem = MediaItem.fromUri(uri)

        _currentFilePath.value = filePath
        player.setMediaItem(mediaItem)
        player.prepare()
        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
            _currentPositionMs.value = startPositionMs
        }
        player.play()
    }

    /**
     * Seeks playback directly to a specific timestamp in milliseconds.
     */
    fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        val clampedPosition = positionMs.coerceIn(0L, _durationMs.value.coerceAtLeast(0L))
        player.seekTo(clampedPosition)
        _currentPositionMs.value = clampedPosition
    }

    fun play() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun stop() {
        exoPlayer?.stop()
        stopProgressPolling()
        _playbackStatus.value = PlaybackStatus.IDLE
        _currentPositionMs.value = 0L
    }

    private fun startProgressPolling() {
        stopProgressPolling()
        progressJob = scope.launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration
                    if (dur > 0L) {
                        _durationMs.value = dur
                    }
                }
                delay(POSITION_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    fun release() {
        stopProgressPolling()
        exoPlayer?.release()
        exoPlayer = null
    }
}

