package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The path field selects its whole value when it takes focus, so the first
 * character replaces the path the picker opened on instead of extending it
 * (device v62, 5560: a hand-typed path came out `/tmp/x/mnt/c/…`).
 *
 * Everything else about the field is untouched by that rule — a tap still moves
 * the caret, the suggestions still read `pathInput`, and the resolved line and
 * the confirm still name [pickerConfirmPath] — so these two helpers are the
 * whole of it.
 */
class DirectoryPickerPathSelectionTest {

    @Test
    fun focusSelectsTheWholePath() {
        assertEquals(TextRange(0, 10), pickerFocusSelection("/tmp/x/mnt"))
    }

    @Test
    fun focusOnAnEmptyFieldSelectsNothing() {
        assertEquals(TextRange(0, 0), pickerFocusSelection(""))
    }

    @Test
    fun theValueKeepsTheBrowsedPathAsItsText() {
        val value = pickerFieldValue("/home/xiufeigo", pickerFocusSelection("/home/xiufeigo"))
        assertEquals("/home/xiufeigo", value.text)
        assertEquals(TextRange(0, 14), value.selection)
    }

    @Test
    fun aSelectionLongerThanTheValueIsClamped() {
        // The value the field is handed can shrink under it (a confirm puts the
        // armed path on screen); a range past its end is an illegal value.
        val value = pickerFieldValue("/tmp", TextRange(0, 40))
        assertEquals(TextRange(0, 4), value.selection)
    }
}
