package com.nobi.nobistream.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivityContinueAllBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.player.PlayerActivity
import jp.wasabeef.recyclerview.animators.FadeInAnimator
import kotlinx.coroutines.launch

/**
 * v4 full Continue Watching list, opened from Home's "See all".
 * Every partially-watched video (no 10-item cap), newest first, with
 * progress bars; tap resumes. Reuses the Home ContinueAdapter cards
 * and the same Room resume data as the Home section.
 */
class ContinueAllActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContinueAllBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var adapter: ContinueAdapter
    private var allItems: List<VideoItem> = emptyList()
    private var groups: List<GroupItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityContinueAllBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ContinueAdapter(
            onClick = { item -> openContinue(item) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            },
            metaProvider = { item -> continueMeta(item) }
        )
        binding.continueAllRecycler.layoutManager = LinearLayoutManager(this)
        binding.continueAllRecycler.adapter = adapter
        binding.continueAllRecycler.itemAnimator = FadeInAnimator()

        binding.emptyState.apply {
            setIcon(R.drawable.ic_history)
            setTitle(getString(R.string.continue_all_empty))
            setBody(getString(R.string.continue_all_empty_body))
            hideAction()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeGroups().collect { groups = it }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeContinueWatchingAll().collect { items ->
                    allItems = items
                    adapter.submitList(items)
                    val empty = items.isEmpty()
                    binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.continueAllRecycler.visibility =
                        if (empty) View.GONE else View.VISIBLE
                }
            }
        }
    }

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

    private fun openContinue(item: VideoItem) {
        PlayerPlaylist.items = allItems
        PlayerPlaylist.chatId = item.chatId
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, item.chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        startActivity(intent)
    }
}
