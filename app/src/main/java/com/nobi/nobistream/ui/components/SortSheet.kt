package com.nobi.nobistream.ui.components

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nobi.nobistream.databinding.ItemSheetOptionBinding
import com.nobi.nobistream.databinding.LayoutBottomSheetSortBinding

/**
 * v8 unified sort picker: a bottom sheet with radio rows (spec §4.9).
 * Replaces the raw PopupMenu + "Newest first" Snackbar echo on Home /
 * Library (6 options) and Channel (2 options). One tap picks and
 * dismisses; the accent RadioButton marks the current sort.
 */
object SortSheet {

    /**
     * v9.1 #12: [icons] is a per-option Material Symbols drawable
     * (same order as [options], 0 = no icon). Trailing + default
     * null so every existing call site compiles unchanged.
     */
    fun show(
        activity: AppCompatActivity,
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit,
        icons: List<Int>? = null
    ) {
        val dialog = BottomSheetDialog(activity)
        val binding = LayoutBottomSheetSortBinding.inflate(activity.layoutInflater)
        binding.sortTitle.text = title
        options.forEachIndexed { index, label ->
            val row = ItemSheetOptionBinding.inflate(
                activity.layoutInflater, binding.sortOptionsContainer, false
            )
            row.optionLabel.text = label
            row.optionRadio.isChecked = index == selectedIndex
            val iconRes = icons?.getOrNull(index) ?: 0
            if (iconRes != 0) {
                row.optionIcon.setImageResource(iconRes)
                row.optionIcon.visibility = View.VISIBLE
            } else {
                row.optionIcon.visibility = View.GONE
            }
            row.root.setOnClickListener {
                // v9.1 #20: haptics through the shared helper.
                Haptics.tap(it)
                onPick(index)
                dialog.dismiss()
            }
            binding.sortOptionsContainer.addView(row.root)
        }
        dialog.setContentView(binding.root)
        dialog.show()
    }
}
