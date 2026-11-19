package com.nobi.nobistream.ui.compose.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.nobi.nobistream.ui.compose.components.ChannelSkeleton
import com.nobi.nobistream.ui.compose.components.GroupAvatar
import com.nobi.nobistream.ui.compose.components.LetterAvatar
import com.nobi.nobistream.ui.compose.components.QualityFilterChips
import com.nobi.nobistream.ui.compose.components.QualityPillBadge
import com.nobi.nobistream.ui.compose.components.VideoThumb
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
 * v2.6.2: grouping-pipeline output, computed OFF the main thread
 * (LaunchedEffect + Dispatchers.Default) and read as State — see
 * ChannelScreen. A top-level (not local) class: local data classes
 * are not allowed in Kotlin.
 */
private data class GroupedChannelLists(
    val groups: List<EpisodeGroup>,
    val episodes: List<VideoItem>,
    val other: List<VideoItem>
)

/**
 * Channel list in the mock Detail layout: banner, Best-copy card,
 * episode rows.
 *
 * First page: an empty window (no videos AND no messages seen) is
 * retried with backoff 1.5s -> 3s -> 5s, bounded only by the 60s
 * timeout, which cancels the hung load and shows the error state +
 * Retry. v2.6.1: a window WITH messages but NO videos is NOT
 * terminal any more — on a cold sync the first window often holds
 * only text/service messages while videos sit in older windows, so
 * the loader auto-pages on until videos arrive or history is
 * exhausted; "No videos found" appears ONLY then. A cached first
 * page (<= 5 min) shows instantly and refreshes in the background.
 * Load More pages with loadVideos(chatId, fromMessageId, 50),
 * advancing on the oldest raw message (hasMore = rawCount >= 50).
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
    // v2.6.1 deep scroll: messageId of the episode to land on.
    targetMessageId: Long? = null,
    onBack: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onShowMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val groups by repository.observeGroups()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val fillerMarks by repository.observeFillerMarks()
        .collectAsStateWithLifecycle(initialValue = emptySet())
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
    // v2.6.2: the ONE true video total — the server-side search
    // count (see fetchServerCount). It is NEVER set from a history
    // page's totalCount any more: that is a locally-known message
    // total, and it froze the subtitle at "50 videos" on a cold sync.
    var serverVideoCount by remember(chatId) { mutableStateOf<Int?>(null) }
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

    // ---- First-page load: cached instant show, auto-page through
    // video-less windows, 60s timeout ----
    fun loadFirstPage() {
        loadJob = scope.launch {
            // v2.6.1 (#10): show the cached first page instantly
            // (<= 5 min old); the network load below still refreshes
            // it in the background and replaces it silently.
            repository.peekFirstPage(chatId)?.let { cached ->
                allVideos = cached.videos
                if (cached.lastMessageId > 0L) fromMessageId = cached.lastMessageId
                hasMore = cached.rawCount >= PAGE_SIZE
                sawMessages = cached.rawCount > 0
            }
            loading = true
            var backoff = FIRST_PAGE_RETRY_DELAY_MS
            var attempts = 0
            // v2.6.1 (#6): cursor for auto-paging through windows
            // that hold only text/service messages. The old code
            // treated "messages seen but NO videos" as TERMINAL
            // empty, so a cold sync showed "No videos found" until
            // a manual refresh. Now a video-less window pages on
            // until videos arrive or history is exhausted.
            var cursor = 0L
            while (true) {
                try {
                    val page = withContext(Dispatchers.IO) {
                        repository.loadVideos(chatId, cursor, PAGE_SIZE)
                    }
                    if (page.videos.isNotEmpty()) {
                        timeoutJob?.cancel()
                        if (cursor == 0L) {
                            allVideos = page.videos
                        } else {
                            // Auto-paged windows held no videos, so
                            // this is the first video-bearing page;
                            // merge with the cached list, if any.
                            val seen = allVideos.map { it.chatId to it.messageId }.toHashSet()
                            allVideos = allVideos +
                                page.videos.filter { seen.add(it.chatId to it.messageId) }
                        }
                        if (page.lastMessageId > 0L) fromMessageId = page.lastMessageId
                        hasMore = page.rawCount >= PAGE_SIZE
                        sawMessages = true
                        loading = false
                        return@launch
                    }
                    if (page.rawCount > 0) {
                        // Messages seen, no videos yet: page on.
                        sawMessages = true
                        if (page.rawCount < PAGE_SIZE || page.lastMessageId <= 0L) {
                            // History exhausted — a real empty state.
                            // (Any cached list stays visible; a fresh
                            // load that found nothing leaves allVideos
                            // as resetAndLoad left it: empty.)
                            timeoutJob?.cancel()
                            hasMore = false
                            loading = false
                            return@launch
                        }
                        if (cursor != 0L && page.lastMessageId >= cursor) {
                            // No forward progress — do not spin.
                            timeoutJob?.cancel()
                            loading = false
                            firstPageError = true
                            return@launch
                        }
                        cursor = page.lastMessageId
                        fromMessageId = cursor
                        continue
                    }
                    // rawCount == 0: nothing seen yet (cold sync) —
                    // retry with backoff until the 60s timeout.
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

    // v2.6.2: fetch the server video total in the background —
    // never blocks the first-page render, and a null (throw / 0)
    // keeps the previous value instead of showing a wrong 0. On
    // success the total is also written to Room so the Home group
    // card shows the same single true total.
    fun fetchServerCount() {
        scope.launch {
            val total = withContext(Dispatchers.IO) {
                repository.getServerVideoCount(chatId)
            }
            if (total != null) {
                serverVideoCount = total
                runCatching { repository.updateServerVideoCount(chatId, total) }
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
        fetchServerCount()
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
    // v2.6.2: the whole grouping pipeline runs OFF the main thread
    // (LaunchedEffect + Dispatchers.Default, result into State).
    // It used to run in remember() DURING composition — groupEpisodes
    // regex-parses every title+caption, so every Load More append,
    // every watched toggle (full-list map copy) and every first-page
    // replace re-parsed ~1000 captions on the UI thread and dropped
    // frames while scrolling. Composition now only reads the State
    // below; nothing here does O(n) regex work.
    var groupedLists by remember(chatId) {
        mutableStateOf(GroupedChannelLists(emptyList(), emptyList(), emptyList()))
    }
    LaunchedEffect(
        allVideos, smartOn, preferredMaxRank, currentFilter, hideFillers, fillerMarks, sortNewest
    ) {
        val computed = withContext(Dispatchers.Default) {
            val groups = if (smartOn) {
                SmartFilter.groupEpisodes(allVideos, preferredMaxRank)
            } else {
                emptyList()
            }
            var list: List<VideoItem> = if (smartOn) {
                groups.map { it.best.copy(hiddenCopies = it.hiddenCount) }
            } else {
                allVideos
            }
            if (currentFilter != VideoQuality.UNKNOWN) {
                list = list.filter { it.quality == currentFilter }
            }
            if (hideFillers) {
                list = list.filter { (it.chatId to it.messageId) !in fillerMarks }
            }
            if (!sortNewest) list = list.reversed()
            GroupedChannelLists(
                groups = groups,
                episodes = list.filter { !SmartFilter.isNonEpisode(it) },
                other = list.filter { SmartFilter.isNonEpisode(it) }
            )
        }
        groupedLists = computed
    }
    // v2.6.1 (#11): non-episode posts (news / announcements /
    // trailers ...) leave the episode rows for a separate "Other
    // videos" section at the bottom. The pre-split list keeps the
    // shared pipeline (dedup -> filter -> fillers -> sort) identical
    // for both sections.
    val currentGroups: List<EpisodeGroup> = groupedLists.groups
    val displayedVideos: List<VideoItem> = groupedLists.episodes
    val otherDisplayed: List<VideoItem> = groupedLists.other
    // v2.6.1 (#11 v): the merged list is re-deduped on every Load
    // More by construction — displayedVideos derives from allVideos
    // via groupEpisodes, whose map is keyed by episode key, so no
    // two displayed rows can share one. (A computed-but-unused
    // "guard" would be vacuous; the invariant is structural, and
    // SmartFilter.hasDuplicateEpisodeKeys exists for tests.)

    fun playVideo(item: VideoItem, playlistOverride: List<VideoItem>? = null) {
        // Episodes first, then Other videos — display order.
        PlayerPlaylist.items = playlistOverride ?: (displayedVideos + otherDisplayed)
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
                if (lastVisible != null &&
                    lastVisible >= displayedVideos.size + otherDisplayed.size - 5
                ) {
                    loadMore()
                }
            }
    }

    // v2.6.1 (#9) deep scroll: after the first load (and after
    // auto-Load-More if the target isn't loaded yet) scroll to the
    // target episode exactly once. The target may be a non-best
    // copy, so match by episode key, not by messageId alone.
    var deepScrolled by remember(chatId, targetMessageId) { mutableStateOf(false) }
    val targetKey = remember(allVideos, targetMessageId) {
        targetMessageId?.let { id ->
            allVideos.firstOrNull { it.messageId == id }?.let { SmartFilter.parseKey(it) }
        }
    }
    LaunchedEffect(displayedVideos, otherDisplayed, hasMore, loading, targetMessageId) {
        val target = targetMessageId ?: return@LaunchedEffect
        if (deepScrolled) return@LaunchedEffect
        fun matches(item: VideoItem): Boolean =
            item.messageId == target ||
                (targetKey != null && SmartFilter.parseKey(item) == targetKey)
        val episodeIndex = displayedVideos.indexOfFirst(::matches)
        if (episodeIndex >= 0) {
            listState.scrollToItem(episodeIndex)
            deepScrolled = true
            return@LaunchedEffect
        }
        val otherIndex = otherDisplayed.indexOfFirst(::matches)
        if (otherIndex >= 0) {
            // Episodes, then the "Other videos" header item, then others.
            listState.scrollToItem(displayedVideos.size + 1 + otherIndex)
            deepScrolled = true
            return@LaunchedEffect
        }
        if (hasMore && !loading) loadMore()
    }

    val group = groups.firstOrNull { it.chatId == chatId }
    val selectedGroup = selectedVideo?.let { selected ->
        currentGroups.firstOrNull {
            it.best.chatId == selected.chatId && it.best.messageId == selected.messageId
        }
    } ?: currentGroups.firstOrNull()
    val showEmpty = displayedVideos.isEmpty() && otherDisplayed.isEmpty() &&
        !loading && !firstPageError && allVideos.isEmpty() && sawMessages

    Column(
        modifier = Modifier
            .fillMaxSize()
            // v2.6.1: root background in theme colours.
            .background(MaterialTheme.colorScheme.background)
            // v2.6.2: the header starts BELOW the status bar —
            // without this the title/back/avatar sat at y=0 under
            // the status icons. (Background is applied BEFORE the
            // padding so the theme colour still covers the strip.)
            .statusBarsPadding()
    ) {
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
                    // v2.6.2: wraps to 2 lines, no ellipsis — the
                    // full channel name must show; maxLines=1 cut
                    // long names mid-word under the status bar.
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2
                )
                Text(
                    // v2.6.2: the SERVER video total only — never a
                    // history total, never an episode count. Blank
                    // until the background fetch lands.
                    text = serverVideoCount?.let { "$it videos" } ?: "",
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
        // v2.6.2: the hint is its OWN full-width row under the header
        // row — it used to sit in an un-weighted Column next to the
        // chips Row and ran UNDER the Skip Fillers button.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    // v2.6.2: the DEDUPED episode count only, NO "+"
                    // suffix ever — the stable bottom footer already
                    // signals more pages loading. (v2.6.1's "+" came
                    // from the same first-page-total confusion as
                    // "50 videos": TDLib's first-page totalCount is a
                    // locally-known message total, unreliable on a
                    // cold sync.)
                    text = "Episodes (${displayedVideos.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f)
                )
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap a video to play • Long-press to mark filler",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }

        when {
            loading && allVideos.isEmpty() -> {
                // v2.6.1: shimmer skeleton during the first load
                // (including auto-paging through video-less windows),
                // not a bare spinner.
                ChannelSkeleton()
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
                        key = { _, item -> item.chatId to item.messageId },
                        contentType = { _, _ -> "episode" }
                    ) { index, item ->
                        EpisodeRow(
                            item = item,
                            position = index + 1,
                            isFiller = (item.chatId to item.messageId) in fillerMarks,
                            downloadManager = downloadManager,
                            repository = repository,
                            onPlay = { playVideo(item) },
                            onToggleWatched = { toggleWatched(item) },
                            onDownload = { downloadVideo(item) },
                            onToggleFiller = { toggleFiller(item) },
                            onOpenCopies = { openCopies(item) }
                        )
                    }
                    // v2.6.1 (#11): non-episode posts live here, at
                    // the bottom — still playable, never deleted.
                    if (otherDisplayed.isNotEmpty()) {
                        item(contentType = "other-header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                            ) {
                                Text(
                                    text = "Other videos (${otherDisplayed.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "News, announcements and trailers — still playable",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        itemsIndexed(
                            otherDisplayed,
                            key = { _, item -> item.chatId to item.messageId },
                            contentType = { _, _ -> "other" }
                        ) { index, item ->
                            EpisodeRow(
                                item = item,
                                position = displayedVideos.size + index + 1,
                                isFiller = (item.chatId to item.messageId) in fillerMarks,
                                downloadManager = downloadManager,
                                repository = repository,
                                onPlay = { playVideo(item) },
                                onToggleWatched = { toggleWatched(item) },
                                onDownload = { downloadVideo(item) },
                                onToggleFiller = { toggleFiller(item) },
                                onOpenCopies = { openCopies(item) }
                            )
                        }
                    }
                    // v2.6.2: the footer is a STABLE fixed-height item
                    // whenever more pages may exist — the spinner only
                    // shows while a page is loading, so the list never
                    // jumps when a page lands. (The old `if (loading)`
                    // item appeared and disappeared per load.)
                    if (hasMore) {
                        item(contentType = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(72.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (loading) CircularProgressIndicator()
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
    downloadManager: DownloadManager,
    repository: NobiRepository,
    onPlay: () -> Unit,
    onToggleWatched: () -> Unit,
    onDownload: () -> Unit,
    onToggleFiller: () -> Unit,
    onOpenCopies: () -> Unit
) {
    // v2.6.1 (#8): this row collects ONLY its own download status
    // — a progress tick recomposes this row alone, not the list.
    val downloadStatus by downloadManager
        .downloadStatus(item.chatId, item.messageId)
        .collectAsStateWithLifecycle(initialValue = null)
    // Episode number from the title; the row's position when the
    // title carries no episode number.
    val episodeNumber = SmartFilter.parseKey(item)?.episode
    val badgeText = if (episodeNumber != null) "EP $episodeNumber" else "$position"
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
                    // v2.6.1 (#4): a real 16:9 thumbnail (Fit,
                    // letterboxed) instead of the old "EP NNN" text
                    // tile. The EP badge rides on the thumb so the
                    // episode number stays visible at a glance.
                    Box(
                        modifier = Modifier
                            .size(96.dp, 54.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        VideoThumb(
                            repository = repository,
                            item = item,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.65f),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badgeText,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp,
                                color = Color.White
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
