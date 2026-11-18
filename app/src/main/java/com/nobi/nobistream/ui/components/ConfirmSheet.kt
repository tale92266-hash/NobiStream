package com.nobi.nobistream.ui.components

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nobi.nobistream.databinding.LayoutBottomSheetConfirmBinding

/**
 * v8 unified destructive confirmation: a bottom sheet with the item
 * name, a Cancel text button and a red confirm button (spec §4.9).
 * Replaces the raw AlertDialog confirms for Remove group, Logout and
 * Clear cache / Clear recent searches.
 */
object ConfirmSheet {

    fun show(
        activity: AppCompatActivity,
        title: String,
        body: String,
        confirmText: String,
        onConfirm: () -> Unit
    ) {
        val dialog = BottomSheetDialog(activity)
        val binding = LayoutBottomSheetConfirmBinding.inflate(activity.layoutInflater)
        binding.confirmTitle.text = title
        binding.confirmBody.text = body
        binding.confirmButton.text = confirmText
        binding.confirmCancelButton.setOnClickListener { dialog.dismiss() }
        binding.confirmButton.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
        dialog.setContentView(binding.root)
        dialog.show()
    }
}
