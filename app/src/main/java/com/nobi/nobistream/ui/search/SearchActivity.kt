package com.nobi.nobistream.ui.search

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.ui.channel.ChannelActivity
import com.nobi.nobistream.ui.compose.screens.SearchScreen
import com.nobi.nobistream.ui.compose.theme.NobiThemeHost
import com.nobi.nobistream.ui.home.MainActivity
import com.nobi.nobistream.ui.player.PlayerActivity
import kotlinx.coroutines.launch

/**
 * v2.6.0: SearchActivity is now a thin Compose host for
 * [SearchScreen] (ui/compose). The old Views search (RecyclerView
 * grid, 400ms debounce, 60s timeout, recent searches, paste-link
 * chip, CopiesSheet Views) is gone; its behaviour is ported into
 * the Compose screen.
 *
 * Kept exactly: the player intent (chat_id / message_id extras —
 * the screen sets PlayerPlaylist itself before calling
 * [openPlayer]), the ChannelActivity intent, and the Add Group
 * link flow (a pasted t.me link opens MainActivity with
 * EXTRA_ADD_GROUP_LINK, which opens the root's Add Group sheet
 * prefilled).
 *
 * Note: in 2.6.0 Search is normally the Catalog tab inside
 * MainActivity's Compose root — this standalone activity only
 * remains for any explicit intent that targets it.
 */
class SearchActivity : AppCompatActivity() {

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository
    private val settingsStore get() = locator.settingsStore
    private val downloadManager get() = locator.downloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // v2.6.1: NobiThemeHost (see ChannelActivity) — the
            // 2.6.0 standalone host had no theme wrapper at all.
            NobiThemeHost(settingsStore = settingsStore) {
                SearchScreen(
                    repository = repository,
                    downloadManager = downloadManager,
                    settingsStore = settingsStore,
                    onPlayVideo = { item -> openPlayer(item) },
                    onOpenChannel = { chatId, title, targetMessageId ->
                        openChannel(chatId, title, targetMessageId)
                    },
                    onAddGroupLink = { link ->
                        val intent = Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_ADD_GROUP_LINK, link)
                        startActivity(intent)
                    },
                    onShowMessage = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        // Downloads resume (same as MainActivity; guarded — the
        // pump needs TDLib READY, which only holds after login).
        if (locator.gateway.authState.value == AuthState.READY) {
            lifecycleScope.launch {
                downloadManager.resumePending()
                downloadManager.start()
            }
        }
    }

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
        if (targetMessageId != null && targetMessageId != 0L) {
            intent.putExtra(ChannelActivity.EXTRA_TARGET_MESSAGE_ID, targetMessageId)
        }
        startActivity(intent)
    }
}
