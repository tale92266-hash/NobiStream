package com.nobi.nobistream.ui.components

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * v9.1 audit #4: the shared 1200ms diagonal shimmer SWEEP — the
 * same sweep ChannelActivity and SearchActivity run inline. One
 * helper so Home / Library skeletons and the hero shimmer can never
 * drift apart (duration, interpolator, direction).
 *
 * [start] returns the animator so the caller can [stop] it when the
 * content arrives or the screen dies; a never-started animator (the
 * container was 0 wide when the post ran) is harmless to cancel.
 * The caller must make the container VISIBLE before calling start —
 * a GONE container measures 0 wide and the sweep never starts.
 */
object Shimmer {

    /** The v8 sweep duration (spec §2F) — 1200ms, infinite, linear. */
    const val DURATION_MS = 1200L

    fun start(sweep: View, container: View): ObjectAnimator {
        val animator = ObjectAnimator.ofFloat(
            sweep, View.TRANSLATION_X,
            -sweep.width.toFloat(), 0f
        ).apply {
            duration = DURATION_MS
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        sweep.post {
            val width = container.width
            if (width == 0) return@post
            animator.setFloatValues(-sweep.width.toFloat(), width.toFloat())
            animator.start()
        }
        return animator
    }

    fun stop(animator: ObjectAnimator?) {
        animator?.cancel()
    }
}
