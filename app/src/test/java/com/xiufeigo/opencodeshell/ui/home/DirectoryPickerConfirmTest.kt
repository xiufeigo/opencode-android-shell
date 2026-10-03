package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.transportFailureFor
import com.xiufeigo.opencodeshell.v2.ServerGeneration
import com.xiufeigo.opencodeshell.v2.TransportFailure
import com.xiufeigo.opencodeshell.v2.V2Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 four-state split ([transportFailureFor]) + P1 two-tap confirm
 * ([confirmStep]): opening the picker while the generation probe is still in
 * flight must read ProbePending (never NotConnected), and the first tap on
 * 「选择此目录」 must always arm the confirm — including the initial `/`.
 */
class DirectoryPickerConfirmTest {

    private fun probe(
        httpStatus: Int?,
        authWall: Boolean = false,
    ) = V2Capability.ProbeResult(
        generation = ServerGeneration.UNKNOWN,
        httpStatus = httpStatus,
        contentType = if (httpStatus == null) null else "application/json",
        detail = "test",
        authWall = authWall,
    )

    @Test
    fun missingVerdictWithUsableServerIsProbePending() {
        assertEquals(
            TransportFailure.ProbePending,
            transportFailureFor(probing = false, lastProbe = null, serverUsable = true),
        )
    }

    @Test
    fun missingVerdictWithoutServerIsNotConnected() {
        assertEquals(
            TransportFailure.NotConnected,
            transportFailureFor(probing = false, lastProbe = null, serverUsable = false),
        )
    }

    @Test
    fun inFlightProbeBeatsAnyLatchedVerdict() {
        assertEquals(
            TransportFailure.ProbePending,
            transportFailureFor(probing = true, lastProbe = probe(200), serverUsable = true),
        )
    }

    @Test
    fun authWallMapsToAuthRequired() {
        assertEquals(
            TransportFailure.AuthRequired,
            transportFailureFor(probing = false, lastProbe = probe(401, authWall = true), serverUsable = true),
        )
    }

    @Test
    fun silentProbeMapsToUnreachable() {
        assertEquals(
            TransportFailure.Unreachable,
            transportFailureFor(probing = false, lastProbe = probe(null), serverUsable = true),
        )
    }

    @Test
    fun definitiveNegativeMapsToNotConnected() {
        assertEquals(
            TransportFailure.NotConnected,
            transportFailureFor(probing = false, lastProbe = probe(200), serverUsable = true),
        )
    }

    @Test
    fun firstTapArmsRoot() {
        assertEquals(DirectoryConfirm.Armed("/"), confirmStep(null, "/"))
    }

    @Test
    fun firstTapArmsAnyPath() {
        assertEquals(DirectoryConfirm.Armed("/home/xiufeigo"), confirmStep(null, "/home/xiufeigo"))
    }

    @Test
    fun secondTapSubmitsArmedPath() {
        assertEquals(DirectoryConfirm.Submit("/"), confirmStep("/", "/"))
        assertEquals(
            DirectoryConfirm.Submit("/home/xiufeigo"),
            confirmStep("/home/xiufeigo", "/home/xiufeigo"),
        )
    }

    @Test
    fun armedPathWinsOverLaterCurrent() {
        assertEquals(DirectoryConfirm.Submit("/a"), confirmStep("/a", "/b"))
    }

    @Test
    fun armThenSubmitRoundTrip() {
        val armed = confirmStep(null, "/home/xiufeigo") as DirectoryConfirm.Armed
        assertEquals(DirectoryConfirm.Submit(armed.path), confirmStep(armed.path, "/home/xiufeigo"))
    }

    // v41 BUG-1: the first tap arms, and a probe answering after the arm must
    // not move the browser under it.

    @Test
    fun armedConfirmBlocksProbeAdoption() {
        assertFalse(probeMayAdopt(userNavigated = false, userEdited = false, pendingConfirm = "/home/xiufeigo"))
    }

    @Test
    fun typedInputBlocksProbeAdoption() {
        assertFalse(probeMayAdopt(userNavigated = false, userEdited = true, pendingConfirm = null))
    }

    @Test
    fun navigationStillBlocksProbeAdoption() {
        assertFalse(probeMayAdopt(userNavigated = true, userEdited = false, pendingConfirm = null))
    }

    @Test
    fun untouchedPickerStillAdoptsProbedStart() {
        assertTrue(probeMayAdopt(userNavigated = false, userEdited = false, pendingConfirm = null))
    }

    @Test
    fun armedConfirmNamesOnePathEverywhere() {
        // The frozen card: the confirm body named the armed path while the
        // field and the resolved line named the freshly adopted start.
        val display = pickerConfirmPath(
            pendingConfirm = "/home/xiufeigo/projA",
            currentPath = "/mnt/c/sensorsdata",
            pathInput = "/mnt/c/sensorsdata",
        )
        assertEquals("/home/xiufeigo/projA", display)
    }

    @Test
    fun outsideConfirmTheFieldWins() {
        assertEquals("/typed", pickerConfirmPath(null, "/current", "/typed"))
        assertEquals("/current", pickerConfirmPath(null, "/current", ""))
    }

    @Test
    fun armThenProbeRewriteThenSubmitStaysOnTheArmedPath() {
        val armed = confirmStep(null, "/home/xiufeigo/projA") as DirectoryConfirm.Armed
        // The probe answers; adoption is refused, so the browsed path never
        // changes and the second tap still submits the path the user saw.
        val browsed = "/home/xiufeigo/projA"
        assertFalse(probeMayAdopt(false, false, armed.path))
        assertEquals(DirectoryConfirm.Submit(browsed), confirmStep(armed.path, browsed))
    }

    // v41 BUG-2: a relative input resolves against the directory on screen.

    @Test
    fun relativeInputResolvesAgainstTheBrowsedPath() {
        val base = pickerInputBase("/home/xiufeigo/projA", "/", "/home/xiufeigo")
        assertEquals(
            "/home/xiufeigo/projA/app/src/main",
            FsBrowse.resolveInput("app/src/main", "/home/xiufeigo", base),
        )
    }

    @Test
    fun emptyBrowserResolvesRelativeInputAgainstHomeNotTheBareRoot() {
        val base = pickerInputBase("", "/", "/home/xiufeigo")
        assertEquals("/home/xiufeigo/app", FsBrowse.resolveInput("app", "/home/xiufeigo", base))
    }

    @Test
    fun driveLevelResolvesRelativeInputAgainstTheProbedRoot() {
        assertEquals("/srv/app", FsBrowse.resolveInput("app", null, pickerInputBase(DRIVES_LEVEL, "/srv", null)))
    }

    @Test
    fun unknownBaseStaysBlankRatherThanGuessing() {
        assertEquals("", pickerInputBase("", "", null))
    }

    // v47 (`research/v47_verify/tD/tD_08_pathB.png` → `tD_09_confirm2.png`):
    // the first tap armed the browsed path, so a typed
    // `/home/xiufeigo/v36projB` froze the parent `/home/xiufeigo` on the
    // confirm card and submitted it on the second tap. The tap now arms the
    // resolved typed target instead.

    @Test
    fun typedChildDoesNotFreezeTheParent() {
        val target = pickerArmTarget(
            pathInput = "/home/xiufeigo/v36projB",
            currentPath = "/home/xiufeigo",
            browseRoot = "/",
            homeTarget = "/home/xiufeigo",
        )
        assertEquals("/home/xiufeigo/v36projB", target)
        assertEquals(DirectoryConfirm.Armed(target), confirmStep(null, target))
    }

    @Test
    fun typedPathArmsCardShowsAndSubmitsExactlyIt() {
        val typed = "/home/xiufeigo/v36projB"
        val current = "/home/xiufeigo"
        val target = pickerArmTarget(typed, current, "/", "/home/xiufeigo")
        val armed = confirmStep(null, target) as DirectoryConfirm.Armed
        // The card names the armed target everywhere while confirming.
        assertEquals(target, pickerConfirmPath(armed.path, current, typed))
        // The second tap submits the armed target, not the browsed path.
        assertEquals(DirectoryConfirm.Submit(target), confirmStep(armed.path, current))
    }

    @Test
    fun blankFieldArmsTheBrowsedPath() {
        assertEquals("/home/xiufeigo", pickerArmTarget("", "/home/xiufeigo", "/", "/home/xiufeigo"))
        assertEquals("/home/xiufeigo", pickerArmTarget("   ", "/home/xiufeigo", "/", "/home/xiufeigo"))
    }

    @Test
    fun tildeArmsTheHomeTarget() {
        assertEquals("/home/xiufeigo", pickerArmTarget("~", "/srv/app", "/", "/home/xiufeigo"))
    }

    @Test
    fun relativeInputArmsAgainstTheBrowsedPath() {
        assertEquals(
            "/home/xiufeigo/projA/app",
            pickerArmTarget("app", "/home/xiufeigo/projA", "/", "/home/xiufeigo"),
        )
    }
}
