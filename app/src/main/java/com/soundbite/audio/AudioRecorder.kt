package com.soundbite.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

data class RecordingOutput(
    val file: File,
    val durationMs: Long,
    val byteSize: Long
)

/**
 * Clean, production-grade 16 kHz Mono PCM audio recorder using the native Android [AudioRecord] API.
 *
 * Features:
 * - Direct 16 kHz, 16-bit Mono PCM recording.
 * - Live RMS amplitude emission via [amplitudeFlow] for real-time waveform visualizers.
 * - Pause / Resume capability without splitting files.
 * - Writes clean RIFF/WAV files with accurate sample headers.
 */
class AudioRecorder(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavAudioWriter? = null
    private var recordingJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _recordingDurationMs = MutableStateFlow(0L)
    val recordingDurationMs: StateFlow<Long> = _recordingDurationMs.asStateFlow()

    private val _amplitudeFlow = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val amplitudeFlow: SharedFlow<Float> = _amplitudeFlow.asSharedFlow()

    private var currentOutputFile: File? = null
    private var totalSamplesRecorded = 0L

    @SuppressLint("MissingPermission")
    suspend fun startRecording(outputFile: File): Result<Unit> = withContext(Dispatchers.IO) {
        if (_isRecording.value) {
            return@withContext Result.failure(IllegalStateException("Recording is already in progress"))
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext Result.failure(IllegalStateException("AudioRecord failed to initialize"))
            }

            audioRecord = record
            currentOutputFile = outputFile
            totalSamplesRecorded = 0L
            _recordingDurationMs.value = 0L
            _isPaused.value = false

            val writer = WavAudioWriter(outputFile, SAMPLE_RATE, channels = 1, bitsPerSample = 16)
            writer.start()
            wavWriter = writer

            record.startRecording()
            _isRecording.value = true

            recordingJob = scope.launch {
                val shortBuffer = ShortArray(bufferSize / 2)
                while (isActive && _isRecording.value) {
                    if (_isPaused.value) {
                        _amplitudeFlow.tryEmit(0f)
                        Thread.sleep(50)
                        continue
                    }

                    val readCount = record.read(shortBuffer, 0, shortBuffer.size)
                    if (readCount > 0) {
                        writer.write(shortBuffer, readCount)
                        totalSamplesRecorded += readCount
                        _recordingDurationMs.value = (totalSamplesRecorded * 1000L) / SAMPLE_RATE

                        // Calculate RMS amplitude for live waveform visualizer
                        var sumSquares = 0.0
                        for (i in 0 until readCount) {
                            val sample = shortBuffer[i].toDouble()
                            sumSquares += sample * sample
                        }
                        val rms = sqrt(sumSquares / readCount)
                        val normalizedAmplitude = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        _amplitudeFlow.tryEmit(normalizedAmplitude)
                    }
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanup()
            Result.failure(e)
        }
    }

    fun pauseRecording() {
        if (_isRecording.value && !_isPaused.value) {
            _isPaused.value = true
        }
    }

    fun resumeRecording() {
        if (_isRecording.value && _isPaused.value) {
            _isPaused.value = false
        }
    }

    suspend fun stopRecording(): RecordingOutput? = withContext(Dispatchers.IO) {
        if (!_isRecording.value) return@withContext null

        _isRecording.value = false
        _isPaused.value = false
        recordingJob?.cancel()
        recordingJob = null

        val finalDurationMs = _recordingDurationMs.value
        val bytesWritten = wavWriter?.stop() ?: 0L
        wavWriter = null

        audioRecord?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
                release()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping audioRecord: ${e.message}")
            }
        }
        audioRecord = null

        val file = currentOutputFile
        currentOutputFile = null

        if (file != null && file.exists()) {
            RecordingOutput(file = file, durationMs = finalDurationMs, byteSize = bytesWritten)
        } else {
            null
        }
    }

    private fun cleanup() {
        _isRecording.value = false
        _isPaused.value = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        }
        audioRecord = null
        wavWriter = null
    }

    fun release() {
        cleanup()
    }
}

