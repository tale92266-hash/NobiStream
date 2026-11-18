package com.nobi.nobistream.ui.compose.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.data.DownloadManager
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.data.SettingsStore
import com.nobi.nobistream.data.db.DownloadEntity
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.compose.components.LibrarySkeleton
import com.nobi.nobistream.ui.compose.components.QualityFilterChips
import com.nobi.nobistream.ui.compose.components.QualityPillBadge
import com.nobi.nobistream.ui.compose.components.VideoThumb
import com.nobi.nobistream.ui.compose.theme.NobiSuccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class WatchlistSort { NEWEST, OLDEST, TITLE_AZ }

/**
 * My Vault tab — BOTH tabs real.
 *
 * Watchlist: real per-video bookmarks from
 * repository.observeBookmarks() (Room). Chips + Smart chip + sort
 * filter the bookmarked videos in memory.
 *
 * Offline Queue: real rows from downloadManager.downloads.
 * FAILED shows Retry; COMPLETED tap plays offline through the
 * existing player; delete cancels (queued/downloading/failed) or
 * deletes the completed row. Delete-all lives in the Control
 * screen, not here.
 *
 * NOTE on the brief's pagination bullet: it asks for loadLibraryPage
 * cursors + Load More here, but the Watchlist tab it defines is
 * backed by observeBookmarks() (Room) — bookmarks need no paging
 * and no first-page load, so loadLibraryPage is NOT called from this
 * screen. The 60s first-page timeout belongs to that paged load and
 * has no meaning over a Room flow that emits in milliseconds; the
 * screen instead shows a spinner only until the first Room emit.
 * Flagged in the build report for the parent to confirm.
 */
@Composable
fun LibraryScreen(
    repository: NobiRepository,
    downloadManager: DownloadManager,
    settingsStore: SettingsStore,
    onPlayVideo: (VideoItem) -> Unit,
    // v2.6.1: third param is the deep-scroll target episode
    // (messageId), or null for a plain open at the top.
    onOpenChannel: (Long, String, Long?) -> Unit,
    onShowMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    val bookmarks by repository.observeBookmarks()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val groups by repository.observeGroups()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloads by downloadManager.downloads
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val savedSmart by settingsStore.smartFilter
        .collectAsStateWithLifecycle(initialValue = true)
    val preferredQuality by settingsStore.preferredQuality
        .collectAsStateWithLifecycle(initialValue = "Highest available")
    val savedDefaultFilter by settingsStore.defaultFilter
        .collectAsStateWithLifecycle(initialValue = "All")

    var bookmarksLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // First Room emit ends the loading state (Room emits in ms).
        repository.observeBookmarks().first()
        bookmarksLoaded = true
    }

    var selectedQualityOverride by remember { mutableStateOf<VideoQuality?>(null) }
    val selectedQuality = selectedQualityOverride
        ?: VideoQuality.fromLabel(savedDefaultFilter)
    var smartOverride by remember { mutableStateOf<Boolean?>(null) }
    val smartOn = smartOverride ?: savedSmart
    var sort by remember { mutableStateOf(WatchlistSort.NEWEST) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val groupNameOf: (Long) -> String = { chatId ->
        groups.firstOrNull { it.chatId == chatId }?.title.orEmpty()
    }

    // Watchlist pipeline: dedup FIRST (as today), then the quality
    // chip, then the sort — the same order as Channel / Library.
    val preferredMaxRank = SmartFilter.maxRankFor(preferredQuality)
    val watchlistGroups: List<EpisodeGroup> = remember(bookmarks, smartOn, preferredMaxRank) {
        if (smartOn) SmartFilter.groupEpisodes(bookmarks, preferredMaxRank) else emptyList()
    }
    val displayedWatchlist: List<VideoItem> = remember(
        bookmarks, smartOn, watchlistGroups, selectedQuality, sort
    ) {
        var list = if (smartOn) {
            watchlistGroups.map { it.best.copy(hiddenCopies = it.hiddenCount) }
        } else {
            bookmarks
        }
        if (selectedQuality != VideoQuality.UNKNOWN) {
            list = list.filter { it.quality == selectedQuality }
        }
        when (sort) {
            WatchlistSort.NEWEST -> list.sortedByDescending { it.dateEpochSec }
            WatchlistSort.OLDEST -> list.sortedBy { it.dateEpochSec }
            WatchlistSort.TITLE_AZ -> list.sortedBy {
                FormatUtils.stripLeadingEmoji(it.title).lowercase()
            }
        }
    }

    fun playWatchlist(item: VideoItem) {
        PlayerPlaylist.items = displayedWatchlist
        PlayerPlaylist.chatId = item.chatId
        PlayerPlaylist.episodeGroups = watchlistGroups
        PlayerPlaylist.fromContinue = false
        onPlayVideo(item)
    }

    fun playOffline(entity: DownloadEntity) {
        val completed = downloads.filter { it.status == "COMPLETED" }
        val items = completed.map { it.toVideoItem() }
        val item = entity.toVideoItem()
        PlayerPlaylist.items = items
        PlayerPlaylist.chatId = entity.chatId
        PlayerPlaylist.episodeGroups = emptyList()
        PlayerPlaylist.fromContinue = false
        onPlayVideo(item)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // v2.6.1: root background in theme colours.
            .background(MaterialTheme.colorScheme.background)
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Text(
                        text = "Watchlist (${bookmarks.size})",
                        fontWeight = if (selectedTab == 0) FontWeight.ExtraBold
                        else FontWeight.Medium
                    )
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Text(
                        text = "Offline Queue (${downloads.size})",
                        fontWeight = if (selectedTab == 1) FontWeight.ExtraBold
                        else FontWeight.Medium
                    )
                }
            )
        }

        if (selectedTab == 0) {
            if (!bookmarksLoaded) {
                // v2.6.1: shimmer skeleton, not a bare spinner.
                LibrarySkeleton()
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Quality chips + Smart chip + sort, as today.
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            QualityFilterChips(
                                selected = selectedQuality,
                                onSelect = { selectedQualityOverride = it }
                            )
                        }
                        item {
                            FilterChip(
                                selected = smartOn,
                                onClick = {
                                    val next = !smartOn
                                    smartOverride = next
                                    scope.launch {
                                        settingsStore.setSmartFilter(next)
                                    }
                                },
                                label = { Text("Smart") }
                            )
                        }
                        item {
                            Box {
                                AssistChip(
                                    onClick = { sortMenuOpen = true },
                                    label = {
                                        Text(
                                            when (sort) {
                                                WatchlistSort.NEWEST -> "Newest"
                                                WatchlistSort.OLDEST -> "Oldest"
                                                WatchlistSort.TITLE_AZ -> "A–Z"
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
                                        onClick = { sort = WatchlistSort.NEWEST; sortMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Oldest") },
                                        onClick = { sort = WatchlistSort.OLDEST; sortMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("A–Z") },
                                        onClick = { sort = WatchlistSort.TITLE_AZ; sortMenuOpen = false }
                                    )
                                }
                            }
                        }
                    }

                    if (displayedWatchlist.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Filled.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = "Your Watchlist is empty",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Bookmark any video to keep it here.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(displayedWatchlist, key = { it.chatId to it.messageId }) { item ->
                                WatchlistCard(
                                    item = item,
                                    repository = repository,
                                    groupName = groupNameOf(item.chatId),
                                    onClick = { playWatchlist(item) },
                                    onOpenChannel = { onOpenChannel(item.chatId, groupNameOf(item.chatId), null) },
                                    onRemove = {
                                        scope.launch { repository.toggleBookmark(item) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.DownloadDone,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No downloads yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Save a video offline from its episode card.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(downloads, key = { it.chatId to it.messageId }) { task ->
                        DownloadCard(
                            task = task,
                            onClick = {
                                if (task.status == "COMPLETED") playOffline(task)
                            },
                            onRetry = {
                                scope.launch {
                                    runCatching {
                                        downloadManager.retry(task.chatId, task.messageId)
                                    }
                                }
                            },
                            onDelete = {
                                scope.launch {
                                    runCatching {
                                        if (task.status == "COMPLETED") {
                                            downloadManager.deleteCompleted(
                                                task.chatId, task.messageId
                                            )
                                        } else {
                                            downloadManager.cancel(
                                                task.chatId, task.messageId
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchlistCard(
    item: VideoItem,
    repository: NobiRepository,
    groupName: String,
    onClick: () -> Unit,
    onOpenChannel: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            VideoThumb(
                repository = repository,
                item = item,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                QualityPillBadge(text = item.quality.label)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = FormatUtils.stripLeadingEmoji(item.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        item.quality.label,
                        FormatUtils.formatDuration(item.durationSec)
                            .takeIf { item.durationSec > 0 },
                        groupName.takeIf { it.isNotEmpty() }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (groupName.isNotEmpty()) {
                        Modifier.clickable { onOpenChannel() }
                    } else {
                        Modifier
                    }
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun DownloadCard(
    task: DownloadEntity,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val progress = when {
        task.status == "COMPLETED" -> 1f
        task.sizeBytes > 0 -> (task.downloadedBytes.toFloat() / task.sizeBytes.toFloat())
            .coerceIn(0f, 1f)
        else -> 0f
    }
    // Episode line: the episode number parsed from the title, else the date.
    val episodeLine = run {
        val probe = task.toVideoItem()
        SmartFilter.parseKey(probe)?.let { key ->
            if (key.season != null) "S${key.season} Ep ${key.episode}"
            else "Ep ${key.episode}"
        } ?: FormatUtils.formatDate(task.updatedAtEpochSec)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = FormatUtils.stripLeadingEmoji(task.title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = episodeLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove download",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when (task.status) {
                    "COMPLETED" -> NobiSuccess
                    "DOWNLOADING" -> MaterialTheme.colorScheme.primary
                    "QUEUED" -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${task.qualityLabel} • ${FormatUtils.formatSize(task.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.status == "FAILED") {
                        TextButton(onClick = onRetry) {
                            Icon(
                                Icons.Filled.Replay,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry")
                        }
                    }
                    Text(
                        text = "${(progress * 100).toInt()}% • ${task.status}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (task.status == "COMPLETED") {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = NobiSuccess,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Saved on this phone — tap to play offline",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** A DownloadEntity as the VideoItem the player opens (offline). */
private fun DownloadEntity.toVideoItem(): VideoItem = VideoItem(
    messageId = messageId,
    chatId = chatId,
    fileId = 0,
    remoteFileId = remoteFileId,
    title = title,
    caption = "",
    quality = VideoQuality.fromLabel(qualityLabel),
    durationSec = 0,
    sizeBytes = sizeBytes,
    dateEpochSec = updatedAtEpochSec,
    thumbFileId = null,
    thumbLocalPath = null
)
