package com.nobi.nobistream.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nobi.nobistream.core.GroupItem
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.ui.compose.theme.NobiAccentCyan
import com.nobi.nobistream.ui.compose.theme.NobiPrimaryViolet
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real thumbnail for a [VideoItem], with the animated mesh poster as
 * the loading / no-thumbnail placeholder underneath — the mesh is
 * never presented as the content.
 *
 * Resolution order (the same as the Views ThumbLoader): (1) a saved
 * local path that still exists on disk; (2) the stored thumbnail id
 * for this session; (3) a re-resolve of the message for a fresh
 * thumbnail id, then download. A successfully downloaded path is
 * written back to Room so the next bind loads from disk.
 *
 * The download runs in a LaunchedEffect keyed on (chatId, messageId),
 * so recomposition never re-triggers it; the remembered path state
 * survives recomposition for the same video.
 *
 * NOTE (deviation from the brief's printed signature): the brief's
 * signature has no repository parameter, but the thumbnail download
 * needs one — [repository] is added as the first parameter.
 */
@Composable
fun VideoThumb(
    repository: NobiRepository,
    item: VideoItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val itemKey = item.chatId to item.messageId
    var thumbPath by remember(itemKey) {
        mutableStateOf(item.thumbLocalPath?.takeIf { File(it).exists() })
    }

    LaunchedEffect(itemKey) {
        if (thumbPath?.let { File(it).exists() } == true) return@LaunchedEffect
        val downloaded = withContext(Dispatchers.IO) {
            var path: String? = null
            if (item.thumbFileId != null) {
                path = runCatching { repository.downloadThumbnail(item.thumbFileId) }.getOrNull()
            }
            if (path.isNullOrBlank()) {
                path = runCatching { repository.refreshThumbnail(item) }.getOrNull()
            }
            path
        }
        if (!downloaded.isNullOrBlank() && File(downloaded).exists()) {
            withContext(Dispatchers.IO) {
                runCatching { repository.saveThumbPath(item, downloaded) }
            }
            thumbPath = downloaded
        }
    }

    val localPath = thumbPath
    Box(modifier = modifier) {
        AnimatedMeshPoster(
            primaryColor = NobiPrimaryViolet,
            secondaryColor = NobiAccentCyan,
            modifier = Modifier.matchParentSize()
        ) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        if (localPath != null && File(localPath).exists()) {
            AsyncImage(
                model = File(localPath),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

/**
 * 56dp-style group avatar: the group's saved photo when it exists on
 * disk, otherwise a letter avatar (first letter of the title) on the
 * primary colour. The caller sets the size via [modifier].
 */
@Composable
fun GroupAvatar(
    group: GroupItem,
    modifier: Modifier = Modifier
) {
    val photoPath = group.photoLocalPath
    if (!photoPath.isNullOrBlank() && File(photoPath).exists()) {
        AsyncImage(
            model = File(photoPath),
            contentDescription = group.title,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = group.title.firstOrNull()?.uppercase().orEmpty(),
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

/** Letter avatar for a bare title (channel banner when no group row is loaded). */
@Composable
fun LetterAvatar(
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.firstOrNull()?.uppercase().orEmpty(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.titleLarge
        )
    }
}
