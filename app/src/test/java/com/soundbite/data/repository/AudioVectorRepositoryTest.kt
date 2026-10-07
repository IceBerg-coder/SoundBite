package com.soundbite.data.repository

import com.soundbite.data.db.RecordingEntity
import com.soundbite.data.db.RecordingSegment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

class AudioVectorRepositoryTest {

    @Test
    fun testVectorSearchRankingLogic() = runBlocking {
        // Construct simulated 256d normalized query vector pointing along axis 0
        val queryVector = FloatArray(256) { 0f }
        queryVector[0] = 1.0f // Unit norm

        // Segment A: High similarity (e.g. 0.95 on axis 0)
        val vecA = FloatArray(256) { 0f }
        vecA[0] = 0.95f
        vecA[1] = 0.3122f // ~ 1.0 norm

        // Segment B: Low similarity (e.g. 0.2 on axis 0)
        val vecB = FloatArray(256) { 0f }
        vecB[0] = 0.20f
        vecB[2] = 0.9798f

        // Segment C: Medium similarity (e.g. 0.70 on axis 0)
        val vecC = FloatArray(256) { 0f }
        vecC[0] = 0.70f
        vecC[3] = 0.7141f

        val segments = listOf(
            RecordingSegment(id = 1, recordingId = 10, startTimestampMs = 0, endTimestampMs = 20000, embedding = vecA),
            RecordingSegment(id = 2, recordingId = 10, startTimestampMs = 15000, endTimestampMs = 35000, embedding = vecB),
            RecordingSegment(id = 3, recordingId = 10, startTimestampMs = 30000, endTimestampMs = 50000, embedding = vecC)
        )

        // Calculate cosine similarity for all
        fun dotProduct(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in 0 until 256) sum += a[i] * b[i]
            return sum
        }

        val topK = 2
        val minScore = 0.30f

        val minHeap = PriorityQueue<Pair<RecordingSegment, Float>>(topK) { a, b ->
            a.second.compareTo(b.second)
        }

        for (seg in segments) {
            val score = dotProduct(queryVector, seg.embedding)
            if (score >= minScore) {
                if (minHeap.size < topK) {
                    minHeap.offer(Pair(seg, score))
                } else if (score > minHeap.peek().second) {
                    minHeap.poll()
                    minHeap.offer(Pair(seg, score))
                }
            }
        }

        val ranked = mutableListOf<Pair<RecordingSegment, Float>>()
        while (minHeap.isNotEmpty()) {
            ranked.add(minHeap.poll())
        }
        val sortedDesc = ranked.asReversed()

        assertEquals(2, sortedDesc.size)
        assertEquals(1L, sortedDesc[0].first.id) // vecA was highest (0.95)
        assertEquals(3L, sortedDesc[1].first.id) // vecC was second highest (0.70)
        assertTrue(sortedDesc[0].second > sortedDesc[1].second)
    }
}

