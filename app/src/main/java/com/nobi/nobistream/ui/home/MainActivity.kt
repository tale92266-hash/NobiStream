package com.nobi.nobistream.ui.home

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivityMainBinding
import com.nobi.nobistream.databinding.LayoutBottomSheetAddChannelBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.channel.ChannelActivity
import com.nobi.nobistream.ui.channel.GridSpacingItemDecoration
import com.nobi.nobistream.ui.channel.VideoAdapter
import com.nobi.nobistream.ui.login.LoginActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import com.nobi.nobistream.ui.search.SearchActivity
import com.nobi.nobistream.ui.settings.SettingsActivity
import jp.wasabeef.recyclerview.animators.FadeInAnimator
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 Home: custom 56dp app bar (brand icon + brand text + search,
 * refresh, settings), ONE Continue Watching section — the single
 * "Continue Watching" heading, then the featured card (most recent
 * episode), then the row (the rest; featured = item[0], row =
 * items.drop(1), so no episode repeats) — then Your Groups, then
 * Your Library — recent videos from ALL groups merged newest
 * first in the same 2-column VideoCard grid as Channel Detail, with
 * the same quality filter chips and a sort control.
 *
 * v4-fb2: the heading used to appear TWICE — the featured card
 * carried its own caps "CONTINUE WATCHING" overline and sat ABOVE
 * the section heading. The overline is removed and the heading now
 * comes first, so the name appears exactly once on Home.
 *
 * Refresh re-fetches each group's title/photo/count from Telegram
 * (only what TDLib returns — no invented statistics) and reloads
 * the library. Library paging loads one page per group at a time
 * and merges by date; scrolling near the bottom loads more.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var continueAdapter: ContinueAdapter
    private lateinit var libraryAdapter: VideoAdapter
    private var continueItems: List<VideoItem> = emptyList()
    private var groups: List<GroupItem> = emptyList()
    private var featuredItem: VideoItem? = null
    private var redirectDone = false
    private var addSheet: BottomSheetDialog? = null

    // ---- Your Library state ----
    private val allLibrary = mutableListOf<VideoItem>()
    private var displayedLibrary: List<VideoItem> = emptyList()
    private var libraryFilter = "All"
    private var librarySort = LibrarySort.NEWEST
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
        binding.homeSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.homeSearchButton.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.homeRefreshButton.setOnClickListener { refreshGroups() }
        binding.continueSeeAll.setOnClickListener {
            startActivity(Intent(this, ContinueAllActivity::class.java))
        }
        binding.libraryLoadMoreButton.setOnClickListener { loadLibraryPage() }
        binding.librarySortButton.setOnClickListener { showSortMenu() }
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
        binding.featuredCard.featuredResumeButton.setOnClickListener {
            featuredItem?.let { openContinue(it) }
        }
        binding.featuredCard.root.setOnClickListener {
            featuredItem?.let { openContinue(it) }
        }
        // ---- Your Library grid (same VideoCard as Channel Detail) ----
        libraryAdapter = VideoAdapter(
            onClick = { item, thumb -> openLibraryPlayer(item, thumb) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            }
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
        binding.homeScroll.setOnScrollChangeListener(
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeGroups().collect { items ->
                    groups = items
                    groupAdapter.submitList(items)
                    val empty = items.isEmpty()
                    binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.homeScroll.visibility = if (empty) View.GONE else View.VISIBLE
                    // Load the library once per set of groups; a group
                    // added/removed changes the signature and reloads.
                    // Count/title refreshes (same chatIds) do not.
                    val signature = items.map { it.chatId }.sorted().joinToString(",")
                    if (!empty && signature != libraryGroupsSignature) {
                        libraryGroupsSignature = signature
                        resetAndLoadLibrary()
                    } else if (empty) {
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
                    // Unified section: featured = first item, row = the rest.
                    continueAdapter.submitList(items.drop(1))
                    val hasAny = items.isNotEmpty()
                    val hasRow = items.size > 1
                    binding.continueHeader.visibility = if (hasAny) View.VISIBLE else View.GONE
                    binding.continueRecycler.visibility = if (hasRow) View.VISIBLE else View.GONE
                    updateFeatured(items.firstOrNull())
                    // v2: one-time redirect to the last-watched episode.
                    // Only on a fresh start (not rotation / return from player).
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

    override fun onDestroy() {
        addSheet?.dismiss()
        addSheet = null
        super.onDestroy()
    }

    /**
     * Responsive grid (pixel spec): 2 columns compact, 3 columns on
     * >=600dp, 4 columns on >=840dp. Cards never stretch — extra width
     * becomes another column, not a wider card.
     */
    private fun gridSpanCount(): Int {
        return when (resources.configuration.screenWidthDp) {
            in 0..599 -> 2
            in 600..839 -> 3
            else -> 4
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
     * v4 explicit Load more button: visible whenever more library
     * videos may exist (libraryHasMore), disabled with a loading
     * label mid-load. Auto-load-on-scroll stays as well.
     */
    private fun updateLibraryLoadMoreButton() {
        val show = libraryHasMore && allLibrary.isNotEmpty() && !libraryFirstPageFailed
        binding.libraryLoadMoreButton.visibility = if (show) View.VISIBLE else View.GONE
        binding.libraryLoadMoreButton.isEnabled = !libraryLoading
        binding.libraryLoadMoreButton.text = getString(
            if (libraryLoading) R.string.channel_loading_more else R.string.channel_load_more
        )
    }

    /** Filter + sort only — DiffUtil, no full reload, scroll preserved. */
    private fun applyLibraryFilter() {
        if (libraryFirstPageFailed) return
        var list = allLibrary.toList()
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

    private fun showSortMenu() {
        val menu = PopupMenu(this, binding.librarySortButton)
        menu.menu.add(0, 1, 0, R.string.home_sort_newest)
        menu.menu.add(0, 2, 0, R.string.home_sort_oldest)
        menu.menu.add(0, 3, 0, R.string.home_sort_title_az)
        menu.menu.add(0, 4, 0, R.string.home_sort_title_za)
        menu.menu.add(0, 5, 0, R.string.home_sort_longest)
        menu.menu.add(0, 6, 0, R.string.home_sort_largest)
        menu.setOnMenuItemClickListener { item ->
            librarySort = when (item.itemId) {
                2 -> LibrarySort.OLDEST
                3 -> LibrarySort.TITLE_AZ
                4 -> LibrarySort.TITLE_ZA
                5 -> LibrarySort.LONGEST
                6 -> LibrarySort.LARGEST
                else -> LibrarySort.NEWEST
            }
            applyLibraryFilter()
            Snackbar.make(binding.root, item.title.toString(), Snackbar.LENGTH_SHORT).show()
            true
        }
        menu.show()
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

    /** Featured card: the most recently watched episode, with remaining time. */
    private fun updateFeatured(item: VideoItem?) {
        featuredItem = item
        if (item == null) {
            binding.featuredCard.root.visibility = View.GONE
            return
        }
        binding.featuredCard.root.visibility = View.VISIBLE
        binding.featuredCard.featuredTitle.text = item.title
        binding.featuredCard.featuredMeta.text = continueMeta(item)
        binding.featuredCard.featuredProgress.progress = (item.progressFraction * 100).toInt()
        ThumbLoader.load(lifecycleScope, repository, item, binding.featuredCard.featuredThumb)
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
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, item.chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        startActivity(intent)
    }

    private fun openLibraryPlayer(item: VideoItem, thumb: ImageView) {
        PlayerPlaylist.items = displayedLibrary
        PlayerPlaylist.chatId = item.chatId
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, item.chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        val options = ActivityOptions.makeSceneTransitionAnimation(
            this, thumb, "video_thumb_${item.messageId}"
        )
        startActivity(intent, options.toBundle())
    }

    /**
     * Add Channel bottom sheet (pixel spec §04): 24dp radius, 20dp padding,
     * 32x4dp handle, heading 20sp, input 56dp, button 52dp. Validates the
     * link, shows an inline loading state, blocks duplicates and double
     * submits. Closes only on success or explicit Cancel.
     */
    private fun showAddChannelSheet() {
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

    private fun confirmRemove(group: GroupItem) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.home_remove_group_title)
            .setMessage(getString(R.string.home_remove_group_body, group.title))
            .setPositiveButton(R.string.home_remove) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.removeGroup(group.chatId)
                }
            }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }
}
