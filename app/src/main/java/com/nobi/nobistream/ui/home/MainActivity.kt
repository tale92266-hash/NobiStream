package com.nobi.nobistream.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ActivityMainBinding
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.ThumbLoader
import com.nobi.nobistream.ui.channel.ChannelActivity
import com.nobi.nobistream.ui.login.LoginActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import com.nobi.nobistream.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Home: saved groups, Continue Watching, add a group/channel link. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var continueAdapter: ContinueAdapter
    private var continueItems: List<VideoItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        if (locator.gateway.authState.value != AuthState.READY) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        groupAdapter = GroupAdapter(
            onClick = { group -> openChannel(group) },
            onLongClick = { group -> confirmRemove(group) }
        )
        binding.groupsRecycler.layoutManager = LinearLayoutManager(this)
        binding.groupsRecycler.adapter = groupAdapter
        continueAdapter = ContinueAdapter(
            onClick = { item -> openContinue(item) },
            onThumbNeeded = { item, view ->
                ThumbLoader.load(lifecycleScope, repository, item, view)
            }
        )
        binding.continueRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.continueRecycler.adapter = continueAdapter
        binding.addGroupButton.setOnClickListener { showAddGroupDialog() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeGroups().collect { groups ->
                    groupAdapter.submitList(groups)
                    binding.emptyState.visibility =
                        if (groups.isEmpty()) View.VISIBLE else View.GONE
                    binding.groupsRecycler.visibility =
                        if (groups.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeContinueWatching().collect { items ->
                    continueItems = items
                    continueAdapter.submitList(items)
                    val visible = items.isNotEmpty()
                    binding.continueTitle.visibility = if (visible) View.VISIBLE else View.GONE
                    binding.continueRecycler.visibility = if (visible) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

    private fun showAddGroupDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.home_add_link_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.home_add_group)
            .setView(input)
            .setPositiveButton(R.string.home_add) { _, _ ->
                val link = input.text?.toString()?.trim().orEmpty()
                if (link.isEmpty()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        repository.addGroup(link)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                e.message ?: getString(R.string.common_error_generic),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }

    private fun confirmRemove(group: GroupItem) {
        AlertDialog.Builder(this)
            .setTitle(group.title)
            .setMessage("Remove this group from NobiStream? Its videos stay on Telegram.")
            .setPositiveButton(R.string.common_ok) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.removeGroup(group.chatId)
                }
            }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }
}
