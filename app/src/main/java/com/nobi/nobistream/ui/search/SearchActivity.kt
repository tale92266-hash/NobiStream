package com.nobi.nobistream.ui.search

import android.animation.ObjectAnimator
import android.app.ActivityOptions
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.GridLayout
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivitySearchBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.CopiesSheet
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.channel.GridSpacingItemDecoration
import com.nobi.nobistream.ui.channel.VideoAdapter
import com.nobi.nobistream.ui.components.BottomNavHelper
import com.nobi.nobistream.ui.components.ConfirmSheet
import com.nobi.nobistream.ui.components.NavTab
import com.nobi.nobistream.ui.home.MainActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4 dedicated Search screen. Searches video titles/captions across
 * ALL added groups via TDLib server-side search (SearchChatMessages +
 * video filter; local filtering over the most recent page is the
 * fallback when a group rejects server search). Results reuse the
 * standard VideoCard grid. States: empty search, typing (400ms
 * debounce), searching (shimmer), results, no results, search error —
 * all with the v3 EmptyState / ErrorState components.
 *
 * Recent searches (query text only, max 10, newest first) live under
 * the field while the query is empty; tap one to run it again, Clear
 * wipes them after a confirmation dialog.
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        /** v9.2: a TDLib search may legitimately take tens of
         * seconds on a slow network; 60s matches the app's existing
         * 60s LauncherActivity auth wait. */
        private const val SEARCH_TIMEOUT_MS = 60_000L
    }

    private lateinit var binding: ActivitySearchBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var adapter: VideoAdapter
    private lateinit var recentAdapter: RecentSearchAdapter

    private var groups: List<GroupItem> = emptyList()
    private var results: List<VideoItem> = emptyList()
    /** v5 Smart Filter: grouping behind the displayed results. */
    private var smartOn = true
    private var searchGroups: List<EpisodeGroup> = emptyList()
    private var searchJob: Job? = null
    /** v9.2: the in-flight search, so the timeout can cancel a hung
     * TDLib call (separate from [searchJob], which is the 400ms
     * debounce). */
    private var searchLoadJob: Job? = null
    /** v9.2: the search timeout job (cancelled on any terminal
     * outcome). */
    private var searchTimeoutJob: Job? = null
    /** v8: the 1200ms diagonal shimmer sweep animator (see showSkeleton). */
    private var shimmerAnimator: ObjectAnimator? = null
    /** v8: t.me link currently on the clipboard (null = no link / field not empty). */
    private var pasteLink: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // v8: Search is a top-level bottom-nav tab now — no toolbar
        // back button (the back stack never leads here from a tab).
        binding.toolbar.navigationIcon = null
        BottomNavHelper.setup(this, binding.bottomNav.root, NavTab.SEARCH)

        adapter = VideoAdapter(
            onClick = { item, thumb -> openPlayer(item, thumb) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            },
            onCopiesClick = { item, thumb -> showCopies(item, thumb) },
            // v8: results mix channels, so the meta leads with the
            // channel name; cached videos get the check badge.
            showChannelName = true,
            channelNameProvider = { item ->
                groups.firstOrNull { it.chatId == item.chatId }?.title.orEmpty()
            },
            cachedProvider = { item ->
                locator.gateway.getCachedFileState(item.fileId)?.isComplete == true
            }
        )
        val spanCount = gridSpanCount()
        layoutSkeletonGrid(spanCount)
        binding.resultsRecycler.layoutManager = GridLayoutManager(this, spanCount)
        binding.resultsRecycler.adapter = adapter
        binding.resultsRecycler.itemAnimator = SlideInUpAnimator()
        binding.resultsRecycler.addItemDecoration(
            GridSpacingItemDecoration(
                spanCount,
                resources.getDimensionPixelSize(R.dimen.grid_gap_horizontal),
                resources.getDimensionPixelSize(R.dimen.grid_gap_vertical)
            )
        )

        recentAdapter = RecentSearchAdapter(
            onClick = { query ->
                binding.searchEdit.setText(query)
                binding.searchEdit.setSelection(query.length)
                runSearch(query)
            },
            // v8: per-row X deletes just that query.
            onDelete = { query ->
                lifecycleScope.launch {
                    repository.settingsStore.removeRecentSearch(query)
                }
            }
        )
        binding.recentRecycler.adapter = recentAdapter
        binding.recentClearButton.setOnClickListener { confirmClearRecent() }

        // v8: "Paste Telegram link" chip — tap it to add the
        // clipboard's t.me link as a group (via MainActivity).
        // v9.1 #16: the chip is ALWAYS visible now (not only when a
        // link is on the clipboard) — with no link it explains
        // itself instead of opening the sheet.
        binding.pasteLinkChip.setOnClickListener {
            val link = pasteLink
            if (link == null) {
                Snackbar.make(
                    binding.root,
                    R.string.search_paste_hint,
                    Snackbar.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val intent = Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_ADD_GROUP_LINK, link)
            startActivity(intent)
        }

        binding.searchEdit.addTextChangedListener { text ->
            val query = text?.toString().orEmpty()
            updatePasteChip()
            searchJob?.cancel()
            if (query.isBlank()) {
                showStartState()
                return@addTextChangedListener
            }
            searchJob = lifecycleScope.launch {
                delay(400L)
                runSearch(query)
            }
        }
        binding.searchEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                runSearch(binding.searchEdit.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }

        binding.emptyState.apply {
            setIcon(R.drawable.ic_search)
            setTitle(getString(R.string.search_start_title))
            setBody(getString(R.string.search_start_body))
            hideAction()
        }
        binding.errorState.apply {
            setTitle(getString(R.string.search_error_title))
            setBody(getString(R.string.search_error_body))
            setRetry { runSearch(binding.searchEdit.text?.toString().orEmpty()) }
        }
        showStartState()
        updatePasteChip()

        lifecycleScope.launch {
            groups = repository.observeGroups().first()
        }
        lifecycleScope.launch {
            smartOn = repository.settingsStore.smartFilter.first()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.recentSearches.collect { queries ->
                    recentAdapter.submitList(queries)
                    binding.recentContainer.visibility =
                        if (binding.searchEdit.text.isNullOrBlank() && queries.isNotEmpty()) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // v8 auto-focus: the field grabs focus and the keyboard
        // opens on every arrival — including a return via bottom-nav
        // REORDER_TO_FRONT, where onCreate does not run again.
        binding.searchEdit.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.searchEdit, InputMethodManager.SHOW_IMPLICIT)
        updatePasteChip()
    }

    override fun onDestroy() {
        searchJob?.cancel()
        stopShimmer()
        super.onDestroy()
    }

    /**
     * v8 responsive grid (spec §2C): 1 column full-width 16:9 on
     * mobile portrait (<600dp), 2 columns on 600–839dp, 3 columns
     * on >=840dp.
     */
    private fun gridSpanCount(): Int {
        return when (resources.configuration.screenWidthDp) {
            in 0..599 -> 1
            in 600..839 -> 2
            else -> 3
        }
    }

    /** v8 skeleton grid: columnCount follows the feed span (1 on mobile — never a hardcoded 2). */
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

    /**
     * v8 "Paste Telegram link" chip: visible ONLY while the field
     * is empty AND the clipboard holds a t.me link. Anything else
     * on the clipboard (or a non-empty field) hides it.
     * v9.1 #16: the chip is ALWAYS visible now — the pasteLink is
     * still only computed when the field is empty, so a tap with
     * no link shows the hint instead of opening the sheet.
     */
    private fun updatePasteChip() {
        pasteLink = if (binding.searchEdit.text.isNullOrBlank()) {
            readTelegramLinkFromClipboard()
        } else {
            null
        }
        binding.pasteLinkChip.visibility = View.VISIBLE
    }

    private fun readTelegramLinkFromClipboard(): String? {
        val clipboard =
            getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        if (!clipboard.hasPrimaryClip()) return null
        val text = clipboard.primaryClip?.getItemAt(0)
            ?.coerceToText(this)?.toString()?.trim() ?: return null
        val lower = text.lowercase()
        return if (lower.startsWith("https://t.me/") ||
            lower.startsWith("http://t.me/") ||
            lower.startsWith("t.me/")
        ) {
            text
        } else {
            null
        }
    }

    private fun showStartState() {
        showSkeleton(false)
        binding.resultsRecycler.visibility = View.GONE
        binding.resultCount.visibility = View.GONE
        binding.errorState.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyState.setTitle(getString(R.string.search_start_title))
        binding.emptyState.setBody(getString(R.string.search_start_body))
        binding.recentContainer.visibility =
            if (recentAdapter.itemCount > 0) View.VISIBLE else View.GONE
    }

    private fun runSearch(rawQuery: String) {
        // v9.2: a new search supersedes the previous one — cancel
        // its load and its timeout so a late result can never
        // overwrite the new search's state.
        searchLoadJob?.cancel()
        searchTimeoutJob?.cancel()
        val query = rawQuery.trim()
        if (query.isEmpty()) {
            showStartState()
            return
        }
        showSkeleton(true)
        binding.resultsRecycler.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.errorState.visibility = View.GONE
        binding.recentContainer.visibility = View.GONE
        // v9.2: the search has no timeout anywhere else — a hung
        // TDLib search left the skeleton up forever. After 60s it
        // fails like a search error (Retry restarts it).
        searchTimeoutJob = lifecycleScope.launch {
            delay(SEARCH_TIMEOUT_MS)
            onSearchTimeout()
        }
        searchLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            // The query is saved once it actually runs (dedup keeps
            // repeats from piling up; only query text is stored).
            repository.settingsStore.addRecentSearch(query)
            try {
                val found = repository.searchVideos(query, groups)
                withContext(Dispatchers.Main) {
                    searchTimeoutJob?.cancel()
                    showSkeleton(false)
                    // v5 Smart Filter: search results dedup too, so
                    // one episode appears once even when it was
                    // posted in four qualities.
                    if (smartOn) {
                        searchGroups = SmartFilter.groupEpisodes(found)
                        results = searchGroups.map {
                            it.best.copy(hiddenCopies = it.hiddenCount)
                        }
                    } else {
                        searchGroups = emptyList()
                        results = found
                    }
                    adapter.submitVideos(results)
                    // v9.1 #16: result count above the grid.
                    if (found.isEmpty()) {
                        binding.resultCount.visibility = View.GONE
                        binding.resultsRecycler.visibility = View.GONE
                        binding.emptyState.visibility = View.VISIBLE
                        binding.emptyState.setTitle(getString(R.string.search_no_results))
                        binding.emptyState.setBody(getString(R.string.search_no_results_body))
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.resultCount.text = getString(
                            R.string.search_results_count, results.size
                        )
                        binding.resultCount.visibility = View.VISIBLE
                        binding.resultsRecycler.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                // v9.2: a search cancelled by the timeout throws
                // here too — the timeout already showed the error
                // state, so there is nothing more to do.
                if (e is CancellationException) return@launch
                withContext(Dispatchers.Main) {
                    searchTimeoutJob?.cancel()
                    showSkeleton(false)
                    binding.resultCount.visibility = View.GONE
                    binding.resultsRecycler.visibility = View.GONE
                    binding.emptyState.visibility = View.GONE
                    binding.errorState.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * v9.2: search timeout — same mechanism as the first-page
     * timeouts in MainActivity / ChannelActivity. After 60s the
     * search fails exactly like a search error (Retry restarts it);
     * the hung search is cancelled so it can never overwrite the
     * error state late.
     */
    private fun onSearchTimeout() {
        searchLoadJob?.cancel()
        searchLoadJob = null
        showSkeleton(false)
        binding.resultCount.visibility = View.GONE
        binding.resultsRecycler.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        binding.errorState.visibility = View.VISIBLE
    }

    /**
     * v8 skeleton: a 1200ms diagonal shimmer SWEEP, not the old
     * alpha pulse. Cancelled whenever the skeleton hides AND in
     * onDestroy.
     */
    private fun showSkeleton(show: Boolean) {
        if (show) {
            binding.skeletonContainer.visibility = View.VISIBLE
            startShimmer()
        } else {
            stopShimmer()
            binding.skeletonContainer.visibility = View.GONE
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

    /** v8: Clear-all recent searches confirms via a bottom sheet (not a raw dialog). */
    private fun confirmClearRecent() {
        ConfirmSheet.show(
            this,
            getString(R.string.search_recent_clear_title),
            getString(R.string.search_recent_clear_body),
            getString(R.string.common_clear)
        ) {
            lifecycleScope.launch {
                repository.settingsStore.clearRecentSearches()
                Snackbar.make(
                    binding.root,
                    R.string.search_recent_cleared,
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openPlayer(item: VideoItem, thumb: ImageView) {
        openPlayer(item, thumb, null)
    }

    /** v5: [playlistOverride] is the copies-picker path (see ChannelActivity). */
    private fun openPlayer(
        item: VideoItem,
        thumb: ImageView,
        playlistOverride: List<VideoItem>?
    ) {
        PlayerPlaylist.items = playlistOverride ?: results
        PlayerPlaylist.chatId = item.chatId
        // v8: Search opens have no episode grouping — reset any
        // stash from an earlier open so the player's Copies pill
        // can't match a stale group (it falls back to its rebuild).
        PlayerPlaylist.episodeGroups = emptyList()
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
    private fun showCopies(best: VideoItem, thumb: ImageView) {
        val group = searchGroups.firstOrNull {
            it.best.chatId == best.chatId && it.best.messageId == best.messageId
        } ?: return
        CopiesSheet.show(
            this, group,
            onPick = { copy ->
                val playlist = results.map { shown ->
                    if (shown.chatId == best.chatId && shown.messageId == best.messageId) copy
                    else shown
                }
                openPlayer(copy, thumb, playlist)
            },
            // v9.1 #15: group name per row.
            groupNameProvider = { video ->
                groups.firstOrNull { it.chatId == video.chatId }?.title.orEmpty()
            }
        )
    }
}
