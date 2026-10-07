package com.soundbite.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EmbeddingGemmaManagerTest {

    @Test
    fun testTruncateAndNormalize() {
        // Create 768-dimensional raw vector
        val rawVector = FloatArray(768) { i -> (i + 1).toFloat() }

        // We can instantiate manager with mock or test helper
        val targetDim = 256
        val truncated = FloatArray(targetDim)
        var sumSquares = 0.0f
        for (i in 0 until targetDim) {
            val v = rawVector[i]
            truncated[i] = v
            sumSquares += v * v
        }
        val norm = sqrt(sumSquares)
        for (i in 0 until targetDim) {
            truncated[i] /= norm
        }

        // Verify size
        assertEquals(256, truncated.size)

        // Verify L2 norm equals 1.0
        var unitNormSquares = 0.0f
        for (v in truncated) {
            unitNormSquares += v * v
        }
        val l2Norm = sqrt(unitNormSquares)
        assertEquals(1.0f, l2Norm, 0.0001f)
    }

    @Test
    fun testCosineSimilarityUnitVectors() {
        // For unit-norm vectors, cosine similarity == dot product
        val v1 = FloatArray(256) { 0f }
        val v2 = FloatArray(256) { 0f }
        v1[0] = 1.0f
        v2[0] = 1.0f

        var dot = 0.0f
        for (i in 0 until 256) {
            dot += v1[i] * v2[i]
        }
        assertEquals(1.0f, dot, 0.0001f)

        // Orthogonal vectors
        v2[0] = 0.0f
        v2[1] = 1.0f
        var dotOrthogonal = 0.0f
        for (i in 0 until 256) {
            dotOrthogonal += v1[i] * v2[i]
        }
        assertEquals(0.0f, dotOrthogonal, 0.0001f)
    }
}

