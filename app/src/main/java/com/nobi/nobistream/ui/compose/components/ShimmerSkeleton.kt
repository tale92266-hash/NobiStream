package com.nobi.nobistream.ui.compose.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v2.6.1 Compose shimmer skeletons. Home / Channel / Search /
 * Library used to show a bare CircularProgressIndicator on first
 * load. ui/components/Shimmer.kt is a VIEWS ObjectAnimator helper
 * and CANNOT be reused in Compose, so this is a fresh Compose
 * implementation: an animated diagonal brush over theme-coloured
 * placeholder shapes (surfaceVariant base, onSurfaceVariant sweep).
 */

/** The travelling shimmer brush; call once per skeleton subtree. */
@Composable
fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerShift"
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val sweep = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    return Brush.linearGradient(
        colors = listOf(base, sweep, base),
        start = Offset(-400f + shift * 1200f, 0f),
        end = Offset(shift * 1200f, 400f)
    )
}

/** One shimmering placeholder block. */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    brush: Brush,
    cornerRadius: Dp = 12.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(brush)
    )
}

/** A skeleton episode row: 16:9 thumb + title / meta lines. */
@Composable
fun SkeletonEpisodeRow(brush: Brush, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        ShimmerBox(
            modifier = Modifier.size(96.dp, 54.dp),
            brush = brush,
            cornerRadius = 12.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(16.dp),
                brush = brush,
                cornerRadius = 6.dp
            )
            Spacer(modifier = Modifier.height(8.dp))
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(12.dp),
                brush = brush,
                cornerRadius = 6.dp
            )
        }
    }
}

/** Channel first load: banner + best-copy card + episode rows. */
@Composable
fun ChannelSkeleton() {
    val brush = rememberShimmerBrush()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ShimmerBox(modifier = Modifier.size(48.dp), brush = brush, cornerRadius = 24.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.6f).height(20.dp),
                        brush = brush, cornerRadius = 6.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.35f).height(12.dp),
                        brush = brush, cornerRadius = 6.dp
                    )
                }
            }
        }
        item {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(64.dp),
                brush = brush,
                cornerRadius = 18.dp
            )
        }
        items(6) { SkeletonEpisodeRow(brush = brush) }
    }
}

/** Home first load: hero + Continue row cards + group rows. */
@Composable
fun HomeSkeleton() {
    val brush = rememberShimmerBrush()
    Column(modifier = Modifier.fillMaxSize()) {
        ShimmerBox(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(370.dp),
            brush = brush,
            cornerRadius = 28.dp
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(3) {
                Column {
                    ShimmerBox(
                        modifier = Modifier.size(172.dp, 215.dp),
                        brush = brush,
                        cornerRadius = 20.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ShimmerBox(
                        modifier = Modifier.width(120.dp).height(12.dp),
                        brush = brush,
                        cornerRadius = 6.dp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        repeat(2) {
            ShimmerBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .height(76.dp),
                brush = brush,
                cornerRadius = 20.dp
            )
        }
    }
}

/** Search while a query runs: result cards. */
@Composable
fun SearchSkeleton() {
    val brush = rememberShimmerBrush()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(5) { SkeletonEpisodeRow(brush = brush) }
    }
}

/** Library while the first Room emit is pending: watchlist rows. */
@Composable
fun LibrarySkeleton() {
    val brush = rememberShimmerBrush()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(5) {
            Row(modifier = Modifier.fillMaxWidth()) {
                ShimmerBox(
                    modifier = Modifier.size(80.dp),
                    brush = brush,
                    cornerRadius = 16.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    ShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.7f).height(16.dp),
                        brush = brush, cornerRadius = 6.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ShimmerBox(
                        modifier = Modifier.fillMaxWidth(0.45f).height(12.dp),
                        brush = brush, cornerRadius = 6.dp
                    )
                }
            }
        }
    }
}
