package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.settings.FOLLOW_UP_BEHAVIOR_KEYBIND
import com.xiufeigo.opencodeshell.ui.settings.FOLLOW_UP_BEHAVIOR_OPTIONS
import com.xiufeigo.opencodeshell.ui.settings.GENERAL_PANEL
import com.xiufeigo.opencodeshell.ui.settings.SettingsControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v27 device finding (Pixel Tablet 2560x1600, UI language 简体中文),
 * Settings → General → **Follow-up behavior**: the row rendered upstream's
 * English title / description / options inside a Chinese UI, and the label
 * column was squeezed into a 1–2 word vertical sliver.
 *
 * Both halves are pinned here, because both fail silently:
 *
 * * the copy is a **generated** resource (`strings_oc_i18n.xml`, from
 *   `research/_i18n_v2/{en,zh,zht}.json`). Android falls back to the default bundle when a
 *   locale omits a key — no build error, no crash, just English in zh, which is
 *   exactly what shipped: `settings.general.row.followUpBehavior.*` existed in
 *   `en.json` only.
 * * the description used to be rendered **inside the row's control column**
 *   (`FollowUpBehaviorControl`), where an unweighted `Text` takes the row's
 *   whole width and starves the weighted copy `Box` — on a 980 dp dialog the
 *   title wrapped to 2 lines and the description to 11. It now goes through the
 *   row's own copy slot like every other row's.
 */
class SettingsFollowUpRowTest {

    private val locales = listOf("values", "values-zh-rCN", "values-zh-rTW")

    /** The four resources this row's copy resolves through. */
    private val copyKeys = listOf(
        "settings_general_row_followUpBehavior_title",
        "settings_general_row_followUpBehavior_description",
        "settings_general_row_followUpBehavior_queue",
        "settings_general_row_followUpBehavior_steer",
    )

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun bundle(dir: String): String =
        source("src/main/res/$dir/strings_oc_i18n.xml")

    private fun value(dir: String, key: String): String? =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(bundle(dir))
            ?.groupValues
            ?.get(1)

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    // ------------------------------------------------- the copy is translated

    @Test
    fun everyLocaleCarriesTheFollowUpRowCopy() {
        for (dir in locales) {
            for (key in copyKeys) {
                val text = value(dir, key)
                assertTrue("$dir/$key is missing", text != null)
                assertTrue("$dir/$key is blank", text!!.isNotBlank())
            }
        }
    }

    @Test
    fun theChineseBundlesAreTranslatedNotFallenBack() {
        // The shipped failure: the key set existed in `values` only, so a zh
        // device silently rendered "Follow-up behavior" / "Choose whether to
        // queue follow-ups…" / "Steer".
        for (dir in listOf("values-zh-rCN", "values-zh-rTW")) {
            for (key in copyKeys) {
                assertNotEquals(
                    "$dir/$key is still the English copy",
                    value("values", key),
                    value(dir, key),
                )
            }
        }
    }

    @Test
    fun theDescriptionKeepsItsKeybindArgumentInEveryLocale() {
        // The row substitutes `{{keybind}}` → `%1$s` (`FOLLOW_UP_BEHAVIOR_KEYBIND`):
        // a translation that drops the specifier would render the description
        // with no key at all, and `stringResource(id, arg)` would silently ignore
        // the argument.
        for (dir in locales) {
            assertTrue(
                "$dir description lost its %1\$s argument",
                value(dir, "settings_general_row_followUpBehavior_description")!!.contains("%1\$s"),
            )
        }
    }

    // ------------------------------------------ the copy stays in the copy slot

    @Test
    fun theRowDeclaresTheGeneratedCopyAndTheGenericSelect() {
        val row = generalRow("settings-follow-up-behavior")
        assertEquals(R.string.settings_general_row_followUpBehavior_title, row.titleResId)
        assertEquals(R.string.settings_general_row_followUpBehavior_description, row.descriptionResId)
        assertEquals(SettingsControl.SELECT, row.control)
        // `settings.general.followUpBehavior` options (`general.tsx:157-184`).
        assertEquals(listOf("queue", "steer"), row.options.map { it.value })
        assertEquals(FOLLOW_UP_BEHAVIOR_OPTIONS.map { it.value }, row.options.map { it.value })
    }

    @Test
    fun theDescriptionIsResolvedIntoTheRowsOwnCopyColumn() {
        val panels = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        // The row hands the *formatted* copy to `SettingsRow`, so the description
        // renders in the row's copy column (upstream's `{{keybind}}` argument is
        // formatted here instead of a literal `%1$s` on screen)…
        assertTrue(
            panels.contains("row.descriptionResId?.let { stringResource(it, FOLLOW_UP_BEHAVIOR_KEYBIND) }"),
        )
        assertTrue(panels.contains("description = description,"))
        // …and nothing renders it inside the control any more: the bespoke
        // `FollowUpBehaviorControl` (select + description stacked in the control
        // column) is gone, and `SettingsRowControl` has no branch for the action.
        assertFalse("FollowUpBehaviorControl must be gone", panels.contains("FollowUpBehaviorControl"))
        val control = window(panels, "private fun SettingsRowControl", "internal const val FOLLOW_UP_BEHAVIOR_KEYBIND")
        assertFalse(
            "the control must not special-case the follow-up row",
            control.contains("settings-follow-up-behavior"),
        )
    }

    @Test
    fun theRowsCopyColumnIsTheOnlyFlexibleOne() {
        val components = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsComponents.kt")
        // Non-stacked row: the copy box is `weight(1f)` and the control is laid
        // out unweighted next to it, so a control can never starve the label
        // (the sliver this finding was). The resolved description is what the
        // copy renders.
        val row = window(components, "if (stacked) {", "if (!last) {")
        assertTrue(row.contains("Box(Modifier.weight(1f).then(toggle))"))
        assertTrue(row.contains("SettingsRowCopy(titleResId, descriptionResId, description = description)"))
        assertTrue(row.contains("control()"))
        // The control this row uses is the bounded `SelectV2` stand-in
        // (`widthIn(min = 140.dp, max = 260.dp)`), not an unbounded text column.
        assertTrue(components.contains("widthIn(min = 140.dp, max = 260.dp)"))
    }

    // ---------------------------------------------------- the Models row meta

    @Test
    fun theModelsLimitMetaIsSingleLineAndTheNameYieldsFirst() {
        val panels = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsPanels.kt")
        val row = window(panels, "private fun ModelVisibilityRow", "/** The `0.5px` `border-bottom")
        // The name column is the row's only flexible element…
        assertTrue(row.contains("Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp))"))
        // …while the limit copy is laid out at its intrinsic width, single-line
        // and non-wrapping, so it is complete before anything is ellipsized.
        val meta = window(row, "formatContextLimit(row.window)?.let { limit ->", "OcSwitch(")
        assertTrue(meta.contains("maxLines = 1"))
        assertTrue(meta.contains("softWrap = false"))
        assertFalse("the limit copy must never be ellipsized", meta.contains("TextOverflow.Ellipsis"))
        // Same single value source as the ring / the context tab (v20):
        // `min(limit.input, limit.context)`, never the raw `limit.context`.
        assertTrue(meta.contains("formatContextLimit(row.window)"))
    }

    @Test
    fun theKeybindIsTheComposerAlternateBinding() {
        // The description's `%1$s` is the delivery toggle's alternate key
        // (`general.tsx:157-184`), not a per-platform guess.
        assertEquals("Ctrl+Enter", FOLLOW_UP_BEHAVIOR_KEYBIND)
    }

    private fun generalRow(action: String) =
        GENERAL_PANEL.flatMap { it.rows }.first { it.action == action }
}
