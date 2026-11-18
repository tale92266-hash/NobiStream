package com.nobi.nobistream.ui.components

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nobi.nobistream.databinding.LayoutBottomSheetInfoBinding

/**
 * v8 unified info sheet (§4.9): title + scrollable body + OK.
 * Replaces the last raw AlertDialog (the open-source licences
 * text) so no system dialog remains in the app.
 */
object InfoSheet {

    fun show(activity: AppCompatActivity, title: String, body: String) {
        val dialog = BottomSheetDialog(activity)
        val binding = LayoutBottomSheetInfoBinding.inflate(activity.layoutInflater)
        binding.infoTitle.text = title
        binding.infoBody.text = body
        binding.infoOkButton.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(binding.root)
        dialog.show()
    }
}
