package com.nobi.nobistream.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.nobi.nobistream.ui.login.LoginActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import com.nobi.nobistream.ui.settings.SettingsActivity
import jp.wasabeef.recyclerview.animators.FadeInAnimator
import jp.wasabeef.recyclerview.animators.SlideInUpAnimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3 Home: custom 56dp app bar (brand icon + brand text + settings),
 * ONE unified Continue Watching section (featured card + row — the old
 * hero card and the row showed the same video twice), then Your Groups.
 *
 * No search action here: a dedicated Search screen does not exist in v2
 * and is listed as a missing feature needing approval (see report).
 * No Home refresh either: groups refresh from Telegram only by opening
 * a channel (v2 behaviour).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var continueAdapter: ContinueAdapter
    private var continueItems: List<VideoItem> = emptyList()
    private var groups: List<GroupItem> = emptyList()
    private var featuredItem: VideoItem? = null
    private var redirectDone = false
    private var addSheet: BottomSheetDialog? = null

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
                    binding.groupsRecycler.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeContinueWatching().collect { items ->
                    continueItems = items
                    // Unified section: featured = first item, row = the rest.
                    continueAdapter.submitList(items.drop(1))
                    val hasRow = items.size > 1
                    binding.continueTitle.visibility = if (hasRow) View.VISIBLE else View.GONE
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
    }

    override fun onDestroy() {
        addSheet?.dismiss()
        addSheet = null
        super.onDestroy()
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
