package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// ============================================================================
// v115 / Task A — make "a record-only fixture must keep a live neighbour" a
// *structural* pin instead of a convention written in a comment.
//
// v114 (`research/v97_fix/TRIPWIRE_CRITERION_FIX3.md` §9#4) left one thing
// open and it is the one thing that can rot silently:
//
//   R5 / R6 are record-only fixtures. `assertEquals(5, 218 - 213)` and
//   `assertTrue(over768 < regionBottomPaddingPx)` are compile-time constants —
//   they can never go red. Their entire detection power is *borrowed* from a
//   production call that happens to sit in the same method. Nothing in the
//   build holds that neighbour in place. Delete it and the suite stays green
//   forever with two decorative assertions in it. That is a silent
//   degradation, and it is the only failure mode in this area that produces
//   no symptom at all.
//
// So the invariant itself becomes code: [TripwireValidator] is a pure
// function (text in, violations out) and [TripwireValidatorTest] proves it
// *rejects* the deletion rather than merely accepting the status quo.
// ============================================================================

/** One tripwire's structural contract. */
internal data class TripwireSpec(
    /** Short id used in violation messages, e.g. `R5`. */
    val id: String,
    /** Repo-relative path, used only in messages. */
    val relativePath: String,
    /** The method that holds the record-only assertions. */
    val fixtureMethod: String,
    /**
     * Symbols that mean "this assertion is routed through production".
     * Counted in *code* only — comments and string literals are masked out
     * first, so a KDoc that merely mentions a production call buys nothing.
     */
    val productionSymbols: List<String>,
    /**
     * Fragments that must survive: the fixture's own message text and the
     * lineage notes. Their loss means somebody deleted the record rather than
     * reasoning about it.
     */
    val lineageMarkers: List<String>,
)

internal data class Violation(val id: String, val kind: String, val detail: String) {
    override fun toString(): String = "$id/$kind: $detail"
}

/**
 * Pure, stateless text analysis. No file access, no reflection — [validate]
 * takes a spec and a source text and returns a list. That is what lets the
 * reverse tests below hand it *mutated* source strings and demand a verdict.
 */
internal object TripwireValidator {

    // ---------------------------------------------------------------- masking

    /**
     * Replace every comment and string-literal **body** with spaces, keeping
     * length and newlines so indices and line numbers still line up.
     *
     * This is the load-bearing step: it is what makes "did somebody delete
     * the assertion or only the line above it" a decidable question, and it is
     * why the KDoc at `V106:331-337` — which names `Frame(600, 387, …)` in
     * prose — cannot be mistaken for a live call.
     */
    fun maskNonCode(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                    out.append("  ")
                    i += 2
                    while (i < n && src[i] != '\n') { out.append(' '); i++ }
                }

                c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                    out.append("  ")
                    i += 2
                    while (i < n && !(src[i] == '*' && i + 1 < n && src[i + 1] == '/')) {
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                    if (i < n) { out.append("  "); i += 2 }
                }

                c == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"' -> {
                    out.append("   ")
                    i += 3
                    while (i < n) {
                        if (src[i] == '"' && i + 2 < n && src[i + 1] == '"' && src[i + 2] == '"') {
                            out.append("   "); i += 3; break
                        }
                        out.append(if (src[i] == '\n') '\n' else ' '); i++
                    }
                }

                c == '"' || c == '\'' -> {
                    val quote = c
                    out.append(' ')
                    i++
                    while (i < n) {
                        val d = src[i]
                        if (d == '\\' && i + 1 < n) { out.append("  "); i += 2; continue }
                        if (d == quote) { out.append(' '); i++; break }
                        if (d == '\n') { out.append('\n'); i++; break } // unterminated: do not run away
                        out.append(' '); i++
                    }
                }

                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** The complement of [maskNonCode]: comments and string bodies, nothing else. */
    fun nonCodeOnly(src: String): String {
        val masked = maskNonCode(src)
        val sb = StringBuilder(src.length)
        for (k in src.indices) sb.append(if (masked[k] == ' ') src[k] else ' ')
        return sb.toString()
    }

    // ------------------------------------------------------------- structural

    private fun methodRegex(name: String) = Regex("fun\\s+" + Regex.escape(name) + "\\s*\\(")

    /** Index of `fun <name>(` in *code* text, or -1. */
    fun methodStart(maskedSrc: String, name: String): Int =
        methodRegex(name).find(maskedSrc)?.range?.first ?: -1

    /** Index just past the `}` that closes the brace opened at [open]. */
    fun matchBrace(masked: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < masked.length) {
            when (masked[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return masked.length - 1
    }

    /** The method's body text (unmasked), or null when the method is gone. */
    fun methodBody(src: String, name: String): String? {
        val masked = maskNonCode(src)
        val start = methodStart(masked, name)
        if (start < 0) return null
        val open = masked.indexOf('{', start)
        if (open < 0) return null
        return src.substring(open, matchBrace(masked, open) + 1)
    }

    // ----------------------------------------------------------- assertions

    private val ASSERT_CALL = Regex("assert[A-Za-z]*\\s*\\(")

    /**
     * Every `assertXxx(...)` call in [maskedBody], as text, with balanced
     * parens resolved. Commented-out calls are invisible here by
     * construction — that is the point of masking first.
     */
    fun assertionCalls(maskedBody: String): List<String> = ASSERT_CALL.findAll(maskedBody).mapNotNull { m ->
        val open = maskedBody.indexOf('(', m.range.first)
        if (open < 0) return@mapNotNull null
        var depth = 0
        var i = open
        while (i < maskedBody.length) {
            when (maskedBody[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return@mapNotNull maskedBody.substring(m.range.first, i + 1)
                }
            }
            i++
        }
        null
    }.toList()

    fun productionHits(text: String, symbols: List<String>): Int =
        symbols.sumOf { sym -> countOf(text, sym) }

    private fun countOf(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) { count++; idx = haystack.indexOf(needle, idx + needle.length) }
        return count
    }

    /** End of the statement starting at [from]: a top-level newline, or EOF. */
    private fun statementEnd(masked: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < masked.length) {
            when (masked[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return i else depth--
                '\n' -> if (depth == 0) return i
            }
            i++
        }
        return masked.length
    }

    /**
     * Locals whose value came out of production, e.g.
     * `val after600 = Frame(600, 387, …)` binds `after600`.
     *
     * This is the step that makes the pin honest for V106: `Frame` is a
     * test-local facade (see `frameIsAFacadeOverTheProductionLayout` below), so
     * the *assertions* downstream of it name `after600`, not `Frame(`. Without
     * the binding step the validator would call those assertions record-only
     * and the pin would fire on healthy source.
     */
    fun boundProductionLocals(maskedBody: String, symbols: List<String>): Set<String> {
        val bound = mutableSetOf<String>()
        for (m in Regex("\\bval\\s+([A-Za-z_]\\w*)\\s*(?::[^=\\n]*)?=").findAll(maskedBody)) {
            val rhsStart = m.range.last + 1
            val rhs = maskedBody.substring(rhsStart, statementEnd(maskedBody, rhsStart))
            if (productionHits(rhs, symbols) > 0) bound += m.groupValues[1]
        }
        return bound
    }

    /** An assertion is *live* when it reads production directly or via a bound local. */
    fun isLiveAssertion(call: String, symbols: List<String>, bound: Set<String>): Boolean {
        if (productionHits(call, symbols) > 0) return true
        return Regex("\\b([A-Za-z_]\\w*)\\b").findAll(call).any { it.value in bound }
    }

    // -------------------------------------------------------------- validate

    fun validate(spec: TripwireSpec, src: String): List<Violation> {
        val violations = mutableListOf<Violation>()
        val maskedSrc = maskNonCode(src)

        // (1) the fixture method itself must still be there, by name.
        val start = methodStart(maskedSrc, spec.fixtureMethod)
        if (start < 0) {
            violations += Violation(
                spec.id,
                "fixture-method-missing",
                "no 'fun ${spec.fixtureMethod}(' in ${spec.relativePath} — the record-only " +
                    "fixture and the live assertion that lends it detection power must stay " +
                    "in the same method, so neither can be deleted alone",
            )
            return violations
        }

        // (2) the method must not have been switched off.
        val window = maskedSrc.substring(maxOf(0, start - 400), start).substringAfterLast("\n\n")
        for (ann in listOf("@Ignore", "@Disabled")) {
            if (window.contains(ann)) {
                violations += Violation(
                    spec.id,
                    "fixture-method-disabled",
                    "$ann sits on ${spec.fixtureMethod}() — a disabled fixture lends its " +
                        "neighbour no protection at all",
                )
            }
        }

        val body = src.substring(maskedSrc.indexOf('{', start), matchBrace(maskedSrc, maskedSrc.indexOf('{', start)) + 1)
        val maskedBody = maskNonCode(body)
        val bound = boundProductionLocals(maskedBody, spec.productionSymbols)
        val calls = assertionCalls(maskedBody)
        val recordOnly = calls.filterNot { isLiveAssertion(it, spec.productionSymbols, bound) }
        val live = calls.count { isLiveAssertion(it, spec.productionSymbols, bound) }

        // (3) the whole point: a record-only method must keep a live neighbour.
        if (recordOnly.isNotEmpty() && live == 0) {
            val prose = productionHits(nonCodeOnly(body), spec.productionSymbols)
            violations += if (prose > 0) {
                Violation(
                    spec.id,
                    "live-assertion-only-in-comments",
                    "${spec.fixtureMethod}() still has ${recordOnly.size} record-only assertion(s) " +
                        "that can never go red, and ${spec.productionSymbols.joinToString(" / ")} " +
                        "survives only in its comments — the live assertion that used to lend it " +
                        "detection power has been deleted or commented out",
                )
            } else {
                Violation(
                    spec.id,
                    "no-live-assertion",
                    "${spec.fixtureMethod}() has ${recordOnly.size} record-only assertion(s) and " +
                        "no assertion routed through ${spec.productionSymbols.joinToString(" / ")} — " +
                        "it has silently degraded into decoration",
                )
            }
        }

        // (4) the record and its lineage must not be quietly removed.
        for (marker in spec.lineageMarkers) {
            if (!src.contains(marker)) {
                violations += Violation(
                    spec.id,
                    "lineage-marker-lost",
                    "\"$marker\" is gone from ${spec.relativePath} — deleting a recorded reading is " +
                        "legitimate only with a decision, not as a side effect of tidying",
                )
            }
        }

        return violations
    }
}

// ----------------------------------------------------------------------------

/**
 * The two contracts, spelled out against the **real** files.
 *
 * Note the paths: v114 §5.1/§5.2 put **R5 in V106** and **R6 in V109**. The
 * brief for this round named V107/V105 instead — those are the classes v114
 * changed for **R8** and `V105:305`, not the R5/R6 hosts. v114 §9#4 itself
 * suggested "next round, add one live criterion to V106/V109", which matches
 * the source. These specs follow the source.
 */
internal object ComposerTripwireSpecs {
    /** R5 — `ComposerBandTallEnoughV106Test.kt:338-341` and `:343`. */
    val R5 = TripwireSpec(
        id = "R5",
        relativePath = "app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandTallEnoughV106Test.kt",
        fixtureMethod = "theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand",
        // `Frame(` is a test-local facade over SessionPaneLayout; its constructor
        // defaults call composerHostHeightPx / composerTopPx / composerBandTouchTargetDp
        // at :141,146,153. `frameIsAFacadeOverTheProductionLayout` below pins that
        // bridge so the symbol list cannot quietly become a lie.
        productionSymbols = listOf("Frame(", "SessionPaneLayout."),
        lineageMarkers = listOf(
            "v114 裁定：**保留**（记账轨）",
            "真机 600 档 ①：unionBottom 218 > hostBottom(imeTop) 213 ⇒ +5",
            "真机 768 档 ①：296 > 294 ⇒ +2",
            "REPORT_COMPOSER_OVERFLOW3.md",
        ),
    )

    /** R6 — `ComposerUnionBottomP5V109Test.kt:151-154` and `:211-214`. */
    val R6 = TripwireSpec(
        id = "R6",
        relativePath = "app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt",
        fixtureMethod = "theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred",
        productionSymbols = listOf("SessionPaneLayout."),
        lineageMarkers = listOf(
            "v114 裁定（R6）",
            "v98 re-enactment: 6 < 8, so the bottom padding alone yields it and the top padding is untouched",
            "v98 re-enactment: 12 > 8, the bottom padding runs out, so the top padding is what gets consumed",
        ),
    )
}

// ----------------------------------------------------------------------------

class ComposerTripwireIntegrityV115Test {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("app/$relative"), File("../$relative"))
            .firstOrNull { it.isFile }
            ?: error("missing $relative (cwd=${File(".").absolutePath})")

    private fun src(spec: TripwireSpec) =
        repoFile(spec.relativePath).readText().replace("\r\n", "\n")

    /**
     * Delete the live half of the fixture method, exactly as a future tidy-up
     * would: every line that calls production goes, the record-only lines stay.
     * Returns a source string; the validator is never told this is a mutant.
     */
    private fun dropLiveAssertions(srcText: String, spec: TripwireSpec): String {
        val masked = TripwireValidator.maskNonCode(srcText)
        val start = TripwireValidator.methodStart(masked, spec.fixtureMethod)
        assertTrue("fixture method present in the real source", start >= 0)
        val open = masked.indexOf('{', start)
        val close = TripwireValidator.matchBrace(masked, open)
        val body = srcText.substring(open, close + 1)
        val bodyMasked = masked.substring(open, close + 1)
        val bodyLines = body.lines()
        val maskedLines = bodyMasked.lines()
        assertEquals("masking must preserve line count", bodyLines.size, maskedLines.size)
        val kept = bodyLines.filterIndexed { i, _ ->
            TripwireValidator.productionHits(maskedLines[i], spec.productionSymbols) == 0
        }
        assertTrue("the mutation must actually delete something", kept.size < bodyLines.size)
        return srcText.substring(0, open) + kept.joinToString("\n") + srcText.substring(close + 1)
    }

    // ------------------------------------------------- the live status quo

    @Test
    fun theRealTripwiresAreClean() {
        for (spec in listOf(ComposerTripwireSpecs.R5, ComposerTripwireSpecs.R6)) {
            assertEquals(
                "${spec.id} must carry a live assertion beside its record-only ones — " +
                    "violations: ${TripwireValidator.validate(spec, src(spec))}",
                emptyList<Violation>(),
                TripwireValidator.validate(spec, src(spec)),
            )
        }
    }

    /**
     * The classification is not vacuous: R5's method really does contain
     * record-only assertions (they are the ones with no bound production local)
     * *and* really does contain live ones. If either half silently became zero
     * the pin above would pass for the wrong reason.
     */
    @Test
    fun theRealTripwiresMixRecordOnlyAndLiveAssertions() {
        for (spec in listOf(ComposerTripwireSpecs.R5, ComposerTripwireSpecs.R6)) {
            val maskedBody = TripwireValidator.maskNonCode(TripwireValidator.methodBody(src(spec), spec.fixtureMethod)!!)
            val bound = TripwireValidator.boundProductionLocals(maskedBody, spec.productionSymbols)
            val calls = TripwireValidator.assertionCalls(maskedBody)
            val live = calls.count { TripwireValidator.isLiveAssertion(it, spec.productionSymbols, bound) }
            val record = calls.size - live
            assertTrue("${spec.id} has record-only assertions to protect", record > 0)
            assertTrue("${spec.id} has live assertions doing the protecting", live > 0)
        }
    }

    // ------------------------------------------------------ the reverse tests

    /**
     * The two ways a record-only method can lose its protection. Both are
     * degradations; which one fires depends on whether the KDoc that
     * *advertises* the production call sits inside the method body (R5: the
     * v114 裁定 comment at `:331-337` is inside it) or above the signature
     * (R6: the v114 裁定 comment at `:173-181` is not). Asserting the set
     * rather than one string keeps the pin from breaking on a comment edit
     * while still refusing to let the degradation through.
     */
    private val degradationKinds = setOf("live-assertion-only-in-comments", "no-live-assertion")

    /**
     * **The core evidence.** Deleting the live assertions beside R5's recorded
     * device readings must be reported, not tolerated.
     */
    @Test
    fun deletingTheLiveAssertionsBesideR5IsReported() {
        val spec = ComposerTripwireSpecs.R5
        val violations = TripwireValidator.validate(spec, dropLiveAssertions(src(spec), spec))
        assertEquals(
            "exactly one violation, and it must name the degradation: $violations",
            1,
            violations.size,
        )
        assertEquals("R5", violations[0].id)
        assertTrue("R5's degradation must be one of $degradationKinds, got: $violations", violations[0].kind in degradationKinds)
        // R5's prose is inside the method, so it can name the sharper failure:
        // the call survives only in the comment that advertises it.
        assertEquals("live-assertion-only-in-comments", violations[0].kind)
        assertTrue(
            "the message must name the method that degraded: ${violations[0]}",
            violations[0].detail.contains(spec.fixtureMethod),
        )
    }

    /** Same, for R6's v98 re-enactment. */
    @Test
    fun deletingTheLiveAssertionsBesideR6IsReported() {
        val spec = ComposerTripwireSpecs.R6
        val violations = TripwireValidator.validate(spec, dropLiveAssertions(src(spec), spec))
        assertEquals(
            "exactly one violation, and it must name the degradation: $violations",
            1,
            violations.size,
        )
        assertEquals("R6", violations[0].id)
        assertTrue("R6's degradation must be one of $degradationKinds, got: $violations", violations[0].kind in degradationKinds)
        assertEquals("no-live-assertion", violations[0].kind)
        assertTrue(
            "the message must name the method that degraded: ${violations[0]}",
            violations[0].detail.contains(spec.fixtureMethod),
        )
    }

    /** Mutant of the whole class: the fixture method renamed out of the way. */
    @Test
    fun renamingTheFixtureMethodIsReported() {
        val spec = ComposerTripwireSpecs.R5
        val renamed = src(spec).replace("fun ${spec.fixtureMethod}(", "fun somethingElse(")
        assertTrue("the rename must have applied", !renamed.contains("fun ${spec.fixtureMethod}("))
        val violations = TripwireValidator.validate(spec, renamed)
        assertEquals("$violations", 1, violations.size)
        assertEquals("fixture-method-missing", violations[0].kind)
    }

    /** Mutant: the method disabled with `@Ignore` — protection by absence. */
    @Test
    fun ignoringTheFixtureMethodIsReported() {
        val spec = ComposerTripwireSpecs.R6
        val ignored = src(spec).replace(
            "    @Test\n    fun ${spec.fixtureMethod}(",
            "    @Test\n    @Ignore(\"v115 tripwire: the live neighbour must stay\")\n    fun ${spec.fixtureMethod}(",
        )
        assertTrue("the @Ignore must have been injected", ignored.contains("@Ignore(\"v115 tripwire"))
        val violations = TripwireValidator.validate(spec, ignored)
        assertTrue("expected a fixture-method-disabled violation, got $violations", violations.any { it.kind == "fixture-method-disabled" })
    }

    /** Mutant: the record itself deleted rather than decided about. */
    @Test
    fun deletingTheRecordedReadingIsReported() {
        val spec = ComposerTripwireSpecs.R5
        val marker = spec.lineageMarkers[1]
        val stripped = src(spec).replace(marker, "removed")
        val violations = TripwireValidator.validate(spec, stripped)
        assertTrue("expected a lineage-marker-lost violation, got $violations", violations.any { it.kind == "lineage-marker-lost" })
    }

    /**
     * Commenting the live assertion out is the same degradation as deleting it,
     * and it is the easier mistake to make by accident. Prose must not count
     * as a live call — the KDoc that *advertises* `Frame(600, 387, …)` is what
     * makes this worth asserting explicitly.
     */
    @Test
    fun commentingOutTheLiveAssertionIsReported() {
        val spec = ComposerTripwireSpecs.R6
        val real = src(spec)
        val liveLine = "        val hostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, 94)"
        assertTrue("the live line must exist in the real source", real.contains(liveLine))
        val commented = real.replace(liveLine, "        // $liveLine")
        val violations = TripwireValidator.validate(spec, commented)
        assertEquals("$violations", 1, violations.size)
        // The now-commented line sits *inside* the method body, so the call
        // survives as prose and the validator can name the sharper failure:
        // the assertion that lends these fixtures their detection power is
        // commented out rather than deleted.
        assertEquals("live-assertion-only-in-comments", violations[0].kind)
    }

    /**
     * Closes the transitive gap the R5 symbol list depends on: `Frame` is a
     * test-local class, so "calls `Frame(`" only means "calls production"
     * while `Frame`'s defaults still call `SessionPaneLayout`. If somebody
     * rewrites `Frame` to compute from literals, R5's pin would keep passing on
     * a fiction — this is the assertion that stops that.
     */
    @Test
    fun frameIsAFacadeOverTheProductionLayout() {
        val lines = src(ComposerTripwireSpecs.R5).lines()
        val start = lines.indexOfFirst { it.trim() == "private class Frame(" }
        assertTrue("Frame must still be declared in V106", start >= 0)
        val frameClass = lines.subList(start, minOf(start + 60, lines.size)).joinToString("\n")
        for (call in listOf(
            "SessionPaneLayout.composerHostHeightPx(",
            "SessionPaneLayout.composerTopPx(",
            "SessionPaneLayout.composerBandTouchTargetDp(",
        )) {
            assertTrue(
                "Frame's defaults must still call $call — R5's 'Frame(' symbol means " +
                    "'production' only through this bridge",
                frameClass.contains(call),
            )
        }
    }
}
