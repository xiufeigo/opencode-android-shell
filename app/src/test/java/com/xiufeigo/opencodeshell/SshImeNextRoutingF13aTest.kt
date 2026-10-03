package com.xiufeigo.opencodeshell

import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
// foundation 1.7.6 moved `KeyboardOptions` out of `ui.text.input`; it is a
// sibling of `KeyboardActions` here.
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F13a — `OcTextField` handed its IME **actions** three of the six slots and
 * left `onNext` out.
 *
 * The shape: `KeyboardActions` (foundation `1.7.6`) is six independent
 * nullable slots — `onDone` / `onGo` / `onNext` / `onPrevious` / `onSearch` /
 * `onSend` — and a `null` slot does **not** fall back to the sibling callbacks.
 * It runs the *default* implementation for that action, which for `onNext` is
 * "move focus to the next item in the focus traversal order"
 * (`KeyboardActions.kt:44-48`). So a caller that asks for
 * `imeAction = ImeAction.Next` **and** supplies an `onImeAction` callback was
 * silently ignored: the field moved focus and the callback never ran.
 *
 * `SshDialog` is the surface that made this visible — the config form's
 * target / name / private-key-path are the three `ImeAction.Next` fields
 * (`SshDialog.kt:384,392,435`), the two secret fields are the default `Done`
 * with `onImeAction = { submit() }`. Any of them that is ever given a `Next`
 * plus a callback would have dropped the callback.
 *
 * Same split as [OcKeyboardOptionsTest]: the callback contract and the
 * framework default are pure and pinned as **behaviour** below, while the
 * composable itself cannot be called from a plain JVM test — so the wiring
 * inside it stays a **source-level** pin, read the way
 * [SettingsComponentsTest] / [OcKeyboardOptionsTest] read it.
 */
class SshImeNextRoutingF13aTest {

    /** The `onNext` getter read reflectively, so a renamed slot fails the pin. */
    private val onNextGetter = KeyboardActions::class.java.getMethod("getOnNext")

    /** Stands in for the `KeyboardActionScope` Compose hands the callback. */
    private class RecordingScope : KeyboardActionScope {
        var defaultFor: ImeAction? = null
            private set

        override fun defaultKeyboardAction(imeAction: ImeAction) {
            defaultFor = imeAction
        }
    }

    // ------------------------------------------------- 1. the callback fires

    @Test
    fun theNextActionInvokesTheCallbackTheFieldNamed() {
        var nextCalls = 0
        val options = KeyboardOptions(imeAction = ImeAction.Next)
        val actions = KeyboardActions(onNext = { nextCalls++ })

        // The pair the F13a fix has to make: ask for `Next` …
        assertEquals(ImeAction.Next, options.imeAction)
        // … and the `onNext` slot is what that action runs.
        val scope = RecordingScope()
        actions.onNext!!.invoke(scope)
        assertEquals(1, nextCalls)

        // Naming the slot replaces the default focus move — the callback is not
        // shadowed by it, which is the whole point of the fix.
        assertNull("an explicit onNext must not also run the default action", scope.defaultFor)

        // … and the sibling slots are untouched, so only `Next` is routed here.
        assertNull(actions.onDone)
        assertNull(actions.onGo)
        assertNull(actions.onSend)
    }

    // ------------------------------------- 2. the default the bug fell into

    @Test
    fun theFrameworksDefaultNextSlotIsEmpty() {
        // The pre-F13a shape: `KeyboardActions` with no `onNext` named. Read
        // through the same getter, the slot is null — so nothing the field
        // passed to `onImeAction` could ever be called. This is the defect, and
        // it is why an unnamed slot cannot be tolerated.
        assertNull(
            "KeyboardActions.Default carries no onNext — that null is the F13a defect",
            onNextGetter.invoke(KeyboardActions.Default),
        )
    }

    @Test
    fun aNamedOnNextSlotReadsBackNonNullThroughTheSameGetter() {
        // The other side of the reflection: naming the slot is observable, so
        // "the field has a Next callback" is a fact a test can assert, not a
        // comment the source happens to carry.
        assertNotNull(
            "an explicit onNext must read back non-null",
            onNextGetter.invoke(KeyboardActions(onNext = {})),
        )
        assertNull(onNextGetter.invoke(KeyboardActions()))
    }

    // ------------------------------------------- 3. OcTextField's own wiring

    @Test
    fun ocTextFieldRoutesTheNextActionToTheSameImeCallback() {
        val actions = ocTextFieldActions()
        assertTrue(
            "F13a: OcTextField must name onNext, or an ImeAction.Next field fires nothing",
            actions.contains("onNext = { onImeAction?.invoke() }"),
        )
        // The callback is the field's own, not a hardcoded submit: `onImeAction`
        // stays optional, so a Next field with no callback is a safe no-op
        // (focus move) rather than a crash.
        assertTrue(actions.contains("onImeAction?.invoke()"))
    }

    @Test
    fun ocTextFieldKeepsEveryOtherImeActionItAlreadyRouted() {
        val actions = ocTextFieldActions()
        // The three that were already there must survive the fix — a `Next`-only
        // rewrite would break every Done field, `submit()` included.
        for (slot in listOf("onDone", "onGo", "onSend")) {
            assertTrue(
                "OcTextField must keep routing $slot",
                actions.contains("$slot = { onImeAction?.invoke() }"),
            )
        }
        // Exactly one KeyboardActions block, so a second one cannot shadow it.
        assertEquals(1, count(components(), "keyboardActions = KeyboardActions("))
    }

    // --------------------------------------- 4. the surface that made it hurt

    @Test
    fun theSshFormIsTheImeNextSurfaceF13aIsAbout() {
        val form = dialog()
        // The premise: three fields ask for `Next` (target / name / key path) …
        assertEquals(3, count(form, "imeAction = ImeAction.Next,"))
        // … and the two secrets are the default `Done` with a real callback.
        assertEquals(2, count(form, "onImeAction = { submit() },"))
    }

    @Test
    fun everySshFormFieldEitherAsksForNextOrCarriesItsOwnImeCallback() {
        val form = dialog()
        val fields = count(form, "OcTextField(")
        assertEquals(5, fields)
        // A field may ask for `Next` (a pure focus move) or carry its own
        // `onImeAction` — never neither, which is the unrouted shape F13a
        // removed from the shared component.
        assertEquals(
            "a field must either ask for Next or carry its own IME callback",
            fields,
            count(form, "imeAction = ImeAction.Next,") + count(form, "onImeAction = { submit() },"),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun components() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")

    private fun dialog() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshDialog.kt")

    /** [OcTextField]'s parameter list — where the `onImeAction` seam is declared. */
    private fun ocTextField(): String =
        components().substringAfter("fun OcTextField(").substringBefore("fun OcSwitch(")

    /** The `KeyboardActions(...)` block of [ocTextField] — the F13a surface. */
    private fun ocTextFieldActions(): String =
        ocTextField()
            .substringAfter("keyboardActions = KeyboardActions(")
            .substringBefore("modifier = Modifier.fillMaxWidth(),")

    private fun count(src: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(src).count()
}
