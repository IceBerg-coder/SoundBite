package com.soundbite.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.soundbite.audio.AudioPlaybackManager
import com.soundbite.audio.PlaybackStatus
import com.soundbite.data.db.RecordingEntity
import com.soundbite.data.repository.AudioVectorRepository
import com.soundbite.data.repository.SearchResult
import com.soundbite.ml.EmbeddingGemmaManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pre-defined quick filter categories with rich semantic prompts.
 */
enum class QuickFilter(val label: String, val semanticQuery: String) {
    DECISIONS("Decisions", "Key decisions made and agreed conclusions"),
    ACTION_ITEMS("Action Items", "Action items, tasks, next steps, and assignments"),
    FINANCIALS("Financials", "Quarterly budget, financial numbers, costs, and expenses"),
    QUESTIONS("Questions", "Unresolved questions, concerns, issues, and inquiries")
}

/**
 * UI representation of a vector nearest-neighbor search match.
 */
data class SearchResultUiModel(
    val segmentId: Long,
    val recordingId: Long,
    val recordingTitle: String,
    val audioFilePath: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val similarityScore: Float,
    val confidencePercentage: Int,
    val formattedTimeRange: String,
    val isHighlighted: Boolean = false,
    val isPlaying: Boolean = false
)

/**
 * Comprehensive UI state for the Recordings & Search screen.
 */
data class SearchUiState(
    val searchQuery: String = "",
    val selectedFilter: QuickFilter? = null,
    val isSearching: Boolean = false,
    val recordings: List<RecordingEntity> = emptyList(),
    val selectedRecording: RecordingEntity? = null,
    val searchResults: List<SearchResultUiModel> = emptyList(),
    val activeSegmentMatch: SearchResultUiModel? = null,
    val currentPlaybackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val playbackStatus: PlaybackStatus = PlaybackStatus.IDLE,
    val errorMessage: String? = null
)

/**
 * One-shot UI navigation and snackbar events.
 */
sealed interface SearchUiEvent {
    data class ShowMessage(val message: String) : SearchUiEvent
    data class SeekToPosition(val positionMs: Long) : SearchUiEvent
}

/**
 * Jetpack Compose ViewModel coordinating semantic vector retrieval,
 * EmbeddingGemma 2 inference, and Media3 audio playback synchronization.
 */
@OptIn(FlowPreview::class)
class SearchAudioViewModel(
    private val repository: AudioVectorRepository,
    private val embeddingManager: EmbeddingGemmaManager,
    private val playbackManager: AudioPlaybackManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SearchUiEvent>()
    val events: SharedFlow<SearchUiEvent> = _events.asSharedFlow()

    private val queryInputFlow = MutableStateFlow("")
    private var searchJob: Job? = null

    init {
        observeRecordings()
        observePlayback()
        observeQueryDebounce()
    }

    private fun observeRecordings() {
        viewModelScope.launch {
            repository.getAllRecordings().collectLatest { list ->
                _uiState.update { state ->
                    val selected = state.selectedRecording?.let { current ->
                        list.find { it.id == current.id } ?: list.firstOrNull()
                    } ?: list.firstOrNull()

                    state.copy(
                        recordings = list,
                        selectedRecording = selected,
                        playbackDurationMs = selected?.durationMs ?: state.playbackDurationMs
                    )
                }
            }
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            playbackManager.playbackStatus.collectLatest { status ->
                _uiState.update { it.copy(playbackStatus = status) }
            }
        }

        viewModelScope.launch {
            playbackManager.currentPositionMs.collectLatest { posMs ->
                _uiState.update { state ->
                    // Determine if current playback cursor is inside any search match window
                    val activeMatch = state.searchResults.find {
                        posMs in it.startTimestampMs..it.endTimestampMs
                    }
                    val updatedResults = state.searchResults.map { match ->
                        val isInside = posMs in match.startTimestampMs..match.endTimestampMs
                        match.copy(
                            isHighlighted = isInside || match.segmentId == activeMatch?.segmentId,
                            isPlaying = isInside && state.playbackStatus == PlaybackStatus.PLAYING
                        )
                    }
                    state.copy(
                        currentPlaybackPositionMs = posMs,
                        activeSegmentMatch = activeMatch ?: state.activeSegmentMatch,
                        searchResults = updatedResults
                    )
                }
            }
        }

        viewModelScope.launch {
            playbackManager.durationMs.collectLatest { durMs ->
                if (durMs > 0L) {
                    _uiState.update { it.copy(playbackDurationMs = durMs) }
                }
            }
        }
    }

    private fun observeQueryDebounce() {
        viewModelScope.launch {
            queryInputFlow
                .debounce(300L)
                .distinctUntilChanged()
                .collectLatest { text ->
                    if (text.isNotBlank()) {
                        executeSemanticSearch(text)
                    } else if (_uiState.value.selectedFilter == null) {
                        _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
                    }
                }
        }
    }

    /**
     * User typed into the search bar.
     */
    fun onQueryChanged(newQuery: String) {
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                selectedFilter = null // Clear chip filter when custom text is typed
            )
        }
        queryInputFlow.value = newQuery
    }

    /**
     * User tapped a quick-filter chip (e.g. "Decisions", "Financials").
     */
    fun onFilterSelected(filter: QuickFilter) {
        val isAlreadySelected = _uiState.value.selectedFilter == filter
        val nextFilter = if (isAlreadySelected) null else filter

        _uiState.update {
            it.copy(
                selectedFilter = nextFilter,
                searchQuery = if (nextFilter != null) nextFilter.label else ""
            )
        }

        if (nextFilter != null) {
            executeSemanticSearch(nextFilter.semanticQuery)
        } else {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
        }
    }

    /**
     * User selected a recording from the dropdown or tab.
     */
    fun onRecordingSelected(recording: RecordingEntity) {
        _uiState.update {
            it.copy(
                selectedRecording = recording,
                playbackDurationMs = recording.durationMs,
                currentPlaybackPositionMs = 0L,
                activeSegmentMatch = null
            )
        }
        // If there's an active search query or filter, re-execute against the selected recording
        val currentQuery = _uiState.value.selectedFilter?.semanticQuery ?: _uiState.value.searchQuery
        if (currentQuery.isNotBlank()) {
            executeSemanticSearch(currentQuery)
        }
    }

    /**
     * Executes the semantic vector retrieval pipeline:
     * 1. Vectorize query via EmbeddingGemmaManager (embedText) -> 256d normalized vector.
     * 2. Query ObjectBox vector database for top-k nearest neighbors.
     * 3. Filter and rank results, updating UI StateFlow.
     */
    fun executeSemanticSearch(queryString: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, errorMessage = null) }

            try {
                // 1. Vectorize the search query using embedText
                val queryVector = embeddingManager.embedText(queryString)

                // 2. Query the vector index for top-k matches with similarity scores
                val rawMatches: List<SearchResult> = repository.searchNearestSegments(
                    queryVector = queryVector,
                    topK = 15,
                    minScore = 0.30f
                )

                val selectedRecId = _uiState.value.selectedRecording?.id

                // Prioritize segments belonging to the currently selected recording if one is active
                val filteredMatches = if (selectedRecId != null && selectedRecId > 0L) {
                    val matchingCurrent = rawMatches.filter { it.segment.recordingId == selectedRecId }
                    if (matchingCurrent.isNotEmpty()) matchingCurrent else rawMatches
                } else {
                    rawMatches
                }

                // 3. Map to UI presentation models
                val uiResults = filteredMatches.map { match ->
                    val isInsideCurrentPos = _uiState.value.currentPlaybackPositionMs in
                            match.segment.startTimestampMs..match.segment.endTimestampMs

                    SearchResultUiModel(
                        segmentId = match.segment.id,
                        recordingId = match.segment.recordingId,
                        recordingTitle = match.recordingTitle,
                        audioFilePath = match.audioFilePath,
                        startTimestampMs = match.segment.startTimestampMs,
                        endTimestampMs = match.segment.endTimestampMs,
                        similarityScore = match.score,
                        confidencePercentage = match.confidencePercentage,
                        formattedTimeRange = "${formatMs(match.segment.startTimestampMs)} - ${formatMs(match.segment.endTimestampMs)}",
                        isHighlighted = isInsideCurrentPos,
                        isPlaying = isInsideCurrentPos && _uiState.value.playbackStatus == PlaybackStatus.PLAYING
                    )
                }

                _uiState.update {
                    it.copy(
                        searchResults = uiResults,
                        isSearching = false,
                        activeSegmentMatch = uiResults.firstOrNull()
                    )
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorMessage = "Search failed: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * User tapped on a search result card or timeline pin:
     * Immediately seeks ExoPlayer to match.startTimestampMs and highlights the segment.
     */
    fun onSearchResultClicked(match: SearchResultUiModel) {
        val currentPath = playbackManager.currentFilePath.value

        if (currentPath != match.audioFilePath) {
            playbackManager.loadAndPlay(match.audioFilePath, match.startTimestampMs)
        } else {
            playbackManager.seekTo(match.startTimestampMs)
            if (playbackManager.playbackStatus.value != PlaybackStatus.PLAYING) {
                playbackManager.play()
            }
        }

        _uiState.update { state ->
            val updated = state.searchResults.map { item ->
                item.copy(
                    isHighlighted = item.segmentId == match.segmentId,
                    isPlaying = item.segmentId == match.segmentId
                )
            }
            state.copy(
                activeSegmentMatch = match,
                currentPlaybackPositionMs = match.startTimestampMs,
                searchResults = updated
            )
        }

        viewModelScope.launch {
            _events.emit(SearchUiEvent.SeekToPosition(match.startTimestampMs))
        }
    }

    /**
     * User dragged or tapped the waveform scrubber.
     */
    fun onSeekTo(positionMs: Long) {
        playbackManager.seekTo(positionMs)
        _uiState.update { it.copy(currentPlaybackPositionMs = positionMs) }
    }

    /**
     * User pressed the Play/Pause button.
     */
    fun onPlayPauseToggle() {
        val selected = _uiState.value.selectedRecording
        if (selected != null && playbackManager.currentFilePath.value != selected.filePath) {
            playbackManager.loadAndPlay(selected.filePath, _uiState.value.currentPlaybackPositionMs)
        } else {
            playbackManager.togglePlayPause()
        }
    }

    private fun formatMs(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onCleared() {
        super.onCleared()
        // AudioPlaybackManager can be singleton or released with ViewModel lifecycle
    }
}

