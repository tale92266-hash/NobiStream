package com.nobi.nobistream.ui.components

import android.view.HapticFeedbackConstants
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

    fun show(
        activity: AppCompatActivity,
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onPick: (Int) -> Unit
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
            row.root.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPick(index)
                dialog.dismiss()
            }
            binding.sortOptionsContainer.addView(row.root)
        }
        dialog.setContentView(binding.root)
        dialog.show()
    }
}
