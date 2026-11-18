package com.nobi.nobistream.ui.compose.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.data.DownloadManager
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.data.SettingsStore
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.compose.CopiesSheetContent
import com.nobi.nobistream.ui.compose.components.GroupAvatar
import com.nobi.nobistream.ui.compose.components.HomeSkeleton
import com.nobi.nobistream.ui.compose.components.NobiMediaCard
import com.nobi.nobistream.ui.compose.components.QualityFilterChips
import com.nobi.nobistream.ui.compose.components.QualityPillBadge
import com.nobi.nobistream.ui.compose.components.SectionHeader
import com.nobi.nobistream.ui.compose.components.VideoThumb
import com.nobi.nobistream.ui.compose.theme.NobiGold
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val GROUPS_TIMEOUT_MS = 10_000L

/**
 * Discover tab.
 *
 * Hero = a pager of the REAL Continue Watching list
 * (observeContinueWatching, up to 10, newest first). The hero video
 * is EXCLUDED from the Continue row below it (the v9 rule). No
 * status banner, no second row.
 *
 * The copies sheet state lives in THIS screen (one place per screen).
 * PlayerPlaylist is set here, immediately before onPlayVideo — the
 * host lambda only builds the player intent and starts the player.
 */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: NobiRepository,
    downloadManager: DownloadManager,
    settingsStore: SettingsStore,
    // v2.6.1: third param is the deep-scroll target episode
    // (messageId), or null for a plain open at the top.
    onOpenChannel: (Long, String, Long?) -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onOpenContinueAll: () -> Unit,
    onAddGroup: () -> Unit,
    onShowMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val groups by repository.observeGroups().collectAsStateWithLifecycle(initialValue = emptyList())
    val continueItemsRaw by repository.observeContinueWatching()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // v2.6.1: one row per episode — the same episode watched in 2
    // qualities is 2 position rows and showed twice (S3: EPISODE-330
    // twice). Keeps the most recently watched copy per episode.
    val continueItems = remember(continueItemsRaw) {
        SmartFilter.dedupContinue(continueItemsRaw)
    }
    val lastSeenCounts by settingsStore.lastSeenVideoCounts
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val savedDefaultFilter by settingsStore.defaultFilter
        .collectAsStateWithLifecycle(initialValue = "All")

    // v9.2 failsafe: Room emits in milliseconds — 10s without an
    // emit means something is deeply wrong; show the loaded-empty
    // state instead of a stuck skeleton. A late Room emission still
    // submits the list normally.
    var groupsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        launch {
            repository.observeGroups().first()
            groupsLoaded = true
        }
        launch {
            delay(GROUPS_TIMEOUT_MS)
            groupsLoaded = true
        }
    }

    var selectedQualityOverride by remember { mutableStateOf<VideoQuality?>(null) }
    val selectedQuality = selectedQualityOverride
        ?: VideoQuality.fromLabel(savedDefaultFilter)
    var isRefreshing by remember { mutableStateOf(false) }
    var heroSheetGroup by remember { mutableStateOf<EpisodeGroup?>(null) }

    val heroItem = continueItems.firstOrNull()
    val rowItems = remember(continueItems, selectedQuality) {
        continueItems.drop(1).filter { item ->
            selectedQuality == VideoQuality.UNKNOWN || item.quality == selectedQuality
        }
    }
    val groupNameOf: (VideoItem) -> String = { item ->
        groups.firstOrNull { it.chatId == item.chatId }?.title.orEmpty()
    }

    fun playContinue(item: VideoItem, playlistOverride: List<VideoItem>? = null) {
        PlayerPlaylist.items = playlistOverride ?: continueItems
        PlayerPlaylist.chatId = item.chatId
        PlayerPlaylist.episodeGroups = emptyList()
        PlayerPlaylist.fromContinue = true
        onPlayVideo(item)
    }

    fun openHeroCopies(item: VideoItem) {
        // v2.6.1: group the RAW continue list here — the deduped
        // list has one copy per episode, so grouping it would hide
        // the very copies this sheet exists to offer.
        // The hero item is the most recently watched copy, which is
        // not necessarily the group's BEST copy — match by membership,
        // not by best == hero.
        val group = SmartFilter.groupEpisodes(continueItemsRaw).firstOrNull { g ->
            g.copies.any { it.chatId == item.chatId && it.messageId == item.messageId }
        }
        if (group != null) {
            heroSheetGroup = group
        } else {
            onShowMessage("Only one copy of this video")
        }
    }

    fun saveCopyOffline(copy: VideoItem) {
        scope.launch {
            val message = when (downloadManager.enqueue(copy)) {
                DownloadManager.EnqueueResult.QUEUED -> "Added to downloads"
                DownloadManager.EnqueueResult.ALREADY_PRESENT -> "Already in downloads"
                DownloadManager.EnqueueResult.ALREADY_COMPLETED -> "Already downloaded"
                DownloadManager.EnqueueResult.NO_SPACE -> "Not enough space on this phone"
            }
            onShowMessage(message)
        }
    }

    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            scope.launch {
                runCatching { repository.refreshGroups(groups) }
                isRefreshing = false
            }
        },
        state = pullState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!groupsLoaded) {
            // v2.6.1: shimmer skeleton, not a bare spinner.
            HomeSkeleton()
        } else if (groups.isEmpty() && continueItems.isEmpty()) {
            // Designed empty Home — never a fake title.
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No groups yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Add a Telegram group or channel to start watching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onAddGroup) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Group")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // 1. Hero Continue Watching pager.
                if (heroItem != null) {
                    item {
                        HeroPager(
                            items = continueItems,
                            repository = repository,
                            groupNameOf = groupNameOf,
                            onPlay = { playContinue(it) },
                            onOpenChannel = { item ->
                                // v2.6.1 deep scroll: land on this episode.
                                onOpenChannel(item.chatId, groupNameOf(item), item.messageId)
                            },
                            onOpenCopies = { openHeroCopies(it) },
                            onToggleBookmark = { item ->
                                scope.launch { repository.toggleBookmark(item) }
                            }
                        )
                    }
                }

                // 2. Quality filter chips (filter the Continue row).
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            QualityFilterChips(
                                selected = selectedQuality,
                                onSelect = { selectedQualityOverride = it }
                            )
                        }
                    }
                }

                // 3. Continue Watching row (hero excluded).
                if (continueItems.size > 1) {
                    item {
                        Spacer(modifier = Modifier.height(10.dp))
                        SectionHeader(
                            title = "Continue Watching",
                            subtitle = "${rowItems.size} videos",
                            actionLabel = "See All",
                            onActionClick = onOpenContinueAll
                        )
                    }
                    item {
                        if (rowItems.isEmpty()) {
                            Text(
                                text = "Nothing in this quality yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(
                                    rowItems,
                                    key = { it.chatId to it.messageId }
                                ) { item ->
                                    BookmarkedMediaCard(
                                        item = item,
                                        repository = repository,
                                        subtitle = continueMeta(item, groupNameOf(item)),
                                        onCardClick = { playContinue(item) },
                                        onToggleBookmark = {
                                            scope.launch { repository.toggleBookmark(item) }
                                        },
                                        modifier = Modifier.width(172.dp),
                                        // v2.6.1: tapping the "group •
                                        // time left" subtitle opens the
                                        // channel scrolled to this episode.
                                        onSubtitleClick = {
                                            onOpenChannel(
                                                item.chatId,
                                                groupNameOf(item),
                                                item.messageId
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Your Groups list.
                if (groups.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        SectionHeader(title = "Your Groups")
                    }
                    items(groups, key = { it.chatId }) { group ->
                        GroupRow(
                            group = group,
                            isNew = lastSeenCounts[group.chatId]
                                ?.let { it < group.videoCount } == true,
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        settingsStore.setLastSeenVideoCount(
                                            group.chatId, group.videoCount
                                        )
                                    }
                                }
                                onOpenChannel(group.chatId, group.title, null)
                            }
                        )
                    }
                }
            }
        }
    }

    // Copies sheet for the hero page (state owned by this screen).
    val sheetGroup = heroSheetGroup
    if (sheetGroup != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { heroSheetGroup = null },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            CopiesSheetContent(
                group = sheetGroup,
                onPlayCopy = { copy ->
                    heroSheetGroup = null
                    // Replace the episode's entry (same episode key)
                    // with the picked copy; standalone items match
                    // only by exact message.
                    val copyKey = SmartFilter.parseKey(copy)
                    val playlist = continueItems.map { shown ->
                        val sameEpisode = if (copyKey != null) {
                            shown.chatId == copy.chatId &&
                                SmartFilter.parseKey(shown) == copyKey
                        } else {
                            shown.chatId == copy.chatId &&
                                shown.messageId == copy.messageId
                        }
                        if (sameEpisode) copy else shown
                    }
                    playContinue(copy, playlist)
                },
                onOfflineSave = { copy ->
                    heroSheetGroup = null
                    saveCopyOffline(copy)
                },
                onDismiss = { heroSheetGroup = null }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroPager(
    items: List<VideoItem>,
    repository: NobiRepository,
    groupNameOf: (VideoItem) -> String,
    onPlay: (VideoItem) -> Unit,
    onOpenChannel: (VideoItem) -> Unit,
    onOpenCopies: (VideoItem) -> Unit,
    onToggleBookmark: (VideoItem) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(370.dp)
        ) { page ->
            val item = items[page]
            HeroPage(
                item = item,
                pageIndex = page,
                repository = repository,
                groupName = groupNameOf(item),
                onPlay = { onPlay(item) },
                onOpenChannel = { onOpenChannel(item) },
                onOpenCopies = { onOpenCopies(item) },
                onToggleBookmark = { onToggleBookmark(item) }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            repeat(items.size) { index ->
                val active = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .height(6.dp)
                        .width(if (active) 24.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                )
            }
        }
    }
}

@Composable
private fun HeroPage(
    item: VideoItem,
    pageIndex: Int,
    repository: NobiRepository,
    groupName: String,
    onPlay: () -> Unit,
    onOpenChannel: () -> Unit,
    onOpenCopies: () -> Unit,
    onToggleBookmark: () -> Unit
) {
    val isBookmarked by repository.isBookmarked(item.chatId, item.messageId)
        .collectAsStateWithLifecycle(initialValue = false)
    val watchedPercent = (item.progressFraction * 100).toInt()
    val metaLine = listOfNotNull(
        FormatUtils.formatDuration(item.durationSec).takeIf { item.durationSec > 0 },
        item.quality.label.takeIf { it.isNotEmpty() },
        if (watchedPercent > 0) "$watchedPercent% watched" else null
    ).joinToString(" • ")

    Card(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .clickable { onPlay() },
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            VideoThumb(
                repository = repository,
                item = item,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityPillBadge(
                            text = "SPOTLIGHT #${pageIndex + 1}",
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            contentColor = Color.White
                        )
                        QualityPillBadge(
                            text = item.quality.label,
                            containerColor = Color.Black.copy(alpha = 0.65f),
                            contentColor = NobiGold
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                ) {
                    if (groupName.isNotEmpty()) {
                        Text(
                            text = groupName,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = FormatUtils.stripLeadingEmoji(item.title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (metaLine.isNotEmpty()) {
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.progressFraction > 0f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { item.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.25f)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onOpenChannel,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                Icons.Filled.ViewList,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Episodes & Info", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalIconButton(
                            onClick = onOpenCopies,
                            shape = RoundedCornerShape(14.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.18f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copies")
                        }
                        FilledTonalIconButton(
                            onClick = onToggleBookmark,
                            shape = RoundedCornerShape(14.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color.White.copy(alpha = 0.18f),
                                contentColor = if (isBookmarked) NobiGold else Color.White
                            )
                        ) {
                            Icon(
                                if (isBookmarked) Icons.Filled.BookmarkAdded
                                else Icons.Filled.BookmarkAdd,
                                contentDescription = "Save to Vault"
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "Group name • 12:34 left" under a Continue Watching card. */
private fun continueMeta(item: VideoItem, groupName: String): String {
    val remaining = if (item.durationSec > 0) {
        val left = (item.durationSec - item.progressSec).coerceAtLeast(0L)
        "${FormatUtils.formatDuration(left.toInt())} left"
    } else {
        ""
    }
    return listOf(groupName, remaining).filter { it.isNotEmpty() }.joinToString(" • ")
}

@Composable
private fun GroupRow(
    group: GroupItem,
    isNew: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GroupAvatar(group = group, modifier = Modifier.size(56.dp))
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isNew) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        group.username.takeIf { it.isNotBlank() }?.let { "@$it" },
                        "${group.videoCount} videos"
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * NobiMediaCard with its bookmark state collected from the real
 * bookmark Flow — one small collector per card.
 */
@Composable
fun BookmarkedMediaCard(
    item: VideoItem,
    repository: NobiRepository,
    subtitle: String,
    onCardClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    onSubtitleClick: (() -> Unit)? = null
) {
    val isBookmarked by repository.isBookmarked(item.chatId, item.messageId)
        .collectAsStateWithLifecycle(initialValue = false)
    NobiMediaCard(
        item = item,
        repository = repository,
        isBookmarked = isBookmarked,
        subtitle = subtitle,
        onCardClick = onCardClick,
        onBookmarkToggle = onToggleBookmark,
        modifier = modifier,
        onSubtitleClick = onSubtitleClick
    )
}
