package com.nobi.nobistream.ui.home

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import com.nobi.nobistream.ui.components.BottomNavHelper
import com.nobi.nobistream.ui.components.ConfirmSheet
import com.nobi.nobistream.ui.components.NavTab
import com.nobi.nobistream.ui.components.SortSheet
import com.nobi.nobistream.ui.login.LoginActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import jp.wasabeef.recyclerview.animators.FadeInAnimator
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        binding.continueSeeAll.setOnClickListener {
            startActivity(Intent(this, ContinueAllActivity::class.java))
        }
        binding.librarySortButton.setOnClickListener { showSortSheet() }
        binding.heroCard.setOnClickListener { heroItem?.let { openContinue(it) } }
        binding.heroResumeButton.setOnClickListener { heroItem?.let { openContinue(it) } }
        groupAdapter = GroupAdapter(
            onClick = { group -> openChannel(group) },
            onLongClick = { group -> confirmRemove(group) }
        )
        binding.groupsRecycler.layoutManager = LinearLayoutManager(this)
        binding.groupsRecycler.adapter = groupAdapter
        binding.groupsRecycler.itemAnimator = SlideInUpAnimator()
        continueAdapter = ContinueAdapter(
            onClick = { item -> openContinue(item) },
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
            onCopiesClick = { item, thumb -> showLibraryCopies(item, thumb) }
        )
        val spanCount = gridSpanCount()
        binding.libraryRecycler.layoutManager = GridLayoutManager(this, spanCount)
        binding.libraryRecycler.adapter = libraryAdapter
        binding.libraryRecycler.itemAnimator = SlideInUpAnimator()
        binding.libraryRecycler.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.grid_gap_horizontal),
                resources.getDimensionPixelSize(R.dimen.grid_gap_vertical)
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
            hideAction()
        }
        binding.libraryErrorState.apply {
            setTitle(getString(R.string.home_library_error_title))
            setBody(getString(R.string.home_library_error_body))
            setRetry { resetAndLoadLibrary() }
        }
        binding.addGroupButton.setOnClickListener { showAddChannelSheet() }
        binding.emptyState.apply {
            setIcon(R.drawable.ic_add)
            setTitle(getString(R.string.home_no_groups))
            setBody(getString(R.string.home_no_groups_body))
            setAction(getString(R.string.home_add_group)) { showAddChannelSheet() }
        }
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
                        libraryAdapter.submitList(emptyList())
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
        binding.homeScroll.visibility =
            if (tab == TAB_HOME && (hasGroups || !groupsLoaded)) View.VISIBLE else View.GONE
        binding.libraryContainer.visibility =
            if (tab == TAB_LIBRARY && hasGroups) View.VISIBLE else View.GONE
        binding.emptyState.visibility =
            if (groupsLoaded && !hasGroups) View.VISIBLE else View.GONE
        setupBottomNav(if (tab == TAB_LIBRARY) NavTab.LIBRARY else NavTab.HOME)
    }

    /** Hero Spotlight content: title, "Channel • 12:34 left", Resume pill. */
    private fun bindHero(item: VideoItem) {
        binding.heroTitle.text = item.title
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
        ThumbLoader.load(lifecycleScope, repository, item, binding.heroThumb)
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
        allLibrary.clear()
        libraryCursors = emptyMap()
        libraryHasMore = true
        libraryLoaded = false
        libraryFirstPageFailed = false
        libraryAdapter.submitList(emptyList())
        binding.libraryErrorState.visibility = View.GONE
        loadLibraryPage()
    }

    private fun loadLibraryPage() {
        if (libraryLoading || !libraryHasMore || groups.isEmpty()) return
        libraryLoading = true
        updateLibraryLoadMoreButton()
        val firstPage = allLibrary.isEmpty()
        if (!firstPage) binding.libraryLoadMoreFooter.visibility = View.VISIBLE
        val currentGroups = groups.toList()
        val cursors = libraryCursors
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val page = repository.loadLibraryPage(currentGroups, cursors)
                withContext(Dispatchers.Main) {
                    libraryLoading = false
                    binding.libraryLoadMoreFooter.visibility = View.GONE
                    libraryCursors = page.cursors
                    libraryHasMore = page.hasMore
                    libraryLoaded = true
                    libraryFirstPageFailed = false
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
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    libraryLoading = false
                    binding.libraryLoadMoreFooter.visibility = View.GONE
                    updateLibraryLoadMoreButton()
                    if (firstPage) {
                        libraryFirstPageFailed = true
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
        libraryAdapter.submitList(list)
        val empty = list.isEmpty() && libraryLoaded
        binding.libraryEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.libraryRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) binding.libraryErrorState.visibility = View.GONE
        updateLibraryLoadMoreButton()
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
            }
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
        val intent = Intent(this, ChannelActivity::class.java)
            .putExtra(ChannelActivity.EXTRA_CHAT_ID, group.chatId)
            .putExtra(ChannelActivity.EXTRA_TITLE, group.title)
        startActivity(intent)
    }

    private fun openContinue(item: VideoItem) {
        PlayerPlaylist.items = continueItems
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
        startActivity(intent)
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
        CopiesSheet.show(this, group) { copy ->
            val playlist = displayedLibrary.map { shown ->
                if (shown.chatId == best.chatId && shown.messageId == best.messageId) copy
                else shown
            }
            openLibraryPlayer(copy, thumb, playlist)
        }
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

        fun setAdding(value: Boolean) {
            adding = value
            sheetBinding.addChannelLoading.visibility = if (value) View.VISIBLE else View.GONE
            sheetBinding.addChannelButton.isEnabled = !value
            sheetBinding.addChannelCancelButton.isEnabled = !value
            sheetBinding.addChannelEdit.isEnabled = !value
        }

        fun submit() {
            if (adding) return
            val link = sheetBinding.addChannelEdit.text?.toString()?.trim().orEmpty()
            val username = extractUsername(link)
            if (username == null) {
                sheetBinding.addChannelInputLayout.error =
                    getString(R.string.home_add_link_invalid)
                return
            }
            if (groups.any { it.username.equals(username, ignoreCase = true) }) {
                sheetBinding.addChannelInputLayout.error = getString(R.string.home_add_exists)
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
                            sheetBinding.addChannelInputLayout.error =
                                getString(R.string.home_add_failed)
                        }
                    }
                }
            }
        }

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
        dialog.setOnDismissListener { if (addSheet == dialog) addSheet = null }
        dialog.show()
        if (!prefillLink.isNullOrBlank()) {
            sheetBinding.addChannelEdit.setText(prefillLink)
            sheetBinding.addChannelEdit.setSelection(prefillLink.length)
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
