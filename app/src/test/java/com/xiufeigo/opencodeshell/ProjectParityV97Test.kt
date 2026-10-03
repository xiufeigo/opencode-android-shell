package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.HomeProjectEdit
import com.xiufeigo.opencodeshell.ui.home.IconAction
import com.xiufeigo.opencodeshell.ui.home.ProjectWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The v97 P2/P3 parity pins for the project edit panel, one class per gap
 * closed in `research/v97_fix/PROJECT_PARITY_FIX.md`:
 *
 * ① 「刷新工作区」 on a worktree the server has no project for
 * ② tapping an uploaded icon removes it (upstream A5)
 * ③ the `$OPENCODE_WORKTREE_*` startup hint (upstream A9)
 *
 * This module's test deps are junit + org.json + mockwebserver only — no
 * Compose, no Robolectric — so the behavioural pins go through the pure
 * functions in [HomeProjectEdit] and the rendering pins are source reads, the
 * same shape `HomeProjectLocalMetaV97Test` uses.
 */
class ProjectParityV97Test {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private val dialog = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEditDialog.kt")
    private val homeScreen = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeScreen.kt")
    private val projectsList = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")
    private val edit = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectEdit.kt")

    private fun value(dir: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(source("src/main/res/$dir/strings.xml"))
            ?.groupValues
            ?.get(1)
            ?: error("$dir missing $key")

    // ---------------------------------------------------------------- ①

    /**
     * Upstream never offers the refresh where there is no server project: the
     * action belongs to the session workspace menu, which is typed on a server
     * `Project` (`session-workspace-menu.tsx:18`) rather than a `LocalProject`,
     * so `props.project.id` is always present when the call is made. Our row
     * menu is the one place a client-local directory can reach it, and there it
     * is hidden — not shown and refused.
     */
    @Test
    fun refreshIsNotOfferedWithoutAServerProject() {
        assertFalse(HomeProjectEdit.refreshApplies(null))
        assertFalse(HomeProjectEdit.refreshApplies(""))
        assertFalse(HomeProjectEdit.refreshApplies("   "))
        // Upstream's pseudo project has no PATCH / refresh route either
        // (`project-model.ts:41` folds it in with the null id).
        assertFalse(HomeProjectEdit.refreshApplies("global"))
        assertTrue(HomeProjectEdit.refreshApplies("prj_01"))
    }

    /**
     * The gate is one predicate on the row's own `serverID`, the value
     * `GET /api/project` supplies (`HomeProjects.merge`) — not a fresh network
     * call from inside the menu, which has no scope for one.
     */
    @Test
    fun theRowMenuGatesTheRefreshItemOnThatPredicate() {
        assertTrue(
            projectsList.contains("if (HomeProjectEdit.refreshApplies(project.serverID)) {"),
        )
        // The item is inside the gate, not merely disabled by it: upstream shows
        // no such row at all for a directory with no server project.
        val gated = Regex(
            """if \(HomeProjectEdit\.refreshApplies\(project\.serverID\)\) \{\s*DropdownMenuItem\(""",
        ).findAll(projectsList).count()
        assertEquals(1, gated)
    }

    /**
     * The refusal toast is gone from the shell's behaviour, not just from the
     * panel. Upstream swallows every failure of this call
     * (`.catch(() => undefined)`, `session-workspace-menu.tsx:57`) — including
     * the ones that are genuinely the server's fault — so there is no
     * 「该项目未在服务器注册」 to show and no toast at all on this branch.
     */
    @Test
    fun refreshingAnUnregisteredProjectIsSilentNotRefused() {
        assertFalse(
            "the NotRegistered branch must not toast the self-invented copy",
            homeScreen.contains("ProjectWrite.NotRegistered -> context.getString(R.string.oc_home_project_edit_missing)"),
        )
        assertTrue(
            "the outcome returns before any toast is built",
            homeScreen.contains("if (result is ProjectWrite.NotRegistered) return@launch"),
        )
        // Upstream's own success/failure toasts are untouched.
        assertTrue(homeScreen.contains("ProjectWrite.Ok -> context.getString(R.string.oc_home_project_worktrees_refreshed)"))
        assertTrue(homeScreen.contains("R.string.oc_home_project_worktrees_refresh_failed"))
    }

    /**
     * The outcome still exists and still means what it said: the API layer is
     * unchanged, and it is the *presentation* of the outcome that changed.
     */
    @Test
    fun theOutcomeItselfSurvivesTheQuietPresentation() {
        val api = source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectApi.kt")
        assertTrue(api.contains("?: return ProjectWrite.NotRegistered"))
        assertTrue(edit.contains("data object NotRegistered : ProjectWrite"))
        assertNotNull(ProjectWrite.NotRegistered)
        // Every outcome is still handled — the `when` stays exhaustive.
        assertTrue(homeScreen.contains("ProjectWrite.NotRegistered -> return@launch"))
    }

    /**
     * The audit found `oc_home_project_edit_missing` has no upstream
     * counterpart (Q3: upstream's i18n greps one hit, a trademark line). With
     * the panel already migrated and the refresh toast now silent, the key has
     * no user left under `ui/home` at all. It stays in all three locales —
     * deleting it is a separate, riskier step (the parity test treats the key
     * set as the contract) — but nothing may reference it again.
     */
    @Test
    fun theSelfInventedCopyIsReferencedNowhereInThePanelHome() {
        // The `R.string.` form is what a live reference looks like, so these
        // assert on the *code* and not on prose: the refresh handler's KDoc
        // deliberately discusses the key it stopped using.
        val reference = "R.string.oc_home_project_edit_missing"
        assertFalse(dialog.contains(reference))
        assertFalse(homeScreen.contains(reference))
        assertFalse(projectsList.contains(reference))
        // And the old toast line, verbatim, is gone from the refresh handler.
        assertFalse(
            homeScreen.contains("ProjectWrite.NotRegistered -> context.getString(R.string.oc_home_project_edit_missing)"),
        )
        // Still present and still translated in all three, so the removal is
        // behavioural and not a silent hole in the resource set.
        assertEquals(
            setOf(
                "This project isn\\'t registered on the server",
                "该项目未在服务器注册",
                "該專案未在伺服器註冊",
            ),
            setOf(
                value("values", "oc_home_project_edit_missing"),
                value("values-zh-rCN", "oc_home_project_edit_missing"),
                value("values-zh-rTW", "oc_home_project_edit_missing"),
            ),
        )
    }

    // ---------------------------------------------------------------- ②

    /**
     * Upstream's `iconClick()` (`project-model.ts:125-132`) is one button that
     * both picks and clears; the branch is `store.iconOverride && iconHover`.
     * A touch screen cannot carry the hover half, so the fork lands on the
     * image: an uploaded image clears, an empty slot picks.
     */
    @Test
    fun tappingTheIconPicksWithoutAnImageAndClearsWithOne() {
        val image = "data:image/png;base64,iVBORw0KGgo="
        assertEquals(IconAction.Pick, HomeProjectEdit.iconTap(null, enabled = true))
        assertEquals(IconAction.Pick, HomeProjectEdit.iconTap("", enabled = true))
        assertEquals(IconAction.Clear, HomeProjectEdit.iconTap(image, enabled = true))
    }

    /**
     * A save in flight still swallows the tap (`IconAction.None`), the way the
     * color swatches do — there is nothing to say about a write already going
     * out.
     */
    @Test
    fun aDisabledIconSwallowsTheTap() {
        assertEquals(IconAction.None, HomeProjectEdit.iconTap(null, enabled = false))
        assertEquals(IconAction.None, HomeProjectEdit.iconTap("data:image/png;base64,x", enabled = false))
    }

    /**
     * The clear path is the same one the 「清除图片」 button uses, and it has
     * upstream's `""` wire semantics (`saveIcon(color, "")`): the image is sent
     * as a cleared field while the name, the color and the startup command stay
     * out of the patch entirely — so a rename or a swatch is not undone by
     * removing the picture.
     */
    @Test
    fun clearingTheIconSendsAnEmptyOverrideAndNothingElse() {
        val image = "data:image/png;base64,iVBORw0KGgo="
        val original = HomeProjectEdit.Original(
            projectID = null,
            name = "renamed",
            color = "cyan",
            iconOverride = image,
            startup = "bun install",
            folderName = "proj",
        )
        val cleared = HomeProjectEdit.patchFor(original, HomeProjectEdit.draftWithoutIcon(original.let {
            HomeProjectEdit.draftOf(it)
        }))

        assertEquals("", cleared.iconOverride)
        assertEquals(null, cleared.iconColor)
        assertEquals(null, cleared.name)
        assertEquals(null, cleared.startup)
        assertFalse("a clear is a real write, not an empty patch", cleared.isEmpty)
    }

    /**
     * Clearing must not disturb the swatch either: `draftWithoutIcon` is a
     * one-field copy, so the tint the user picked survives and, with the
     * override gone, the swatch row comes back (upstream's
     * `<Show when={!model.store.iconOverride}>`, `project.tsx:103`).
     */
    @Test
    fun clearingTheIconKeepsTheColorAndTheOtherFields() {
        val draft = HomeProjectEdit.Draft(
            name = "renamed",
            color = "cyan",
            startup = "bun install",
            iconOverride = "data:image/png;base64,iVBORw0KGgo=",
        )
        val cleared = HomeProjectEdit.draftWithoutIcon(draft)
        assertEquals(null, cleared.iconOverride)
        assertEquals("renamed", cleared.name)
        assertEquals("cyan", cleared.color)
        assertEquals("bun install", cleared.startup)
    }

    /**
     * Both entry points exist: the tap on the image, and the labelled
     * 「清除图片」 button that stays for discoverability (an unlabelled ✕ on a
     * 32 dp avatar is not a control a phone user finds). The button is
     * unchanged from before — the fix is additive, nothing was taken away.
     */
    @Test
    fun bothClearEntryPointsShareTheOnePureFunction() {
        assertTrue(dialog.contains("IconAction.Clear -> Box("))
        assertTrue(dialog.contains("onChange(HomeProjectEdit.draftWithoutIcon(draft))"))
        assertTrue(dialog.contains("R.string.oc_home_project_edit_icon_clear"))
        // The pick button and the file filter are untouched.
        assertTrue(dialog.contains("R.string.oc_home_project_edit_icon_pick"))
        assertTrue(dialog.contains("""pickImage.launch("image/*")"""))
    }

    /**
     * The tap reuses the existing 「清除图片」 string as its accessibility
     * label rather than adding one — a `contentDescription` is a
     * user-visible string like any other, and no new key was introduced.
     */
    @Test
    fun theIconTapBorrowsTheExistingClearCopyForItsLabel() {
        assertTrue(
            dialog.contains("val clearIconLabel = stringResource(R.string.oc_home_project_edit_icon_clear)"),
        )
        assertTrue(dialog.contains("contentDescription = clearIconLabel"))
        assertFalse(
            "no invented accessibility copy",
            dialog.contains("oc_home_project_edit_icon_remove"),
        )
    }

    // ---------------------------------------------------------------- ③

    /**
     * Upstream's `inlineVariables` (`project.tsx:165-169`) splits on
     * `/(\$[A-Z][A-Z0-9_]*)/g` and wraps the captured runs in `<code>`; because
     * each match lands on an odd index of the split, the captured runs are
     * exactly the variable runs.
     */
    @Test
    fun theVariableSplitMatchesUpstreamsRegex() {
        val segments = HomeProjectEdit.variableSegments("Use \$OPENCODE_WORKTREE_BASE for the base worktree.")
        assertEquals(
            listOf(
                "Use " to false,
                "\$OPENCODE_WORKTREE_BASE" to true,
                " for the base worktree." to false,
            ),
            segments.map { it.text to it.variable },
        )
    }

    /**
     * `$` alone, a lowercase name and a leading digit are **not** variables —
     * the token is `[A-Z][A-Z0-9_]*`, so the same command text upstream would
     * not highlight is not highlighted here either.
     */
    @Test
    fun theVariableSplitRejectsWhatUpstreamDoesNotMatch() {
        for (text in listOf("cost: \$5", "\$lower_case", "\$1ST", "plain text", "")) {
            assertTrue(
                "no variable run in <$text>",
                HomeProjectEdit.variableSegments(text).none { it.variable },
            )
        }
        // A name with digits after the first character is still a match.
        assertEquals(
            listOf("\$A1_B2" to true),
            HomeProjectEdit.variableSegments("\$A1_B2").map { it.text to it.variable },
        )
    }

    /**
     * Two variables in one line, and the plain text between them is preserved
     * — the hint is not a lookup table, it renders whatever the string says.
     */
    @Test
    fun theVariableSplitKeepsEveryRunAndAllSurroundingText() {
        val line = "\$A and \$B2 end"
        assertEquals(
            listOf(
                "\$A" to true,
                " and " to false,
                "\$B2" to true,
                " end" to false,
            ),
            HomeProjectEdit.variableSegments(line).map { it.text to it.variable },
        )
        // The concatenation is lossless.
        assertEquals(
            line,
            HomeProjectEdit.variableSegments(line).joinToString("") { it.text },
        )
    }

    /**
     * The two hint lines are upstream's, key for key, in all three locales —
     * `project.settings.worktree.startup.hint.base` / `.hint.new`
     * (`project.tsx:154-157`, `en.ts:1097-1098`).
     */
    @Test
    fun theStartupHintIsUpstreamsTwoLinesInAllThreeLocales() {
        val expected = mapOf(
            "oc_home_project_edit_startup_hint_base" to "\$OPENCODE_WORKTREE_BASE",
            "oc_home_project_edit_startup_hint_new" to "\$OPENCODE_WORKTREE_PATH",
        )
        for (dir in listOf("values", "values-zh-rCN", "values-zh-rTW")) {
            for ((key, variable) in expected) {
                val copy = value(dir, key)
                assertTrue(
                    "$dir/$key must name $variable",
                    copy.contains(variable),
                )
                // The copy is prose around exactly one variable run, so the
                // split yields prose + one highlighted token.
                val variables = HomeProjectEdit.variableSegments(copy).filter { it.variable }
                assertEquals("$dir/$key", 1, variables.size)
                assertEquals(variable, variables.single().text)
            }
        }
        // Upstream's wording, verbatim, in English.
        assertEquals(
            "Use \$OPENCODE_WORKTREE_BASE for the base worktree.",
            value("values", "oc_home_project_edit_startup_hint_base"),
        )
        assertEquals(
            "Use \$OPENCODE_WORKTREE_PATH for the new worktree.",
            value("values", "oc_home_project_edit_startup_hint_new"),
        )
    }

    /**
     * The pre-existing one-line hint is untouched — A9 is additive, the panel
     * did not trade a sentence for two.
     */
    @Test
    fun theExistingStartupHintIsStillThere() {
        assertTrue(dialog.contains("R.string.oc_home_project_edit_startup_hint)"))
        assertEquals("Runs once after creating a new worktree", value("values", "oc_home_project_edit_startup_hint"))
    }

    /**
     * The hint is rendered unconditionally and through the same segment helper
     * the test above pins, in the monospace face upstream's `<code>` carries.
     */
    @Test
    fun theHintRendersBothLinesThroughTheSegmentHelper() {
        assertTrue(dialog.contains("StartupVariableHint(stringResource(R.string.oc_home_project_edit_startup_hint_base))"))
        assertTrue(dialog.contains("StartupVariableHint(stringResource(R.string.oc_home_project_edit_startup_hint_new))"))
        assertTrue(dialog.contains("HomeProjectEdit.variableSegments(text)"))
        assertTrue(dialog.contains("withStyle(SpanStyle(fontFamily = FontFamily.Monospace"))
    }

    /**
     * The two variables the hint names are the two the server actually exports
     * into the startup command — the copy is a fact about the server, not a
     * guess.
     */
    @Test
    fun theHintNamesTheVariablesTheServerExports() {
        // Gradle runs this with either the repo root or the module dir as the
        // working directory (the `source()` helper above already has to cope
        // with both), so the read-only upstream copy is looked up from each.
        val worktree = listOf(
            File("../_upstream/packages/core/src/worktree.ts"),
            File("_upstream/packages/core/src/worktree.ts"),
            File("app/../_upstream/packages/core/src/worktree.ts"),
        ).firstOrNull { it.isFile }?.readText() ?: error("missing upstream worktree.ts")
        assertTrue(worktree.contains("OPENCODE_WORKTREE_BASE:"))
        assertTrue(worktree.contains("OPENCODE_WORKTREE_PATH:"))
    }
}
