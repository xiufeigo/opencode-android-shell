package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.review.audioTimeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A-13: the pure half of the audio viewer
 * (`ui/session/review/ArtifactView.kt`) — the transport readout under the seek
 * track. The `MediaPlayer` behind it needs a device, so this is what a JVM test
 * can pin: the label never renders a sign or a negative minute.
 */
class AudioArtifactTest {

    @Test
    fun theReadoutIsMinutesAndPaddedSeconds() {
        assertEquals("0:00", audioTimeLabel(0))
        assertEquals("0:00", audioTimeLabel(999))
        assertEquals("0:01", audioTimeLabel(1_000))
        assertEquals("1:05", audioTimeLabel(65_000))
        assertEquals("10:00", audioTimeLabel(600_000))
        assertEquals("59:59", audioTimeLabel(3_599_999))
    }

    @Test
    fun aNegativeOffsetClampsInsteadOfRenderingASign() {
        assertEquals("0:00", audioTimeLabel(-1))
        assertEquals("0:00", audioTimeLabel(Int.MIN_VALUE))
    }
}
