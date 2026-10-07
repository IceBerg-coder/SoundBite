package com.soundbite.ml

/**
 * Core interface for on-device multimodal embedding generation using Google's EmbeddingGemma 2.
 *
 * Maps both continuous audio waveform samples and natural-language text queries into a shared
 * 256-dimensional semantic metric space using Matryoshka Representation Learning (MRL) and L2-normalization.
 */
interface EmbeddingRunner {

    /**
     * Target dimensionality after Matryoshka Representation Learning (MRL) truncation.
     */
    val targetDimensions: Int get() = 256

    /**
     * Initializes model weights, delegates (XNNPACK/GPU/NNAPI), and tensor buffers.
     *
     * @return Result indicating success or failure.
     */
    suspend fun initialize(): Result<Unit>

    /**
     * Checks if the underlying inference engine is loaded and ready for prediction.
     */
    fun isModelLoaded(): Boolean

    /**
     * Generates a 256-dimensional, L2-normalized embedding vector from raw audio PCM samples.
     *
     * @param samples Normalized 32-bit float audio samples in range [-1.0f, 1.0f] sampled at 16 kHz.
     * @return Unit-norm float array of size [targetDimensions] (256).
     */
    suspend fun embedAudio(samples: FloatArray): FloatArray

    /**
     * Generates a 256-dimensional, L2-normalized embedding vector from a natural language text query.
     *
     * The output vector resides in the identical geometric metric space as audio embeddings,
     * enabling direct cosine-similarity nearest neighbor matching.
     *
     * @param query Natural language text (e.g. "when was the quarterly budget discussed?").
     * @return Unit-norm float array of size [targetDimensions] (256).
     */
    suspend fun embedText(query: String): FloatArray

    /**
     * Releases memory, native interpreters, and hardware delegates.
     */
    fun close()
}

