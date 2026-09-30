package com.jarves.mh.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos

/**
 * Frame-driven, continuous "CSS-style" smooth scroll toward the bottom of a
 * chat list.
 *
 * ## Why not [LazyListState.animateScrollToItem]?
 * `animateScrollToItem(lastIndex)` computes its target offset when the call is
 * made, then animates toward it. While a message is streaming, the last item's
 * height keeps growing, so the target is stale the moment the animation starts
 * — the scroll lurches, snaps, and only catches up when tokens pause. That is
 * the "not smooth / only works sometimes" behaviour.
 *
 * ## What this does instead
 * Every animation frame it re-measures how far the bottom of the list still
 * sits below the viewport and eases a fraction of that distance, exactly like
 * CSS `scroll-behavior: smooth`. Because the target is recomputed each frame,
 * it stays glued to content that is still growing.
 *
 * Call this from a [androidx.compose.runtime.LaunchedEffect] keyed on the
 * content that changes while streaming; the caller's coroutine scope is used,
 * so the loop is cancelled/restarted for free when content changes.
 *
 * @param itemCount total number of items in the list.
 * @param ease fraction of the remaining distance travelled per frame
 *             (0..1). Higher is snappier, lower is more liquid.
 */
suspend fun LazyListState.smoothFollowBottom(itemCount: Int, ease: Float = 0.18f) {
    if (itemCount <= 0) return
    val lastIndex = itemCount - 1
    // Exit only after several consecutive frames already at the bottom, so a
    // brief gap between streamed tokens doesn't let the loop stall mid-stream.
    var idleFrames = 0
    while (idleFrames < IDLE_FRAMES_BEFORE_STOP) {
        // Yield immediately if the user takes over the gesture — never fight a
        // finger on the screen. The caller's follow effect will not relaunch
        // until scrolling stops and follow mode is re-enabled.
        if (isScrollInProgress) return
        val layout = layoutInfo ?: return
        val lastVisible = layout.visibleItemsInfo.lastOrNull()
        val gap = if (lastVisible != null && lastVisible.index >= lastIndex) {
            // The final message is on screen: the exact distance its bottom edge
            // still sits below the end of the viewport.
            (lastVisible.offset + lastVisible.size) - layout.viewportEndOffset
        } else {
            // The final message is further down than we can see (e.g. a large
            // block just arrived): take a viewport-sized stride toward it so the
            // scroll still glides instead of teleporting.
            (layout.viewportEndOffset - layout.viewportStartOffset) * STRIDE_WHEN_OFFSCREEN
        }
        if (gap <= SETTLED_THRESHOLD) {
            idleFrames++
        } else {
            idleFrames = 0
            // Keep a minimum step so a tiny but persistent gap still closes.
            dispatchRawDelta(gap * ease)
        }
        withFrameNanos { }
    }
}

private const val IDLE_FRAMES_BEFORE_STOP = 12
private const val SETTLED_THRESHOLD = 1f
private const val STRIDE_WHEN_OFFSCREEN = 0.45f
