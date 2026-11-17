package com.nobi.nobistream.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.nobi.nobistream.R
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.VideoItem

/**
 * v5 Smart Filter: the copies picker.
 *
 * A best-copy card shows a "N copies" badge; tapping it opens this
 * small list of every posted copy of that episode with its quality,
 * size and date, so the user can play any copy manually — for
 * example when the best copy turns out to be corrupt. The best copy
 * is marked "Best" in the list.
 */
object CopiesSheet {

    fun show(context: Context, group: EpisodeGroup, onPick: (VideoItem) -> Unit) {
        val best = group.best
        val labels = group.copies.map { copy ->
            val quality = copy.quality.label
            val size = FormatUtils.formatSize(copy.sizeBytes)
            val date = FormatUtils.formatDate(copy.dateEpochSec)
            val parts = listOf(quality, size, date).filter { it.isNotEmpty() }
            val label = parts.joinToString(" • ")
            if (copy.chatId == best.chatId && copy.messageId == best.messageId) {
                "$label • ${context.getString(R.string.smart_copies_best)}"
            } else {
                label
            }
        }.toTypedArray()
        val episodeLabel = group.key?.let { key ->
            if (key.season != null) "S${key.season} Ep ${key.episode}"
            else "Ep ${key.episode}"
        } ?: best.title
        AlertDialog.Builder(context)
            .setTitle(
                context.getString(
                    R.string.smart_copies_title,
                    episodeLabel,
                    group.copies.size
                )
            )
            .setItems(labels) { _, which -> onPick(group.copies[which]) }
            .setNegativeButton(R.string.home_cancel, null)
            .show()
    }
}
