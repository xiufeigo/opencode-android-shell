package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshFormError
import com.xiufeigo.opencodeshell.ui.servers.SshFormField
import com.xiufeigo.opencodeshell.ui.servers.sshFormDrawnFields
import com.xiufeigo.opencodeshell.ui.servers.sshFormErrorField
import com.xiufeigo.opencodeshell.ui.servers.sshFormFieldsAllReachable
import com.xiufeigo.opencodeshell.ui.servers.sshFormRequiredFields
import com.xiufeigo.opencodeshell.ui.servers.validateSshForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v98 D3 — 「Add/Edit SSH 表单的字段不可达：口令字段落在裁切线外、被剔出 a11y 树，
 * 导致表单根本提交不了」(`research/v97_verify/5554_v98d3/REPORT_COVERAGE4.md`
 * §2.1/§2.5、`REPORT_COVERAGE5.md` §4.3).
 *
 * The device run's finding, in its own words: the form's body is a **nested
 * ScrollView** whose inner viewport is ~136.5 dp (80.5 dp pinned footer + the
 * form's own 56 dp floor — `SERVER_DIALOG_WANTED_BAND_DP`) while the form's
 * content runs to the password field at ~218 dp, so the password field is the
 * deepest field of the `PASSWORD` branch and every scroll position that still
 * showed the form's head left it below the clip. **A node fully outside the clip
 * is pruned from the semantics tree** — 11 scroll positions across two rounds
 * never surfaced it, so it was not focusable, not clickable and not addressable
 * by any a11y client. `validateSshForm` then refused every submit with
 * `PasswordRequired` and `onSubmit` was never reached: no entry persisted, no
 * tunnel row created.
 *
 * So this suite pins the two halves of the fix, and the boundary it must not
 * cross:
 *
 *  1. **可达** — the form's host is scrollable, and **every** field every branch
 *     draws carries its own `BringIntoViewRequester`, so a refusal reveals the
 *     field it names instead of only drawing a line about it (source pins, the
 *     same shape as the row-delete fix in `SshTunnelDeleteV91Test` /
 *     `SshDeletePathV97Test`).
 *  2. **未放宽** — the gate is untouched. `PasswordRequired` still exists, still
 *     fires on an empty password, and `Add server` still requires a non-blank
 *     target. The fix makes the password field **reachable**, never optional.
 */
class SshFormReachabilityV98Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun dialog() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")

    /** The dialog's body with `//` line comments dropped, so a pin cannot pass on prose. */
    private fun dialogCode(): String = dialog()
        .lines()
        .map { it.substringBefore("//") }
        .joinToString("\n")

    /**
     * The `revealRefusedField` body, from its signature to the next top-level
     * declaration. Bounded by a *declaration*, not by a brace: the body contains
     * `withFrameNanos { }` and a `runCatching { … }`, so any `}`-based cut stops
     * inside the function and the rest of it silently vanishes from the window.
     */
    private fun reveal(): String = dialogCode()
        .substringAfter("suspend fun revealRefusedField")
        .substringBefore("LaunchedEffect(connection)")

    /** The card: from the outer `Column(` to the end of the file. */
    private fun card() = dialog().substringAfter("Column(\n        Modifier\n            .width(width)")

    /** Just the `submit` local fun. */
    private fun submit() = dialog()
        .substringAfter("fun submit() {")
        .substringBefore("\n    }\n")

    // ------------------------------------------- 1a: the host can scroll at all

    @Test
    fun theFormHostIsScrollableAndTheFieldsAreInsideThatScroller() {
        val body = dialogCode()
        // Exactly one scroller in the card, and it is the form's own body — the
        // v90 pin's shape, restated so *this* suite fails with the change that
        // disturbs it.
        assertEquals(1, Regex("verticalScroll\\(").findAll(card()).count())
        assertTrue(body.contains(".verticalScroll(rememberScrollState())"))

        val scroller = body.indexOf(".verticalScroll(rememberScrollState())")
        // Every field's requester must be composed **inside** that scroller: a
        // handle attached outside it would answer a request nobody can honour.
        for (field in SshFormField.entries) {
            val handle = body.indexOf("bringIntoViewRequester(${field.requesterName()})")
            assertTrue("no requester for $field", handle > 0)
            assertTrue("$field is outside the scroller", scroller < handle)
        }
    }

    // --------------------------------- 1b: every drawn field carries a handle

    @Test
    fun everyFieldEveryBranchDrawsHasItsOwnRevealHandle() {
        val body = dialogCode()
        // One `remember { BringIntoViewRequester() }` per field, plus the v90
        // feedback group's own — so the count is the field count *plus one*.
        assertEquals(
            SshFormField.entries.size + 1,
            Regex("remember \\{ BringIntoViewRequester\\(\\) \\}").findAll(body).count(),
        )
        // …bound to its enum constant, so a handle can never be handed to the
        // wrong field (which would make a refusal reveal the wrong input).
        for (field in SshFormField.entries) {
            val bound = Regex("bringIntoViewRequester\\(${field.requesterName()}\\)")
                .findAll(body)
                .count()
            assertEquals("$field needs exactly one composed field", 1, bound)
            assertTrue(
                "$field is not in the handle map",
                body.contains("SshFormField.${field.name} to ${field.requesterName()}"),
            )
        }
    }

    /** The dialog's per-field handle locals, named after the enum constant. */
    private fun SshFormField.requesterName(): String = when (this) {
        SshFormField.Target -> "targetRequester"
        SshFormField.Name -> "nameRequester"
        SshFormField.Password -> "passwordRequester"
        SshFormField.PrivateKeyPath -> "privateKeyRequester"
        SshFormField.Passphrase -> "passphraseRequester"
    }

    @Test
    fun thePasswordFieldIsTheOneTheFixNamed() {
        // The field the v98 run could not reach is the password one; this pins
        // that the fix touched exactly it, and did not "solve" the problem by
        // touching the feedback line alone (which is what v90 already did).
        val body = dialogCode()
        assertTrue(body.contains("bringIntoViewRequester(passwordRequester)"))
        // The password field is the **last field the PASSWORD branch draws** —
        // that is what put it past the clip. So its handle is composed after its
        // branch's own first field and before the `KEY` branch starts, i.e. the
        // handle sits where the field sits, in the same `when (auth)`.
        val branchStart = body.indexOf("SshAuthMethod.PASSWORD -> {")
        val branchEnd = body.indexOf("SshAuthMethod.KEY -> {")
        assertTrue("the PASSWORD branch is not found", branchStart in 1 until branchEnd)
        val passwordField = body.indexOf("bringIntoViewRequester(passwordRequester)")
        val hostField = body.indexOf("bringIntoViewRequester(targetRequester)")
        assertTrue(
            "the password field is composed in the PASSWORD branch",
            branchStart < passwordField && passwordField < branchEnd,
        )
        assertTrue(
            "the password field is the deepest one of its branch",
            hostField < passwordField,
        )
    }

    // --------------------------------------- 1c: a refusal reveals its own field

    @Test
    fun aRefusalRevealsTheFieldItPointsAt() {
        val body = dialogCode()
        // The v90 effect still brings the **line** back…
        assertTrue(body.contains("feedbackRequester.bringIntoView()"))
        // …and now also the field, through the same attempt key so a repeat
        // refusal is not silent the second time either.
        val effect = body
            .substringAfter("LaunchedEffect(submitAttempts, feedback)")
            .substringBefore("DisposableEffect")
        assertTrue(effect.contains("revealRefusedField(refusal)"))
        // The reveal is a bring-into-view request, not a scroll write: it walks
        // the whole responder chain (this scroller *and* the panel's outer one),
        // which is what puts the field back in the semantics tree.
        assertTrue(body.contains("runCatching { fieldRequesters[sshFormErrorField(refusal)]?.bringIntoView() }"))
        // The wait-for-a-frame reason the v90 request has: an unattached or
        // unplaced node answers nothing.
        val reveal = reveal()
        assertTrue(reveal.contains("withFrameNanos { }"))
        assertTrue(reveal.contains("bringIntoView()"))
        assertTrue(
            reveal.indexOf("withFrameNanos { }") < reveal.indexOf("bringIntoView()"),
        )
    }

    /**
     * The refusal's own safety net: it is `runCatching`-wrapped, so a request
     * with no attached node (a branch that has not composed that field) cannot
     * throw out of the effect and take the form's other state writes with it.
     */
    @Test
    fun theRevealCannotThrowOutOfTheEffect() {
        val body = dialogCode()
        val reveal = reveal()
        assertTrue(reveal.contains("runCatching"))
        // It is a reveal, never a scroll-state write: the form's own
        // `rememberScrollState()` is untouched, so v90's geometry and the band
        // arithmetic the geometry suites pin are all still the same code.
        assertEquals(1, Regex("rememberScrollState\\(\\)").findAll(body).count())
    }

    // ----------------------------------------------- 2: the gate is NOT relaxed

    @Test
    fun theGateStillDemandsThePassword() {
        // The defect was "the field is unreachable", never "the rule is wrong".
        // Every one of these is the unchanged v90 behaviour, restated here so a
        // future "just make the password optional" cannot pass this suite.
        assertEquals(
            SshFormError.PasswordRequired,
            validateSshForm("ssh deploy@host", SshAuthMethod.PASSWORD, null, null),
        )
        assertEquals(
            SshFormError.PasswordRequired,
            validateSshForm("ssh deploy@host", SshAuthMethod.PASSWORD, "", null),
        )
        // The password test is `password.isNullOrEmpty()` (`SshModel.kt:107`) —
        // a whitespace password is **not** blank to this gate. Pinned as it is,
        // not as a stricter rule: tightening the predicate is not this round's
        // job, and the point of the pin is that the predicate did not move.
        assertNull(validateSshForm("ssh deploy@host", SshAuthMethod.PASSWORD, "   ", null))
        // The other two branches keep their own rules too.
        assertEquals(
            SshFormError.TargetRequired,
            validateSshForm("   ", SshAuthMethod.PASSWORD, "hunter2", null),
        )
        assertEquals(
            SshFormError.KeyRequired,
            validateSshForm("ssh deploy@host", SshAuthMethod.KEY, null, "  "),
        )
        // A whole credential still passes — reachability is what this round
        // restores, and the accepting half must not have moved either.
        assertNull(validateSshForm("ssh deploy@host", SshAuthMethod.PASSWORD, "hunter2", null))
        assertNull(validateSshForm("ssh deploy@host", SshAuthMethod.KEY, null, "/sdcard/id_ed25519"))
    }

    @Test
    fun thePrimaryActionKeepsItsGate() {
        val card = card()
        assertTrue(card.contains("enabled = !busy && target.isNotBlank()"))
        // No half-measure slipped in: nothing in the dialog makes the primary
        // action fire without the gate running first.
        val submit = submit()
        val gate = submit.indexOf("validateSshForm(")
        val refusal = submit.indexOf("error = invalid")
        val handOver = submit.indexOf("onSubmit(sshConfig, tunnel)")
        assertTrue("the gate runs", gate in 1 until refusal)
        assertTrue("refusing returns before the hand-over", refusal < handOver)
        assertTrue(
            "the gate still refuses by returning",
            submit.substring(refusal, handOver).trimStart().let {
                it.startsWith("error = invalid") && it.contains("return")
            },
        )
        // The reachability work is presentation-only: it lives outside `submit`.
        assertFalse(submit.contains("bringIntoView"))
    }

    // ------------------------------------------- 3: the reach vocabulary itself

    /**
     * The whole [SshFormError] vocabulary, written out: a new branch added to the
     * sealed interface is not automatically covered by a loop over its
     * `entries`, and the point of the pin is that **every** refusal names a
     * field the form draws.
     */
    private fun allFormErrors(): List<SshFormError> = listOf(
        SshFormError.TargetRequired,
        SshFormError.PasswordRequired,
        SshFormError.KeyRequired,
    )

    @Test
    fun everyRefusalNamesAFieldAndItIsOneTheFormDraws() {
        for (error in allFormErrors()) {
            val field = sshFormErrorField(error)
            assertNotNull("$error names no field", field)
            // The named field is drawn by the branch that can produce it: the
            // gate never points at a field the user cannot see exist.
            val auth = when (error) {
                SshFormError.TargetRequired -> SshAuthMethod.PASSWORD
                SshFormError.PasswordRequired -> SshAuthMethod.PASSWORD
                SshFormError.KeyRequired -> SshAuthMethod.KEY
            }
            assertTrue(
                "$error names a field the $auth branch does not draw",
                sshFormDrawnFields(auth).contains(field),
            )
            // …and every named field is one the gate actually demands.
            assertTrue(
                "$error names a field the gate does not require",
                sshFormRequiredFields(auth).contains(field),
            )
        }
    }

    @Test
    fun drawnAndRequiredAreTwoDistinctSets() {
        // `name` and the passphrase are drawn and must be reachable, but the gate
        // never demands them — collapsing the two sets would be how a "reach"
        // change quietly turns into a "required" change.
        for (auth in SshAuthMethod.entries) {
            val drawn = sshFormDrawnFields(auth)
            val required = sshFormRequiredFields(auth)
            assertTrue("a branch draws nothing", drawn.isNotEmpty())
            assertTrue("required is not a subset of drawn", drawn.containsAll(required))
            assertTrue("the target is never required", required.contains(SshFormField.Target))
            assertTrue("name is drawn", drawn.contains(SshFormField.Name))
            assertFalse("name is never required", required.contains(SshFormField.Name))
            assertFalse(
                "the passphrase is never required",
                required.contains(SshFormField.Passphrase),
            )
            assertTrue(
                "the branch's own field is required",
                required.contains(
                    if (auth == SshAuthMethod.PASSWORD) SshFormField.Password
                    else SshFormField.PrivateKeyPath,
                ),
            )
        }
    }

    @Test
    fun allFieldsReachableIsFalseForAMissingHandleAndTrueForAFullSet() {
        val every = SshFormField.entries.toSet()
        for (auth in SshAuthMethod.entries) {
            assertTrue(auth.name, sshFormFieldsAllReachable(auth, every))
            // Drop **any** one handle and the answer is false — including the
            // deepest one, which is the v98 defect itself.
            for (missing in sshFormDrawnFields(auth)) {
                assertFalse(
                    "${auth.name} without $missing",
                    sshFormFieldsAllReachable(auth, every - missing),
                )
            }
        }
        // An empty handle set is never "reachable": a silent success here is
        // how the defect would come back unnoticed.
        assertFalse(sshFormFieldsAllReachable(SshAuthMethod.PASSWORD, emptySet()))
    }

    /** Every drawn field is covered by exactly one branch's field set. */
    @Test
    fun theUnionOfBothBranchesCoversTheWholeEnum() {
        val union = sshFormDrawnFields(SshAuthMethod.PASSWORD) +
            sshFormDrawnFields(SshAuthMethod.KEY)
        assertEquals(SshFormField.entries.toSet(), union.toSet())
    }
}
