package com.soundbite.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.soundbite.SoundBiteApplication
import com.soundbite.audio.AudioRecorder
import com.soundbite.audio.RecordingOutput
import com.soundbite.data.db.RecordingEntity
import com.soundbite.worker.AudioIndexingWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordUiState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val currentAmplitude: Float = 0.05f,
    val activeIndexingCount: Int = 0,
    val latestIndexingProgress: Int? = null,
    val indexingStatusMessage: String = "Vector engine idle",
    val lastRecordedTitle: String? = null
)

sealed interface RecordEvent {
    data class ShowToast(val message: String) : RecordEvent
    data class RecordingSaved(val recordingId: Long) : RecordEvent
}

class RecordViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val app = application as SoundBiteApplication
    private val audioRecorder = AudioRecorder(application)
    private val repository = app.audioVectorRepository

    private val _uiState = MutableStateFlow(RecordUiState())
    val uiState: StateFlow<RecordUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<RecordEvent>()
    val events: SharedFlow<RecordEvent> = _events.asSharedFlow()

    init {
        observeRecorder()
        observeWorkManager()
    }

    private fun observeRecorder() {
        viewModelScope.launch {
            audioRecorder.isRecording.collectLatest { rec ->
                _uiState.update { it.copy(isRecording = rec) }
            }
        }

        viewModelScope.launch {
            audioRecorder.isPaused.collectLatest { paused ->
                _uiState.update { it.copy(isPaused = paused) }
            }
        }

        viewModelScope.launch {
            audioRecorder.recordingDurationMs.collectLatest { dur ->
                _uiState.update { it.copy(recordingDurationMs = dur) }
            }
        }

        viewModelScope.launch {
            audioRecorder.amplitudeFlow.collectLatest { amp ->
                _uiState.update { it.copy(currentAmplitude = amp) }
            }
        }
    }

    private fun observeWorkManager() {
        val workManager = WorkManager.getInstance(getApplication())
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(AudioIndexingWorker.TAG).collectLatest { workInfos ->
                val runningWorks = workInfos.filter { it.state == WorkInfo.State.RUNNING }
                val enqueuedWorks = workInfos.filter { it.state == WorkInfo.State.ENQUEUED }
                val activeCount = runningWorks.size + enqueuedWorks.size

                val currentRunning = runningWorks.firstOrNull()
                val progress = currentRunning?.progress?.getInt(AudioIndexingWorker.KEY_PROGRESS, 0)

                val statusMessage = when {
                    runningWorks.isNotEmpty() -> "Indexing vectors on-device: ${progress ?: 0}%"
                    enqueuedWorks.isNotEmpty() -> "Queued for background vector indexing (${enqueuedWorks.size} pending)"
                    else -> "Vector index up-to-date (0 pending)"
                }

                _uiState.update {
                    it.copy(
                        activeIndexingCount = activeCount,
                        latestIndexingProgress = if (runningWorks.isNotEmpty()) progress else null,
                        indexingStatusMessage = statusMessage
                    )
                }
            }
        }
    }

    fun onRecordToggle() {
        viewModelScope.launch {
            if (_uiState.value.isRecording) {
                if (_uiState.value.isPaused) {
                    audioRecorder.resumeRecording()
                } else {
                    audioRecorder.pauseRecording()
                }
            } else {
                startRecordingSession()
            }
        }
    }

    private suspend fun startRecordingSession() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "soundbite_$timestamp.wav"
        val storageDir = File(getApplication<Application>().filesDir, "recordings")
        storageDir.mkdirs()
        val outputFile = File(storageDir, fileName)

        val result = audioRecorder.startRecording(outputFile)
        if (result.isFailure) {
            _events.emit(RecordEvent.ShowToast("Failed to start audio recording: ${result.exceptionOrNull()?.message}"))
        }
    }

    fun onStopRecording() {
        viewModelScope.launch {
            val output: RecordingOutput? = audioRecorder.stopRecording()
            if (output != null && output.file.exists()) {
                val formattedDate = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date())
                val title = "Recording $formattedDate"

                val recordingEntity = RecordingEntity(
                    title = title,
                    filePath = output.file.absolutePath,
                    durationMs = output.durationMs,
                    createdAt = System.currentTimeMillis(),
                    isIndexed = false,
                    chunkCount = 0
                )

                // Save recording metadata to local DB
                val recordingId = repository.saveRecording(recordingEntity)

                // Schedule background WorkManager indexing
                AudioIndexingWorker.schedule(
                    context = getApplication(),
                    recordingId = recordingId,
                    filePath = output.file.absolutePath,
                    requiresCharging = false
                )

                _uiState.update {
                    it.copy(
                        lastRecordedTitle = title,
                        indexingStatusMessage = "Indexing scheduled via WorkManager"
                    )
                }

                _events.emit(RecordEvent.RecordingSaved(recordingId))
                _events.emit(RecordEvent.ShowToast("Saved '$title'. Background indexing started."))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
    }
}

