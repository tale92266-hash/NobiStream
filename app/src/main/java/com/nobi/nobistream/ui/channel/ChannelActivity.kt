package com.nobi.nobistream.ui.channel

import android.animation.ObjectAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

/** One group/channel: its videos, quality filter chips, search, sort. */
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
        binding.videosRecycler.layoutManager = LinearLayoutManager(this)
        binding.videosRecycler.adapter = adapter
        binding.videosRecycler.itemAnimator = SlideInUpAnimator()
        binding.videosRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val manager = recyclerView.layoutManager as LinearLayoutManager
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
            binding.sortButton.setText(
                if (sortNewest) R.string.channel_sort_newest else R.string.channel_sort_oldest
            )
            applyFilter()
        }
        binding.refreshButton.setOnClickListener { resetAndLoad() }
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

    private fun resetAndLoad() {
        allVideos.clear()
        fromMessageId = 0L
        hasMore = true
        adapter.submitList(emptyList())
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
                    loading = false
                    showSkeleton(false)
                    binding.loadMoreFooter.visibility = View.GONE
                    if (page.videos.isNotEmpty()) {
                        allVideos.addAll(page.videos)
                        fromMessageId = page.videos.last().messageId
                    }
                    if (page.videos.size < PAGE_SIZE) hasMore = false
                    applyFilter()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loading = false
                    showSkeleton(false)
                    binding.loadMoreFooter.visibility = View.GONE
                    Toast.makeText(
                        this@ChannelActivity,
                        e.message ?: getString(R.string.common_error_generic),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /** Shimmer skeleton: placeholder cards pulsing while the first page loads. */
    private fun showSkeleton(show: Boolean) {
        if (show) {
            binding.skeletonContainer.visibility = View.VISIBLE
            binding.videosRecycler.visibility = View.GONE
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
        adapter.submitList(list)
        val empty = list.isEmpty() && !loading
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.videosRecycler.visibility = if (empty) View.GONE else View.VISIBLE
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
