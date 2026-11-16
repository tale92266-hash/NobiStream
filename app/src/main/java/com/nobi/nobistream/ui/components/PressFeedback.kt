package com.nobi.nobistream.ui.components

import android.view.MotionEvent
import android.view.View

/**
 * Press feedback (pixel spec): a subtle ~0.97 scale on press + the native
 * ripple, 100–130ms. Applied to card roots (video / group / continue
 * cards). The touch listener returns false, so clicks and ripples still
 * work exactly as before.
 */
object PressFeedback {

    private const val PRESSED_SCALE = 0.97f
    private const val DURATION_MS = 120L

    fun apply(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(PRESSED_SCALE)
                    .scaleY(PRESSED_SCALE)
                    .setDuration(DURATION_MS)
                    .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(DURATION_MS)
                    .start()
            }
            false
        }
    }
}
