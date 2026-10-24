package com.tv.player.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import kotlin.math.abs

/**
 * A vertical list of rows (a title and a horizontal list of cards each) for the TV home. Two things a plain scroll view
 * gets wrong with a remote:
 *  - Up / Down first scroll the list in small steps and only then move the focus, so crossing a row took three presses.
 *    Here the focus goes straight to the next card.
 *  - When the focus lands on a card, only that card is brought into view, which leaves the row's title above the edge
 *    and the card half under the fade. Here the list scrolls to the row, its title [titleGap] below the top edge.
 * Moving sideways inside a row asks for the same position, so nothing moves.
 */
class RowScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : NestedScrollView(context, attrs) {
    /** Space kept above a row's title; 0 leaves the normal behaviour (phones, touch). */
    var titleGap = 0

    override fun arrowScroll(direction: Int): Boolean {
        if (titleGap <= 0 || (direction != FOCUS_UP && direction != FOCUS_DOWN)) return super.arrowScroll(direction)
        val current = findFocus()
        val next = FocusFinder.getInstance().findNextFocus(this, if (current === this) null else current, direction)
        // Nothing further in this direction inside the list: the normal handling decides (it lets the key out of here).
        return if (next != null && next !== this && next !== current) next.requestFocus(direction) else super.arrowScroll(direction)
    }

    /** [rect] is the focused card in this view's content coordinates. Scrolls to its row itself (smoothly) and asks for no jump. */
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val list = if (childCount > 0) getChildAt(0) as? ViewGroup else null
        val row = list?.let { l -> (0 until l.childCount).map { l.getChildAt(it) }.firstOrNull { rect.centerY() >= l.top + it.top && rect.centerY() < l.top + it.bottom } }
        if (titleGap <= 0 || list == null || row == null) return super.computeScrollDeltaToGetChildRectOnScreen(rect)
        val target = (list.top + row.top - titleGap).coerceIn(0, maxOf(0, list.bottom + paddingBottom - height))
        if (abs(scrollY - target) > 2) smoothScrollTo(0, target)
        return 0
    }
}
