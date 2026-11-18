package com.nobi.nobistream.ui.compose.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.data.DownloadManager
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.data.SettingsStore
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.compose.CopiesSheetModal
import com.nobi.nobistream.ui.compose.components.GroupAvatar
import com.nobi.nobistream.ui.compose.components.LetterAvatar
import com.nobi.nobistream.ui.compose.components.QualityFilterChips
import com.nobi.nobistream.ui.compose.components.QualityPillBadge
import com.nobi.nobistream.ui.compose.theme.NobiGold
import com.nobi.nobistream.ui.compose.theme.NobiSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PAGE_SIZE = 50
private const val FIRST_PAGE_RETRY_DELAY_MS = 1_500L
private const val FIRST_PAGE_RETRY_MAX_DELAY_MS = 5_000L
private const val FIRST_PAGE_TIMEOUT_MS = 60_000L
private const val MAX_FIRST_PAGE_ATTEMPTS = 3

/**
 * Channel list in the mock Detail layout: banner, Best-copy card,
 * episode rows.
 *
 * First page: an empty window (no videos AND no messages seen) is
 * retried with backoff 1.5s -> 3s -> 5s, bounded only by the 60s
 * timeout, which cancels the hung load and shows the error state +
 * Retry. "No videos found" appears ONLY when messages were actually
 * seen with no videos in them. Load More pages with
 * loadVideos(chatId, fromMessageId, 50), advancing on the oldest raw
 * message (hasMore = rawCount >= 50).
 *
 * The copies sheet state lives in THIS screen. PlayerPlaylist is set
 * here, immediately before onPlayVideo — the host lambda only builds
 * the player intent and starts the player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(
    repository: NobiRepository,
    downloadManager: DownloadManager,
    settingsStore: SettingsStore,
    chatId: Long,
    title: String,
    onBack: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onShowMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val groups by repository.observeGroups()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val fillerMarks by repository.observeFillerMarks()
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val downloads by downloadManager.downloads
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val savedSmart by settingsStore.smartFilter
        .collectAsStateWithLifecycle(initialValue = true)
    val preferredQuality by settingsStore.preferredQuality
        .collectAsStateWithLifecycle(initialValue = "Highest available")
    val savedDefaultFilter by settingsStore.defaultFilter
        .collectAsStateWithLifecycle(initialValue = "All")

    var allVideos by remember(chatId) { mutableStateOf<List<VideoItem>>(emptyList()) }
    var fromMessageId by remember(chatId) { mutableStateOf(0L) }
    var hasMore by remember(chatId) { mutableStateOf(true) }
    var loading by remember(chatId) { mutableStateOf(false) }
    var firstPageError by remember(chatId) { mutableStateOf(false) }
    var firstPageTimedOut by remember(chatId) { mutableStateOf(false) }
    var sawMessages by remember(chatId) { mutableStateOf(false) }
    var totalVideoCount by remember(chatId) { mutableStateOf<Int?>(null) }
    var loadJob by remember(chatId) { mutableStateOf<Job?>(null) }
    var timeoutJob by remember(chatId) { mutableStateOf<Job?>(null) }

    var currentFilterOverride by remember(chatId) { mutableStateOf<VideoQuality?>(null) }
    val currentFilter = currentFilterOverride ?: VideoQuality.fromLabel(savedDefaultFilter)
    var smartOverride by remember(chatId) { mutableStateOf<Boolean?>(null) }
    val smartOn = smartOverride ?: savedSmart
    var hideFillers by remember(chatId) { mutableStateOf(false) }
    var sortNewest by remember(chatId) { mutableStateOf(true) }
    var selectedVideo by remember(chatId) { mutableStateOf<VideoItem?>(null) }
    var sheetGroup by remember(chatId) { mutableStateOf<EpisodeGroup?>(null) }

    val preferredMaxRank = SmartFilter.maxRankFor(preferredQuality)
    val downloadById = remember(downloads) {
        downloads.associateBy { it.chatId to it.messageId }
    }

    // ---- First-page load with empty-window retry + 60s timeout ----
    fun loadFirstPage() {
        loadJob = scope.launch {
            loading = true
            var backoff = FIRST_PAGE_RETRY_DELAY_MS
            var attempts = 0
            while (true) {
                try {
                    val page = withContext(Dispatchers.IO) {
                        repository.loadVideos(chatId, 0L, PAGE_SIZE)
                    }
                    if (page.videos.isNotEmpty() || page.rawCount > 0) {
                        // Terminal: videos arrived, or messages were
                        // actually seen (an empty channel is a real
                        // empty state, not a sync delay).
                        timeoutJob?.cancel()
                        allVideos = page.videos
                        if (page.lastMessageId > 0L) fromMessageId = page.lastMessageId
                        hasMore = page.rawCount >= PAGE_SIZE
                        totalVideoCount = page.totalCount
                        sawMessages = page.rawCount > 0
                        loading = false
                        return@launch
                    }
                    if (firstPageTimedOut) return@launch
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(FIRST_PAGE_RETRY_MAX_DELAY_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    attempts++
                    if (attempts < MAX_FIRST_PAGE_ATTEMPTS) {
                        delay(FIRST_PAGE_RETRY_DELAY_MS)
                        continue
                    }
                    timeoutJob?.cancel()
                    loading = false
                    firstPageError = true
                    return@launch
                }
            }
        }
    }

    fun resetAndLoad() {
        loadJob?.cancel()
        timeoutJob?.cancel()
        firstPageTimedOut = false
        allVideos = emptyList()
        fromMessageId = 0L
        hasMore = true
        firstPageError = false
        sawMessages = false
        totalVideoCount = null
        timeoutJob = scope.launch {
            delay(FIRST_PAGE_TIMEOUT_MS)
            if (allVideos.isEmpty() && !firstPageError) {
                firstPageTimedOut = true
                loadJob?.cancel()
                loading = false
                firstPageError = true
            }
        }
        loadFirstPage()
    }

    fun loadMore() {
        if (loading || !hasMore) return
        loading = true
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    repository.loadVideos(chatId, fromMessageId, PAGE_SIZE)
                }
                val seen = allVideos.map { it.chatId to it.messageId }.toHashSet()
                val fresh = page.videos.filter { seen.add(it.chatId to it.messageId) }
                allVideos = allVideos + fresh
                if (page.lastMessageId > 0L) fromMessageId = page.lastMessageId
                hasMore = page.rawCount >= PAGE_SIZE
                if (page.rawCount > 0) sawMessages = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onShowMessage("Couldn't load more videos")
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(chatId) {
        resetAndLoad()
    }

    // ---- Displayed list: dedup FIRST, then quality chip, then fillers, then sort ----
    val currentGroups: List<EpisodeGroup> = remember(allVideos, smartOn, preferredMaxRank) {
        if (smartOn) SmartFilter.groupEpisodes(allVideos, preferredMaxRank) else emptyList()
    }
    val displayedVideos: List<VideoItem> = remember(
        allVideos, smartOn, currentGroups, currentFilter, hideFillers, fillerMarks, sortNewest
    ) {
        var list = if (smartOn) {
            currentGroups.map { it.best.copy(hiddenCopies = it.hiddenCount) }
        } else {
            allVideos
        }
        if (currentFilter != VideoQuality.UNKNOWN) {
            list = list.filter { it.quality == currentFilter }
        }
        if (hideFillers) {
            list = list.filter { (it.chatId to it.messageId) !in fillerMarks }
        }
        if (!sortNewest) list.reversed() else list
    }

    fun playVideo(item: VideoItem, playlistOverride: List<VideoItem>? = null) {
        PlayerPlaylist.items = playlistOverride ?: displayedVideos
        PlayerPlaylist.chatId = chatId
        PlayerPlaylist.episodeGroups = currentGroups
        PlayerPlaylist.fromContinue = false
        onPlayVideo(item)
    }

    fun openCopies(best: VideoItem) {
        val group = currentGroups.firstOrNull {
            it.best.chatId == best.chatId && it.best.messageId == best.messageId
        }
        if (group != null) {
            selectedVideo = best
            sheetGroup = group
        }
    }

    fun toggleFiller(item: VideoItem) {
        val marked = (item.chatId to item.messageId) in fillerMarks
        scope.launch {
            runCatching { repository.toggleFillerMark(item) }
            onShowMessage(if (marked) "Filler mark removed" else "Marked as filler")
        }
    }

    fun toggleWatched(item: VideoItem) {
        scope.launch {
            runCatching { repository.setWatched(item, !item.watched) }
            allVideos = allVideos.map {
                if (it.chatId == item.chatId && it.messageId == item.messageId) {
                    it.copy(watched = !item.watched)
                } else {
                    it
                }
            }
        }
    }

    fun downloadVideo(item: VideoItem) {
        scope.launch {
            val message = when (downloadManager.enqueue(item)) {
                DownloadManager.EnqueueResult.QUEUED -> "Added to downloads"
                DownloadManager.EnqueueResult.ALREADY_PRESENT -> "Already in downloads"
                DownloadManager.EnqueueResult.ALREADY_COMPLETED -> "Already downloaded"
                DownloadManager.EnqueueResult.NO_SPACE -> "Not enough space on this phone"
            }
            onShowMessage(message)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && lastVisible >= displayedVideos.size - 5) {
                    loadMore()
                }
            }
    }

    val group = groups.firstOrNull { it.chatId == chatId }
    val selectedGroup = selectedVideo?.let { selected ->
        currentGroups.firstOrNull {
            it.best.chatId == selected.chatId && it.best.messageId == selected.messageId
        }
    } ?: currentGroups.firstOrNull()
    val showEmpty = displayedVideos.isEmpty() && !loading && !firstPageError &&
        allVideos.isEmpty() && sawMessages

    Column(modifier = Modifier.fillMaxSize()) {
        // Banner: back + avatar + title + count + chips + Smart chip.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            if (group != null) {
                GroupAvatar(group = group, modifier = Modifier.size(48.dp))
            } else {
                LetterAvatar(title = title, modifier = Modifier.size(48.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = totalVideoCount?.let { "$it videos" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { resetAndLoad() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                QualityFilterChips(
                    selected = currentFilter,
                    onSelect = { currentFilterOverride = it }
                )
            }
            item {
                FilterChip(
                    selected = smartOn,
                    onClick = {
                        val next = !smartOn
                        smartOverride = next
                        scope.launch { settingsStore.setSmartFilter(next) }
                    },
                    label = { Text("Smart") }
                )
            }
        }

        // Best-copy card: the selected episode's best copy, or the
        // group's best copy overall when nothing is selected yet.
        val bestGroup = selectedGroup
        if (bestGroup != null && !loading) {
            val reason = SmartFilter.bestReasonFor(bestGroup)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { sheetGroup = bestGroup },
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Best copy: ${bestGroup.best.quality.label}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                QualityPillBadge(
                                    text = "Best",
                                    containerColor = NobiGold.copy(alpha = 0.2f),
                                    contentColor = NobiGold
                                )
                            }
                            Text(
                                text = listOfNotNull(
                                    FormatUtils.formatSize(bestGroup.best.sizeBytes)
                                        .takeIf { bestGroup.best.sizeBytes > 0 },
                                    when (reason) {
                                        SmartFilter.BestReason.QUALITY -> "Best quality"
                                        SmartFilter.BestReason.SIZE -> "Largest file"
                                        SmartFilter.BestReason.NEWEST -> "Newest"
                                        SmartFilter.BestReason.PREFERRED -> "Matches your preference"
                                    }
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(onClick = { sheetGroup = bestGroup }) {
                        Text("Switch", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Episode filter header.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Episodes (${displayedVideos.size}" +
                        (totalVideoCount?.let { "/$it" } ?: "") + ")",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "Tap a video to play • Long-press to mark filler",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = hideFillers,
                    onClick = { hideFillers = !hideFillers },
                    label = { Text("Skip Fillers") }
                )
                IconButton(onClick = { sortNewest = !sortNewest }) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = if (sortNewest) "Oldest first" else "Newest first"
                    )
                }
            }
        }

        when {
            loading && allVideos.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading videos…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            firstPageError -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "Couldn't load videos",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Check your connection and try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { resetAndLoad() }) {
                            Text("Retry")
                        }
                    }
                }
            }
            showEmpty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "No videos found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "This group has no videos yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { resetAndLoad() }) {
                            Text("Refresh")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    itemsIndexed(
                        displayedVideos,
                        key = { _, item -> item.chatId to item.messageId }
                    ) { index, item ->
                        EpisodeRow(
                            item = item,
                            position = index + 1,
                            isFiller = (item.chatId to item.messageId) in fillerMarks,
                            downloadStatus = downloadById[item.chatId to item.messageId]?.status,
                            onPlay = { playVideo(item) },
                            onToggleWatched = { toggleWatched(item) },
                            onDownload = { downloadVideo(item) },
                            onToggleFiller = { toggleFiller(item) },
                            onOpenCopies = { openCopies(item) }
                        )
                    }
                    if (loading) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    // Copies sheet (state owned by this screen).
    val activeSheetGroup = sheetGroup
    if (activeSheetGroup != null) {
        CopiesSheetModal(
            group = activeSheetGroup,
            onPlayCopy = { copy ->
                sheetGroup = null
                val playlist = displayedVideos.map { shown ->
                    if (shown.chatId == activeSheetGroup.best.chatId &&
                        shown.messageId == activeSheetGroup.best.messageId
                    ) copy else shown
                }
                playVideo(copy, playlist)
            },
            onOfflineSave = { copy ->
                sheetGroup = null
                downloadVideo(copy)
            },
            onDismiss = { sheetGroup = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(
    item: VideoItem,
    position: Int,
    isFiller: Boolean,
    downloadStatus: String?,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onDownload: () -> Unit,
    onToggleFiller: () -> Unit,
    onOpenCopies: () -> Unit
) {
    // Episode number from the title; the row's position when the
    // title carries no episode number.
    val episodeNumber = SmartFilter.parseKey(item)?.episode
    val tileText = if (episodeNumber != null) "EP $episodeNumber" else "$position"
    // Caption excerpt ONLY when non-empty and different from the title.
    val excerpt = item.caption.trim().takeIf {
        it.isNotEmpty() && !it.equals(item.title.trim(), ignoreCase = true)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = onPlay,
                onLongClick = onToggleFiller
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.watched) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = if (item.watched) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = tileText,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = FormatUtils.stripLeadingEmoji(item.title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isFiller) {
                                Spacer(modifier = Modifier.width(6.dp))
                                QualityPillBadge(
                                    text = "FILLER",
                                    containerColor = Color(0xFFFF7675).copy(alpha = 0.2f),
                                    contentColor = Color(0xFFFF7675)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = listOfNotNull(
                                FormatUtils.formatDuration(item.durationSec)
                                    .takeIf { item.durationSec > 0 },
                                FormatUtils.formatDate(item.dateEpochSec)
                                    .takeIf { it.isNotEmpty() }
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (item.hiddenCopies > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${item.hiddenCopies + 1} copies",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onOpenCopies() }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleWatched) {
                        Icon(
                            imageVector = if (item.watched) Icons.Filled.CheckCircle
                            else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = "Mark watched",
                            tint = if (item.watched) NobiSuccess
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDownload) {
                        val active = downloadStatus in
                            listOf("QUEUED", "DOWNLOADING", "COMPLETED")
                        Icon(
                            imageVector = if (active) Icons.Filled.DownloadDone
                            else Icons.Filled.DownloadForOffline,
                            contentDescription = "Save offline",
                            tint = if (active) NobiSuccess
                            else MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
            if (excerpt != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
