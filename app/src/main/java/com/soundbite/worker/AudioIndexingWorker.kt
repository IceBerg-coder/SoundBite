package com.soundbite.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.soundbite.SoundBiteApplication
import com.soundbite.audio.AudioChunker
import com.soundbite.data.db.RecordingSegment
import com.soundbite.ml.EmbeddingGemmaManager
import java.io.File

/**
 * WorkManager [CoroutineWorker] that executes asynchronous on-device audio chunking,
 * EmbeddingGemma 2 inference, and local vector database insertion.
 *
 * Configured with battery-conscious constraints to run safely in the background
 * without draining device resources or requiring cloud connectivity.
 */
class AudioIndexingWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "AudioIndexingWorker"
        const val KEY_RECORDING_ID = "key_recording_id"
        const val KEY_AUDIO_FILE_PATH = "key_audio_file_path"
        const val KEY_PROGRESS = "key_progress"
        const val NOTIFICATION_CHANNEL_ID = "soundbite_indexing_channel"
        const val NOTIFICATION_ID = 1001

        /**
         * Schedules background indexing with battery and charging constraints.
         */
        fun schedule(
            context: Context,
            recordingId: Long,
            filePath: String,
            requiresCharging: Boolean = false
        ) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(requiresCharging)
                // Note: No network constraint required, enforcing 100% on-device privacy!
                .build()

            val workRequest = OneTimeWorkRequestBuilder<AudioIndexingWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        KEY_RECORDING_ID to recordingId,
                        KEY_AUDIO_FILE_PATH to filePath
                    )
                )
                .addTag("indexing_recording_$recordingId")
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "work_index_recording_$recordingId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.i(TAG, "Enqueued indexing work for recording ID: $recordingId")
        }
    }

    override suspend fun doWork(): Result {
        val recordingId = inputData.getLong(KEY_RECORDING_ID, -1L)
        val filePath = inputData.getString(KEY_AUDIO_FILE_PATH)

        if (recordingId == -1L || filePath.isNullOrBlank()) {
            Log.e(TAG, "Invalid work parameters: recordingId=$recordingId, filePath=$filePath")
            return Result.failure()
        }

        val audioFile = File(filePath)
        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file does not exist or is empty: $filePath")
            return Result.failure()
        }

        Log.i(TAG, "Starting on-device semantic indexing for recording ID $recordingId (${audioFile.name})")

        // Promote to foreground service notification if running on API 26+
        try {
            setForeground(createForegroundInfo(0, "Analyzing audio recording..."))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set foreground info: ${e.message}")
        }

        val app = context.applicationContext as SoundBiteApplication
        val repository = app.audioVectorRepository
        val embeddingManager = app.embeddingGemmaManager
        val audioChunker = AudioChunker(
            sampleRate = AudioChunker.DEFAULT_SAMPLE_RATE,
            windowDurationSec = AudioChunker.DEFAULT_WINDOW_DURATION_SEC,
            overlapDurationSec = AudioChunker.DEFAULT_OVERLAP_DURATION_SEC
        )

        return try {
            // Ensure model interpreter and weights are primed
            embeddingManager.initialize().getOrThrow()

            // Step 1: Chunk audio file using 20s window with 5s overlap (15s step)
            val chunks = audioChunker.chunkFileStreaming(
                file = audioFile,
                isWav = true,
                recordingId = recordingId
            )

            if (chunks.isEmpty()) {
                Log.w(TAG, "No audio chunks produced for recording ID $recordingId")
                repository.updateRecordingIndexStatus(recordingId, isIndexed = true, chunkCount = 0)
                return Result.success()
            }

            val totalChunks = chunks.size
            Log.i(TAG, "Generated $totalChunks chunks for recording ID $recordingId. Running EmbeddingGemma 2 inference...")

            val segments = mutableListOf<RecordingSegment>()

            // Step 2: Iterate over each chunk, run EmbeddingGemma 2, and collect 256d normalized vectors
            for ((index, chunk) in chunks.withIndex()) {
                if (isStopped) {
                    Log.w(TAG, "Indexing cancelled or interrupted by OS constraints.")
                    return Result.retry()
                }

                // Generates 256-d L2-normalized embedding
                val embeddingVector = embeddingManager.embedAudio(chunk.pcmSamples)

                val segment = RecordingSegment(
                    recordingId = chunk.recordingId,
                    startTimestampMs = chunk.startTimestampMs,
                    endTimestampMs = chunk.endTimestampMs,
                    embedding = embeddingVector,
                    textTranscript = null
                )
                segments.add(segment)

                val progress = ((index + 1) * 100) / totalChunks
                setProgress(workDataOf(KEY_PROGRESS to progress))

                if (index % 5 == 0 || index == totalChunks - 1) {
                    try {
                        setForeground(
                            createForegroundInfo(
                                progress,
                                "Indexing audio vectors: $progress% ($index/$totalChunks)"
                            )
                        )
                    } catch (e: Exception) {
                        // Foreground update failure is non-fatal
                    }
                }
            }

            // Step 3: Insert segments into vector database and mark recording as indexed
            repository.insertSegments(segments)
            repository.updateRecordingIndexStatus(
                recordingId = recordingId,
                isIndexed = true,
                chunkCount = segments.size
            )

            Log.i(TAG, "Successfully indexed recording $recordingId with ${segments.size} vector segments.")
            Result.success(workDataOf(KEY_PROGRESS to 100))
        } catch (e: Exception) {
            Log.e(TAG, "Error indexing audio recording $recordingId: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(progress: Int, statusText: String): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SoundBite On-Device Indexing")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Audio Vector Indexing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies when SoundBite is indexing recordings on-device"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

