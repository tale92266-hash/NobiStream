package com.nobi.nobistream.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.ui.compose.components.QualityPillBadge
import com.nobi.nobistream.ui.compose.theme.NobiGold

/** Why the best copy won, in words (mirrors SmartFilter.bestReasonFor). */
private fun bestReasonWords(reason: SmartFilter.BestReason): String = when (reason) {
    SmartFilter.BestReason.QUALITY -> "Best quality"
    SmartFilter.BestReason.SIZE -> "Largest file"
    SmartFilter.BestReason.NEWEST -> "Newest"
    SmartFilter.BestReason.PREFERRED -> "Matches your preference"
}

/**
 * The copies sheet — the mock's sheet UI kept (28dp top radius, title
 * area, rows, two buttons), with REAL content: one row per real copy
 * of the episode from Smart Filter. No fake names, no invented
 * numbers, no audio-type line, no "Fastest" / route / mirror /
 * server words.
 *
 * Title = cleaned episode title. Subtitle = "N copies • Best: 1080p"
 * (real count, real best quality). Each row: quality label as title,
 * real size + real date as subtitle, gold "Best" badge on the Smart
 * Filter best copy with the reason in words. Selected row has the
 * selected border. "Play this copy" plays THAT copy; "Offline Save"
 * queues THAT copy for download.
 */
@Composable
fun CopiesSheetContent(
    group: EpisodeGroup,
    onPlayCopy: (VideoItem) -> Unit,
    onOfflineSave: (VideoItem) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(group) { mutableStateOf(group.best) }
    val reason = SmartFilter.bestReasonFor(group)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = FormatUtils.stripLeadingEmoji(group.best.title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${group.copies.size} copies • Best: ${group.best.quality.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        group.copies.forEach { copy ->
            val isSelected =
                copy.chatId == selected.chatId && copy.messageId == selected.messageId
            val isBest =
                copy.chatId == group.best.chatId && copy.messageId == group.best.messageId
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                label = "copyBorder"
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
                    .clickable { selected = copy },
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.CheckCircle
                            else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = copy.quality.label,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (isBest) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    QualityPillBadge(
                                        text = "Best",
                                        containerColor = NobiGold.copy(alpha = 0.2f),
                                        contentColor = NobiGold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = bestReasonWords(reason),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = listOfNotNull(
                                    FormatUtils.formatSize(copy.sizeBytes)
                                        .takeIf { copy.sizeBytes > 0 },
                                    FormatUtils.formatDate(copy.dateEpochSec)
                                        .takeIf { it.isNotEmpty() }
                                ).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Selected",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { onOfflineSave(selected) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Offline Save")
            }
            Button(
                onClick = { onPlayCopy(selected) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Play this copy")
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
    }
}

/** ModalBottomSheet wrapper the screens use for [CopiesSheetContent]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopiesSheetModal(
    group: EpisodeGroup,
    onPlayCopy: (VideoItem) -> Unit,
    onOfflineSave: (VideoItem) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        CopiesSheetContent(
            group = group,
            onPlayCopy = onPlayCopy,
            onOfflineSave = onOfflineSave,
            onDismiss = onDismiss
        )
    }
}
