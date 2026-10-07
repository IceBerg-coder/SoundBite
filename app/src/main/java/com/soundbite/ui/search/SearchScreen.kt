package com.soundbite.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.soundbite.audio.PlaybackStatus
import com.soundbite.ui.components.AudioPlayerScrubberView
import com.soundbite.ui.components.SearchResultCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchAudioViewModel,
    onNavigateToRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var isRecordingDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 1. Natural Language Search Bar
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = { viewModel.onQueryChanged(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. \"when was the quarterly budget mentioned?\"") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Semantic Search",
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                if (uiState.isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChanged("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Quick-Filter Chips ("Decisions", "Action Items", "Financials", "Questions")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickFilter.values().forEach { filter ->
                val isSelected = uiState.selectedFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { viewModel.onFilterSelected(filter) },
                    label = { Text(filter.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 3. Audio Recording Selection Dropdown (if multiple recordings exist)
        if (uiState.recordings.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = isRecordingDropdownExpanded,
                onExpandedChange = { isRecordingDropdownExpanded = !isRecordingDropdownExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = uiState.selectedRecording?.title ?: "Select Recording",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Active Recording") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isRecordingDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.outlinedTextFieldColors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                ExposedDropdownMenu(
                    expanded = isRecordingDropdownExpanded,
                    onDismissRequest = { isRecordingDropdownExpanded = false }
                ) {
                    uiState.recordings.forEach { recording ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = recording.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = if (recording.isIndexed) "${recording.chunkCount} vectors indexed" else "Indexing in progress",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = if (recording.isIndexed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    )
                                }
                            },
                            onClick = {
                                viewModel.onRecordingSelected(recording)
                                isRecordingDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 4. Waveform Scrubber Timeline with Marker Pins
        if (uiState.selectedRecording != null) {
            AudioPlayerScrubberView(
                currentPositionMs = uiState.currentPlaybackPositionMs,
                totalDurationMs = uiState.playbackDurationMs,
                searchResults = uiState.searchResults,
                activeMatch = uiState.activeSegmentMatch,
                onSeek = { viewModel.onSeekTo(it) },
                onPinClick = { viewModel.onSearchResultClicked(it) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Playback Transport Controls (Play / Pause / Current Track)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.selectedRecording?.title ?: "No file loaded",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (uiState.activeSegmentMatch != null) {
                            "Focused: ${uiState.activeSegmentMatch?.formattedTimeRange} (${uiState.activeSegmentMatch?.confidencePercentage}% match)"
                        } else {
                            "Tap any pin or match to preview"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                FilledIconButton(
                    onClick = { viewModel.onPlayPauseToggle() },
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (uiState.playbackStatus == PlaybackStatus.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.playbackStatus == PlaybackStatus.PLAYING) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // 5. Ranked Search Results List
        if (uiState.recordings.isEmpty()) {
            // Empty state: No recordings exist yet
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No audio recordings found",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Record a meeting or lecture first to search via on-device AI.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                    )
                }
            }
        } else if (uiState.searchResults.isEmpty() && uiState.searchQuery.isNotBlank() && !uiState.isSearching) {
            // No matches found for current query
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No semantic matches found",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Try rephrasing your search or selecting a quick-filter chip.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.outline)
                    )
                }
            }
        } else {
            // Ranked cards
            Text(
                text = if (uiState.searchResults.isNotEmpty()) "Top Ranked Semantic Matches" else "Indexed Recordings",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(
                    items = uiState.searchResults,
                    key = { _, item -> item.segmentId }
                ) { index, match ->
                    SearchResultCard(
                        result = match,
                        rankIndex = index + 1,
                        onClick = { viewModel.onSearchResultClicked(match) },
                        onPlayClick = { viewModel.onSearchResultClicked(match) }
                    )
                }
            }
        }
    }
}

