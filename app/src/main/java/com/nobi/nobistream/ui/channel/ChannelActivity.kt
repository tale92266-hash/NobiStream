package com.nobi.nobistream.ui.channel

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
import com.nobi.nobistream.ui.compose.screens.ChannelScreen
import com.nobi.nobistream.ui.compose.theme.NobiThemeHost
import com.nobi.nobistream.ui.player.PlayerActivity
import kotlinx.coroutines.launch

/**
 * v2.6.0: ChannelActivity is now a thin Compose host for
 * [ChannelScreen] (ui/compose). The old Views channel grid
 * (RecyclerView, SwipeRefreshLayout, sort sheet, CopiesSheet Views)
 * is gone; its behaviour is ported into the Compose screen: paged
 * loads with the 60s first-page timeout, quality chips, Smart
 * Filter grouping, sort, the Compose copies sheet with the real
 * Smart Filter copies, and per-copy offline save.
 *
 * Kept exactly: EXTRA_CHAT_ID / EXTRA_TITLE, the player intent
 * (chat_id / message_id extras — the screen sets PlayerPlaylist
 * itself before calling [openPlayer]), and the back behaviour
 * (toolbar back + system back both finish).
 */
class ChannelActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_TITLE = "title"
        /**
         * v2.6.1 deep scroll: messageId of the episode to land on
         * after the first load (0 / absent = top, as before).
         */
        const val EXTRA_TARGET_MESSAGE_ID = "target_message_id"
    }

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository
    private val settingsStore get() = locator.settingsStore
    private val downloadManager get() = locator.downloadManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val targetMessageId = intent.getLongExtra(EXTRA_TARGET_MESSAGE_ID, 0L)
            .takeIf { it != 0L }
        if (chatId == 0L) {
            finish()
            return
        }
        setContent {
            // v2.6.1: every standalone Compose host wraps in
            // NobiThemeHost — without it Compose falls back to the
            // default LIGHT theme on this dark window (2.6.0 bug).
            NobiThemeHost(settingsStore = settingsStore) {
                ChannelScreen(
                    repository = repository,
                    downloadManager = downloadManager,
                    settingsStore = settingsStore,
                    chatId = chatId,
                    title = title,
                    targetMessageId = targetMessageId,
                    onBack = { finish() },
                    onPlayVideo = { item -> openPlayer(item) },
                    onShowMessage = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        // Downloads resume (same as MainActivity; guarded — the
        // pump needs TDLib READY, which only holds after login).
        // Covers a process restore straight into this activity.
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
}
