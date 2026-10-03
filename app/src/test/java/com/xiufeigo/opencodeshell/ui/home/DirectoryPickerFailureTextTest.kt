package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.v2.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P3: the browse body must name the failure the user can act on.
 *
 * The regression this pins: every [TransportFailure] rendered as 「not
 * connected to a server」, so a live server whose route refused the read (or
 * whose probe simply never accepted it as a 2.x server) sent the user off to
 * fix a connection that was measurably fine. Each kind now has its own
 * message, and no two kinds share one.
 */
class DirectoryPickerFailureTextTest {

    @Test
    fun probeWindowReadsAsConnectingNotAsAFailure() {
        assertEquals(R.string.oc_summary_status_connecting, pickerFailureRes(TransportFailure.ProbePending))
    }

    @Test
    fun authWallPointsAtTheServerRow() {
        assertEquals(R.string.oc_server_error_auth, pickerFailureRes(TransportFailure.AuthRequired))
    }

    @Test
    fun unreachableSocketReadsAsAConnectionFailure() {
        assertEquals(R.string.oc_server_error_connect, pickerFailureRes(TransportFailure.Unreachable))
    }

    @Test
    fun aReachableServerWithoutTheApiIsNotReportedAsNoConnection() {
        val res = pickerFailureRes(TransportFailure.NotConnected)
        assertEquals(R.string.oc_picker_error_unusable, res)
        // The old text told the user to fix the connection; the server answered.
        assertNotEquals(R.string.oc_home_no_server, res)
    }

    @Test
    fun aRefusedReadKeepsTheServerMessageAndItsOwnText() {
        assertEquals(
            R.string.oc_picker_error_request,
            pickerFailureRes(TransportFailure.RequestFailed("boom: 500")),
        )
    }

    @Test
    fun anEmptyBodiedFailureReadsAsMissingOrUnreadable() {
        // Measured on 4095: listing a directory that is not there answers a
        // bodiless 500, which `V2Api.errorText` renders as the status alone.
        assertEquals(R.string.oc_picker_error_missing, pickerFailureRes(TransportFailure.RequestFailed("HTTP 500")))
        assertEquals(R.string.oc_picker_error_missing, pickerFailureRes(TransportFailure.RequestFailed(null)))
        assertEquals(R.string.oc_picker_error_missing, pickerFailureRes(TransportFailure.RequestFailed("  ")))
    }

    @Test
    fun anExplainedRefusalIsNotReportedAsAMissingDirectory() {
        assertEquals(
            R.string.oc_picker_error_request,
            pickerFailureRes(TransportFailure.RequestFailed("HTTP 500 PlatformError: NotFound")),
        )
        // A non-status message must never be swallowed by the status-only rule.
        assertEquals(
            R.string.oc_picker_error_request,
            pickerFailureRes(TransportFailure.RequestFailed("HTTP 500 something")),
        )
    }

    @Test
    fun noVerdictYetFallsBackToTheGenericLine() {
        assertEquals(R.string.oc_home_projects_load_failed, pickerFailureRes(null))
    }

    @Test
    fun everyKindHasItsOwnLine() {
        val kinds = listOf(
            TransportFailure.ProbePending,
            TransportFailure.AuthRequired,
            TransportFailure.Unreachable,
            TransportFailure.NotConnected,
            TransportFailure.RequestFailed("detail"),
            TransportFailure.RequestFailed(null),
            null,
        )
        val res = kinds.map { pickerFailureRes(it) }
        assertEquals("two failure kinds share one message: $res", res.size, res.toSet().size)
    }

    /**
     * v40: the friendly line above and then the raw body again — a bare
     * `HTTP 500` rendered a second time under 「目录不存在或无法读取」. The
     * status-only split is the same one [pickerFailureRes] uses, so a message
     * that adds nothing beyond the status never reaches the second line.
     */
    @Test
    fun aStatusOnlyFailureHasNoDetailLine() {
        assertNull(failureDetail(TransportFailure.RequestFailed("HTTP 500")))
        assertNull(failureDetail(TransportFailure.RequestFailed("HTTP 404")))
        assertNull(failureDetail(TransportFailure.RequestFailed("  HTTP 500  ")))
        assertNull(failureDetail(TransportFailure.RequestFailed(null)))
        assertNull(failureDetail(TransportFailure.RequestFailed("   ")))
        assertNull(failureDetail(null))
    }

    @Test
    fun anExplainedRefusalKeepsItsDetailLine() {
        assertEquals(
            "HTTP 500 PlatformError: NotFound",
            failureDetail(TransportFailure.RequestFailed("HTTP 500 PlatformError: NotFound")),
        )
        // Not status-shaped, so it is the server's own words and must survive.
        assertEquals("boom: 500", failureDetail(TransportFailure.RequestFailed("boom: 500")))
    }

    @Test
    fun onlyARefusedReadCarriesADetail() {
        // Every other kind has no server message to show.
        for (failure in listOf(
            TransportFailure.ProbePending,
            TransportFailure.AuthRequired,
            TransportFailure.Unreachable,
            TransportFailure.NotConnected,
        )) {
            assertNull("$failure must not render a detail line", failureDetail(failure))
        }
    }
}
