package com.nobi.nobistream.playback

import com.nobi.nobistream.core.VideoItem

/**
 * In-memory playlist handed from the channel list to the player, so Next /
 * Previous follow exactly the order (and filter) the user was looking at.
 * If the process was killed, the player falls back to reloading the chat.
 */
object PlayerPlaylist {
    var items: List<VideoItem> = emptyList()
    var chatId: Long = 0L
}
