package com.soundbite.data.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.VectorDistanceType

/**
 * Database entity representing an embedded audio window segment.
 *
 * Utilizes ObjectBox's native on-device HNSW (Hierarchical Navigable Small World)
 * vector index for sub-millisecond approximate nearest neighbor (ANN) retrieval
 * over 256-dimensional L2-normalized EmbeddingGemma vectors.
 */
@Entity
data class RecordingSegment(
    @Id
    var id: Long = 0L,

    @Index
    var recordingId: Long = 0L,

    var startTimestampMs: Long = 0L,

    var endTimestampMs: Long = 0L,

    /**
     * 256-dimensional MRL-truncated, L2-normalized embedding vector.
     * Indexed via ObjectBox HNSW with Cosine distance metric.
     */
    @HnswIndex(dimensions = 256, distanceType = VectorDistanceType.COSINE)
    var embedding: FloatArray = FloatArray(256),

    /**
     * Optional transcription or recognized keyword tags for this segment.
     */
    var textTranscript: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RecordingSegment
        if (id != other.id) return false
        if (recordingId != other.recordingId) return false
        if (startTimestampMs != other.startTimestampMs) return false
        if (endTimestampMs != other.endTimestampMs) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (textTranscript != other.textTranscript) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + recordingId.hashCode()
        result = 31 * result + startTimestampMs.hashCode()
        result = 31 * result + endTimestampMs.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (textTranscript?.hashCode() ?: 0)
        return result
    }
}

