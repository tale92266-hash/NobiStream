package com.nobi.nobistream.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nobi.nobistream.R
import com.nobi.nobistream.core.EpisodeGroup
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.SmartFilter
import com.nobi.nobistream.core.VideoItem
import com.nobi.nobistream.databinding.ItemCopiesRowBinding
import com.nobi.nobistream.databinding.LayoutBottomSheetCopiesBinding

/**
 * v5 Smart Filter: the copies picker — v8 as a real bottom sheet
 * (spec §4.9), not the old AlertDialog list.
 *
 * A best-copy card shows a "N copies" badge; tapping it opens this
 * sheet with every posted copy of that episode — quality pill, file
 * size and date per row. The best copy is marked with an amber
 * "Best" pill (@color/quality_badge, black text) + an accent
 * checkmark. Single tap plays that copy. The 24dp top radius,
 * 32x4dp handle and 20sp title come from the app bottom-sheet
 * theme / shared sheet layout, like SortSheet and ConfirmSheet.
 *
 * Public API is unchanged: show(context, group, onPick).
 */
object CopiesSheet {

    /**
     * v9.1 #15: [groupNameProvider] resolves the channel name for a
     * copy (Main/Search look it up from the groups list, Channel /
     * Player pass their channel title). Blank = no name shown.
     */
    fun show(
        context: Context,
        group: EpisodeGroup,
        onPick: (VideoItem) -> Unit,
        groupNameProvider: ((VideoItem) -> String)? = null
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = LayoutBottomSheetCopiesBinding.inflate(LayoutInflater.from(context))
        val best = group.best
        binding.copiesTitle.text = context.getString(
            R.string.smart_copies_title,
            episodeLabel(group),
            group.copies.size
        )
        // v9.1 #15: WHY this copy is Best — the first criterion where
        // it beats the second-best copy (rank, then size, then date).
        val reason = SmartFilter.bestReasonFor(group)
        val bestText = context.getString(
            when (reason) {
                SmartFilter.BestReason.QUALITY -> R.string.smart_best_quality
                SmartFilter.BestReason.SIZE -> R.string.smart_best_size
                SmartFilter.BestReason.NEWEST -> R.string.smart_best_newest
                SmartFilter.BestReason.PREFERRED -> R.string.smart_best_preferred
            }
        )
        group.copies.forEach { copy ->
            val row = ItemCopiesRowBinding.inflate(
                LayoutInflater.from(context), binding.copiesRowsContainer, false
            )
            row.copyQuality.text = copy.quality.label
            val size = FormatUtils.formatSize(copy.sizeBytes)
            val date = FormatUtils.formatDate(copy.dateEpochSec)
            // v9.1 #15: group name leads the meta ("Channel • 1.4 GB • 12 Nov").
            val groupName = groupNameProvider?.invoke(copy).orEmpty()
            row.copyMeta.text =
                listOf(groupName, size, date).filter { it.isNotEmpty() }.joinToString(" • ")
            val isBest = copy.chatId == best.chatId && copy.messageId == best.messageId
            row.copyBest.visibility = if (isBest) View.VISIBLE else View.GONE
            if (isBest) row.copyBest.text = bestText
            row.copyCheck.visibility = if (isBest) View.VISIBLE else View.GONE
            row.root.setOnClickListener {
                dialog.dismiss()
                onPick(copy)
            }
            binding.copiesRowsContainer.addView(row.root)
        }
        dialog.setContentView(binding.root)
        dialog.show()
    }

    private fun episodeLabel(group: EpisodeGroup): String {
        return group.key?.let { key ->
            if (key.season != null) "S${key.season} Ep ${key.episode}"
            else "Ep ${key.episode}"
        } ?: group.best.title
    }
}
