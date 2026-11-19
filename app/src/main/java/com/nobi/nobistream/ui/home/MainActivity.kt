package com.nobi.nobistream.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.playback.PlayerPlaylist
import com.nobi.nobistream.ui.channel.ChannelActivity
import com.nobi.nobistream.ui.compose.NobiComposeRoot
import com.nobi.nobistream.ui.login.LoginActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v2.6.0: MainActivity is now a thin Compose host. The whole home
 * surface — Discover (Home), Catalog (Search), My Vault (Library),
 * Control (Settings hub) — lives in [NobiComposeRoot] (Compose,
 * ui/compose). The old Views home/library (RecyclerViews,
 * SwipeRefreshLayouts, skeletons, hero Views, BottomNavHelper) is
 * gone; its behaviour is ported into the Compose screens:
 * groups + Continue Watching + hero pager (HomeScreen), bookmarks +
 * offline queue (LibraryScreen), real Settings hub (ControlScreen).
 *
 * Kept exactly: the auth gate (not READY -> Login), the one-time
 * open-last-episode redirect, the player intent (chat_id /
 * message_id extras — the screens set [PlayerPlaylist] themselves
 * before calling [openPlayer]), the ChannelActivity intent, the
 * ContinueAllActivity entry, and the EXTRA_TAB / EXTRA_ADD_GROUP_LINK
 * contract (BottomNavHelper still references these constants).
 *
 * Downloads resume here: after the auth gate (TDLib READY) the
 * download pump is resumed + started, so a queued download
 * continues across restarts with no foreground service.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = "home"
        const val TAB_LIBRARY = "library"
        const val EXTRA_ADD_GROUP_LINK = "add_group_link"
    }

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository
    private val settingsStore get() = locator.settingsStore
    private val downloadManager get() = locator.downloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (locator.gateway.authState.value != AuthState.READY) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        val startTab = intent.getStringExtra(EXTRA_TAB)
            ?.takeIf { it == TAB_LIBRARY } ?: TAB_HOME
        val startAddGroupLink = intent.getStringExtra(EXTRA_ADD_GROUP_LINK)
        setContent {
            NobiComposeRoot(
                repository = repository,
                downloadManager = downloadManager,
                settingsStore = settingsStore,
                startTab = startTab,
                startAddGroupLink = startAddGroupLink,
                onOpenChannel = { chatId, title, targetMessageId ->
                    openChannel(chatId, title, targetMessageId)
                },
                onPlayVideo = { item -> openPlayer(item) },
                onOpenContinueAll = {
                    startActivity(Intent(this, ContinueAllActivity::class.java))
                },
                onOpenLogin = {
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                },
                onFinish = { finish() }
            )
        }
        // v2.6.0 downloads: resume queued / in-progress rows from
        // Room and start the pump (idempotent; safe to call again
        // from the other host activities after a process restore).
        lifecycleScope.launch {
            downloadManager.resumePending()
            downloadManager.start()
        }
        // v2: one-time redirect to the last-watched episode. Only
        // on a fresh start (not rotation / return from player /
        // recreate from onNewIntent) — same guard as v9.9.
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                // v2.6.1: dedup by episode — the same episode in 2
                // qualities must not open as two "last" entries.
                val items = SmartFilter.dedupContinue(
                    repository.observeContinueWatching().first()
                )
                val openLast = settingsStore.openLastEpisode.first()
                val last = items.firstOrNull()
                if (openLast && last != null) {
                    PlayerPlaylist.items = items
                    PlayerPlaylist.chatId = last.chatId
                    PlayerPlaylist.episodeGroups = emptyList()
                    PlayerPlaylist.fromContinue = true
                    openPlayer(last)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // No in-app sender of EXTRA_TAB / EXTRA_ADD_GROUP_LINK
        // remains in 2.6.0 (Search and Settings are tabs inside
        // the root now), but the contract is kept: a new intent
        // carrying either extra restarts the activity so the root
        // picks up the requested tab / prefilled Add Group sheet.
        if (intent.hasExtra(EXTRA_TAB) || intent.hasExtra(EXTRA_ADD_GROUP_LINK)) {
            recreate()
        }
    }

    /**
     * The player intent. The Compose screens set [PlayerPlaylist]
     * immediately before calling this — the host only builds the
     * intent with the two extras, exactly as v9.9 did. (The v9.9
     * shared-element transition is gone: a Compose thumb has no
     * View to transition from.)
     */
    private fun openPlayer(item: VideoItem) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_CHAT_ID, item.chatId)
            .putExtra(PlayerActivity.EXTRA_MESSAGE_ID, item.messageId)
        startActivity(intent)
    }

    private fun openChannel(chatId: Long, title: String, targetMessageId: Long? = null) {
        val intent = Intent(this, ChannelActivity::class.java)
            .putExtra(ChannelActivity.EXTRA_CHAT_ID, chatId)
            .putExtra(ChannelActivity.EXTRA_TITLE, title)
        // v2.6.1 deep scroll: the episode to land on, if any.
        if (targetMessageId != null && targetMessageId != 0L) {
            intent.putExtra(ChannelActivity.EXTRA_TARGET_MESSAGE_ID, targetMessageId)
        }
        startActivity(intent)
        // v2.6.3 (fix E): NO transition animation — the system
        // default slide drew Home + Channel at once (ghosting).
        // Same precedent as BottomNavHelper tab switches.
        overridePendingTransition(0, 0)
    }
}
