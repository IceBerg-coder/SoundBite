package com.soundbite.data.repository

import com.soundbite.data.db.RecordingEntity
import com.soundbite.data.db.RecordingSegment
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for managing audio recordings and executing on-device
 * vector similarity searches over audio segment embeddings.
 */
interface AudioVectorRepository {

    /**
     * Saves or updates a recording metadata entry.
     * @return Unique ID of the saved recording.
     */
    suspend fun saveRecording(recording: RecordingEntity): Long

    /**
     * Retrieves a single recording by its ID.
     */
    suspend fun getRecording(recordingId: Long): RecordingEntity?

    /**
     * Observes all recordings ordered chronologically descending.
     */
    fun getAllRecordings(): Flow<List<RecordingEntity>>

    /**
     * Inserts a list of embedded audio segments into the vector database.
     */
    suspend fun insertSegments(segments: List<RecordingSegment>)

    /**
     * Retrieves all segments belonging to a given recording ID.
     */
    suspend fun getSegmentsForRecording(recordingId: Long): List<RecordingSegment>

    /**
     * Performs an approximate nearest neighbor (ANN) vector search or exact cosine similarity
     * against stored audio segment embeddings.
     *
     * @param queryVector 256-dimensional unit-norm embedding of the text query.
     * @param topK Maximum number of nearest matches to return (default 10).
     * @param minScore Minimum cosine similarity threshold (default 0.35f).
     * @return Ranked list of [SearchResult] items ordered by descending similarity score.
     */
    suspend fun searchNearestSegments(
        queryVector: FloatArray,
        topK: Int = 10,
        minScore: Float = 0.35f
    ): List<SearchResult>

    /**
     * Updates indexing completion state and chunk count for a recording.
     */
    suspend fun updateRecordingIndexStatus(recordingId: Long, isIndexed: Boolean, chunkCount: Int)

    /**
     * Deletes a recording and all of its associated vector segments.
     */
    suspend fun deleteRecording(recordingId: Long)
}

