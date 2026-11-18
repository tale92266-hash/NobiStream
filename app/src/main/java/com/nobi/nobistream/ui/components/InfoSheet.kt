package com.nobi.nobistream.ui.components

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nobi.nobistream.R
import com.nobi.nobistream.databinding.ItemLicenceSectionBinding
import com.nobi.nobistream.databinding.LayoutBottomSheetInfoBinding

/**
 * v8 unified info sheet (§4.9): title + scrollable body + OK.
 * Replaces the last raw AlertDialog (the open-source licences
 * text) so no system dialog remains in the app.
 *
 * v9.1 #34: [showSections] — expandable sections (one tap opens a
 * section, the chevron rotates). Used for the licences list, where
 * each library is a section and the licence is its body.
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

    fun showSections(
        activity: AppCompatActivity,
        title: String,
        intro: String,
        sections: List<Pair<String, String>>
    ) {
        val dialog = BottomSheetDialog(activity)
        val binding = LayoutBottomSheetInfoBinding.inflate(activity.layoutInflater)
        binding.infoTitle.text = title
        binding.infoBody.visibility = View.GONE
        binding.infoIntro.text = intro
        binding.infoIntro.visibility = if (intro.isEmpty()) View.GONE else View.VISIBLE
        binding.infoSectionsContainer.visibility = View.VISIBLE
        sections.forEach { (sectionTitle, sectionBody) ->
            val row = ItemLicenceSectionBinding.inflate(
                activity.layoutInflater, binding.infoSectionsContainer, false
            )
            row.licenceTitle.text = sectionTitle
            row.licenceBody.text = sectionBody
            row.licenceHeader.setOnClickListener {
                val open = row.licenceBody.visibility != View.VISIBLE
                row.licenceBody.visibility = if (open) View.VISIBLE else View.GONE
                row.licenceChevron.animate()
                    .rotation(if (open) 90f else 0f)
                    .setDuration(200)
                    .start()
                Haptics.tap(it)
            }
            binding.infoSectionsContainer.addView(row.root)
        }
        binding.infoOkButton.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(binding.root)
        dialog.show()
    }

    /**
     * v9.1 #34: the licences list as expandable sections. Parses
     * [R.string.settings_licences_body] — intro text, then one
     * "• Library — Licence" bullet per line; the library name is
     * the section title, "Licence: <licence>" its body. Falls back
     * to the plain [show] when the text does not parse (fewer than
     * 2 bullets), so a string change can never blank the sheet.
     */
    fun showLicences(activity: AppCompatActivity) {
        val raw = activity.getString(R.string.settings_licences_body)
        val intro = raw.substringBefore("• ").trim()
        val bullets = raw.split("\n• ").drop(1)
            .map { it.trim() }.filter { it.isNotEmpty() }
        val sections = bullets.mapNotNull { bullet ->
            val parts = bullet.split(" — ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                parts[0].trim() to "Licence: ${parts[1].trim()}"
            } else {
                null
            }
        }
        if (sections.size < 2) {
            show(
                activity,
                activity.getString(R.string.settings_licences),
                raw
            )
            return
        }
        showSections(
            activity,
            activity.getString(R.string.settings_licences),
            intro,
            sections
        )
    }
}
