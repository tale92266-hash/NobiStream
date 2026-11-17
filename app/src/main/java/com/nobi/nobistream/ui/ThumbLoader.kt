package com.nobi.nobistream.ui

import android.widget.ImageView
import coil.load
import com.nobi.nobistream.R
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.data.NobiRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads a video thumbnail through TDLib (once) and shows it with Coil.
 *
 * v4-fb2: thumbnails of Continue Watching cards (Room items) kept
 * showing the placeholder (big blue play triangle). Root cause:
 * TDLib LOCAL file ids are ephemeral — one client lifetime — while
 * Room keeps them across app restarts. An id saved before a restart
 * is dead, so DownloadFile with it fails and the placeholder stays.
 * Now the load tries, in order: (1) a thumbnail path already saved
 * in Room (instant, no TDLib call); (2) the stored thumbnail id
 * (works when it is from this session); (3) a re-resolve — re-read
 * the message via GetMessage for a FRESH thumbnail id in this
 * session, then download that. A message with genuinely no
 * thumbnail (e.g. a Document-type video Telegram never thumbnailed)
 * still ends on the graceful placeholder — but only after a real
 * attempt. A successfully downloaded path is written back to Room
 * so the next bind loads from disk.
 */
object ThumbLoader {

    fun load(scope: CoroutineScope, repo: NobiRepository, item: VideoItem, imageView: ImageView) {
        // Tag the view with the ITEM, not the thumbnail id: the id
        // can change across the fallback re-resolve below, and the
        // tag is what stops a late download from landing in a
        // recycled view that now shows another video.
        val itemKey = item.chatId to item.messageId
        imageView.tag = itemKey
        val local = item.thumbLocalPath
        if (!local.isNullOrBlank() && File(local).exists()) {
            imageView.load(File(local)) {
                placeholder(R.drawable.ic_video_placeholder)
                error(R.drawable.ic_video_placeholder)
                // Thumbnail fade 200ms (animation token); Coil already
                // sizes the decode to the view, so no oversized bitmaps.
                crossfade(200)
            }
            return
        }
        val thumbId = item.thumbFileId
        imageView.setImageResource(R.drawable.ic_video_placeholder)
        scope.launch {
            var path: String? = null
            // (2) Stored thumbnail id — the fast path for fresh items
            // (channel grid, search, library) and same-session rows.
            if (thumbId != null) {
                path = withContext(Dispatchers.IO) {
                    runCatching { repo.downloadThumbnail(thumbId) }.getOrNull()
                }
            }
            // (3) Stale or missing id: re-read the message for a
            // fresh thumbnail id in this TDLib session.
            if (path.isNullOrBlank()) {
                path = withContext(Dispatchers.IO) {
                    runCatching { repo.refreshThumbnail(item) }.getOrNull()
                }
            }
            if (!path.isNullOrBlank() && File(path).exists()) {
                // `path` is a var, so it won't smart-cast inside the
                // lambda below — pin it to a val first.
                val finalPath = path
                withContext(Dispatchers.IO) {
                    runCatching { repo.saveThumbPath(item, finalPath) }
                }
                if (imageView.tag == itemKey) {
                    imageView.load(File(finalPath)) {
                        placeholder(R.drawable.ic_video_placeholder)
                        error(R.drawable.ic_video_placeholder)
                        crossfade(200)
                    }
                }
            }
            // path still blank = genuinely no thumbnail (or the
            // download failed): the placeholder stays. Deliberate.
        }
    }
}
