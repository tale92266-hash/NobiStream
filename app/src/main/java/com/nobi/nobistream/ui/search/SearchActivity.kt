package com.nobi.nobistream.ui.search

import android.animation.ObjectAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivitySearchBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.channel.GridSpacingItemDecoration
import com.nobi.nobistream.ui.channel.VideoAdapter
import com.nobi.nobistream.ui.player.PlayerActivity
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
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

    private lateinit var binding: ActivitySearchBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var adapter: VideoAdapter
    private lateinit var recentAdapter: RecentSearchAdapter

    private var groups: List<GroupItem> = emptyList()
    private var results: List<VideoItem> = emptyList()
    private var searchJob: Job? = null
    private var skeletonAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = VideoAdapter(
            onClick = { item, thumb -> openPlayer(item, thumb) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            }
        )
        val spanCount = gridSpanCount()
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

        recentAdapter = RecentSearchAdapter { query ->
            binding.searchEdit.setText(query)
            binding.searchEdit.setSelection(query.length)
            runSearch(query)
        }
        binding.recentRecycler.adapter = recentAdapter
        binding.recentClearButton.setOnClickListener { confirmClearRecent() }

        binding.searchEdit.addTextChangedListener { text ->
            val query = text?.toString().orEmpty()
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

        lifecycleScope.launch {
            groups = repository.observeGroups().first()
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

    override fun onDestroy() {
        searchJob?.cancel()
        skeletonAnimator?.cancel()
        super.onDestroy()
    }

    /** Responsive grid (pixel spec): 2 columns compact, 3 on >=600dp, 4 on >=840dp. */
    private fun gridSpanCount(): Int {
        return when (resources.configuration.screenWidthDp) {
            in 0..599 -> 2
            in 600..839 -> 3
            else -> 4
        }
    }

    private fun showStartState() {
        showSkeleton(false)
        binding.resultsRecycler.visibility = View.GONE
        binding.errorState.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.emptyState.setTitle(getString(R.string.search_start_title))
        binding.emptyState.setBody(getString(R.string.search_start_body))
        binding.recentContainer.visibility =
            if (recentAdapter.itemCount > 0) View.VISIBLE else View.GONE
    }

    private fun runSearch(rawQuery: String) {
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
        lifecycleScope.launch(Dispatchers.IO) {
            // The query is saved once it actually runs (dedup keeps
            // repeats from piling up; only query text is stored).
            repository.settingsStore.addRecentSearch(query)
            try {
                val found = repository.searchVideos(query, groups)
                withContext(Dispatchers.Main) {
                    showSkeleton(false)
                    results = found
                    adapter.submitList(found)
                    if (found.isEmpty()) {
                        binding.resultsRecycler.visibility = View.GONE
                        binding.emptyState.visibility = View.VISIBLE
                        binding.emptyState.setTitle(getString(R.string.search_no_results))
                        binding.emptyState.setBody(getString(R.string.search_no_results_body))
                    } else {
                        binding.emptyState.visibility = View.GONE
                        binding.resultsRecycler.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    showSkeleton(false)
                    binding.resultsRecycler.visibility = View.GONE
                    binding.emptyState.visibility = View.GONE
                    binding.errorState.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Shimmer skeleton: placeholder cards pulsing while searching. */
    private fun showSkeleton(show: Boolean) {
        if (show) {
            binding.skeletonContainer.visibility = View.VISIBLE
            skeletonAnimator = ObjectAnimator.ofFloat(
                binding.skeletonContainer, View.ALPHA, 0.45f, 1f
            ).apply {
                duration = 900L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } else {
            skeletonAnimator?.cancel()
            skeletonAnimator = null
            binding.skeletonContainer.visibility = View.GONE
        }
    }

    private fun confirmClearRecent() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.search_recent_clear_title)
            .setMessage(R.string.search_recent_clear_body)
            .setPositiveButton(R.string.common_clear) { _, _ ->
                lifecycleScope.launch {
                    repository.settingsStore.clearRecentSearches()
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        R.string.search_recent_cleared,
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }

    private fun openPlayer(item: VideoItem, thumb: ImageView) {
        PlayerPlaylist.items = results
        PlayerPlaylist.chatId = item.chatId
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, item.chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        val options = ActivityOptions.makeSceneTransitionAnimation(
            this, thumb, "video_thumb_${item.messageId}"
        )
        startActivity(intent, options.toBundle())
    }
}
