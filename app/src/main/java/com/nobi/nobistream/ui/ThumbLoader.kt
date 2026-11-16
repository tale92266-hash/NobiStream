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

/** Downloads a video thumbnail through TDLib (once) and shows it with Coil. */
object ThumbLoader {

    fun load(scope: CoroutineScope, repo: NobiRepository, item: VideoItem, imageView: ImageView) {
        val thumbId = item.thumbFileId
        val local = item.thumbLocalPath
        if (thumbId == null) {
            imageView.setImageResource(R.drawable.ic_video_placeholder)
            return
        }
        if (!local.isNullOrBlank() && File(local).exists()) {
            imageView.load(File(local)) {
                placeholder(R.drawable.ic_video_placeholder)
                error(R.drawable.ic_video_placeholder)
            }
            return
        }
        imageView.tag = thumbId
        imageView.setImageResource(R.drawable.ic_video_placeholder)
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching { repo.downloadThumbnail(thumbId) }.getOrNull()
            }
            if (imageView.tag == thumbId && !path.isNullOrBlank() && File(path).exists()) {
                imageView.load(File(path)) {
                    placeholder(R.drawable.ic_video_placeholder)
                    error(R.drawable.ic_video_placeholder)
                }
            }
        }
    }
}
