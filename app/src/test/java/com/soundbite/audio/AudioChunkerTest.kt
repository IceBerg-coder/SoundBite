package com.soundbite.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioChunkerTest {

    private lateinit var chunker: AudioChunker

    @Before
    fun setUp() {
        chunker = AudioChunker(
            sampleRate = 16_000,
            windowDurationSec = 20,
            overlapDurationSec = 5
        )
    }

    @Test
    fun testWindowAndStepCalculations() {
        assertEquals(320_000, chunker.windowSamples) // 20s * 16,000
        assertEquals(240_000, chunker.stepSamples)   // 15s * 16,000
        assertEquals(80_000, chunker.overlapSamples)  // 5s * 16,000
    }

    @Test
    fun testSampleToMillisecondConversions() {
        assertEquals(0L, chunker.samplesToMs(0L))
        assertEquals(1_000L, chunker.samplesToMs(16_000L))
        assertEquals(20_000L, chunker.samplesToMs(320_000L))
        assertEquals(15_000L, chunker.samplesToMs(240_000L))

        assertEquals(0L, chunker.msToSamples(0L))
        assertEquals(16_000L, chunker.msToSamples(1_000L))
        assertEquals(320_000L, chunker.msToSamples(20_000L))
    }

    @Test
    fun testChunkFloatSamples_40SecondsAudio() {
        // 40 seconds of audio at 16 kHz = 640,000 samples
        val totalSamples = 40 * 16_000
        val pcm = FloatArray(totalSamples) { 0.5f }

        val chunks = chunker.chunkFloatSamples(pcm, recordingId = 42L)

        // Window 0: 0s to 20s (samples 0 to 320,000)
        // Window 1: 15s to 35s (samples 240,000 to 560,000)
        // Window 2: 30s to 40s (samples 480,000 to 640,000, padded to 320,000)
        assertEquals(3, chunks.size)

        // Verify Chunk 0
        assertEquals(0L, chunks[0].startTimestampMs)
        assertEquals(20_000L, chunks[0].endTimestampMs)
        assertEquals(320_000, chunks[0].pcmSamples.size)
        assertEquals(42L, chunks[0].recordingId)

        // Verify Chunk 1
        assertEquals(15_000L, chunks[1].startTimestampMs)
        assertEquals(35_000L, chunks[1].endTimestampMs)
        assertEquals(320_000, chunks[1].pcmSamples.size)

        // Verify Chunk 2
        assertEquals(30_000L, chunks[2].startTimestampMs)
        assertEquals(40_000L, chunks[2].endTimestampMs)
        assertEquals(320_000, chunks[2].pcmSamples.size)
    }

    @Test
    fun testChunkFloatSamples_ShortAudio() {
        // 10 seconds audio (shorter than 20-second window, but > 1-second min threshold)
        val shortPcm = FloatArray(10 * 16_000) { 0.1f }
        val chunks = chunker.chunkFloatSamples(shortPcm, recordingId = 99L)

        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].startTimestampMs)
        assertEquals(10_000L, chunks[0].endTimestampMs)
        assertEquals(320_000, chunks[0].pcmSamples.size)
    }

    @Test
    fun testPcmBytesToNormalizedFloat() {
        val byteBuffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.putShort(32767.toShort())  // ~ 1.0f
        byteBuffer.putShort((-32768).toShort()) // -1.0f

        val floats = chunker.pcmBytesToNormalizedFloat(byteBuffer.array(), isWav = false)
        assertEquals(2, floats.size)
        assertTrue(floats[0] > 0.99f && floats[0] <= 1.0f)
        assertEquals(-1.0f, floats[1], 0.001f)
    }
}

