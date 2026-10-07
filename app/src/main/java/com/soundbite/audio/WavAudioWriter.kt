package com.soundbite.audio

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for streaming raw 16-bit Mono PCM audio into a standard RIFF/WAV file.
 *
 * Writes a valid 44-byte WAV header upfront and dynamically updates the RIFF chunk
 * size and data sub-chunk size upon completion.
 */
class WavAudioWriter(
    private val outputFile: File,
    private val sampleRate: Int = 16_000,
    private val channels: Short = 1,
    private val bitsPerSample: Short = 16
) {
    private var fos: FileOutputStream? = null
    private var totalAudioBytesWritten = 0L

    fun start() {
        if (outputFile.exists()) {
            outputFile.delete()
        }
        outputFile.parentFile?.mkdirs()
        fos = FileOutputStream(outputFile)
        totalAudioBytesWritten = 0L

        // Write placeholder 44-byte header
        val placeholderHeader = ByteArray(44)
        fos?.write(placeholderHeader)
    }

    fun write(buffer: ShortArray, readSize: Int) {
        val stream = fos ?: return
        val byteBuffer = ByteBuffer.allocate(readSize * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until readSize) {
            byteBuffer.putShort(buffer[i])
        }
        val bytes = byteBuffer.array()
        stream.write(bytes)
        totalAudioBytesWritten += bytes.size
    }

    fun stop(): Long {
        fos?.flush()
        fos?.close()
        fos = null

        // Overwrite the placeholder header with the finalized sizes
        updateWavHeader()
        return totalAudioBytesWritten
    }

    private fun updateWavHeader() {
        if (!outputFile.exists() || totalAudioBytesWritten == 0L) return

        RandomAccessFile(outputFile, "rw").use { raf ->
            raf.seek(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

            val totalDataLen = totalAudioBytesWritten
            val totalFileLen = totalDataLen + 36
            val byteRate = (sampleRate * channels * bitsPerSample / 8)
            val blockAlign = (channels * bitsPerSample / 8).toShort()

            // RIFF header
            header.put("RIFF".toByteArray())
            header.putInt(totalFileLen.toInt())
            header.put("WAVE".toByteArray())

            // 'fmt ' subchunk
            header.put("fmt ".toByteArray())
            header.putInt(16) // Subchunk1Size for PCM
            header.putShort(1) // AudioFormat 1 = PCM
            header.putShort(channels)
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign)
            header.putShort(bitsPerSample)

            // 'data' subchunk
            header.put("data".toByteArray())
            header.putInt(totalDataLen.toInt())

            raf.write(header.array())
        }
    }
}

