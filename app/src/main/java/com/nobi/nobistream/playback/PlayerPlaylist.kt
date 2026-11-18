package com.nobi.nobistream.playback

import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.VideoItem

/**
 * In-memory playlist handed from the channel list to the player, so Next /
 * Previous follow exactly the order (and filter) the user was looking at.
 * If the process was killed, the player falls back to reloading the chat.
 */
object PlayerPlaylist {
    var items: List<VideoItem> = emptyList()
    var chatId: Long = 0L

    /**
     * v8 §4.4: the full episode grouping (best + hidden copies) the
     * playlist was built from, stashed by the opener (Channel /
     * Library). The playlist itself holds only best copies when
     * Smart Filter is on, so the player's Copies pill reads the
     * group from here instead of rebuilding from the playlist —
     * rebuilding would list a single copy. Empty for Continue /
     * Search opens, where no grouping exists (the pill then falls
     * back to the rebuild and hides when it finds one copy).
     */
    var episodeGroups: List<EpisodeGroup> = emptyList()

    /**
     * v4-fb3: true when the playlist came from Continue Watching
     * (Home / See all) — a Room-only slice of at most 10 items, NOT
     * the channel's list. The player then expands it in the
     * background to the channel's full filtered list so Next /
     * Previous walk the whole channel instead of stopping at "2 / 2".
     * False for Channel / Search / Library opens, whose playlists
     * already are the list the user was looking at.
     */
    var fromContinue: Boolean = false
}
