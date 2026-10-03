package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.timeline.TimelineFollow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline's follow policy (v53 tE: a reader scrolling up through a short
 * window was pulled back to the newest message by every arriving token —
 * `research/v53_verify/tE/v53_07_scroll.png`). The whole
 * 「贴底跟随 / 上翻停止 / 回底恢复」 matrix is pinned here, so the rule is decided
 * in a pure function rather than in a `SideEffect` that only samples the frames
 * a recomposition happens to catch.
 *
 * The timeline is `reverseLayout`: index 0 is the newest message and sits at the
 * visual bottom, so a user "scrolling up" is the first visible index **growing**.
 */
class TimelineFollowTest {

    private val threshold = 24

    // ---- atBottom ----------------------------------------------------------

    @Test
    fun `the newest message at rest is the bottom`() {
        assertTrue(TimelineFollow.atBottom(firstIndex = 0, firstOffset = 0, thresholdPx = threshold))
    }

    @Test
    fun `one item of slack absorbs the partially visible second row`() {
        assertTrue(TimelineFollow.atBottom(firstIndex = 1, firstOffset = threshold, thresholdPx = threshold))
    }

    @Test
    fun `a few pixels past the window is still the bottom`() {
        assertFalse(TimelineFollow.atBottom(firstIndex = 0, firstOffset = threshold + 1, thresholdPx = threshold))
    }

    @Test
    fun `scrolling away from the newest is not the bottom`() {
        assertFalse(TimelineFollow.atBottom(firstIndex = 2, firstOffset = 0, thresholdPx = threshold))
        assertFalse(TimelineFollow.atBottom(firstIndex = 12, firstOffset = 0, thresholdPx = threshold))
    }

    // ---- the latch ---------------------------------------------------------

    @Test
    fun `pinned at the bottom means following`() {
        assertTrue(TimelineFollow.onScrollSample(false, 0, 0, threshold))
        assertTrue(TimelineFollow.onScrollSample(true, 1, threshold, threshold))
    }

    @Test
    fun `one scroll sample away from the bottom stops the follow`() {
        assertFalse(TimelineFollow.onScrollSample(true, 2, 0, threshold))
    }

    @Test
    fun `scrolling through several screens stays stopped`() {
        // The reported failure: after two or three screens of history the list
        // kept snapping back. The latch must survive every intermediate frame,
        // not just the first one past the window.
        var following = true
        listOf(2, 5, 9, 14, 20).forEach { index ->
            following = TimelineFollow.onScrollSample(following, index, 0, threshold)
            assertFalse("index $index must stay unfollowed", following)
        }
    }

    @Test
    fun `returning to the bottom restores the follow`() {
        val away = TimelineFollow.onScrollSample(true, 9, 0, threshold)
        assertFalse(away)
        assertTrue(TimelineFollow.onScrollSample(away, 0, 0, threshold))
    }

    @Test
    fun `the jump button re-arms the follow`() {
        val away = TimelineFollow.onScrollSample(true, 9, 0, threshold)
        assertTrue(TimelineFollow.onJumpToLatest())
        assertFalse(away)
    }

    // ---- appends -----------------------------------------------------------

    @Test
    fun `an append follows while the user is pinned`() {
        assertTrue(TimelineFollow.followOnAppend(TimelineFollow.onScrollSample(false, 0, 0, threshold)))
    }

    @Test
    fun `an append does not steal the position of a user reading history`() {
        val following = TimelineFollow.onScrollSample(true, 9, 0, threshold)
        assertFalse(TimelineFollow.followOnAppend(following))
        // …and it stays unfollowed for the next appends too: one token must not
        // re-arm the follow the user switched off.
        assertFalse(TimelineFollow.followOnAppend(following))
    }

    // ---- the 「回到最新」 button --------------------------------------------

    @Test
    fun `the jump button shows only away from the end`() {
        assertFalse(TimelineFollow.showsJumpButton(atEnd = true, hasMessages = true))
        assertTrue(TimelineFollow.showsJumpButton(atEnd = false, hasMessages = true))
    }

    @Test
    fun `the jump button stays hidden on an empty timeline`() {
        assertFalse(TimelineFollow.showsJumpButton(atEnd = false, hasMessages = false))
    }
}

/**
 * v77 P2 — the latch against a position change nobody asked for. The list
 * re-anchors by key across an append and re-lays itself when the sticky inset
 * or the dock changes height, so an off-bottom *position* is only the reader's
 * doing when a scroll put it there
 * (`research/v76_verify/5556/33_stopped.png`, `34_after_stop_next.png`).
 */
class TimelinePositionSampleTest {

    @Test
    fun `a position change with nothing scrolling leaves the latch alone`() {
        assertTrue(TimelineFollow.onPositionSample(following = true, atBottom = false, scrolling = false))
        assertFalse(TimelineFollow.onPositionSample(following = false, atBottom = false, scrolling = false))
    }

    @Test
    fun `a scroll away from the end drops the latch`() {
        assertFalse(TimelineFollow.onPositionSample(following = true, atBottom = false, scrolling = true))
    }

    @Test
    fun `returning to the end re-arms it whatever the gesture state`() {
        assertTrue(TimelineFollow.onPositionSample(following = false, atBottom = true, scrolling = true))
        assertTrue(TimelineFollow.onPositionSample(following = false, atBottom = true, scrolling = false))
    }

    @Test
    fun `a relayout cannot unpin a reader who is pinned`() {
        // The Stop settle: the append inserts its `Interrupted` divider at index
        // 0, the list keeps the row it had at offset 0 and `firstVisibleItemIndex`
        // moves past BOTTOM_ITEMS — with no scroll anywhere.
        val following = TimelineFollow.onPositionSample(
            following = true,
            atBottom = TimelineFollow.atBottom(firstIndex = 3, firstOffset = 0, thresholdPx = 24),
            scrolling = false,
        )
        assertTrue(following)
        // …so the very next append still follows, which is the message the
        // reader had just sent.
        assertTrue(TimelineFollow.followOnAppend(following))
    }

    @Test
    fun `a reader who genuinely scrolled away still keeps their position`() {
        val following = TimelineFollow.onPositionSample(following = true, atBottom = false, scrolling = true)
        assertFalse(TimelineFollow.followOnAppend(following))
        assertFalse(TimelineFollow.followOnAppend(following))
    }

    @Test
    fun `a jump to another message drops the follow explicitly`() {
        // The rail's tick: a programmatic jump away, which geometry alone cannot
        // tell from a relayout — upstream unpins for it in `navigateMessage`
        // (`interaction.ts:144`).
        val jumped = TimelineFollow.onNavigateAway()
        assertFalse(jumped)
        assertFalse(TimelineFollow.followOnAppend(jumped))
    }
}

/**
 * v63 bucket C — the search/follow handover. A live search owns the viewport
 * (it is what scrolled it off the end), so the floating button and the append
 * follow stand down for its duration and the pre-search position is handed back
 * when the query is cleared or the search is closed
 * (`research/v63_verify/5554/47_find_clear.png`, `48_find_typed_clear.png`,
 * `50_find_closed2.png`).
 */
class TimelineSearchFollowTest {

    @Test
    fun `the jump button stands down while a search is live`() {
        assertFalse(
            TimelineFollow.showsJumpButton(atEnd = false, hasMessages = true, searching = true),
        )
    }

    @Test
    fun `a cleared search releases a pinned reader back to the end`() {
        assertTrue(
            TimelineFollow.onSearchReleased(armedAtEnd = true, atEnd = false),
        )
    }

    @Test
    fun `a cleared search leaves a reader who was in history where the search put them`() {
        assertFalse(
            TimelineFollow.onSearchReleased(armedAtEnd = false, atEnd = false),
        )
        assertTrue(
            TimelineFollow.onSearchReleased(armedAtEnd = false, atEnd = true),
        )
    }

    @Test
    fun `an append never follows while a search is live`() {
        assertFalse(TimelineFollow.followOnAppendWhileSearching(following = true, searching = true))
        assertTrue(TimelineFollow.followOnAppendWhileSearching(following = true, searching = false))
        assertFalse(TimelineFollow.followOnAppendWhileSearching(following = false, searching = false))
    }

    @Test
    fun `the full residual matrix - a clear leaves no button and no follow`() {
        // A search that scrolled the reader off the end, then a cleared box.
        val searching = true
        assertFalse(TimelineFollow.showsJumpButton(atEnd = false, hasMessages = true, searching))
        // …released: the reader is back at the end, so the button is not owed.
        val released = TimelineFollow.onSearchReleased(armedAtEnd = true, atEnd = false)
        assertFalse(TimelineFollow.showsJumpButton(atEnd = true, hasMessages = true, searching = false))
        assertTrue(TimelineFollow.followOnAppend(released))
    }
}
