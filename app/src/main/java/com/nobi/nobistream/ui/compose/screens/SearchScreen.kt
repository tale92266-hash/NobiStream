package com.nobi.nobistream.ui.compose.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.data.DownloadManager
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.data.SettingsStore
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.compose.components.QualityFilterChips
import com.nobi.nobistream.ui.compose.components.SearchSkeleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SearchSort { NEWEST, OLDEST, TITLE_AZ }

/**
 * Catalog tab: real search across the saved groups
 * (repository.searchVideos), 400ms debounce, real recent searches
 * (SettingsStore), real result count, real results grid.
 *
 * Sort is Newest / Oldest / A–Z — there is no rating sort (ratings
 * do not exist). Genre chips are deleted (no genres); the chip row
 * is the real quality filter.
 *
 * PlayerPlaylist is set here, immediately before onPlayVideo — the
 * host lambda only builds the player intent and starts the player.
 */
@Composable
fun SearchScreen(
    repository: NobiRepository,
    downloadManager: DownloadManager,
    settingsStore: SettingsStore,
    onPlayVideo: (VideoItem) -> Unit,
    // v2.6.1: third param is the deep-scroll target episode
    // (messageId), or null for a plain open at the top.
    onOpenChannel: (Long, String, Long?) -> Unit,
    onAddGroupLink: (String) -> Unit,
    onShowMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val groups by repository.observeGroups()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val recentSearches by settingsStore.recentSearches
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val savedSmart by settingsStore.smartFilter
        .collectAsStateWithLifecycle(initialValue = true)
    val preferredQuality by settingsStore.preferredQuality
        .collectAsStateWithLifecycle(initialValue = "Highest available")

    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf(VideoQuality.UNKNOWN) }
    var sort by remember { mutableStateOf(SearchSort.NEWEST) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var confirmClearRecent by remember { mutableStateOf(false) }

    val preferredMaxRank = SmartFilter.maxRankFor(preferredQuality)
    val groupNameOf: (VideoItem) -> String = { item ->
        groups.firstOrNull { it.chatId == item.chatId }?.title.orEmpty()
    }

    // 400ms debounce, like SearchActivity. A new keystroke cancels
    // the pending search (LaunchedEffect restarts on query change).
    LaunchedEffect(query, groups, savedSmart, preferredMaxRank) {
        val clean = query.trim()
        if (clean.isEmpty()) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(400L)
        searching = true
        try {
            val found = withContext(Dispatchers.IO) {
                repository.searchVideos(clean, groups)
            }
            settingsStore.addRecentSearch(clean)
            results = if (savedSmart) {
                SmartFilter.groupEpisodes(found, preferredMaxRank)
                    .map { it.best.copy(hiddenCopies = it.hiddenCount) }
            } else {
                found
            }
        } catch (_: Exception) {
            results = emptyList()
            onShowMessage("Search failed — try again")
        } finally {
            searching = false
        }
    }

    val displayedResults = remember(results, selectedQuality, sort) {
        var list = results
        if (selectedQuality != VideoQuality.UNKNOWN) {
            list = list.filter { it.quality == selectedQuality }
        }
        when (sort) {
            SearchSort.NEWEST -> list.sortedByDescending { it.dateEpochSec }
            SearchSort.OLDEST -> list.sortedBy { it.dateEpochSec }
            SearchSort.TITLE_AZ -> list.sortedBy {
                FormatUtils.stripLeadingEmoji(it.title).lowercase()
            }
        }
    }

    fun playResult(item: VideoItem) {
        PlayerPlaylist.items = displayedResults
        PlayerPlaylist.chatId = item.chatId
        PlayerPlaylist.episodeGroups = emptyList()
        PlayerPlaylist.fromContinue = false
        onPlayVideo(item)
    }

    fun pasteLink() {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = clipboard?.takeIf { it.hasPrimaryClip() }
            ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
        val lower = text?.lowercase().orEmpty()
        if (text != null && (lower.startsWith("https://t.me/") ||
                lower.startsWith("http://t.me/") || lower.startsWith("t.me/"))
        ) {
            onAddGroupLink(text)
        } else {
            onShowMessage("Copy a Telegram group link first")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // v2.6.1: root background in theme colours.
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search videos in your groups…") },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Quality filter chips.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                QualityFilterChips(
                    selected = selectedQuality,
                    onSelect = { selectedQuality = it }
                )
            }
        }

        // Result count + sort AssistChip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (query.isBlank()) "" else "Showing ${displayedResults.size} videos",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box {
                AssistChip(
                    onClick = { sortMenuOpen = true },
                    label = {
                        Text(
                            when (sort) {
                                SearchSort.NEWEST -> "Sorted: Newest"
                                SearchSort.OLDEST -> "Sorted: Oldest"
                                SearchSort.TITLE_AZ -> "Sorted: A–Z"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Newest") },
                        onClick = { sort = SearchSort.NEWEST; sortMenuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Oldest") },
                        onClick = { sort = SearchSort.OLDEST; sortMenuOpen = false }
                    )
                    DropdownMenuItem(
                        text = { Text("A–Z") },
                        onClick = { sort = SearchSort.TITLE_AZ; sortMenuOpen = false }
                    )
                }
            }
        }

        // Paste-link chip (always visible; explains itself with no link).
        AssistChip(
            onClick = { pasteLink() },
            label = { Text("Paste link") },
            leadingIcon = {
                Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (query.isBlank()) {
            // Start state: recent searches under the field.
            if (recentSearches.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent searches",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { confirmClearRecent = true }) {
                        Text("Clear all")
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(recentSearches, key = { it }) { recent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { query = recent }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = recent,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    settingsStore.removeRecentSearch(recent)
                                }
                            }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Search your groups",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Find any video by its title or caption.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (searching) {
            // v2.6.1: shimmer skeleton, not a bare spinner.
            SearchSkeleton()
        } else if (displayedResults.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.SearchOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No matching videos found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Try another keyword or a different quality filter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 158.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 100.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(displayedResults, key = { it.chatId to it.messageId }) { item ->
                    BookmarkedMediaCard(
                        item = item,
                        repository = repository,
                        subtitle = groupNameOf(item),
                        onCardClick = { playResult(item) },
                        onToggleBookmark = {
                            scope.launch { repository.toggleBookmark(item) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onSubtitleClick = {
                            // v2.6.1 deep scroll: land on this episode.
                            onOpenChannel(item.chatId, groupNameOf(item), item.messageId)
                        }
                    )
                }
            }
        }
    }

    if (confirmClearRecent) {
        AlertDialog(
            onDismissRequest = { confirmClearRecent = false },
            title = { Text("Clear recent searches?") },
            text = { Text("Your recent search history will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearRecent = false
                    scope.launch {
                        settingsStore.clearRecentSearches()
                        onShowMessage("Recent searches cleared")
                    }
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearRecent = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
