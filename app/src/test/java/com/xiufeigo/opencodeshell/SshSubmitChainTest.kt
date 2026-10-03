package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.SshFormError
import com.xiufeigo.opencodeshell.ui.servers.SshPrompt
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.SshSubmitFeedback
import com.xiufeigo.opencodeshell.ui.servers.sshFormErrorResId
import com.xiufeigo.opencodeshell.ui.servers.sshReadyLocalUrl
import com.xiufeigo.opencodeshell.ui.servers.sshServerEntry
import com.xiufeigo.opencodeshell.ui.servers.sshSubmitFeedback
import com.xiufeigo.opencodeshell.ui.servers.validateSshForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v90 — the **submit chain** of the Add-SSH dialog, pinned as a state machine plus
 * the structural guarantee that its feedback cannot fall off the screen.
 *
 * Device evidence (`research/v89_verify/5556/`, 1366x768 @160 dpi, 1 px = 1 dp):
 * Settings → Servers → Add SSH server, Host `127.0.0.1:4095` (and `:4097`) filled,
 * "Add server" tapped with the keyboard up and down — *no* save, *no* line, *no*
 * close, clean logcat, while the same footer's Cancel closed the dialog and the
 * primary button reported `clickable+enabled` with its enabled `#67B1C6` fill
 * (`ui_10_saved_kbd.xml`, `_t5.xml`). Every dump of an SSH state
 * (`ui_06_add_ready.xml` … `ui_10_saved_kbd.xml`, `_t3.xml` … `_t5.xml`) shows the
 * form at scroll offset 0 (its first child is always the `Host or SSH command`
 * label) and **not one** of them carries a tunnel row — `SSH tunnels`,
 * `Connecting…`, `Reconnect`, `Disconnected` appear in none — so
 * `SshTunnelHost.submit` (i.e. `SshDialog.onSubmit`) was never reached. `ui_06`
 * puts the visible form's last line on the auth select (`Authentication` y=506,
 * its `Password` value y=544) with the pinned footer at y=575: the password field
 * the gate demands, and the error line after it, drew below the clip.
 *
 * The break is therefore the *gate* half of `SshDialog.submit` (`validateSshForm`
 * refuses `PasswordRequired` on the state the tester could produce) **plus its only
 * surface** — and the dial-failure half of the same form could be lost the same
 * way. So the tap's three outcomes are pinned here as [SshSubmitFeedback]
 * (refused / failed / nothing), `onScreen` answers "the user has to be shown this",
 * and the dialog's wiring is pinned to bring `onScreen` feedback back into the
 * form's scroller on **every** attempt.
 */
class SshSubmitChainTest {

    /** The device's own form state: Host + name typed, password untouched. */
    private val deviceTarget = "127.0.0.1:4095"
    private val deviceName = "E2ECHK5556"

    private fun source(relative: String): String =
        listOf("app/$relative", relative)
            .map { File(it) }
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun dialog() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")

    private fun model() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshModel.kt")

    /** The card half of the dialog — the substring the geometry suites also pin. */
    private fun card() = dialog().substringAfter("val busy = isSshConnecting(status.stage)")

    /** Every `@string` name a file references. */
    private fun resourceIds(text: String): List<String> =
        Regex("R\\.string\\.([A-Za-z0-9_]+)")
            .findAll(text)
            .map { it.groupValues[1] }
            .toList()

    /** All of a locale's string files, concatenated (the shell keeps two). */
    private fun localeStrings(locale: String): String {
        val dir = listOf("app/src/main/res/$locale", "src/main/res/$locale")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
            ?: error("missing $locale")
        return dir.listFiles { file -> file.extension == "xml" }
            ?.sortedBy { it.name }
            ?.joinToString("\n") { it.readText() }
            ?: error("no string files under $locale")
    }

    private fun status(
        stage: SshStage = SshStage.DISCONNECTED,
        error: SshErrorCode? = null,
        prompt: SshPrompt? = null,
        localUrl: String? = null,
    ) = SshConnectionStatus(stage = stage, error = error, prompt = prompt, localUrl = localUrl)

    private val everyFormError = listOf(
        SshFormError.TargetRequired,
        SshFormError.PasswordRequired,
        SshFormError.KeyRequired,
    )

    // ------------------------------------------------- state 1: the gate refuses

    @Test
    fun theDeviceStateIsRefusedByThePasswordGateAndTheRefusalIsShown() {
        // What the v89 tester actually had: a target, a name, the default password
        // method, and no password (the field sits below the band's clip — ui_06).
        val refused = validateSshForm(deviceTarget, SshAuthMethod.PASSWORD, "", "")
        assertEquals(SshFormError.PasswordRequired, refused)
        assertEquals(
            SshSubmitFeedback.REFUSED,
            sshSubmitFeedback(refused, SshConnectionStatus()),
        )
        assertTrue("a refusal must be shown", sshSubmitFeedback(refused, status()).onScreen)
        // The other two gate branches refuse the same way: every one of them is a state
        // the user has to be told about, never a silent return.
        assertEquals(
            SshFormError.TargetRequired,
            validateSshForm("   ", SshAuthMethod.PASSWORD, "secret", ""),
        )
        assertEquals(
            SshFormError.KeyRequired,
            validateSshForm("ssh deploy@host", SshAuthMethod.KEY, null, "  "),
        )
        for (error in everyFormError) {
            assertEquals("$error", SshSubmitFeedback.REFUSED, sshSubmitFeedback(error, status()))
            assertTrue("$error must be shown", sshSubmitFeedback(error, status()).onScreen)
        }
        // The refusal line is the form's own resource, one per branch.
        val ids = everyFormError.map { sshFormErrorResId(it) }
        assertEquals(3, ids.toSet().size)
        assertTrue(ids.none { it == 0 })
    }

    @Test
    fun theGateAcceptsOnlyAWholeCredentialAndThenNothingIsShown() {
        // Legal input: the dial owns the dialog (busy → the footer's own label), so the
        // form has nothing to add on screen.
        assertNull(validateSshForm(deviceTarget, SshAuthMethod.PASSWORD, "secret", ""))
        assertNull(
            validateSshForm(
                "ssh -p 4096 deploy@host",
                SshAuthMethod.KEY,
                null,
                "~/.ssh/id_ed25519",
            ),
        )
        assertEquals(
            SshSubmitFeedback.NONE,
            sshSubmitFeedback(null, status(stage = SshStage.CONNECTING)),
        )
        assertFalse(sshSubmitFeedback(null, status(stage = SshStage.CONNECTING)).onScreen)
    }

    // ---------------------------------------------- state 2: the dial failed

    @Test
    fun aDialFailureIsTheFailedStateAndReadyIsTheOnlyWayOut() {
        // `127.0.0.1:4095` is an HTTP port: jsch's dial fails and `sshFailure` lands on
        // `connection` — the second half of the submit chain, silent until v90.
        val failed = status(stage = SshStage.FAILED, error = SshErrorCode.CONNECTION)
        assertEquals(SshSubmitFeedback.FAILED, sshSubmitFeedback(null, failed))
        assertTrue(sshSubmitFeedback(null, failed).onScreen)
        // An auth wall parks on `authentication` with the same reason — still shown.
        assertEquals(
            SshSubmitFeedback.FAILED,
            sshSubmitFeedback(
                null,
                status(stage = SshStage.AUTHENTICATION, error = SshErrorCode.CONNECTION),
            ),
        )
        // A trust / continue prompt is its own full surface (`SshAuthenticate`), so the
        // form stays quiet while it is up.
        assertEquals(
            SshSubmitFeedback.NONE,
            sshSubmitFeedback(
                null,
                status(
                    stage = SshStage.CONNECTING,
                    prompt = SshPrompt("p1", "The authenticity of host …", confirm = true),
                ),
            ),
        )
        // The success half: READY hands the loopback url to the host, the dialog closes,
        // the form has nothing to draw.
        val ready = status(stage = SshStage.READY, localUrl = "http://127.0.0.1:40123")
        assertEquals(SshSubmitFeedback.NONE, sshSubmitFeedback(null, ready))
        assertEquals("http://127.0.0.1:40123", sshReadyLocalUrl(ready))
        val entry = sshServerEntry(
            SshConnectionConfig(
                id = "c1",
                target = "ssh deploy@host",
                name = deviceName,
                auth = SshAuthMethod.PASSWORD,
            ),
            "http://127.0.0.1:40123",
        )
        assertEquals(deviceName, entry.name)
        assertEquals("deploy", entry.username)
        assertEquals("http://127.0.0.1:40123", entry.url)
        // …and the close is wired to exactly that stage.
        assertTrue(dialog().contains("if (status.stage == SshStage.READY) onDismiss()"))
        // A cleared failure (disconnect / the next attempt) is quiet again, and READY
        // without a forward is not a persisted url.
        assertEquals(SshSubmitFeedback.NONE, sshSubmitFeedback(null, status()))
        assertNull(sshReadyLocalUrl(status(stage = SshStage.READY, localUrl = null)))
    }

    @Test
    fun theRefusalOutranksAnOlderDialFailure() {
        // A refused tap does not clear the previous attempt's status, so both lines can
        // be in the group at once: the fresher refusal is what the tap has to explain,
        // and either way the group is brought back on screen.
        val older = status(stage = SshStage.FAILED, error = SshErrorCode.CONNECTION)
        assertEquals(
            SshSubmitFeedback.REFUSED,
            sshSubmitFeedback(SshFormError.PasswordRequired, older),
        )
        assertTrue(sshSubmitFeedback(SshFormError.PasswordRequired, older).onScreen)
    }

    // ------------------------------------- the feedback cannot fall off the screen

    @Test
    fun everyLineTheChainCanShowIsAStringAllThreeLocalesCarry() {
        // No new copy: every resource the dialog and its model reach for already exists
        // in every locale — the refusal / failure / stage lines this fix re-uses are the
        // ones the form has always had (a new one would have to be added three times,
        // StringsLocaleParityTest).
        val ids = (resourceIds(dialog()) + resourceIds(model())).distinct()
        assertTrue("the dialog references strings", ids.isNotEmpty())
        assertTrue(ids.contains("dialog_server_add_button"))
        assertTrue(ids.contains("oc_ssh_error_password_required"))
        for (locale in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            val xml = localeStrings(locale)
            val missing = ids.filterNot { xml.contains("name=\"$it\"") }
            assertTrue("$locale is missing $missing", missing.isEmpty())
        }
    }

    @Test
    fun theSubmitPathIsTheSameThreeStepsAndOnlyTheGateStopsIt() {
        val submit = dialog()
            .substringAfter("fun submit() {")
            .substringBefore("\n    val prompt")
        val armed = submit.indexOf("submitAttempts++")
        val gate = submit.indexOf("validateSshForm(")
        val refusal = submit.indexOf("error = invalid")
        val handOver = submit.indexOf("onSubmit(sshConfig, tunnel)")
        val dial = submit.indexOf("scope.launch { tunnel.connect() }")
        assertTrue("the attempt counter is armed first", armed in 1 until gate)
        assertTrue("the gate runs next", gate in 1 until refusal)
        assertTrue("refusing returns before anything is handed over", refusal < handOver)
        assertTrue("the host gets the tunnel", handOver in 1 until dial)
        assertTrue("and only then does the dial start", dial > handOver)
        // The gate branch stays a plain state write + return: v90 makes the *surface*
        // reachable, it does not add a second submit path.
        val refusalBlock = submit.substring(refusal, handOver).trimStart()
        assertTrue(refusalBlock.startsWith("error = invalid"))
        assertTrue(refusalBlock.contains("return"))
    }

    @Test
    fun theFeedbackIsBroughtBackIntoTheFormOnEveryAttempt() {
        val body = dialog()
        // The state machine feeds the scroll, and the attempt counter (not the value)
        // re-arms it, so tapping the same refusal twice is not silent the second time.
        assertTrue(body.contains("val feedback = sshSubmitFeedback(error, status)"))
        assertTrue(body.contains("LaunchedEffect(submitAttempts, feedback)"))
        assertTrue(body.contains("if (feedback.onScreen) {"))
        assertTrue(body.contains("feedbackRequester.bringIntoView()"))
        // The request waits one frame so the group's node is placed before the scroller
        // is asked to reveal it (an unattached requester answers nothing).
        val effect = body.substringAfter("LaunchedEffect(submitAttempts, feedback)").substringBefore("DisposableEffect")
        assertTrue(effect.contains("withFrameNanos { }"))
        assertTrue(effect.indexOf("withFrameNanos { }") < effect.indexOf("feedbackRequester.bringIntoView()"))
        assertTrue(body.contains("val feedbackRequester = remember { BringIntoViewRequester() }"))
        // The form's own scroller answers it: the group carries the requester and sits
        // inside the `verticalScroll` the band caps.
        val requester = body.indexOf("bringIntoViewRequester(feedbackRequester)")
        val scroller = body.indexOf(".verticalScroll(rememberScrollState())")
        assertTrue("the requester is in the form's own scroller", scroller in 1 until requester)
        // Both existing error slots are inside that group — the surface is the one the
        // dialog always had, so nothing new can be "the" place feedback goes, and the
        // group is the only thing the scroller is asked for.
        assertTrue(requester < body.indexOf("stringResource(sshFormErrorResId(invalid))"))
        assertTrue(requester < body.indexOf("stringResource(sshErrorResId(sshError))"))
        assertTrue(requester < body.indexOf("stringResource(sshStageResId(status.stage))"))
        assertTrue(
            "the requester is asked for once, from the form's own handle",
            1 == Regex("feedbackRequester\\.bringIntoView\\(\\)").findAll(body).count(),
        )
        // …and it is not the footer: the pinned footer keeps the 80.5 dp the geometry
        // suites pin, so the group stays above it.
        assertTrue("the feedback group is above the pinned footer", requester < body.indexOf("R.string.common_cancel"))
        assertFalse(body.contains("oc_ssh_feedback"))
    }

    @Test
    fun thePrimaryActionStaysLiveSoARefusalCanBeExplained() {
        // v89's device evidence: the button was `clickable+enabled` with the enabled
        // accent fill while the tap did nothing. Greying it out would have hidden the
        // same bug behind an unexplained disabled control, so the enabled rule is
        // untouched and the refusal line is what explains the tap.
        val card = card()
        assertTrue(card.contains("enabled = !busy && target.isNotBlank()"))
        assertTrue(card.contains("R.string.dialog_server_add_button"))
        assertTrue(card.contains("onClick = { submit() }"))
    }

    @Test
    fun theFeedbackGroupLeavesTheV89ShapeUntouched() {
        val card = card()
        // The group draws nothing of its own — no height, no padding, no second scroller —
        // and the drawn box is unchanged (the 442.5 dp the device measured).
        assertTrue(card.contains("Modifier.fillMaxWidth().bringIntoViewRequester(feedbackRequester),"))
        assertEquals("one scroller in the card", 1, Regex("verticalScroll\\(").findAll(card).count())
        assertTrue(card.contains(".verticalScroll(rememberScrollState())"))
        assertFalse(card.contains("imePadding"))
        // The band / floor / cap shape the geometry suites pin, restated here so *this*
        // suite fails with the change that disturbs it.
        assertTrue(card.contains("Modifier.height(band)"))
        assertTrue(card.contains("Modifier.heightIn(max = band)"))
        assertTrue(card.contains("Modifier.weight(1f, fill = false)"))
        assertTrue(card.contains("serverDialogBandDp("))
        assertTrue(card.contains("serverDialogBandLiftDp("))
        assertTrue(card.contains("panel?.topDp ?: -1f"))
        assertEquals(
            "the keyboard condition is what both the cap and the fill read",
            2,
            Regex(Regex.escape("imeBottomDp > 0.dp && panelHeightDp > 0f")).findAll(card).count(),
        )
        // The lines in the group keep the spacing the form's own `spacedBy(12.dp)` gave
        // them when they were three siblings, and add no padding or height of their own
        // (the footer below the form is what carries the pinned 12/24 dp box).
        val pinnedFooterComment = card.indexOf("// Cancel / Add server is pinned")
        assertTrue("the form's end is where the pinned footer starts", pinnedFooterComment > 0)
        val group = card.substring(
            card.indexOf("bringIntoViewRequester(feedbackRequester)"),
            pinnedFooterComment,
        )
        assertTrue(group.contains("verticalArrangement = Arrangement.spacedBy(12.dp)"))
        assertEquals(0, Regex("Modifier\\.padding").findAll(group).count())
        assertEquals(0, Regex("Modifier\\.height").findAll(group).count())
    }

    @Test
    fun theDiagnosisItselfIsRecordedNextToTheCode() {
        // The next round has to find *why* the surface is scrolled instead of
        // re-deriving it from the device: the evidence lives with the effect.
        val body = dialog()
        assertTrue(body.contains("research/v89_verify/5556/"))
        assertTrue(body.contains("ui_06_add_ready.xml"))
        assertTrue(body.contains("BringIntoViewResponderNode"))
        val modelText = model()
        assertTrue(modelText.contains("research/v89_verify/5556/"))
        assertTrue(modelText.contains("fun sshSubmitFeedback("))
        assertTrue(modelText.contains("val onScreen: Boolean"))
        assertTrue(modelText.contains("enum class SshSubmitFeedback"))
    }
}
