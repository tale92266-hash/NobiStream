package com.nobi.nobistream.ui.home

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.PopupMenu
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivityMainBinding
import com.nobi.nobistream.databinding.LayoutBottomSheetAddChannelBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.CopiesSheet
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.channel.ChannelActivity
import com.nobi.nobistream.ui.channel.GridSpacingItemDecoration
import com.nobi.nobistream.ui.channel.VideoAdapter
import com.nobi.nobistream.ui.channel.VideoRow
import com.nobi.nobistream.ui.components.BottomNavHelper
import com.nobi.nobistream.ui.components.ConfirmSheet
import com.nobi.nobistream.ui.components.InfoSheet
import com.nobi.nobistream.ui.components.NavTab
import com.nobi.nobistream.ui.components.Shimmer
import com.nobi.nobistream.ui.components.SortSheet
import com.nobi.nobistream.ui.login.LoginActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import jp.wasabeef.recyclerview.animators.FadeInAnimator
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v8 Home: bottom navigation (Home / Search / Library / Settings)
 * + cleaned top bar (brand + Add Group + refresh; Search and
 * Settings live in the bottom nav). Two tab containers share one
 * FrameLayout:
 *
 * - HOME: Hero Spotlight (the most recent Continue Watching video,
 *   EXCLUDED from the row below — v4 lesson, hero + row never
 *   duplicate) + Continue Watching row + Your Groups.
 * - LIBRARY: the merged all-groups library in a 1-column grid
 *   (2 on >=600dp, 3 on >=840dp), quality chips + Smart toggle +
 *   sort sheet, footer spinner only (no Load More button).
 *
 * Refresh re-fetches each group's title/photo/count from Telegram
 * (only what TDLib returns — no invented statistics) and reloads
 * the library. Library paging loads one page per group at a time
 * and merges by date; scrolling near the bottom loads more.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Which tab to show (TAB_HOME / TAB_LIBRARY). */
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = "home"
        const val TAB_LIBRARY = "library"

        /** v8: prefill the Add Group sheet with a clipboard link
         * (from the Search paste-link chip). */
        const val EXTRA_ADD_GROUP_LINK = "add_group_link"

        /** v9.2: Room emits in milliseconds — 10s without an emit
         * means something is deeply wrong; treat the list as loaded
         * so the Home skeleton can never sit over the content. */
        private const val GROUPS_TIMEOUT_MS = 10_000L
        /** v9.2: a TDLib first page may legitimately take tens of
         * seconds on a slow network; 60s matches the app's existing
         * 60s LauncherActivity auth wait. */
        private const val FIRST_PAGE_TIMEOUT_MS = 60_000L
    }

    private lateinit var binding: ActivityMainBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var continueAdapter: ContinueAdapter
    private lateinit var libraryAdapter: VideoAdapter
    private var continueItems: List<VideoItem> = emptyList()
    private var groups: List<GroupItem> = emptyList()
    private var redirectDone = false
    private var addSheet: BottomSheetDialog? = null
    private var currentTab = TAB_HOME
    private var groupsLoaded = false
    /** v8 Hero Spotlight item (the row's first item, excluded). */
    private var heroItem: VideoItem? = null

    // ---- Your Library state ----
    private val allLibrary = mutableListOf<VideoItem>()
    private var displayedLibrary: List<VideoItem> = emptyList()
    private var libraryFilter = "All"
    private var librarySort = LibrarySort.NEWEST
    /** v5 Smart Filter (same semantics as ChannelActivity). */
    private var librarySmartOn = true
    private var libraryGroups: List<EpisodeGroup> = emptyList()
    private var libraryCursors: Map<Long, Long> = emptyMap()
    private var libraryHasMore = true
    private var libraryLoading = false
    private var libraryLoaded = false
    private var libraryGroupsSignature = ""
    private var libraryFirstPageFailed = false
    private var refreshing = false
    /** v9.2: the in-flight library page load, so the first-page
     * timeout can cancel a hung TDLib call. */
    private var libraryLoadJob: Job? = null
    /** v9.2: the first-page timeout job (cancelled on any terminal
     * outcome); null once the first page has loaded or failed. */
    private var libraryTimeoutJob: Job? = null
    /** v9.2: true after a first-page timeout until an explicit
     * Retry — background re-entry must not restart the load. */
    private var libraryTimedOut = false
    /** v9.1 #24: the last library load error, for the Details action. */
    private var libraryLastError: String? = null
    /** v9.1 #19: last-seen video count per group (NEW dot source). */
    private var lastSeenCounts: Map<Long, Int> = emptyMap()
    /** v9.1 #4: shimmer animators for the Home / Library skeletons. */
    private var homeShimmerAnimator: android.animation.ObjectAnimator? = null
    private var libraryShimmerAnimator: android.animation.ObjectAnimator? = null
    /** v9.1 #9: hero shimmer animator (stopped when the thumb lands). */
    private var heroShimmerAnimator: android.animation.ObjectAnimator? = null

    private enum class LibrarySort {
        NEWEST, OLDEST, TITLE_AZ, TITLE_ZA, LONGEST, LARGEST
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (locator.gateway.authState.value != AuthState.READY) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        binding.homeRefreshButton.setOnClickListener { refreshGroups() }
        // v9.1 #6: pull-to-refresh on both tabs (same refreshGroups()).
        binding.homeSwipeRefresh.setOnRefreshListener { refreshGroups() }
        binding.librarySwipeRefresh.setOnRefreshListener { refreshGroups() }
        binding.continueSeeAll.setOnClickListener {
            startActivity(Intent(this, ContinueAllActivity::class.java))
        }
        binding.librarySortButton.setOnClickListener { showSortSheet() }
        binding.heroCard.setOnClickListener { heroItem?.let { openContinue(it, binding.heroThumb) } }
        binding.heroResumeButton.setOnClickListener { heroItem?.let { openContinue(it, binding.heroThumb) } }
        binding.heroMore.setOnClickListener { heroItem?.let { showHeroMenu(it) } }
        groupAdapter = GroupAdapter(
            onClick = { group -> openChannel(group) },
            onLongClick = { group, anchor -> showGroupMenu(group, anchor) },
            // v9.1 #19: NEW dot when the count grew since the last open.
            isNewProvider = { group ->
                lastSeenCounts[group.chatId]?.let { it < group.videoCount } == true
            }
        )
        binding.groupsRecycler.layoutManager = LinearLayoutManager(this)
        binding.groupsRecycler.adapter = groupAdapter
        binding.groupsRecycler.itemAnimator = SlideInUpAnimator()
        continueAdapter = ContinueAdapter(
            onClick = { item, thumb -> openContinue(item, thumb) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            },
            metaProvider = { item -> continueMeta(item) }
        )
        binding.continueRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.continueRecycler.adapter = continueAdapter
        binding.continueRecycler.itemAnimator = FadeInAnimator()
        // ---- Your Library grid (same VideoCard as Channel Detail) ----
        libraryAdapter = VideoAdapter(
            onClick = { item, thumb -> openLibraryPlayer(item, thumb) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            },
            onCopiesClick = { item, thumb -> showLibraryCopies(item, thumb) },
            // v9.1 #8: library cards lead with the channel name (2-line meta).
            showChannelName = true,
            channelNameProvider = { item ->
                groups.firstOrNull { it.chatId == item.chatId }?.title.orEmpty()
            }
        )
        val spanCount = gridSpanCount()
        binding.libraryRecycler.layoutManager = GridLayoutManager(this, spanCount).apply {
            // v9.1 #8: section headers span the full grid width.
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (libraryAdapter.isHeader(position)) spanCount else 1
                }
            }
        }
        binding.libraryRecycler.adapter = libraryAdapter
        binding.libraryRecycler.itemAnimator = SlideInUpAnimator()
        binding.libraryRecycler.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.grid_gap_horizontal),
                resources.getDimensionPixelSize(R.dimen.grid_gap_vertical),
                isFullSpan = { position -> libraryAdapter.isHeader(position) }
            )
        )
        binding.libraryFilterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            libraryFilter = when (checkedIds.firstOrNull()) {
                R.id.libChip480 -> "480p"
                R.id.libChip720 -> "720p"
                R.id.libChip1080 -> "1080p"
                R.id.libChip4k -> "4K"
                else -> "All"
            }
            applyLibraryFilter()
        }
        // v5 Smart Filter toggle (see ChannelActivity).
        // v9.1 #35: the chip uses the Smart toggle style (outline
        // when ON, quiet surface when OFF) — see activity_main.xml.
        binding.libChipSmart.setOnCheckedChangeListener { _, checked ->
            librarySmartOn = checked
            lifecycleScope.launch {
                repository.settingsStore.setSmartFilter(checked)
            }
            applyLibraryFilter()
        }
        // v8: the Library lives in its own tab container — the
        // auto-load trigger moves from the Home scroll to it.
        binding.libraryContainer.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
                // Only while the user scrolls down and nears the bottom.
                if (scrollY <= oldScrollY) return@OnScrollChangeListener
                val child = v.getChildAt(0) ?: return@OnScrollChangeListener
                if (scrollY + v.height >= child.height - 600) loadLibraryPage()
            }
        )
        binding.libraryEmptyState.apply {
            setIcon(R.drawable.ic_search)
            setTitle(getString(R.string.home_library_empty))
            setBody(getString(R.string.home_library_empty_body))
            // v9.1 #24: the empty library offers the fix — add a group.
            setAction(getString(R.string.home_add_group)) { showAddChannelSheet() }
        }
        binding.libraryErrorState.apply {
            setTitle(getString(R.string.home_library_error_title))
            setBody(getString(R.string.home_library_error_body))
            setRetry { resetAndLoadLibrary() }
            // v9.1 #24: Details shows the error + the crash-log path.
            setDetails(getString(R.string.common_details)) { showLibraryErrorDetails() }
        }
        binding.addGroupButton.setOnClickListener { showAddChannelSheet() }
        binding.emptyState.apply {
            setIcon(R.drawable.ic_add)
            setTitle(getString(R.string.home_no_groups))
            setBody(getString(R.string.home_no_groups_body))
            setAction(getString(R.string.home_add_group)) { showAddChannelSheet() }
        }
        // v9.1 #4: skeleton grids (same 1-column margin fix as
        // ChannelActivity — a single column must not double the
        // outer margin).
        layoutSkeletonGrid(binding.homeSkeletonGrid, 1)
        layoutSkeletonGrid(binding.librarySkeletonGrid, gridSpanCount())
        // v9: quiet-state Add Group button (shown when 1–2 groups exist).
        binding.homeQuietAddButton.setOnClickListener { showAddChannelSheet() }
        // v8 bottom navigation. The starting tab comes from the
        // intent (Search/Settings navigate back with TAB_LIBRARY);
        // the paste-link chip opens the Add Group sheet prefilled.
        val startTab = intent.getStringExtra(EXTRA_TAB)
            ?.takeIf { it == TAB_LIBRARY } ?: TAB_HOME
        setupBottomNav(if (startTab == TAB_LIBRARY) NavTab.LIBRARY else NavTab.HOME)
        showTab(startTab)
        intent.getStringExtra(EXTRA_ADD_GROUP_LINK)?.let { showAddChannelSheet(it) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeGroups().collect { items ->
                    groups = items
                    groupAdapter.submitList(items)
                    groupsLoaded = true
                    updateSkeletons()
                    // v9: quiet state under "Your Groups" when the
                    // list is short (1–2 groups). Zero groups is
                    // covered by the full-screen empty state instead.
                    binding.homeQuietState.visibility =
                        if (items.size in 1..2) View.VISIBLE else View.GONE
                    // Tab containers: exactly one visible; the empty
                    // state covers both tabs when there are no groups.
                    showTab(currentTab)
                    // Load the library once per set of groups; a group
                    // added/removed changes the signature and reloads.
                    // Count/title refreshes (same chatIds) do not.
                    val signature = items.map { it.chatId }.sorted().joinToString(",")
                    if (!items.isEmpty() && signature != libraryGroupsSignature) {
                        libraryGroupsSignature = signature
                        resetAndLoadLibrary()
                    } else if (items.isEmpty()) {
                        libraryGroupsSignature = ""
                        libraryLoaded = false
                        allLibrary.clear()
                        libraryAdapter.submitVideos(emptyList())
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeContinueWatching().collect { items ->
                    continueItems = items
                    // v8 Hero Spotlight: the most recent in-progress
                    // episode is the hero; it is EXCLUDED from the
                    // row below (v4 lesson — hero + row must never
                    // show the same video twice).
                    val hero = items.firstOrNull()
                    heroItem = hero
                    continueAdapter.submitList(items.drop(1))
                    val hasRow = items.size > 1
                    binding.heroSection.visibility =
                        if (hero != null) View.VISIBLE else View.GONE
                    if (hero != null) bindHero(hero)
                    binding.continueHeader.visibility =
                        if (hasRow) View.VISIBLE else View.GONE
                    binding.continueRecycler.visibility =
                        if (hasRow) View.VISIBLE else View.GONE
                    // v9.1 #27: the header carries the row count
                    // ("Continue Watching • 12").
                    if (hasRow) {
                        binding.continueTitle.text = getString(
                            R.string.home_continue_watching_count, items.size - 1
                        )
                    }
                    // v2: one-time redirect to the last-watched episode.
                    // Only on a fresh start (not rotation / return from player).
                    // The redirect target is the row's first item — the
                    // hero card it used to come from is gone.
                    if (!redirectDone && savedInstanceState == null) {
                        redirectDone = true
                        val openLast = repository.settingsStore.openLastEpisode.first()
                        val last = items.firstOrNull()
                        if (openLast && last != null) openContinue(last)
                    }
                }
            }
        }
        lifecycleScope.launch {
            val savedSmart = repository.settingsStore.smartFilter.first()
            if (savedSmart != librarySmartOn) {
                librarySmartOn = savedSmart
                binding.libChipSmart.isChecked = savedSmart
            }
            val savedFilter = repository.settingsStore.defaultFilter.first()
            if (savedFilter != libraryFilter) {
                libraryFilter = savedFilter
                val chipId = when (savedFilter) {
                    "480p" -> R.id.libChip480
                    "720p" -> R.id.libChip720
                    "1080p" -> R.id.libChip1080
                    "4K" -> R.id.libChip4k
                    else -> R.id.libChipAll
                }
                binding.libraryFilterChips.check(chipId)
            }
        }
        // v9.1 #19: NEW dots — re-submit the groups list when the
        // last-seen map changes so dots appear / clear live.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.lastSeenVideoCounts.collect { counts ->
                    lastSeenCounts = counts
                    groupAdapter.submitList(groups)
                }
            }
        }
        // v9.2: Home failsafe — Room emits in milliseconds; if the
        // groups flow has not emitted 10s after start, treat the
        // list as loaded so the Home skeleton can never sit over
        // the content forever. A late Room emission still submits
        // the list normally (the collector runs regardless).
        lifecycleScope.launch {
            delay(GROUPS_TIMEOUT_MS)
            if (!groupsLoaded) {
                groupsLoaded = true
                updateSkeletons()
                showTab(currentTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop: tab switches from Search/Settings land here.
        intent.getStringExtra(EXTRA_TAB)?.let { showTab(it) }
        intent.getStringExtra(EXTRA_ADD_GROUP_LINK)?.let { showAddChannelSheet(it) }
    }

    override fun onDestroy() {
        addSheet?.dismiss()
        addSheet = null
        // v9.1 #4: never leak a running shimmer animator.
        Shimmer.stop(homeShimmerAnimator)
        Shimmer.stop(libraryShimmerAnimator)
        Shimmer.stop(heroShimmerAnimator)
        super.onDestroy()
    }

    // ---- v8 tabs + Hero Spotlight ----

    private fun setupBottomNav(selected: NavTab) {
        BottomNavHelper.setup(this, binding.bottomNav.root, selected) { tab ->
            when (tab) {
                NavTab.HOME -> {
                    showTab(TAB_HOME)
                    true
                }
                NavTab.LIBRARY -> {
                    showTab(TAB_LIBRARY)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Show exactly one tab container. Before the first groups emit
     * ([groupsLoaded] false) the Home container stays visible so
     * the screen never flashes the empty state on a cold start.
     */
    private fun showTab(tab: String) {
        currentTab = tab
        val hasGroups = groups.isNotEmpty()
        // v9.3 Bug A: the two SwipeRefreshLayouts are stacked full-screen
        // siblings in the tab FrameLayout (librarySwipeRefresh above
        // homeSwipeRefresh). showTab used to toggle only the inner scroll
        // containers, so the LIBRARY layout stayed VISIBLE + enabled on
        // the Home tab — an empty, enabled SwipeRefreshLayout whose
        // (gone) child cannot scroll up returns true for ACTION_DOWN
        // (SwipeRefreshLayout.onTouchEvent) and eats every tap on the
        // Home tab, cold start included. Toggle the layouts themselves
        // (plus isEnabled as defence-in-depth) so the inactive tab's
        // layout can never intercept a touch. Pull-to-refresh still
        // works: the active tab's layout is visible + enabled.
        val homeVisible = tab == TAB_HOME && (hasGroups || !groupsLoaded)
        val libraryVisible = tab == TAB_LIBRARY && hasGroups
        binding.homeSwipeRefresh.visibility = if (homeVisible) View.VISIBLE else View.GONE
        binding.homeSwipeRefresh.isEnabled = homeVisible
        binding.homeScroll.visibility = if (homeVisible) View.VISIBLE else View.GONE
        binding.librarySwipeRefresh.visibility = if (libraryVisible) View.VISIBLE else View.GONE
        binding.librarySwipeRefresh.isEnabled = libraryVisible
        binding.libraryContainer.visibility = if (libraryVisible) View.VISIBLE else View.GONE
        binding.emptyState.visibility =
            if (groupsLoaded && !hasGroups) View.VISIBLE else View.GONE
        setupBottomNav(if (tab == TAB_LIBRARY) NavTab.LIBRARY else NavTab.HOME)
        updateSkeletons()
    }

    /**
     * v9.1 #4: skeletons — the Home skeleton shows while the groups
     * are still loading (cold start), the Library skeleton while
     * the first library page is still loading. The shimmer only
     * runs while its container is visible; stopping it also hides
     * the sweep view.
     */
    private fun updateSkeletons() {
        val showHome = currentTab == TAB_HOME && !groupsLoaded
        binding.homeSkeletonContainer.visibility =
            if (showHome) View.VISIBLE else View.GONE
        if (showHome && homeShimmerAnimator == null) {
            homeShimmerAnimator = Shimmer.start(binding.homeShimmerSweep, binding.homeSkeletonContainer)
        } else if (!showHome) {
            Shimmer.stop(homeShimmerAnimator)
            homeShimmerAnimator = null
        }
        val showLibrary = currentTab == TAB_LIBRARY &&
            !libraryLoaded && !libraryFirstPageFailed && groups.isNotEmpty()
        binding.librarySkeletonContainer.visibility =
            if (showLibrary) View.VISIBLE else View.GONE
        if (showLibrary && libraryShimmerAnimator == null) {
            libraryShimmerAnimator = Shimmer.start(
                binding.libraryShimmerSweep, binding.librarySkeletonContainer
            )
        } else if (!showLibrary) {
            Shimmer.stop(libraryShimmerAnimator)
            libraryShimmerAnimator = null
        }
    }

    /** v9.1 #4: fill a skeleton grid with 4 placeholder cards. */
    private fun layoutSkeletonGrid(grid: android.widget.GridLayout, spanCount: Int) {
        grid.columnCount = spanCount
        grid.removeAllViews()
        repeat(4) {
            val card = layoutInflater.inflate(R.layout.item_skeleton, grid, false)
            val params = android.widget.GridLayout.LayoutParams().apply {
                width = 0
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = android.widget.GridLayout.spec(
                    android.widget.GridLayout.UNDEFINED, 1f
                )
                if (spanCount == 1) {
                    // Single column: no horizontal margin (the
                    // container already has the screen margin).
                    setMargins(0, 0, 0, 0)
                }
            }
            grid.addView(card, params)
        }
    }

    /** Hero Spotlight content: title, "Channel • 12:34 left", Resume pill. */
    private fun bindHero(item: VideoItem) {
        // v9: headline hygiene — leading emoji stripped (display only).
        binding.heroTitle.text = FormatUtils.stripLeadingEmoji(item.title)
        binding.heroMeta.text = continueMeta(item)
        val left = if (item.durationSec > 0) {
            (item.durationSec - item.progressSec).coerceAtLeast(0L)
        } else {
            0L
        }
        binding.heroResumeButton.text = if (left > 0) {
            "${getString(R.string.home_resume)} • " +
                getString(R.string.home_left, FormatUtils.formatDuration(left.toInt()))
        } else {
            getString(R.string.home_resume)
        }
        // v9.1 #9: duration + quality pills on the hero thumb.
        if (item.durationSec > 0) {
            binding.heroDuration.visibility = View.VISIBLE
            binding.heroDuration.text = FormatUtils.formatDuration(item.durationSec)
        } else {
            binding.heroDuration.visibility = View.GONE
        }
        if (item.quality.label.isNotEmpty()) {
            binding.heroQuality.visibility = View.VISIBLE
            binding.heroQuality.text = item.quality.label
        } else {
            binding.heroQuality.visibility = View.GONE
        }
        // v9.1 #1: the hero thumb is the shared element into the player.
        binding.heroThumb.transitionName = "video_thumb_${item.messageId}"
        // v9.1 #9: hero shimmer until the thumbnail lands.
        Shimmer.stop(heroShimmerAnimator)
        heroShimmerAnimator = null
        binding.heroShimmer.visibility = View.VISIBLE
        heroShimmerAnimator = Shimmer.start(binding.heroShimmer, binding.heroThumb)
        ThumbLoader.load(lifecycleScope, repository, item, binding.heroThumb) {
            Shimmer.stop(heroShimmerAnimator)
            heroShimmerAnimator = null
            binding.heroShimmer.visibility = View.GONE
        }
    }

    /** v9.1 #9: hero overflow — View copies / Open channel. */
    private fun showHeroMenu(item: VideoItem) {
        val popup = PopupMenu(this, binding.heroMore)
        popup.menuInflater.inflate(R.menu.hero_menu, popup.menu)
        val group = SmartFilter.groupEpisodes(continueItems).firstOrNull {
            it.best.chatId == item.chatId && it.best.messageId == item.messageId
        }
        val hasCopies = group != null && group.copies.size > 1
        popup.menu.findItem(R.id.hero_menu_copies).isVisible = hasCopies
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.hero_menu_copies -> {
                    if (group != null) {
                        CopiesSheet.show(
                            this, group,
                            onPick = { copy ->
                                val playlist = continueItems.map { shown ->
                                    if (shown.chatId == item.chatId &&
                                        shown.messageId == item.messageId
                                    ) copy else shown
                                }
                                openContinue(copy, binding.heroThumb, playlist)
                            },
                            groupNameProvider = { video ->
                                groups.firstOrNull { it.chatId == video.chatId }
                                    ?.title.orEmpty()
                            }
                        )
                    }
                    true
                }
                R.id.hero_menu_open_channel -> {
                    groups.firstOrNull { it.chatId == item.chatId }?.let { openChannel(it) }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /**
     * v8 responsive grid: 1 column compact, 2 columns on >=600dp,
     * 3 columns on >=840dp. Cards never stretch — extra width
     * becomes another column, not a wider card.
     */
    private fun gridSpanCount(): Int {
        return when (resources.configuration.screenWidthDp) {
            in 0..599 -> 1
            in 600..839 -> 2
            else -> 3
        }
    }

    // ---- Your Library ----

    private fun resetAndLoadLibrary() {
        libraryTimeoutJob?.cancel()
        libraryTimedOut = false
        allLibrary.clear()
        libraryCursors = emptyMap()
        libraryHasMore = true
        libraryLoaded = false
        libraryFirstPageFailed = false
        libraryLastError = null
        libraryAdapter.submitVideos(emptyList())
        binding.libraryErrorState.visibility = View.GONE
        updateSkeletons()
        loadLibraryPage()
    }

    private fun loadLibraryPage() {
        // v9.2: after a first-page timeout, background re-entry (a
        // scroll) must not silently restart the load — only an
        // explicit Retry (resetAndLoadLibrary) clears the flag.
        if (libraryLoading || !libraryHasMore || groups.isEmpty() ||
            (libraryTimedOut && allLibrary.isEmpty())
        ) return
        libraryLoading = true
        updateLibraryLoadMoreButton()
        val firstPage = allLibrary.isEmpty()
        if (!firstPage) binding.libraryLoadMoreFooter.visibility = View.VISIBLE
        if (firstPage) {
            // v9.2: the first page has no timeout anywhere else — a
            // hung TDLib load left the skeleton up forever. After
            // 60s the page fails like a load error (Retry restarts
            // it); the hung load itself is cancelled.
            libraryTimeoutJob?.cancel()
            libraryTimeoutJob = lifecycleScope.launch {
                delay(FIRST_PAGE_TIMEOUT_MS)
                onLibraryFirstPageTimeout()
            }
        }
        val currentGroups = groups.toList()
        val cursors = libraryCursors
        libraryLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val page = repository.loadLibraryPage(currentGroups, cursors)
                withContext(Dispatchers.Main) {
                    libraryTimeoutJob?.cancel()
                    libraryLoading = false
                    binding.libraryLoadMoreFooter.visibility = View.GONE
                    libraryCursors = page.cursors
                    libraryHasMore = page.hasMore
                    libraryLoaded = true
                    libraryFirstPageFailed = false
                    libraryLastError = null
                    updateSkeletons()
                    val seen = allLibrary.map { it.chatId to it.messageId }.toHashSet()
                    for (video in page.videos) {
                        if (seen.add(video.chatId to video.messageId)) {
                            allLibrary.add(video)
                        }
                    }
                    binding.libraryHeader.visibility = View.VISIBLE
                    binding.libraryFilterScroll.visibility = View.VISIBLE
                    applyLibraryFilter()
                }
            } catch (e: Exception) {
                // v9.2: a load cancelled by the first-page timeout
                // throws here too — the timeout already showed the
                // error state, so there is nothing more to do.
                if (e is CancellationException) return@launch
                withContext(Dispatchers.Main) {
                    libraryTimeoutJob?.cancel()
                    libraryLoading = false
                    binding.libraryLoadMoreFooter.visibility = View.GONE
                    updateLibraryLoadMoreButton()
                    updateSkeletons()
                    if (firstPage) {
                        libraryFirstPageFailed = true
                        // v9.1 #24: keep the message for the Details action.
                        libraryLastError = e.message ?: e.javaClass.simpleName
                        binding.libraryHeader.visibility = View.VISIBLE
                        binding.libraryFilterScroll.visibility = View.VISIBLE
                        binding.libraryRecycler.visibility = View.GONE
                        binding.libraryEmptyState.visibility = View.GONE
                        binding.libraryErrorState.visibility = View.VISIBLE
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
     * v9.2: first-page timeout — the library skeleton hid only when
     * a load returned or threw; a hung TDLib load left it up
     * forever with no error and no recovery, and a stuck skeleton
     * is taller than the screen (4 full-width 16:9 cards), so every
     * tap landed on a dead placeholder while the header and nav bar
     * kept working. After 60s the page fails exactly like a load
     * error (Retry restarts it); the hung load is cancelled so it
     * can never overwrite the error state late.
     */
    private fun onLibraryFirstPageTimeout() {
        if (libraryLoaded || allLibrary.isNotEmpty()) return
        libraryTimedOut = true
        libraryLoadJob?.cancel()
        libraryLoadJob = null
        libraryLoading = false
        binding.libraryLoadMoreFooter.visibility = View.GONE
        libraryFirstPageFailed = true
        libraryLastError = getString(R.string.library_error_timeout)
        binding.libraryHeader.visibility = View.VISIBLE
        binding.libraryFilterScroll.visibility = View.VISIBLE
        binding.libraryRecycler.visibility = View.GONE
        binding.libraryEmptyState.visibility = View.GONE
        binding.libraryErrorState.visibility = View.VISIBLE
        updateSkeletons()
    }

    /**
     * v8: the Load More button is gone — the footer spinner is the
     * only loading indicator and loadLibraryPage() drives it
     * directly. Kept as a no-op so the call sites stay unchanged.
     */
    private fun updateLibraryLoadMoreButton() = Unit

    /** Filter + sort only — DiffUtil, no full reload, scroll preserved. */
    private fun applyLibraryFilter() {
        if (libraryFirstPageFailed) return
        var list = allLibrary.toList()
        // v5 Smart Filter — dedup FIRST, then the quality chip (same
        // order as ChannelActivity). Load More re-runs this over the
        // merged list, so duplicates never reappear.
        if (librarySmartOn) {
            libraryGroups = SmartFilter.groupEpisodes(list)
            list = libraryGroups.map { it.best.copy(hiddenCopies = it.hiddenCount) }
        } else {
            libraryGroups = emptyList()
        }
        if (libraryFilter != "All") {
            list = list.filter { it.quality.label == libraryFilter }
        }
        list = when (librarySort) {
            LibrarySort.NEWEST -> list.sortedByDescending { it.dateEpochSec }
            LibrarySort.OLDEST -> list.sortedBy { it.dateEpochSec }
            LibrarySort.TITLE_AZ -> list.sortedBy { it.title.lowercase() }
            LibrarySort.TITLE_ZA -> list.sortedByDescending { it.title.lowercase() }
            LibrarySort.LONGEST -> list.sortedByDescending { it.durationSec }
            LibrarySort.LARGEST -> list.sortedByDescending { it.sizeBytes }
        }
        displayedLibrary = list
        // v9.1 #8: section the flat sorted list by channel, in the
        // order the sections first appear in the current sort.
        // Videos inside a section keep their sort order; the
        // section order itself follows the sort (Newest = the
        // section whose first video is newest comes first).
        val rows = ArrayList<VideoRow>()
        val byChannel = LinkedHashMap<Long, MutableList<VideoItem>>()
        for (video in list) {
            byChannel.getOrPut(video.chatId) { mutableListOf() }.add(video)
        }
        for ((chatId, videos) in byChannel) {
            val name = groups.firstOrNull { it.chatId == chatId }?.title
                ?: getString(R.string.library_unknown_channel)
            rows.add(VideoRow.Header(name, videos.size))
            for (video in videos) rows.add(VideoRow.Item(video))
        }
        libraryAdapter.submitLibraryRows(rows)
        val empty = list.isEmpty() && libraryLoaded
        binding.libraryEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.libraryRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) binding.libraryErrorState.visibility = View.GONE
        updateLibraryLoadMoreButton()
    }

    /** v9.1 #24: the library error's technical detail + crash-log path. */
    private fun showLibraryErrorDetails() {
        InfoSheet.show(
            this,
            getString(R.string.library_error_details_title),
            getString(
                R.string.library_error_details_body,
                libraryLastError ?: getString(R.string.library_error_unknown)
            )
        )
    }

    /** v8: sort is a bottom sheet (§4.9), not a PopupMenu + Snackbar echo. */
    private fun showSortSheet() {
        val options = listOf(
            getString(R.string.home_sort_newest),
            getString(R.string.home_sort_oldest),
            getString(R.string.home_sort_title_az),
            getString(R.string.home_sort_title_za),
            getString(R.string.home_sort_longest),
            getString(R.string.home_sort_largest)
        )
        SortSheet.show(
            activity = this,
            title = getString(R.string.home_sort),
            options = options,
            selectedIndex = librarySort.ordinal,
            onPick = { index ->
                librarySort = LibrarySort.entries[index]
                applyLibraryFilter()
            },
            // v9.1 #12: one icon per sort option.
            icons = listOf(
                R.drawable.ic_arrow_downward,
                R.drawable.ic_arrow_upward,
                R.drawable.ic_sort,
                R.drawable.ic_sort,
                R.drawable.ic_history,
                R.drawable.ic_bars_desc
            )
        )
    }

    // ---- Home groups refresh ----

    private fun refreshGroups() {
        if (refreshing) return
        refreshing = true
        binding.homeRefreshButton.visibility = View.GONE
        binding.homeRefreshProgress.visibility = View.VISIBLE
        val currentGroups = groups.toList()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.refreshGroups(currentGroups) }.getOrNull()
            withContext(Dispatchers.Main) {
                refreshing = false
                binding.homeRefreshProgress.visibility = View.GONE
                binding.homeRefreshButton.visibility = View.VISIBLE
                // v9.1 #6: stop both pull-to-refresh spinners.
                binding.homeSwipeRefresh.isRefreshing = false
                binding.librarySwipeRefresh.isRefreshing = false
                if (result == null) {
                    Snackbar.make(
                        binding.root,
                        R.string.home_refresh_failed,
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.common_retry) { refreshGroups() }.show()
                    return@withContext
                }
                // Groups flow re-emits with fresh titles/counts; the
                // library reloads from the refreshed groups.
                resetAndLoadLibrary()
                if (result.failed == 0) {
                    Snackbar.make(
                        binding.root,
                        R.string.home_refreshed,
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else if (result.refreshed > 0) {
                    Snackbar.make(
                        binding.root,
                        R.string.home_refresh_partial,
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.common_retry) { refreshGroups() }.show()
                } else {
                    Snackbar.make(
                        binding.root,
                        R.string.home_refresh_failed,
                        Snackbar.LENGTH_LONG
                    ).setAction(R.string.common_retry) { refreshGroups() }.show()
                }
            }
        }
    }

    // ---- Continue Watching / navigation ----

    /** "Channel name • 12:34 left" under a Continue Watching row card. */
    private fun continueMeta(item: VideoItem): String {
        val groupName = groups.firstOrNull { it.chatId == item.chatId }?.title.orEmpty()
        val remaining = if (item.durationSec > 0) {
            val left = (item.durationSec - item.progressSec).coerceAtLeast(0L)
            getString(R.string.home_left, FormatUtils.formatDuration(left.toInt()))
        } else {
            ""
        }
        return listOf(groupName, remaining).filter { it.isNotEmpty() }.joinToString(" • ")
    }

    private fun openChannel(group: GroupItem) {
        // v9.1 #19: opening a channel marks its videos as seen —
        // the NEW dot clears until new videos arrive.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                repository.settingsStore.setLastSeenVideoCount(group.chatId, group.videoCount)
            }
        }
        val intent = Intent(this, ChannelActivity::class.java)
            .putExtra(ChannelActivity.EXTRA_CHAT_ID, group.chatId)
            .putExtra(ChannelActivity.EXTRA_TITLE, group.title)
        startActivity(intent)
    }

    /** v9.1 #19: long-press menu — Open / Refresh / Remove. */
    private fun showGroupMenu(group: GroupItem, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.group_menu, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.group_menu_open -> {
                    openChannel(group)
                    true
                }
                R.id.group_menu_refresh -> {
                    refreshSingleGroup(group)
                    true
                }
                R.id.group_menu_remove -> {
                    confirmRemove(group)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** v9.1 #19: refresh ONE group (title/photo/count) without a full reload. */
    private fun refreshSingleGroup(group: GroupItem) {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { repository.refreshGroups(listOf(group)) }.getOrNull()
            withContext(Dispatchers.Main) {
                refreshing = false
                Snackbar.make(
                    binding.root,
                    if (result != null && result.failed == 0) R.string.home_refreshed
                    else R.string.home_refresh_failed,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * v9.1 #1: [thumb] is the shared element into the player (null
     * = no transition — the one-time redirect). [playlistOverride]
     * is the copies-picker path (the picked copy replaces the hero
     * item in the playlist).
     */
    private fun openContinue(item: VideoItem, thumb: ImageView? = null, playlistOverride: List<VideoItem>? = null) {
        PlayerPlaylist.items = playlistOverride ?: continueItems
        PlayerPlaylist.chatId = item.chatId
        // v8: Continue opens have no episode grouping (Room-only
        // slice) — the player's Copies pill falls back to its
        // playlist rebuild and hides when it finds one copy.
        PlayerPlaylist.episodeGroups = emptyList()
        // v4-fb3: Continue items are a Room-only slice — the player
        // expands this to the channel's full filtered list.
        PlayerPlaylist.fromContinue = true
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, item.chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        if (thumb != null) {
            val options = ActivityOptions.makeSceneTransitionAnimation(
                this, thumb, "video_thumb_${item.messageId}"
            )
            startActivity(intent, options.toBundle())
        } else {
            startActivity(intent)
        }
    }

    private fun openLibraryPlayer(item: VideoItem, thumb: ImageView) {
        openLibraryPlayer(item, thumb, null)
    }

    /** v5: [playlistOverride] is the copies-picker path (see ChannelActivity). */
    private fun openLibraryPlayer(
        item: VideoItem,
        thumb: ImageView,
        playlistOverride: List<VideoItem>?
    ) {
        PlayerPlaylist.items = playlistOverride ?: displayedLibrary
        PlayerPlaylist.chatId = item.chatId
        // v8 §4.4: stash the library's episode grouping so the
        // player's Copies pill can list the real copies — the
        // playlist itself holds only best copies when Smart
        // Filter is on. (When Smart is off libraryGroups is
        // empty and the pill falls back to its rebuild.)
        PlayerPlaylist.episodeGroups = libraryGroups
        PlayerPlaylist.fromContinue = false
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, item.chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        val options = ActivityOptions.makeSceneTransitionAnimation(
            this, thumb, "video_thumb_${item.messageId}"
        )
        startActivity(intent, options.toBundle())
    }

    /** v5 Smart Filter: "N copies" badge → pick any copy of the episode. */
    private fun showLibraryCopies(best: VideoItem, thumb: ImageView) {
        val group = libraryGroups.firstOrNull {
            it.best.chatId == best.chatId && it.best.messageId == best.messageId
        } ?: return
        CopiesSheet.show(
            this, group,
            onPick = { copy ->
                val playlist = displayedLibrary.map { shown ->
                    if (shown.chatId == best.chatId && shown.messageId == best.messageId) copy
                    else shown
                }
                openLibraryPlayer(copy, thumb, playlist)
            },
            // v9.1 #15: group name per row.
            groupNameProvider = { video ->
                groups.firstOrNull { it.chatId == video.chatId }?.title.orEmpty()
            }
        )
    }

    /**
     * Add Channel bottom sheet (pixel spec §04): 24dp radius, 20dp padding,
     * 32x4dp handle, heading 20sp, input 56dp, button 52dp. Validates the
     * link, shows an inline loading state, blocks duplicates and double
     * submits. Closes only on success or explicit Cancel.
     *
     * [prefillLink] v8: the Search paste-link chip opens this sheet
     * with the clipboard's t.me link already filled in.
     */
    private fun showAddChannelSheet(prefillLink: String? = null) {
        val sheetBinding = LayoutBottomSheetAddChannelBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this).apply {
            setContentView(sheetBinding.root)
            // Spec: closes only on success or explicit Cancel.
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        addSheet = dialog
        var adding = false
        // v9.1 #3: resolving preview — debounced, one resolve per
        // username; the job is cancelled on every keystroke.
        var previewJob: Job? = null
        var lastPreviewUsername: String? = null

        fun setAdding(value: Boolean) {
            adding = value
            sheetBinding.addChannelLoading.visibility = if (value) View.VISIBLE else View.GONE
            sheetBinding.addChannelButton.isEnabled = !value
            sheetBinding.addChannelCancelButton.isEnabled = !value
            sheetBinding.addChannelEdit.isEnabled = !value
        }

        /** v9.1 #3: field error state — red error text + a shake,
         * so an invalid / duplicate / failed link is impossible to
         * miss. Cleared on the next keystroke (see the watcher). */
        fun setFieldError(message: String) {
            sheetBinding.addChannelInputLayout.error = message
            val layout = sheetBinding.addChannelInputLayout
            android.animation.ObjectAnimator.ofFloat(
                layout, "translationX", 0f, -12f, 12f, -8f, 8f, 0f
            ).apply {
                duration = 400
                interpolator = AccelerateDecelerateInterpolator()
            }.start()
        }

        /** v9.1 #3: the resolving preview — avatar + title + count
         * while the user types, hidden when the text is not a link. */
        fun schedulePreview() {
            previewJob?.cancel()
            val username = extractUsername(
                sheetBinding.addChannelEdit.text?.toString().orEmpty()
            )
            if (username == null || username == lastPreviewUsername) {
                if (username == null) {
                    lastPreviewUsername = null
                    sheetBinding.addChannelPreview.visibility = View.GONE
                }
                return
            }
            lastPreviewUsername = username
            sheetBinding.addChannelPreview.visibility = View.VISIBLE
            sheetBinding.addChannelPreviewTitle.visibility = View.GONE
            sheetBinding.addChannelPreviewSubtitle.visibility = View.GONE
            sheetBinding.addChannelPreviewLoading.visibility = View.VISIBLE
            previewJob = lifecycleScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(500)
                val resolved = runCatching {
                    repository.resolveGroupPreview(
                        sheetBinding.addChannelEdit.text?.toString().orEmpty()
                    )
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    sheetBinding.addChannelPreviewLoading.visibility = View.GONE
                    if (resolved == null) {
                        // Not resolvable (yet) — hide the preview,
                        // the submit error will explain on Add.
                        sheetBinding.addChannelPreview.visibility = View.GONE
                        return@withContext
                    }
                    val photoPath = resolved.photoLocalPath
                    if (!photoPath.isNullOrBlank() && File(photoPath).exists()) {
                        sheetBinding.addChannelPreviewAvatar.load(File(photoPath)) {
                            placeholder(R.drawable.ic_video_placeholder)
                            error(R.drawable.ic_video_placeholder)
                            crossfade(200)
                        }
                    } else {
                        sheetBinding.addChannelPreviewAvatar.setImageResource(
                            R.drawable.ic_video_placeholder
                        )
                    }
                    sheetBinding.addChannelPreviewTitle.text = resolved.title
                    sheetBinding.addChannelPreviewTitle.visibility = View.VISIBLE
                    sheetBinding.addChannelPreviewSubtitle.text = getString(
                        R.string.home_videos_count, resolved.videoCount
                    )
                    sheetBinding.addChannelPreviewSubtitle.visibility = View.VISIBLE
                }
            }
        }

        fun submit() {
            if (adding) return
            val link = sheetBinding.addChannelEdit.text?.toString()?.trim().orEmpty()
            val username = extractUsername(link)
            if (username == null) {
                setFieldError(getString(R.string.home_add_link_invalid))
                return
            }
            if (groups.any { it.username.equals(username, ignoreCase = true) }) {
                setFieldError(getString(R.string.home_add_exists))
                return
            }
            sheetBinding.addChannelInputLayout.error = null
            setAdding(true)
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching { repository.addGroup(link) }.isSuccess
                withContext(Dispatchers.Main) {
                    if (!isFinishing && dialog.isShowing) {
                        if (ok) {
                            dialog.dismiss()
                            Snackbar.make(
                                binding.root,
                                R.string.home_add_added,
                                Snackbar.LENGTH_SHORT
                            ).show()
                        } else {
                            setAdding(false)
                            // Generic message only — never a technical
                            // exception string in the UI.
                            setFieldError(getString(R.string.home_add_failed))
                        }
                    }
                }
            }
        }

        // v9.1 #3: Paste — fills the field with the clipboard's
        // t.me link (same check as Search's paste chip).
        sheetBinding.pasteButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val link = if (clip.contains("t.me/")) clip.trim() else null
            if (link == null) {
                Snackbar.make(
                    sheetBinding.root,
                    R.string.home_add_no_clipboard_link,
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                sheetBinding.addChannelEdit.setText(link)
                sheetBinding.addChannelEdit.setSelection(link.length)
                schedulePreview()
            }
        }
        sheetBinding.addChannelEdit.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    sheetBinding.addChannelInputLayout.error = null
                    schedulePreview()
                }
            }
        )
        sheetBinding.addChannelButton.setOnClickListener { submit() }
        sheetBinding.addChannelEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        sheetBinding.addChannelCancelButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            previewJob?.cancel()
            if (addSheet == dialog) addSheet = null
        }
        dialog.show()
        if (!prefillLink.isNullOrBlank()) {
            sheetBinding.addChannelEdit.setText(prefillLink)
            sheetBinding.addChannelEdit.setSelection(prefillLink.length)
            schedulePreview()
        }
    }

    /**
     * Username out of a t.me link / @username / bare username.
     * Null when nothing usable is in the text (invalid link).
     */
    private fun extractUsername(link: String): String? {
        var text = link.trim()
        if (text.isEmpty()) return null
        text = text.removePrefix("https://").removePrefix("http://")
            .removePrefix("www.").removePrefix("t.me/").removePrefix("@")
        val segment = text.split('/', '?', ' ', '\n').firstOrNull().orEmpty()
        if (segment.isEmpty()) return null
        if (!segment.matches(Regex("[A-Za-z0-9_]{4,}"))) return null
        return segment
    }

    /** v8: remove-group confirm is a bottom sheet (§4.9), not an AlertDialog. */
    private fun confirmRemove(group: GroupItem) {
        ConfirmSheet.show(
            activity = this,
            title = getString(R.string.home_remove_group_title),
            body = getString(R.string.home_remove_group_body, group.title),
            confirmText = getString(R.string.home_remove),
            onConfirm = {
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.removeGroup(group.chatId)
                }
            }
        )
    }
}
