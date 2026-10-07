package com.soundbite.audio

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Data model representing a chunked audio segment for embedding and semantic indexing.
 *
 * @property chunkId Unique identifier for the chunk.
 * @property recordingId Reference to the parent recording entity.
 * @property startTimestampMs Starting offset of this chunk in the original recording (milliseconds).
 * @property endTimestampMs Ending offset of this chunk in the original recording (milliseconds).
 * @property pcmSamples Normalized 32-bit floating point audio samples in range [-1.0f, 1.0f].
 */
data class AudioChunk(
    val chunkId: String,
    val recordingId: Long,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val pcmSamples: FloatArray
) {
    val durationMs: Long get() = endTimestampMs - startTimestampMs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioChunk
        if (chunkId != other.chunkId) return false
        if (recordingId != other.recordingId) return false
        if (startTimestampMs != other.startTimestampMs) return false
        if (endTimestampMs != other.endTimestampMs) return false
        if (!pcmSamples.contentEquals(other.pcmSamples)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + recordingId.hashCode()
        result = 31 * result + startTimestampMs.hashCode()
        result = 31 * result + endTimestampMs.hashCode()
        result = 31 * result + pcmSamples.contentHashCode()
        return result
    }
}

/**
 * High-performance audio chunking engine designed for modern Android on-device ML workflows.
 *
 * Slices 16 kHz 16-bit Mono PCM audio into rolling 20-second evaluation windows with a 5-second
 * overlapping stride (15-second step).
 *
 * Features:
 * - Direct FloatArray in-memory slicing
 * - Raw 16-bit PCM and RIFF/WAV file decoding
 * - Memory-efficient streaming chunker from disk to prevent OutOfMemory (OOM) on large recordings
 * - Precise sample-to-millisecond and millisecond-to-sample conversions
 */
class AudioChunker(
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val windowDurationSec: Int = DEFAULT_WINDOW_DURATION_SEC,
    val overlapDurationSec: Int = DEFAULT_OVERLAP_DURATION_SEC
) {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 16_000 // 16 kHz
        const val DEFAULT_WINDOW_DURATION_SEC = 20 // 20 seconds
        const val DEFAULT_OVERLAP_DURATION_SEC = 5 // 5 seconds overlap (15-second step)
        const val BYTES_PER_SAMPLE = 2 // 16-bit PCM = 2 bytes per sample
        const val WAV_HEADER_SIZE_BYTES = 44
        const val MIN_CHUNK_DURATION_MS = 1_000L // Discard or zero-pad fragments < 1 sec
    }

    val stepDurationSec: Int = windowDurationSec - overlapDurationSec
    val windowSamples: Int = windowDurationSec * sampleRate // 20 * 16,000 = 320,000 samples
    val stepSamples: Int = stepDurationSec * sampleRate     // 15 * 16,000 = 240,000 samples
    val overlapSamples: Int = overlapDurationSec * sampleRate // 5 * 16,000 = 80,000 samples

    init {
        require(windowDurationSec > overlapDurationSec) {
            "windowDurationSec ($windowDurationSec) must be greater than overlapDurationSec ($overlapDurationSec)"
        }
        require(sampleRate > 0) { "sampleRate must be strictly positive" }
    }

    /**
     * Converts a sample count/index to milliseconds given the sample rate.
     */
    fun samplesToMs(sampleCount: Long): Long {
        return (sampleCount * 1_000L) / sampleRate
    }

    /**
     * Converts milliseconds to sample count given the sample rate.
     */
    fun msToSamples(ms: Long): Long {
        return (ms * sampleRate) / 1_000L
    }

    /**
     * Chunks an in-memory [FloatArray] of normalized audio samples [-1.0f, 1.0f].
     *
     * @param pcmSamples Full array of normalized float samples.
     * @param recordingId Unique ID of the recording.
     * @return Ordered list of [AudioChunk] segments with 20s window and 15s step.
     */
    fun chunkFloatSamples(
        pcmSamples: FloatArray,
        recordingId: Long
    ): List<AudioChunk> {
        val totalSamples = pcmSamples.size
        if (totalSamples == 0) return emptyList()

        val chunks = mutableListOf<AudioChunk>()
        var chunkIndex = 0
        var startSample = 0

        while (startSample < totalSamples) {
            val endSample = min(startSample + windowSamples, totalSamples)
            val chunkLength = endSample - startSample
            val startTimestampMs = samplesToMs(startSample.toLong())
            val endTimestampMs = samplesToMs(endSample.toLong())

            // If the segment is too small (e.g., trailing fragment < 1 sec), stop if previous chunks exist
            if (chunkLength < msToSamples(MIN_CHUNK_DURATION_MS) && chunks.isNotEmpty()) {
                break
            }

            // Fixed-length array for model inference: pad with zeros if endSample reaches EOF early
            val windowBuffer = FloatArray(windowSamples)
            System.arraycopy(pcmSamples, startSample, windowBuffer, 0, chunkLength)

            val chunkId = buildChunkId(recordingId, chunkIndex, startTimestampMs, endTimestampMs)
            chunks.add(
                AudioChunk(
                    chunkId = chunkId,
                    recordingId = recordingId,
                    startTimestampMs = startTimestampMs,
                    endTimestampMs = endTimestampMs,
                    pcmSamples = windowBuffer
                )
            )

            chunkIndex++
            startSample += stepSamples

            // If the last window already reached the end of the audio, terminate
            if (endSample >= totalSamples) {
                break
            }
        }

        return chunks
    }

    /**
     * Chunks a raw 16-bit Mono PCM byte array or standard RIFF WAV byte array.
     *
     * @param audioBytes Byte array containing audio data.
     * @param isWav True if the byte array contains a 44-byte WAV header.
     * @param recordingId Unique ID of the recording.
     */
    fun chunkPcmBytes(
        audioBytes: ByteArray,
        isWav: Boolean = false,
        recordingId: Long
    ): List<AudioChunk> {
        val floatSamples = pcmBytesToNormalizedFloat(audioBytes, isWav)
        return chunkFloatSamples(floatSamples, recordingId)
    }

    /**
     * Memory-efficient streaming chunker that reads audio directly from a file without
     * loading the entire audio file into RAM at once.
     *
     * Essential for long recordings on memory-constrained mobile devices.
     *
     * @param file Audio file (.wav or raw .pcm).
     * @param isWav True if the file contains a 44-byte RIFF WAV header.
     * @param recordingId Unique ID of the recording.
     * @return Sequence of [AudioChunk] items.
     */
    fun chunkFileStreaming(
        file: File,
        isWav: Boolean = true,
        recordingId: Long
    ): List<AudioChunk> {
        if (!file.exists() || file.length() == 0L) return emptyList()

        val chunks = mutableListOf<AudioChunk>()
        val bytesPerWindow = windowSamples * BYTES_PER_SAMPLE // 320,000 * 2 = 640,000 bytes
        val bytesPerStep = stepSamples * BYTES_PER_SAMPLE     // 240,000 * 2 = 480,000 bytes
        val bytesOverlap = overlapSamples * BYTES_PER_SAMPLE  // 80,000 * 2 = 160,000 bytes

        val buffer = ByteArray(bytesPerWindow)
        val pcmByteLength = if (isWav) file.length() - WAV_HEADER_SIZE_BYTES else file.length()
        val totalSamples = pcmByteLength / BYTES_PER_SAMPLE

        FileInputStream(file).use { fis ->
            if (isWav) {
                val skipped = fis.skip(WAV_HEADER_SIZE_BYTES.toLong())
                if (skipped < WAV_HEADER_SIZE_BYTES) return emptyList()
            }

            var chunkIndex = 0
            var cumulativeSamplesRead = 0L

            var bytesInWindow = 0
            val readBuffer = ByteArray(bytesPerStep)

            // Read the initial window
            var totalReadFirst = 0
            while (totalReadFirst < bytesPerWindow) {
                val read = fis.read(buffer, totalReadFirst, bytesPerWindow - totalReadFirst)
                if (read == -1) break
                totalReadFirst += read
            }
            bytesInWindow = totalReadFirst

            while (bytesInWindow > 0) {
                val samplesInWindow = bytesInWindow / BYTES_PER_SAMPLE
                val startTimestampMs = samplesToMs(cumulativeSamplesRead)
                val endTimestampMs = samplesToMs(min(cumulativeSamplesRead + samplesInWindow, totalSamples))

                if (samplesInWindow < msToSamples(MIN_CHUNK_DURATION_MS) && chunks.isNotEmpty()) {
                    break
                }

                // Convert bytes in window to normalized float samples
                val floatSamples = FloatArray(windowSamples)
                val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesInWindow).order(ByteOrder.LITTLE_ENDIAN)
                var sIdx = 0
                while (byteBuffer.remaining() >= 2 && sIdx < windowSamples) {
                    val pcm16 = byteBuffer.short
                    floatSamples[sIdx++] = pcm16 / 32768.0f
                }

                val chunkId = buildChunkId(recordingId, chunkIndex, startTimestampMs, endTimestampMs)
                chunks.add(
                    AudioChunk(
                        chunkId = chunkId,
                        recordingId = recordingId,
                        startTimestampMs = startTimestampMs,
                        endTimestampMs = endTimestampMs,
                        pcmSamples = floatSamples
                    )
                )

                chunkIndex++
                cumulativeSamplesRead += stepSamples

                if (bytesInWindow < bytesPerWindow) {
                    // Reached EOF during this window
                    break
                }

                // Shift overlap bytes to the beginning of the window buffer
                System.arraycopy(buffer, bytesPerStep, buffer, 0, bytesOverlap)

                // Read next step (bytesPerStep) into the remaining slot of buffer
                var nextStepRead = 0
                while (nextStepRead < bytesPerStep) {
                    val read = fis.read(readBuffer, nextStepRead, bytesPerStep - nextStepRead)
                    if (read == -1) break
                    nextStepRead += read
                }

                if (nextStepRead > 0) {
                    System.arraycopy(readBuffer, 0, buffer, bytesOverlap, nextStepRead)
                    bytesInWindow = bytesOverlap + nextStepRead
                } else {
                    bytesInWindow = 0
                }
            }
        }

        return chunks
    }

    /**
     * Converts raw 16-bit Little-Endian PCM byte array to normalized float array [-1.0f, 1.0f].
     */
    fun pcmBytesToNormalizedFloat(audioBytes: ByteArray, isWav: Boolean = false): FloatArray {
        val offset = if (isWav && audioBytes.size >= WAV_HEADER_SIZE_BYTES) WAV_HEADER_SIZE_BYTES else 0
        val pcmDataLength = audioBytes.size - offset
        val sampleCount = pcmDataLength / BYTES_PER_SAMPLE
        val floatSamples = FloatArray(sampleCount)

        val byteBuffer = ByteBuffer.wrap(audioBytes, offset, pcmDataLength).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val pcm16 = byteBuffer.short
            floatSamples[i] = pcm16 / 32768.0f
        }
        return floatSamples
    }

    /**
     * Generates a deterministic, unique chunk ID.
     */
    private fun buildChunkId(
        recordingId: Long,
        chunkIndex: Int,
        startTimestampMs: Long,
        endTimestampMs: Long
    ): String {
        return "rec_${recordingId}_chunk_${chunkIndex}_${startTimestampMs}_${endTimestampMs}"
    }
}

