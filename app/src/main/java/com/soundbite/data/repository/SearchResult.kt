package com.soundbite.data.repository

import com.soundbite.data.db.RecordingSegment

/**
 * Result of a semantic nearest neighbor vector search.
 *
 * @property segment The matched audio segment entity.
 * @property recordingTitle Human-readable title of the audio recording.
 * @property audioFilePath File path to the audio file in app internal storage.
 * @property score Cosine similarity score in range [-1.0f, 1.0f] (higher is better).
 * @property confidencePercentage Normalized confidence percentage [0..100]%.
 */
data class SearchResult(
    val segment: RecordingSegment,
    val recordingTitle: String,
    val audioFilePath: String,
    val score: Float,
    val confidencePercentage: Int
)

