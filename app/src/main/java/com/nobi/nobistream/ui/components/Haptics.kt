package com.nobi.nobistream.ui.components

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * v9.1 audit #20: ONE place for every haptic in the app, so the
 * feedback vocabulary stays consistent — a tap is always
 * KEYBOARD_TAP, a nav-tab switch is always CLOCK_TICK, a long-press
 * is always LONG_PRESS. Callers pass the view that was touched;
 * performHapticFeedback no-ops when the device has no vibrator.
 */
object Haptics {

    /** Short tap feedback — hold-to-2x engage/release, lock/unlock, sheet rows. */
    fun tap(view: View) = view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    /** Bottom-nav tab switch tick. */
    fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

    /** Long-press confirmation. */
    fun longPress(view: View) = view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}
