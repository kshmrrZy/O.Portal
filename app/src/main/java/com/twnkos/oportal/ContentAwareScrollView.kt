package com.twnkos.oportal

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.widget.ScrollView

/**
 * ScrollView that only intercepts/scrolls when its content is taller than the viewport.
 * Prevents the slight "rubber" scroll when a grid of tiles already fits on screen.
 */
class ContentAwareScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private var scrollingEnabled = false

    fun updateScrollEnabled() {
        val child = getChildAt(0)
        if (child == null || width == 0 || height == 0) {
            scrollingEnabled = false
            return
        }
        // Remeasure wrap_content children (e.g. RecyclerView) so we do not leave
        // bottom tiles clipped with scrolling disabled.
        val widthSpec = MeasureSpec.makeMeasureSpec(width - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        child.measure(widthSpec, heightSpec)
        val childH = maxOf(child.height, child.measuredHeight)
        val needScroll = childH > height - paddingTop - paddingBottom + 1
        scrollingEnabled = needScroll
        overScrollMode = if (needScroll) OVER_SCROLL_IF_CONTENT_SCROLLS else OVER_SCROLL_NEVER
        if (!needScroll && scrollY != 0) {
            scrollTo(0, 0)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        updateScrollEnabled()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { updateScrollEnabled() }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!scrollingEnabled) return false
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!scrollingEnabled) return false
        return super.onTouchEvent(ev)
    }

    override fun executeKeyEvent(event: KeyEvent): Boolean {
        if (!scrollingEnabled) return false
        return super.executeKeyEvent(event)
    }

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        // Always allow focus-driven scroll so D-pad can reach bottom category tiles
        // even if updateScrollEnabled has not run yet after a layout pass.
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }
}
