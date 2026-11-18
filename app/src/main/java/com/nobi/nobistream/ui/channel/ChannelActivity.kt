package com.nobi.nobistream.ui.channel

import android.animation.ObjectAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.GridLayout
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.databinding.ActivityChannelBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.CopiesSheet
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.components.SortSheet
import com.nobi.nobistream.ui.player.PlayerActivity
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One group/channel: its videos in the SAME boxless VideoCard grid as the
 * Home library (pixel spec §03/§05 — one card style, never a second
 * variant). Quality filter chips, search, sort, refresh — all v2 behaviour,
 * redesigned. Filter changes only re-run DiffUtil (no full reload, scroll
 * position preserved). First-page failures show an error state with Retry
 * and a generic message — never a technical exception string.
 */
class ChannelActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_TITLE = "title"
        private const val PAGE_SIZE = 50
        /** v4-fb4: first-page retries while TDLib syncs the chat. */
        private const val MAX_FIRST_PAGE_ATTEMPTS = 3
        private const val FIRST_PAGE_RETRY_DELAY_MS = 1500L
    }

    private lateinit var binding: ActivityChannelBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var adapter: VideoAdapter

    private var chatId: Long = 0L
    private val allVideos = mutableListOf<VideoItem>()
    private var displayedVideos: List<VideoItem> = emptyList()
    private var fromMessageId: Long = 0L
    private var loading = false
    private var hasMore = true
    private var currentFilter = "All"
    private var query = ""
    private var sortNewest = true
    /**
     * v5 Smart Filter: ON by default (SettingsStore default). When
     * ON, applyFilter() dedups allVideos into episodes first and the
     * quality chip then filters the deduped list. currentGroups is
     * the grouping behind the displayed list — the copies picker
     * looks a tapped card up in it.
     */
    private var smartOn = true
    private var currentGroups: List<EpisodeGroup> = emptyList()
    private var firstPageAttempts = 0
    private var firstPageRetrying = false
    /** v8: the 1200ms diagonal shimmer sweep animator (see showSkeleton). */
    private var shimmerAnimator: ObjectAnimator? = null
    /** v8 toolbar subtitle: total videos from the first page. */
    private var totalVideoCount: Int? = null
    /** v8 toolbar subtitle: group's last-updated time (0 = unknown). */
    private var groupUpdatedEpochSec: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
        enableEdgeToEdge()
        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbar.setNavigationOnClickListener { finish() }
        // v9.1 #32: refresh lives in the toolbar menu now — the
        // search row below is full-width.
        binding.toolbar.inflateMenu(R.menu.channel_menu)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == R.id.action_refresh) {
                resetAndLoad()
                true
            } else {
                false
            }
        }
        adapter = VideoAdapter(
            onClick = { item, thumb -> openPlayer(item, thumb) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            },
            onCopiesClick = { item, thumb -> showCopies(item, thumb) },
            // v8: free cache read — shows the cached badge on fully
            // downloaded videos. Never starts a download.
            cachedProvider = { item ->
                locator.gateway.getCachedFileState(item.fileId)?.isComplete == true
            }
        )
        val spanCount = gridSpanCount()
        layoutSkeletonGrid(spanCount)
        binding.videosRecycler.layoutManager = GridLayoutManager(this, spanCount)
        binding.videosRecycler.adapter = adapter
        binding.videosRecycler.itemAnimator = SlideInUpAnimator()
        binding.videosRecycler.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.grid_gap_horizontal),
                resources.getDimensionPixelSize(R.dimen.grid_gap_vertical)
            )
        )
        binding.videosRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val manager = recyclerView.layoutManager as GridLayoutManager
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - 5) {
                    loadPage()
                }
            }
        })
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chip480 -> "480p"
                R.id.chip720 -> "720p"
                R.id.chip1080 -> "1080p"
                R.id.chip4k -> "4K"
                else -> "All"
            }
            applyFilter()
        }
        // v5 Smart Filter toggle — independent of the quality chips
        // (it is NOT in the single-selection ChipGroup). Persists to
        // Settings, so the switch there and this chip stay in sync.
        binding.chipSmart.setOnCheckedChangeListener { _, checked ->
            smartOn = checked
            lifecycleScope.launch {
                repository.settingsStore.setSmartFilter(checked)
            }
            applyFilter()
        }
        binding.searchEdit.addTextChangedListener { text ->
            query = text?.toString()?.trim().orEmpty()
            applyFilter()
        }
        // v8: sort is a bottom sheet with exactly 2 radio rows
        // (Newest first / Oldest first) — the old toggle + Snackbar
        // echo is gone. v9.1 #12: the button shows the current sort
        // ("Sort: Newest") with an icon per option.
        binding.sortButton.setOnClickListener {
            SortSheet.show(
                this,
                getString(R.string.channel_sort),
                listOf(
                    getString(R.string.channel_sort_newest),
                    getString(R.string.channel_sort_oldest)
                ),
                if (sortNewest) 0 else 1,
                onPick = { index ->
                    sortNewest = index == 0
                    updateSortButton()
                    applyFilter()
                },
                icons = listOf(
                    R.drawable.ic_arrow_downward,
                    R.drawable.ic_arrow_upward
                )
            )
        }
        updateSortButton()
        binding.emptyState.apply {
            setIcon(R.drawable.ic_search)
            setTitle(getString(R.string.channel_no_videos))
            setBody(getString(R.string.channel_no_videos_body))
            setAction(getString(R.string.channel_refresh)) { resetAndLoad() }
        }
        binding.errorState.apply {
            setTitle(getString(R.string.channel_load_error_title))
            setBody(getString(R.string.channel_load_error_body))
            setRetry { resetAndLoad() }
        }
        lifecycleScope.launch {
            val savedSmart = repository.settingsStore.smartFilter.first()
            if (savedSmart != smartOn) {
                smartOn = savedSmart
                binding.chipSmart.isChecked = savedSmart
            }
            val savedFilter = repository.settingsStore.defaultFilter.first()
            if (savedFilter != currentFilter) {
                currentFilter = savedFilter
                val chipId = when (savedFilter) {
                    "480p" -> R.id.chip480
                    "720p" -> R.id.chip720
                    "1080p" -> R.id.chip1080
                    "4K" -> R.id.chip4k
                    else -> R.id.chipAll
                }
                binding.filterChips.check(chipId)
            }
            resetAndLoad()
        }
        // v8 toolbar subtitle: the group's last-updated time, from
        // the stored groups (never invented — 0 means "unknown", and
        // the subtitle then shows the count only).
        lifecycleScope.launch {
            val group = repository.observeGroups().first()
                .firstOrNull { it.chatId == chatId }
            groupUpdatedEpochSec = group?.lastUpdatedEpochSec ?: 0L
            updateToolbarSubtitle()
        }
    }

    override fun onDestroy() {
        // v8: the shimmer animator must die with the activity —
        // the old pulse animator leaked here (never cancelled).
        stopShimmer()
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            repository.settingsStore.setLastChat(chatId, currentFilter)
        }
    }

    /**
     * v8 responsive grid (spec §2C): 1 column full-width 16:9 on
     * mobile portrait (<600dp), 2 columns on 600–839dp, 3 columns
     * on >=840dp. The old 2/3/4 grid made phone thumbnails small
     * and titles unreadable.
     */
    private fun gridSpanCount(): Int {
        return when (resources.configuration.screenWidthDp) {
            in 0..599 -> 1
            in 600..839 -> 2
            else -> 3
        }
    }

    /** v8 toolbar subtitle: "N videos • Updated 12 Nov" (count only when the date is unknown). */
    private fun updateToolbarSubtitle() {
        val count = totalVideoCount ?: return
        val date = FormatUtils.formatDate(groupUpdatedEpochSec)
        binding.toolbar.subtitle = if (date.isEmpty()) {
            getString(R.string.home_videos_count, count)
        } else {
            "${getString(R.string.home_videos_count, count)} • " +
                getString(R.string.home_group_updated, date)
        }
    }

    /**
     * v8 skeleton grid: columnCount follows the feed span (1 on
     * mobile — never a hardcoded 2), and with a single column the
     * skeleton items span the full width like the real cards do
     * (the decoration adds no side gaps at span 1, so the 6dp
     * half-gap side margins would otherwise leave the skeleton
     * 12dp narrower than the loaded cards — a layout shift).
     */
    private fun layoutSkeletonGrid(spanCount: Int) {
        val grid = binding.skeletonGrid
        grid.columnCount = spanCount
        if (spanCount == 1) {
            for (i in 0 until grid.childCount) {
                val child = grid.getChildAt(i)
                val params = child.layoutParams as GridLayout.LayoutParams
                params.leftMargin = 0
                params.rightMargin = 0
                child.layoutParams = params
            }
        }
    }

    private fun resetAndLoad() {
        allVideos.clear()
        fromMessageId = 0L
        hasMore = true
        firstPageAttempts = 0
        firstPageRetrying = false
        adapter.submitList(emptyList())
        binding.errorState.visibility = View.GONE
        loadPage()
    }

    private fun loadPage() {
        if (loading || !hasMore) return
        loading = true
        val firstPage = allVideos.isEmpty()
        if (firstPage) showSkeleton(true) else binding.loadMoreFooter.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val page = repository.loadVideos(chatId, fromMessageId, PAGE_SIZE)
                withContext(Dispatchers.Main) {
                    // v4-fb4: the FIRST GetChatHistory for a chat whose
                    // history isn't synced yet can SUCCEED with an empty
                    // window (rawCount==0) — the old code then showed
                    // "No videos found" and set hasMore=false, so the
                    // list looked empty until Refresh was tapped. An
                    // empty first page is not proof the channel is
                    // empty: retry a few times, shimmer still showing,
                    // before concluding anything.
                    if (firstPage && page.videos.isEmpty() && page.rawCount == 0 &&
                        firstPageAttempts < MAX_FIRST_PAGE_ATTEMPTS
                    ) {
                        firstPageAttempts++
                        firstPageRetrying = true
                        loading = false
                        lifecycleScope.launch {
                            delay(FIRST_PAGE_RETRY_DELAY_MS)
                            loadPage()
                        }
                        return@withContext
                    }
                    firstPageRetrying = false
                    loading = false
                    showSkeleton(false)
                    binding.loadMoreFooter.visibility = View.GONE
                    // v8: first page carries the channel total — the
                    // toolbar subtitle ("N videos • Updated 12 Nov").
                    if (firstPage) {
                        totalVideoCount = page.totalCount
                        updateToolbarSubtitle()
                    }
                    if (page.videos.isNotEmpty()) {
                        allVideos.addAll(page.videos)
                    }
                    // v4: advance on the oldest RAW message, not the
                    // last video — a window can hold few videos (text
                    // posts between them). hasMore comes from the raw
                    // window too: a full 50-message window means older
                    // messages may still exist, even when it held 0
                    // videos. The old video-count check stopped the
                    // list after the first thin window.
                    if (page.lastMessageId > 0L) fromMessageId = page.lastMessageId
                    hasMore = page.rawCount >= PAGE_SIZE
                    applyFilter()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // v4-fb4: a first-page failure can also just mean
                    // the chat isn't ready yet — retry before showing
                    // the error state, same as the empty-window case.
                    if (firstPage && firstPageAttempts < MAX_FIRST_PAGE_ATTEMPTS) {
                        firstPageAttempts++
                        firstPageRetrying = true
                        loading = false
                        lifecycleScope.launch {
                            delay(FIRST_PAGE_RETRY_DELAY_MS)
                            loadPage()
                        }
                        return@withContext
                    }
                    firstPageRetrying = false
                    loading = false
                    showSkeleton(false)
                    binding.loadMoreFooter.visibility = View.GONE
                    if (firstPage) {
                        // Error state with Retry; the technical message
                        // stays out of the UI.
                        binding.videosRecycler.visibility = View.GONE
                        binding.emptyState.visibility = View.GONE
                        binding.errorState.visibility = View.VISIBLE
                    } else {
                        Snackbar.make(
                            binding.root,
                            R.string.channel_load_more_error,
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * v8 skeleton: a 1200ms diagonal shimmer SWEEP (a translucent
     * band translating across the skeleton container), not the old
     * alpha pulse. The animator is cancelled whenever the skeleton
     * hides AND in onDestroy — the old code never cancelled it in
     * onDestroy, so it leaked on Channel.
     */
    private fun showSkeleton(show: Boolean) {
        if (show) {
            binding.skeletonContainer.visibility = View.VISIBLE
            binding.videosRecycler.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
            binding.errorState.visibility = View.GONE
            startShimmer()
        } else {
            stopShimmer()
            binding.skeletonContainer.visibility = View.GONE
            binding.videosRecycler.visibility = View.VISIBLE
        }
    }

    private fun startShimmer() {
        stopShimmer()
        val sweep = binding.shimmerSweep
        sweep.post {
            val width = binding.skeletonContainer.width
            if (width == 0) return@post
            shimmerAnimator = ObjectAnimator.ofFloat(
                sweep, View.TRANSLATION_X,
                -sweep.width.toFloat(), width.toFloat()
            ).apply {
                duration = 1200L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
    }

    private fun applyFilter() {
        var list = allVideos.toList()
        // v5 Smart Filter — dedup FIRST, then the quality chip
        // filters the deduped list. So with the 1080p chip on, only
        // episodes whose BEST copy is 1080p show; an episode whose
        // best copy is 4K does not show under 1080p. Grouping runs
        // over ALL loaded videos (not just this page), and later
        // pages re-run it, so a duplicate merged on page 1 never
        // reappears when page 2 loads.
        if (smartOn) {
            currentGroups = SmartFilter.groupEpisodes(list)
            list = currentGroups.map { it.best.copy(hiddenCopies = it.hiddenCount) }
        } else {
            currentGroups = emptyList()
        }
        if (currentFilter != "All") {
            list = list.filter { it.quality.label == currentFilter }
        }
        if (query.isNotEmpty()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.caption.contains(query, ignoreCase = true)
            }
        }
        if (!sortNewest) list = list.reversed()
        displayedVideos = list
        // DiffUtil only — no full reload, scroll position preserved.
        adapter.submitList(list)
        // v4-fb4: empty only when a load SUCCEEDED and the list is
        // genuinely empty — never while loading or while a first-page
        // retry is still in flight (the shimmer owns that state).
        val empty = list.isEmpty() && !loading && !firstPageRetrying
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.videosRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) binding.errorState.visibility = View.GONE
    }

    private fun openPlayer(item: VideoItem, thumb: ImageView) {
        openPlayer(item, thumb, null)
    }

    /**
     * v5: [playlistOverride] is the copies-picker path — the picked
     * copy replaces its episode's best copy in the playlist, so
     * Next/Previous keep walking the same (deduped) list and the
     * counter stays right. Null = play from the displayed list.
     */
    private fun openPlayer(
        item: VideoItem,
        thumb: ImageView,
        playlistOverride: List<VideoItem>?
    ) {
        PlayerPlaylist.items = playlistOverride ?: displayedVideos
        PlayerPlaylist.chatId = chatId
        // v8 §4.4: stash the channel's episode grouping so the
        // player's Copies pill can list the real copies — the
        // playlist itself holds only best copies when Smart
        // Filter is on. (When Smart is off currentGroups is
        // empty and the pill falls back to its rebuild.)
        PlayerPlaylist.episodeGroups = currentGroups
        PlayerPlaylist.fromContinue = false
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        val options = ActivityOptions.makeSceneTransitionAnimation(
            this, thumb, "video_thumb_${item.messageId}"
        )
        startActivity(intent, options.toBundle())
    }

    /** v9.1 #12: the sort button always names the current sort. */
    private fun updateSortButton() {
        val label = getString(
            if (sortNewest) R.string.channel_sort_newest else R.string.channel_sort_oldest
        )
        binding.sortButton.text = getString(R.string.channel_sort_button, label)
        binding.sortButton.contentDescription = label
    }

    /** v5 Smart Filter: "N copies" badge → pick any copy of the episode. */
    private fun showCopies(best: VideoItem, thumb: ImageView) {
        val group = currentGroups.firstOrNull {
            it.best.chatId == best.chatId && it.best.messageId == best.messageId
        } ?: return
        CopiesSheet.show(
            this, group,
            onPick = { copy ->
                val playlist = displayedVideos.map { shown ->
                    if (shown.chatId == best.chatId && shown.messageId == best.messageId) copy
                    else shown
                }
                openPlayer(copy, thumb, playlist)
            },
            // v9.1 #15: group name per row = this channel's title.
            groupNameProvider = { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
        )
    }
}
