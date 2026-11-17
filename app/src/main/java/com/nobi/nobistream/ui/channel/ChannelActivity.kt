package com.nobi.nobistream.ui.channel

import android.animation.ObjectAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
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
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivityChannelBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.player.PlayerActivity
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One group/channel: its videos in the SAME 2-column VideoCard grid as the
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
    private var skeletonAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_CONTENT_TRANSITIONS)
        enableEdgeToEdge()
        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        binding.toolbar.title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = VideoAdapter(
            onClick = { item, thumb -> openPlayer(item, thumb) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            }
        )
        val spanCount = gridSpanCount()
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
        binding.searchEdit.addTextChangedListener { text ->
            query = text?.toString()?.trim().orEmpty()
            applyFilter()
        }
        binding.sortButton.setOnClickListener {
            sortNewest = !sortNewest
            binding.sortButton.contentDescription = getString(
                if (sortNewest) R.string.channel_sort_newest else R.string.channel_sort_oldest
            )
            applyFilter()
            Snackbar.make(
                binding.root,
                if (sortNewest) R.string.channel_sort_newest else R.string.channel_sort_oldest,
                Snackbar.LENGTH_SHORT
            ).show()
        }
        binding.refreshButton.setOnClickListener { resetAndLoad() }
        binding.loadMoreButton.setOnClickListener { loadPage() }
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
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch {
            repository.settingsStore.setLastChat(chatId, currentFilter)
        }
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

    private fun resetAndLoad() {
        allVideos.clear()
        fromMessageId = 0L
        hasMore = true
        adapter.submitList(emptyList())
        binding.errorState.visibility = View.GONE
        loadPage()
    }

    private fun loadPage() {
        if (loading || !hasMore) return
        loading = true
        updateLoadMoreButton()
        val firstPage = allVideos.isEmpty()
        if (firstPage) showSkeleton(true) else binding.loadMoreFooter.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val page = repository.loadVideos(chatId, fromMessageId, PAGE_SIZE)
                withContext(Dispatchers.Main) {
                    loading = false
                    showSkeleton(false)
                    binding.loadMoreFooter.visibility = View.GONE
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
                    updateLoadMoreButton()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    showSkeleton(false)
                    binding.loadMoreFooter.visibility = View.GONE
                    updateLoadMoreButton()
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
     * v4 explicit Load more button: visible whenever more videos may
     * exist (hasMore), disabled with a loading label while a page
     * loads. Auto-load-on-scroll stays, but the button is the
     * guaranteed path — scrolling never has to be the only way.
     */
    private fun updateLoadMoreButton() {
        val show = hasMore && allVideos.isNotEmpty()
        binding.loadMoreButton.visibility = if (show) View.VISIBLE else View.GONE
        binding.loadMoreButton.isEnabled = !loading
        binding.loadMoreButton.text = getString(
            if (loading) R.string.channel_loading_more else R.string.channel_load_more
        )
    }

    /** Shimmer skeleton: placeholder cards pulsing while the first page loads. */
    private fun showSkeleton(show: Boolean) {
        if (show) {
            binding.skeletonContainer.visibility = View.VISIBLE
            binding.videosRecycler.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
            binding.errorState.visibility = View.GONE
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
            binding.videosRecycler.visibility = View.VISIBLE
        }
    }

    private fun applyFilter() {
        var list = allVideos.toList()
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
        val empty = list.isEmpty() && !loading
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.videosRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        if (!empty) binding.errorState.visibility = View.GONE
        updateLoadMoreButton()
    }

    private fun openPlayer(item: VideoItem, thumb: ImageView) {
        PlayerPlaylist.items = displayedVideos
        PlayerPlaylist.chatId = chatId
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        val options = ActivityOptions.makeSceneTransitionAnimation(
            this, thumb, "video_thumb_${item.messageId}"
        )
        startActivity(intent, options.toBundle())
    }
}
