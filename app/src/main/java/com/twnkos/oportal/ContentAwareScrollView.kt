package com.twnkos.oportal

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * ScrollView that only intercepts/scrolls when its content is taller than the viewport.
 * Prevents the slight "rubber" scroll when a grid of tiles already fits on screen.
 *
 * Set [forceDpadPaging] for TV forms (EPG settings) so DPAD keeps scrolling inside the
 * form even when a leaf focusable would otherwise trap navigation.
 */
class ContentAwareScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    private var scrollingEnabled = false

    /** When true, DPAD pages/scrolls within this view and never escapes to outer chrome. */
    var forceDpadPaging: Boolean = false

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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (forceDpadPaging && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (moveFocusInside(FOCUS_DOWN) || pageScrollByDirection(1)) return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (moveFocusInside(FOCUS_UP) || pageScrollByDirection(-1)) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun executeKeyEvent(event: KeyEvent): Boolean {
        if (!scrollingEnabled && !forceDpadPaging) return false
        // When a deeply nested focusable (e.g. EditText) consumes DPAD_DOWN without
        // moving, still scroll the page so TV users are not trapped mid-form.
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!canScrollVertically(1) && !forceDpadPaging) return false
                    return handleVerticalDpad(event, FOCUS_DOWN, +1)
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!canScrollVertically(-1) && !forceDpadPaging) return false
                    return handleVerticalDpad(event, FOCUS_UP, -1)
                }
            }
        }
        return super.executeKeyEvent(event)
    }

    /**
     * ScrollView.arrowScroll moves focus once. A second arrowScroll when scrollY did not
     * change (focused row already on-screen) skipped every other category/service row on TV.
     */
    private fun handleVerticalDpad(event: KeyEvent, focusDirection: Int, scrollDirection: Int): Boolean {
        val focusedBefore = findFocus()
        val scrollBefore = scrollY
        val handled = super.executeKeyEvent(event)
        val focusedAfter = findFocus()
        if (focusedAfter != null && focusedAfter !== focusedBefore) {
            // Focus already advanced one step — do not arrowScroll again.
            return true
        }
        if (scrollY != scrollBefore) {
            return true
        }
        // Nested RecyclerView may have consumed the key without moving focus (custom handler).
        if (focusedBefore != null && isInsideRecyclerView(focusedBefore)) {
            return handled
        }
        arrowScroll(focusDirection)
        if (scrollY != scrollBefore || findFocus() !== focusedBefore) return true
        if (forceDpadPaging && pageScrollByDirection(scrollDirection)) return true
        return handled
    }

    private fun isInsideRecyclerView(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v is RecyclerView) return true
            v = v.parent as? View
        }
        return false
    }

    private fun moveFocusInside(direction: Int): Boolean {
        val focused = findFocus()
        val next = FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: return false
        if (next === focused || !isDescendant(next)) return false
        next.requestFocus()
        return true
    }

    private fun isDescendant(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === this) return true
            v = v.parent as? View
        }
        return false
    }

    private fun pageScrollByDirection(direction: Int): Boolean {
        updateScrollEnabled()
        val max = ((getChildAt(0)?.height ?: height) - height).coerceAtLeast(0)
        if (max <= 0) return false
        val step = (height * 0.55f).toInt().coerceAtLeast(80)
        val target = (scrollY + direction * step).coerceIn(0, max)
        if (target == scrollY) return false
        smoothScrollTo(0, target)
        return true
    }

    override fun requestChildRectangleOnScreen(
        child: View,
        rectangle: Rect,
        immediate: Boolean
    ): Boolean {
        // When content already fits, do not shift tiles up on TV focus to the last card.
        if (!scrollingEnabled) return false
        return super.requestChildRectangleOnScreen(child, rectangle, immediate)
    }
}
