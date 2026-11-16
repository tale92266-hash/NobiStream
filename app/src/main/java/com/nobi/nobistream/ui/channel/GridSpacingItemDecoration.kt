package com.nobi.nobistream.ui.channel

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid gaps for the 2-column video grid (pixel spec): 12dp between columns,
 * 20dp between rows. The outer 16dp screen margin comes from the
 * RecyclerView's own padding, so this decoration only adds the gaps
 * between items — cards never stretch to fill extra width.
 */
class GridSpacingItemDecoration(
    private val spanCount: Int,
    private val horizontalGapPx: Int,
    private val verticalGapPx: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % spanCount
        val halfH = horizontalGapPx / 2
        val halfV = verticalGapPx / 2
        outRect.left = if (column == 0) 0 else halfH
        outRect.right = if (column == spanCount - 1) 0 else halfH
        outRect.top = if (position < spanCount) 0 else halfV
        outRect.bottom = halfV
    }
}
