package com.soundbite.data.db

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

/**
 * Database entity representing a recorded audio file metadata entry.
 */
@Entity
data class RecordingEntity(
    @Id
    var id: Long = 0L,

    var title: String = "",

    var filePath: String = "",

    var durationMs: Long = 0L,

    @Index
    var createdAt: Long = System.currentTimeMillis(),

    var isIndexed: Boolean = false,

    var chunkCount: Int = 0
)

