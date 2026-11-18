package com.nobi.nobistream.ui.compose.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.core.VideoQuality
import com.nobi.nobistream.data.NobiRepository
import com.nobi.nobistream.ui.compose.theme.NobiGold
import com.nobi.nobistream.ui.compose.theme.NobiSuccess

/**
 * Animated gradient mesh poster, ported unchanged from the mock's
 * PremiumComponents. It is the loading placeholder / fallback UNDER
 * a real thumbnail (see VideoThumb) — never the content itself.
 */
@Composable
fun AnimatedMeshPoster(
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
    overlayContent: @Composable BoxScope.() -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "meshTransition")
    val shift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "meshShift"
    )

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.90f),
                        secondaryColor.copy(alpha = 0.70f),
                        Color(0xFF0A0E17)
                    ),
                    start = Offset(0f, shift * 280f),
                    end = Offset(480f, (1f - shift) * 520f)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = secondaryColor.copy(alpha = 0.22f),
                radius = size.minDimension * 0.45f,
                center = Offset(size.width * (0.25f + 0.35f * shift), size.height * 0.3f)
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.28f),
                radius = size.minDimension * 0.52f,
                center = Offset(size.width * (0.75f - 0.25f * shift), size.height * 0.7f)
            )
        }
        overlayContent()
    }
}

@Composable
fun QualityPillBadge(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
    contentColor: Color = MaterialTheme.colorScheme.secondary
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = contentColor.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp)
        )
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * Media card rebuilt on the REAL VideoItem: real thumbnail (mesh
 * underneath while it loads), real quality badge, real duration, real
 * progress bar + watched tick. No star rating anywhere.
 *
 * The caller sets the width via [modifier] (172dp in horizontal rows,
 * fillMaxWidth in grids). [subtitle] is the group name (search) or
 * the "group • time left" meta (Continue row). [onCopiesClick] is
 * null unless the caller wires the "N copies" badge; [onSubtitleClick]
 * is null unless the subtitle itself is a navigation target.
 */
@Composable
fun NobiMediaCard(
    item: VideoItem,
    repository: NobiRepository,
    isBookmarked: Boolean,
    subtitle: String,
    onCardClick: () -> Unit,
    onBookmarkToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onCopiesClick: ((VideoItem) -> Unit)? = null,
    onSubtitleClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onCardClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(215.dp)
            ) {
                VideoThumb(
                    repository = repository,
                    item = item,
                    modifier = Modifier.fillMaxSize()
                )
                // Top row badges.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QualityPillBadge(
                        text = item.quality.label,
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        contentColor = Color.White
                    )
                    IconButton(
                        onClick = onBookmarkToggle,
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Filled.Bookmark
                            else Icons.Outlined.BookmarkBorder,
                            contentDescription = "Watchlist",
                            tint = if (isBookmarked) NobiGold else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                // Bottom gradient overlay: duration, copies badge,
                // watched tick — then the real progress bar.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                            )
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = FormatUtils.formatDuration(item.durationSec),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.hiddenCopies > 0 && onCopiesClick != null) {
                                Text(
                                    text = "${item.hiddenCopies + 1} copies",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .clickable { onCopiesClick(item) }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            if (item.watched) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Watched",
                                    tint = NobiSuccess,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    if (item.progressFraction > 0f) {
                        LinearProgressIndicator(
                            progress = { item.progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.25f)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = FormatUtils.stripLeadingEmoji(item.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onSubtitleClick != null) {
                        Modifier.clickable { onSubtitleClick() }
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

/** Section header, as the mock: title + optional subtitle + optional action. */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String = "Explore All",
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onActionClick != null) {
            TextButton(onClick = onActionClick) {
                Text(
                    text = actionLabel,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Real quality filter chips (All / 480p / 720p / 1080p / 4K from
 * VideoQuality.filterOptions; "All" = UNKNOWN, as in the existing
 * filter code). Groups have no categories — the mock's category
 * names are deleted, the chip row shape stays.
 */
@Composable
fun QualityFilterChips(
    selected: VideoQuality,
    onSelect: (VideoQuality) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        VideoQuality.filterOptions.forEach { option ->
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = if (option == VideoQuality.UNKNOWN) "All" else option.label,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else null,
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
