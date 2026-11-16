package com.nobi.nobistream.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton
import com.nobi.nobistream.R

/**
 * Reusable empty state: icon + title + body + one optional action.
 * Used on Home (no groups), Channel (no videos), Search (no results / start).
 * Never a blank screen: every empty list explains itself.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val titleView: TextView
    private val bodyView: TextView
    private val actionButton: MaterialButton

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER
        LayoutInflater.from(context).inflate(R.layout.view_empty_state, this, true)
        iconView = findViewById(R.id.emptyIcon)
        titleView = findViewById(R.id.emptyTitle)
        bodyView = findViewById(R.id.emptyBody)
        actionButton = findViewById(R.id.emptyAction)
    }

    fun setIcon(@DrawableRes resId: Int) {
        iconView.setImageResource(resId)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setBody(text: CharSequence) {
        bodyView.text = text
        bodyView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setAction(text: CharSequence, onClick: () -> Unit) {
        actionButton.text = text
        actionButton.visibility = View.VISIBLE
        actionButton.setOnClickListener { onClick() }
    }

    fun hideAction() {
        actionButton.visibility = View.GONE
    }
}
