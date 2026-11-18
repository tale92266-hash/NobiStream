package com.nobi.nobistream.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton
import com.nobi.nobistream.R

/**
 * Reusable error state: icon + title + what happened + Retry, plus an
 * optional dismiss action. Used on Channel (first-page load failure) and
 * in the Player (playback failure). Every error names a recovery action.
 */
class ErrorStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    private val bodyView: TextView
    private val retryButton: MaterialButton
    private val dismissButton: MaterialButton
    private val detailsButton: MaterialButton

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER
        LayoutInflater.from(context).inflate(R.layout.view_error_state, this, true)
        titleView = findViewById(R.id.errorTitle)
        bodyView = findViewById(R.id.errorBody)
        retryButton = findViewById(R.id.errorRetry)
        dismissButton = findViewById(R.id.errorDismiss)
        detailsButton = findViewById(R.id.errorDetails)
    }

    fun setIcon(@DrawableRes resId: Int) {
        findViewById<android.widget.ImageView>(R.id.errorIcon).setImageResource(resId)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    fun setBody(text: CharSequence) {
        bodyView.text = text
        bodyView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setRetry(text: CharSequence = context.getString(R.string.common_retry), onClick: () -> Unit) {
        retryButton.text = text
        retryButton.visibility = View.VISIBLE
        retryButton.setOnClickListener { onClick() }
    }

    fun hideRetry() {
        retryButton.visibility = View.GONE
    }

    fun setDismiss(text: CharSequence = context.getString(R.string.common_close), onClick: () -> Unit) {
        dismissButton.text = text
        dismissButton.visibility = View.VISIBLE
        dismissButton.setOnClickListener { onClick() }
    }

    fun hideDismiss() {
        dismissButton.visibility = View.GONE
    }

    /**
     * v9.1 #24: Details action — a quiet text button below Dismiss
     * that shows the technical detail (error message + where the
     * crash log lives) in an info sheet. Library only for now.
     */
    fun setDetails(text: CharSequence = context.getString(R.string.common_details), onClick: () -> Unit) {
        detailsButton.text = text
        detailsButton.visibility = View.VISIBLE
        detailsButton.setOnClickListener { onClick() }
    }

    fun hideDetails() {
        detailsButton.visibility = View.GONE
    }
}
