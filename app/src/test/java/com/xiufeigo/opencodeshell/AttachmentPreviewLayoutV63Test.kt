package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPreviewLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v63 BUG-2 「附件预览整屏覆盖 composer」: one picked file grew the composer card
 * to the whole content pane — the editor and the tool row painted nothing and
 * nothing inside it responded to taps until the chip was removed
 * (`research/v63_verify/5558/27-29`, reproduced live on `emulator-5558`).
 *
 * The pending-attachment strip now rides in its own band above the card under
 * [AttachmentPreviewLayout.MAX_HEIGHT], so the card's measurement is the
 * editor + tool row whatever the strip is doing.
 */
class AttachmentPreviewLayoutV63Test {

    @Test
    fun theBandIsCappedAtOneStripPlusOneProgressRow() {
        assertEquals(
            AttachmentPreviewLayout.stripHeight + AttachmentPreviewLayout.PROGRESS_ROW_RESERVE,
            AttachmentPreviewLayout.MAX_HEIGHT,
        )
    }

    /** One strip row: the chip, the remove badge's overhang and its padding. */
    @Test
    fun theStripLeavesRoomForTheBadgeOverhang() {
        assertEquals(46.0 + 4.0 + 12.0, AttachmentPreviewLayout.stripHeight.value.toDouble(), 0.001)
    }

    /** The ceiling must stay a composer-sized band, never a pane-sized one. */
    @Test
    fun theCeilingIsComposerSized() {
        assertTrue(
            "band ${AttachmentPreviewLayout.MAX_HEIGHT} must stay under the 180 dp editor",
            AttachmentPreviewLayout.MAX_HEIGHT.value <= 180.0,
        )
        assertTrue(AttachmentPreviewLayout.MAX_HEIGHT.value > 0.0)
    }

    /** Adding a progress row must not change the ceiling — it is a maximum. */
    @Test
    fun theCeilingIsIndependentOfWhatTheBandShows() {
        assertEquals(AttachmentPreviewLayout.MAX_HEIGHT, AttachmentPreviewLayout.bandHeight)
        assertTrue(AttachmentPreviewLayout.bandHeight > AttachmentPreviewLayout.stripHeight)
    }
}
