package com.xiufeigo.opencodeshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// ============================================================================
// v116 / Task A — turn "≥ 1 live assertion" into "**that exact call** is still
// there", and make the *provenance* of its arguments part of the pin.
//
// v115 (`research/v97_fix/TRIPWIRE_HARDENING.md` §8) left two holes open and
// named them itself:
//
//   1. §8#1 — the v115 validator asks "is there **at least one** live
//      assertion?". It never asks "is **the** `composerTopPx` call still
//      here?". So *add a new production call, delete the old one* keeps the
//      suite green. That is the exact "add-new-then-delete-old" move.
//   2. §8#2 — `Frame` is a test-local facade over `SessionPaneLayout`, and
//      v115 only pinned that three calls survive inside it. If somebody
//      rewrites `pane` / `bandPx` to compute from literals, the call is still
//      *there* (nominally alive) while the geometry is invented (substantively
//      dead). v115 stays green.
//
// v116 answers both with the same primitive: a **call fingerprint** —
// `(scope, callee, canonical args)`. Not a count, not a symbol. `Frame(600,
// 387, bandHeightPx = BAND_AFTER_PX)` and `Frame(600, 387, bandHeightPx = 48)`
// are **different fingerprints**, so the second one cannot stand in for the
// first; and a fresh `Frame(...)` under a new name in the same method does not
// reproduce the pinned one either. The `canonicalArgs` are what makes hole #2
// visible: a pinned call must also prove that at least one of its arguments is
// a **production-derived symbol**, not a hardcoded literal.
//
// Everything below is a pure function over source text. The mutations in the
// tests hand it mutated strings and demand a verdict, which is what makes the
// three reverse cases real evidence rather than assertions about a status quo.
//
// ============================================================================
// v117 — Lead's ruling: R5's required set is **both tiers**, not either one
// ----------------------------------------------------------------------------
// v116 §7#2 left one hole open and named it: `V106:364`'s
// `after768 = Frame(768, 466, …)` was never pinned, so "add a new call and
// delete that one" still worked against it. Closing it required first deciding
// *which* call R5 depends on — a question about the criterion's ownership, not
// about the validator. Lead ruled: **both are required**.
//
// Why both, rather than picking the stronger one:
//
//   * `V106:351` — the **600 cell**. This is the defect site: the device read
//     `unionBottom 218 > hostBottom 213` (+5) and the fix makes it exactly
//     equal. It is the cell that proves the fix *works*.
//   * `V106:364` — the **768 cell**. Also a failing reading (`296 > 294`,
//     +2), fixed the same way. It is the cell that proves the fix *did not
//     break the other configuration*.
//
// The composer band fix's contract is the conjunction of those two claims, so
// pinning one tier pins half the contract and leaves the other half unguarded —
// which is precisely the "nominally alive, substantively dead" failure this
// file exists to prevent, one level up. Neither call can stand in for the
// other, and `neitherTierSubstitutesForTheOther` proves it in both
// directions against the real file.
//
// Everything v116 established is preserved verbatim; the change is strictly
// additive (one fingerprint appended to the required set, plus tests).
// ============================================================================

// ----------------------------------------------------------------------------
// 1. The fingerprint itself
// ----------------------------------------------------------------------------

/**
 * One pinned production call.
 *
 * A fingerprint is **exact**: scope + callee + the canonical text of every
 * argument. Adding an unrelated call to the same method cannot reproduce it,
 * and editing one argument makes it stop matching. That is the whole point.
 *
 * @param scope      the `fun` or `class` the call must live in.
 * @param callee     the production (or facade) symbol being called.
 * @param args       canonical form of each top-level argument, in order.
 * @param minProductionArgs how many arguments must be a *production-derived
 *        symbol* rather than a literal. This is the anti-"nominally alive,
 *        substantively dead" rule: a call like `composerTopPx(46, 94)` keeps
 *        the callee but invents the inputs, so it must not satisfy the pin.
 */
internal data class CallFingerprint(
    val scope: String,
    val callee: String,
    val args: List<String>,
    val minProductionArgs: Int,
) {
    /** Stable, human-readable identity used in violation messages. */
    fun render(): String = "$scope { $callee(${args.joinToString(", ")}) }"

    /** Compact key for set operations. */
    fun key(): String = "$scope|$callee|${args.joinToString(",")}"
}

// ----------------------------------------------------------------------------
// 2. The scanner (pure)
// ----------------------------------------------------------------------------

internal object CallFingerprintScanner {

    /** Reuses v115's masking so both validators agree on what "code" means. */
    fun mask(src: String) = TripwireValidator.maskNonCode(src)

    /**
     * Canonical text of an expression: collapse whitespace runs, then drop the
     * spaces hugging `( ) , =`. `rows = 0` and `rows=0` become identical, so
     * a pure-reformatting commit cannot break a pin, while changing `0` to
     * `1` can.
     */
    fun canonical(text: String): String =
        text.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*([(),=])\\s*"), "$1")
            .trim()

    /**
     * Split a call's raw argument text on **top-level** commas only, so
     * `composerTopPx(pane = pane, composer = hostPx)` is two arguments while
     * `if (oneRow) bandHeightPx else STACKED_NATURAL_PX` stays one.
     */
    fun splitArgs(raw: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val cur = StringBuilder()
        for (c in raw) {
            when (c) {
                '(', '[', '{' -> { depth++; cur.append(c) }
                ')', ']', '}' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { parts += cur.toString(); cur.clear() } else cur.append(c)
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) parts += cur.toString()
        return parts.map { canonical(it) }.filter { it.isNotEmpty() }
    }

    /** Body of `fun <name>( ... ) { … }`, or null. */
    fun funBody(maskedSrc: String, name: String): String? {
        val start = Regex("fun\\s+" + Regex.escape(name) + "\\s*\\(").find(maskedSrc)?.range?.first ?: return null
        val open = maskedSrc.indexOf('{', start).let { if (it < 0) return null else it }
        return maskedSrc.substring(open, TripwireValidator.matchBrace(maskedSrc, open) + 1)
    }

    /**
     * Body of `class <name>( … ) { … }`, skipping the primary-constructor
     * parameter list — `Frame`'s parameters contain no braces, but walking to
     * the *matching* `)` first is what keeps this correct if that ever changes.
     */
    fun classBody(maskedSrc: String, name: String): String? {
        val at = Regex("class\\s+" + Regex.escape(name) + "\\s*\\(").find(maskedSrc)?.range?.first ?: return null
        var depth = 0
        var i = maskedSrc.indexOf('(', at)
        while (i < maskedSrc.length) {
            when (maskedSrc[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) { i++; break } }
            }
            i++
        }
        val open = maskedSrc.indexOf('{', i).let { if (it < 0) return null else it }
        return maskedSrc.substring(open, TripwireValidator.matchBrace(maskedSrc, open) + 1)
    }

    /**
     * Every `<callee>( … )` call in [scopeBody], as canonical argument lists.
     * Comments and string bodies are already masked out, so a KDoc that
     * *advertises* `Frame(600, 387, …)` contributes nothing — the same
     * load-bearing property v115 established, reused rather than reinvented.
     */
    fun callsIn(scopeBody: String, callee: String): List<List<String>> {
        val hits = mutableListOf<List<String>>()
        var from = 0
        while (true) {
            val at = scopeBody.indexOf(callee, from)
            if (at < 0) break
            from = at + callee.length
            val open = scopeBody.indexOf('(', from)
            if (open < 0) break
            // The callee must actually be called: no `foo.bar` / `foo?.bar`
            // prefix and nothing identifier-ish glued to its last character.
            val before = scopeBody.getOrNull(at - 1)
            if (before != null && (before.isLetterOrDigit() || before == '_' || before == '.')) continue
            var depth = 0
            var i = open
            while (i < scopeBody.length) {
                when (scopeBody[i]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                }
                i++
            }
            if (i >= scopeBody.length) break
            hits += splitArgs(scopeBody.substring(open + 1, i))
        }
        return hits
    }

    /** Canonical right-hand side of `val <name> = …` inside [scopeBody]. */
    fun bindingRhs(scopeBody: String, name: String): String? {
        val m = Regex("\\bval\\s+" + Regex.escape(name) + "\\s*(?::[^=\\n]*)?=").find(scopeBody) ?: return null
        val start = m.range.last + 1
        var depth = 0
        var i = start
        while (i < scopeBody.length) {
            when (scopeBody[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) break else depth--
                '\n' -> if (depth == 0) break
            }
            i++
        }
        return canonical(scopeBody.substring(start, i))
    }

    // ------------------------------------------------------- literal test

    private val NUMERIC_LITERAL = Regex("^[-+]?\\d+(\\.\\d+)?f?$")
    private val UNIT_LITERAL = Regex("^[-+]?\\d+(\\.\\d+)?\\.(dp|px|sp)$")
    private val KEYWORD_LITERAL = Regex("^(true|false|null)$")

    /**
     * Is this argument a **hardcoded literal**?
     *
     * `rows=0`, `oneRow=true`, `600`, `48`, `48.dp` count. `BAND_AFTER_PX`,
     * `paneHeight768`, `bandPx.dp`, `minOf(bandHeightPx, hostPx)` do not.
     *
     * Booleans are included on purpose: a call whose only non-numeric argument
     * is `oneRow=true` is a call that has *no* production input at all, and
     * must not be able to satisfy a fingerprint.
     */
    fun isLiteralArg(arg: String): Boolean {
        val v = if (arg.contains('=')) arg.substringAfter('=').trim() else arg.trim()
        if (v.isEmpty()) return true
        return NUMERIC_LITERAL.matches(v) || UNIT_LITERAL.matches(v) || KEYWORD_LITERAL.matches(v)
    }

    /** How many arguments of [args] are production-derived rather than literals. */
    fun productionArgCount(args: List<String>): Int = args.count { !isLiteralArg(it) }
}

// ----------------------------------------------------------------------------
// 3. The validator (pure)
// ----------------------------------------------------------------------------

internal object CallFingerprintValidator {

    /**
     * Assert **set containment** of [required] in what the source actually
     * contains. No counts: a missing fingerprint is a violation even when
     * other, unpinned calls sit right next to it.
     */
    fun validate(
        scopeSource: String,
        required: List<CallFingerprint>,
    ): List<Violation> {
        val masked = CallFingerprintScanner.mask(scopeSource)
        val violations = mutableListOf<Violation>()

        for (pin in required) {
            val body = if (pin.scope.startsWith("class ")) {
                CallFingerprintScanner.classBody(masked, pin.scope.removePrefix("class "))
            } else {
                CallFingerprintScanner.funBody(masked, pin.scope.removePrefix("fun "))
            }
            if (body == null) {
                violations += Violation(
                    pin.render(),
                    "fingerprint-scope-missing",
                    "scope '${pin.scope}' is gone from the source, so the pinned call " +
                        "${pin.callee}(…) has nowhere to live — the tripwire's detection power " +
                        "cannot be reasoned about in a method that no longer exists",
                )
                continue
            }

            val found = CallFingerprintScanner.callsIn(body, pin.callee)
            if (found.none { it == pin.args }) {
                violations += Violation(
                    pin.render(),
                    "fingerprint-missing",
                    "no call in '${pin.scope}' matches this exact fingerprint; found " +
                        "${pin.callee} at ${found.map { a -> a.joinToString(", ") }} — " +
                        "an added call does NOT substitute for the pinned one, and editing an " +
                        "argument makes the pin miss on purpose",
                )
                continue
            }

            // Hole #2: the call is there, but is it *fed* by production?
            if (CallFingerprintScanner.productionArgCount(pin.args) < pin.minProductionArgs) {
                violations += Violation(
                    pin.render(),
                    "hardcoded-argument",
                    "this call survives but its arguments are all literals — the geometry is " +
                        "invented, not computed, so the tripwire is nominally alive and " +
                        "substantively dead; at least ${pin.minProductionArgs} argument(s) must " +
                        "be a production-derived symbol",
                )
            }
        }

        return violations
    }

    /**
     * The `Frame`-bridge half of hole #2: even when all three production calls
     * are still there, the *fields they consume* must still be derived from
     * the frame's own inputs. A `val pane: Int = 51` or a `val bandPx: Int = 48`
     * is the literal rewrite v115 could not see.
     */
    fun validateBindings(
        scopeSource: String,
        className: String,
        required: List<BridgeBindingPin>,
    ): List<Violation> {
        val masked = CallFingerprintScanner.mask(scopeSource)
        val body = CallFingerprintScanner.classBody(masked, className)
            ?: return listOf(
                Violation(
                    "class $className",
                    "fingerprint-scope-missing",
                    "the facade class is gone, so every pinned call inside it lost its substrate",
                )
            )

        val violations = mutableListOf<Violation>()
        for (pin in required) {
            val rhs = CallFingerprintScanner.bindingRhs(body, pin.field)
            if (rhs == null) {
                violations += Violation(
                    "class $className { val ${pin.field} }",
                    "fingerprint-binding-missing",
                    "'val ${pin.field}' is gone from the facade; the pinned production calls now " +
                        "read something else and R5's live assertions would be measuring a fiction",
                )
                continue
            }
            if (CallFingerprintScanner.isLiteralArg(rhs) || pin.requiredRefs.none { rhs.contains(it) }) {
                violations += Violation(
                    "class $className { val ${pin.field} = $rhs }",
                    "hardcoded-field",
                    "'val ${pin.field}' no longer derives from ${pin.requiredRefs.joinToString(" / ")} " +
                        "— it is now a hardcoded literal, so every downstream 'live' assertion is " +
                        "nominally alive and substantively dead",
                )
            }
        }
        return violations
    }
}

/** One pinned field of the `Frame` facade: it must still be *derived*. */
internal data class BridgeBindingPin(
    val field: String,
    val requiredRefs: List<String>,
)

// ----------------------------------------------------------------------------
// 4. The pins, spelled out against the **real** files
// ----------------------------------------------------------------------------

internal object ComposerFingerprintSpecs {

    const val V106 = "app/src/test/java/com/xiufeigo/opencodeshell/ComposerBandTallEnoughV106Test.kt"
    const val V109 = "app/src/test/java/com/xiufeigo/opencodeshell/ComposerUnionBottomP5V109Test.kt"

    const val R5_METHOD =
        "theFivePixelOverflowIsReproducedOnTheDeviceReadingsAndGoneOnTheNewBand"
    const val R6_METHOD =
        "theBottomPaddingAbsorbsTheOverrunOnSevenSixEightButNotOnSixHundred"

    // ------------------------------------------------------------------
    // v117 裁定（R5 的必需调用清单 = **两档**，不是二选一）
    //
    // v116 §7#2 自陈的缺口：`:364` 的 `Frame(768, 466, …)` 没有进指纹集合，
    // 对它做「加新的删旧的」仍可能通过。补它之前必须先判定
    // **R5 到底该依赖 `:351` 还是 `:364`** —— Lead 已裁定：
    //
    //   **两条都要进，任一缺失即违规。** 理由：composer 那次修复的契约
    //   **同时覆盖两档** —— `:351` 的 600 档是「缺陷现场 / 已修格」，
    //   `:364` 的 768 档是「不得回退格」。只钉一档 = 放掉另一半契约，
    //   正是本仓反复踩的「名义活、实质死」。
    //
    // ⚠️ 口径统一：v115 报告把侦测力来源记成 `:347`，v116 已按源码改为 `:351`；
    //   本轮起 **R5 = `:351` + `:364` 两档**，`:347` 是记录性 fixture（真机读数），
    //   不是判据依赖。行号对照表见本报告 §3。
    // ------------------------------------------------------------------

    /** 600 档（`V106:351`）—— 缺陷现场 / 已修格：`unionBottom 218 > 213` 那档。 */
    const val R5_CALL_600 = "600, 387, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX"

    /** 768 档（`V106:364`）—— 不得回退格：`296 > 294` 那档。 */
    const val R5_CALL_768 = "768, 466, rows=0, oneRow=true, bandHeightPx=BAND_AFTER_PX"

    /**
     * R5 的**必需**调用清单 —— **两档都是必需项**。
     *
     * 每档的 `minProductionArgs = 1` 都是 load-bearing：两档的五个实参里
     * `600/387`（或 `768/466`）、`rows=0`、`oneRow=true` **全是字面量**，
     * 唯一撑住"来自生产"的就是 `BAND_AFTER_PX`。把它换成 `48`
     * ⇒ 五实参全字面量 ⇒ tripwire 掏空。
     * 而 `BAND_AFTER_PX` **就是 48**（`V106:93`）—— 所以这个变异**数值完全等价**，
     * V106 自己的 7 个用例**照样全绿**，只有指纹校验器能看见。
     * 这就是"名义活、实质死"的字面含义，也是 `minProductionArgs` 存在的理由。
     *
     * `:372` 那条 `Frame(768, 466, rows = rows, …)`（队列 0/2/11 的循环）
     * **不在**必需集合内：它是遍历队列条数的参数化检查，不是某一档的定格读数。
     * 它的存活由"循环体里至少还有一个 `Frame` 调用"间接保证；把它也 pin 死
     * 会让"改队列档位列表"这种**无害**改动被迫改 pin。判据强度不因此下降：
     * 两档的定格调用一旦被删，本清单立刻报红。
     */
    val R5_CALLS = listOf(
        CallFingerprint(
            scope = "fun $R5_METHOD",
            callee = "Frame",
            args = listOf("600", "387", "rows=0", "oneRow=true", "bandHeightPx=BAND_AFTER_PX"),
            minProductionArgs = 1,
        ),
        CallFingerprint(
            scope = "fun $R5_METHOD",
            callee = "Frame",
            args = listOf("768", "466", "rows=0", "oneRow=true", "bandHeightPx=BAND_AFTER_PX"),
            minProductionArgs = 1,
        ),
    )

    /**
     * R6's detection power is `SessionPaneLayout.composerTopPx(paneHeight768, 94)`
     * at `V109:201`. `paneHeight768` is the production-derived argument; `94` is
     * a recorded device reading and legitimately stays literal. Rewriting the
     * call to `composerTopPx(46, 94)` reproduces the *value* while removing the
     * production input — the exact "nominally alive" shape.
     */
    val R6_CALLS = listOf(
        CallFingerprint(
            scope = "fun $R6_METHOD",
            callee = "SessionPaneLayout.composerTopPx",
            args = listOf("paneHeight768", "94"),
            minProductionArgs = 1,
        ),
    )

    /**
     * The three calls v115 *did* pin — now pinned **by fingerprint**, so a new
     * fourth call cannot stand in for one of them.
     */
    val FRAME_CALLS = listOf(
        CallFingerprint(
            scope = "class Frame",
            callee = "SessionPaneLayout.composerHostHeightPx",
            args = listOf("panePx=pane", "ceilingPx=if(oneRow)bandHeightPx else STACKED_NATURAL_PX"),
            minProductionArgs = 1,
        ),
        CallFingerprint(
            scope = "class Frame",
            callee = "SessionPaneLayout.composerTopPx",
            args = listOf("pane=pane", "composer=hostPx"),
            minProductionArgs = 2,
        ),
        CallFingerprint(
            scope = "class Frame",
            callee = "SessionPaneLayout.composerBandTouchTargetDp",
            args = listOf("oneRow=oneRow", "band=bandPx.dp"),
            minProductionArgs = 2,
        ),
    )

    /**
     * v115 §8#2, verbatim: the fields those three calls read. `pane` is the
     * one v115's report named, `bandPx` and `hostPx` are the rest of the
     * substrate — hardcoding any of them hollows the whole facade.
     */
    val FRAME_BINDINGS = listOf(
        BridgeBindingPin("pane", listOf("windowPx", "imeBottomPx", "paneOverride")),
        BridgeBindingPin("bandPx", listOf("bandHeightPx", "hostPx")),
        BridgeBindingPin("hostPx", listOf("SessionPaneLayout.composerHostHeightPx", "bandHeightPx")),
    )
}

// ----------------------------------------------------------------------------
// 5. The tests
// ----------------------------------------------------------------------------

class ComposerCallFingerprintV116Test {

    private fun repoFile(relative: String): File =
        listOf(File(relative), File("app/$relative"), File("../$relative"))
            .firstOrNull { it.isFile }
            ?: error("missing $relative (cwd=${File(".").absolutePath})")

    private fun src(relative: String) = repoFile(relative).readText().replace("\r\n", "\n")

    private val v106 get() = src(ComposerFingerprintSpecs.V106)
    private val v109 get() = src(ComposerFingerprintSpecs.V109)

    // ------------------------------------------------------------ the status quo

    /** All five pinned call fingerprints are present in the real source. */
    @Test
    fun theRealSourcesContainEveryPinnedCallFingerprint() {
        assertEquals(
            "R5: ${CallFingerprintValidator.validate(v106, ComposerFingerprintSpecs.R5_CALLS)}",
            emptyList<Violation>(),
            CallFingerprintValidator.validate(v106, ComposerFingerprintSpecs.R5_CALLS),
        )
        assertEquals(
            "R6: ${CallFingerprintValidator.validate(v109, ComposerFingerprintSpecs.R6_CALLS)}",
            emptyList<Violation>(),
            CallFingerprintValidator.validate(v109, ComposerFingerprintSpecs.R6_CALLS),
        )
        assertEquals(
            "Frame: ${CallFingerprintValidator.validate(v106, ComposerFingerprintSpecs.FRAME_CALLS)}",
            emptyList<Violation>(),
            CallFingerprintValidator.validate(v106, ComposerFingerprintSpecs.FRAME_CALLS),
        )
    }

    /** And the facade's fields are still derived, not hardcoded. */
    @Test
    fun theFacadeFieldsAreStillDerived() {
        assertEquals(
            "Frame: ${CallFingerprintValidator.validateBindings(v106, "Frame", ComposerFingerprintSpecs.FRAME_BINDINGS)}",
            emptyList<Violation>(),
            CallFingerprintValidator.validateBindings(v106, "Frame", ComposerFingerprintSpecs.FRAME_BINDINGS),
        )
    }

    /**
     * The pins are not decorative: every one of them is found by the same scan
     * that reports it missing. Without this, a typo in a pin would make the
     * reverse tests "pass" for the wrong reason.
     */
    @Test
    fun everyPinIsActuallyFoundInTheRealSource() {
        val maskedV106 = CallFingerprintScanner.mask(v106)
        val r5Body = CallFingerprintScanner.funBody(maskedV106, ComposerFingerprintSpecs.R5_METHOD)!!
        val frameBody = CallFingerprintScanner.classBody(maskedV106, "Frame")!!
        for (pin in ComposerFingerprintSpecs.R5_CALLS) {
            assertTrue(
                "R5 pin must be found by the scanner: ${pin.render()}",
                CallFingerprintScanner.callsIn(r5Body, pin.callee).contains(pin.args),
            )
        }
        for (pin in ComposerFingerprintSpecs.FRAME_CALLS) {
            assertTrue(
                "Frame pin must be found by the scanner: ${pin.render()}",
                CallFingerprintScanner.callsIn(frameBody, pin.callee).contains(pin.args),
            )
        }
        val maskedV109 = CallFingerprintScanner.mask(v109)
        val r6Body = CallFingerprintScanner.funBody(maskedV109, ComposerFingerprintSpecs.R6_METHOD)!!
        for (pin in ComposerFingerprintSpecs.R6_CALLS) {
            assertTrue(
                "R6 pin must be found by the scanner: ${pin.render()}",
                CallFingerprintScanner.callsIn(r6Body, pin.callee).contains(pin.args),
            )
        }
    }

    // ------------------------------------------- reverse case ①: delete the call

    /**
     * **Mutation ① — delete the pinned call.** The record-only assertions
     * stay; the production call goes. Must be reported.
     */
    @Test
    fun deletingThePinnedCallIsReported() {
        val real = v106
        val victim = "        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)\n"
        assertTrue("the pinned line must exist verbatim in the real source", real.contains(victim))
        val deleted = real.replace(victim, "")
        val violations = CallFingerprintValidator.validate(deleted, ComposerFingerprintSpecs.R5_CALLS)
        assertEquals("only the 600 pin may fail — got $violations", 1, violations.size)
        assertEquals("fingerprint-missing", violations.first().kind)
        assertTrue(
            "must name the 600 fingerprint, not the 768 one: ${violations.first().id}",
            violations.first().id.contains("600, 387"),
        )
    }

    // ------------------------ reverse case ②: add a new one AND delete the old

    /**
     * **Mutation ② — the v115 hole.** Add a brand-new, perfectly live
     * `Frame(...)` call and delete the pinned one. v115's count-based check
     * ("≥ 1 live assertion") is *satisfied* by the new call and stays green.
     * The fingerprint check must still go red, because the pinned fingerprint
     * is not present.
     *
     * Both halves are asserted here: the new call really is present and really
     * does satisfy the old count-style criterion, and the fingerprint check
     * still refuses it. That is the proof the improvement is real.
     */
    @Test
    fun addingANewCallAndDeletingTheOldOneIsStillReported() {
        val real = v106
        val victim = "        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)\n"
        assertTrue("the pinned line must exist verbatim in the real source", real.contains(victim))
        val replacement =
            "        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_BEFORE_PX)\n" +
                "        val after900 = Frame(900, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)\n"
        val mutated = real.replace(victim, replacement)
        assertTrue("the old fingerprint must be gone", !mutated.contains("Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)"))

        // The new call is genuinely there — a count-based validator is content.
        val body = CallFingerprintScanner.funBody(CallFingerprintScanner.mask(mutated), ComposerFingerprintSpecs.R5_METHOD)!!
        val newCalls = CallFingerprintScanner.callsIn(body, "Frame")
        assertTrue(
            "the replacement call must be present, otherwise this test proves nothing: $newCalls",
            newCalls.contains(listOf("900", "387", "rows=0", "oneRow=true", "bandHeightPx=BAND_AFTER_PX")),
        )

        // …and the fingerprint check refuses it anyway.
        val violations = CallFingerprintValidator.validate(mutated, ComposerFingerprintSpecs.R5_CALLS)
        assertEquals("only the 600 pin may fail — got $violations", 1, violations.size)
        assertEquals("fingerprint-missing", violations.first().kind)
        assertTrue(
            "must name the 600 fingerprint: ${violations.first().id}",
            violations.first().id.contains("600, 387"),
        )
    }

    // --------------------------- reverse case ③: arguments hardcoded to literals

    /**
     * **Mutation ③ — the v115 §8#2 hole.** The call is still there, the method
     * still has live assertions, so v115 is green; but `bandHeightPx` is now a
     * literal and the geometry is invented. Must be reported twice over: the
     * call no longer matches the pinned fingerprint, *and* the matching shape
     * has no production-derived argument at all.
     */
    @Test
    fun hardcodingThePinnedCallArgumentIsReported() {
        val real = v106
        val mutated = real.replace(
            "bandHeightPx = BAND_AFTER_PX)\n        assertEquals(\"600+IME pane\"",
            "bandHeightPx = 48)\n        assertEquals(\"600+IME pane\"",
        )
        assertTrue("the mutation must have applied", !mutated.contains("bandHeightPx = BAND_AFTER_PX)\n        assertEquals(\"600+IME pane\""))
        val violations = CallFingerprintValidator.validate(mutated, ComposerFingerprintSpecs.R5_CALLS)
        assertTrue("expected fingerprint-missing, got $violations", violations.any { it.kind == "fingerprint-missing" })

        // Same shape, expressed as a pin whose arguments are all literals: the
        // call is "present" yet must be refused.
        val allLiteral = listOf(
            CallFingerprint(
                scope = "fun ${ComposerFingerprintSpecs.R5_METHOD}",
                callee = "Frame",
                args = listOf("600", "387", "rows=0", "oneRow=true", "bandHeightPx=48"),
                minProductionArgs = 1,
            ),
        )
        val literalViolations = CallFingerprintValidator.validate(mutated, allLiteral)
        assertTrue("expected hardcoded-argument, got $literalViolations", literalViolations.any { it.kind == "hardcoded-argument" })
    }

    /**
     * Mutation ③ at the bridge: `val pane: Int = …` and `val bandPx: Int = …`
     * rewritten to literals. All three production calls survive untouched, so
     * v115's `frameIsAFacadeOverTheProductionLayout` is still green — this is
     * the case it structurally cannot see.
     */
    @Test
    fun hardcodingTheFacadeFieldsIsReported() {
        val paneLiteral = v106.replace(
            "        val pane: Int = (paneOverride ?: (windowPx - CHROME_PX - imeBottomPx)).coerceAtLeast(0)",
            "        val pane: Int = 51",
        )
        assertTrue("the pane mutation must have applied", paneLiteral.contains("val pane: Int = 51"))
        val paneViolations = CallFingerprintValidator.validateBindings(paneLiteral, "Frame", ComposerFingerprintSpecs.FRAME_BINDINGS)
        assertTrue("expected hardcoded-field for pane, got $paneViolations", paneViolations.any { it.kind == "hardcoded-field" && it.id.contains("val pane") })
        // The three production calls are all still present — v115 stays green here.
        assertEquals(
            "the production calls must be untouched by this mutation",
            emptyList<Violation>(),
            CallFingerprintValidator.validate(paneLiteral, ComposerFingerprintSpecs.FRAME_CALLS),
        )

        val bandLiteral = v106.replace(
            "        val bandPx: Int = minOf(bandHeightPx, hostPx)",
            "        val bandPx: Int = 48",
        )
        assertTrue("the bandPx mutation must have applied", bandLiteral.contains("val bandPx: Int = 48"))
        val bandViolations = CallFingerprintValidator.validateBindings(bandLiteral, "Frame", ComposerFingerprintSpecs.FRAME_BINDINGS)
        assertTrue("expected hardcoded-field for bandPx, got $bandViolations", bandViolations.any { it.kind == "hardcoded-field" && it.id.contains("val bandPx") })
    }

    /** Deleting a facade field outright is a distinct, also-reported failure. */
    @Test
    fun deletingTheFacadeFieldIsReported() {
        val mutated = v106.replace(
            "        val bandPx: Int = minOf(bandHeightPx, hostPx)\n",
            "",
        )
        assertTrue("the mutation must have applied", !mutated.contains("val bandPx: Int = minOf(bandHeightPx, hostPx)"))
        val violations = CallFingerprintValidator.validateBindings(mutated, "Frame", ComposerFingerprintSpecs.FRAME_BINDINGS)
        assertTrue("expected fingerprint-binding-missing, got $violations", violations.any { it.kind == "fingerprint-binding-missing" })
    }

    /** R6's call: hardcoding `paneHeight768` to the value it computes. */
    @Test
    fun hardcodingR6sProductionArgumentIsReported() {
        val mutated = v109.replace(
            "        val hostTop768 = SessionPaneLayout.composerTopPx(paneHeight768, 94)",
            "        val hostTop768 = SessionPaneLayout.composerTopPx(46, 94)",
        )
        assertTrue("the mutation must have applied", mutated.contains("composerTopPx(46, 94)"))
        val violations = CallFingerprintValidator.validate(mutated, ComposerFingerprintSpecs.R6_CALLS)
        assertTrue("expected fingerprint-missing, got $violations", violations.any { it.kind == "fingerprint-missing" })
        val sameShape = listOf(
            CallFingerprint(
                scope = "fun ${ComposerFingerprintSpecs.R6_METHOD}",
                callee = "SessionPaneLayout.composerTopPx",
                args = listOf("46", "94"),
                minProductionArgs = 1,
            ),
        )
        assertTrue(
            "expected hardcoded-argument, got ${CallFingerprintValidator.validate(mutated, sameShape)}",
            CallFingerprintValidator.validate(mutated, sameShape).any { it.kind == "hardcoded-argument" },
        )
    }

    // ------------------------------------------------- v117: the 768 tier
    //
    // Everything above is v116 and still stands. v117 adds the second half of
    // R5's contract: `V106:364`'s `Frame(768, 466, …)`. Lead's ruling is that
    // **both** tiers are required — 600 is the fixed cell, 768 is the
    // must-not-regress cell — so deleting either one alone must go red, and
    // neither call may stand in for the other.

    /** Verbatim source lines for the two pinned tiers (exact indentation). */
    private val line600 =
        "        val after600 = Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)\n"
    private val line768 =
        "        val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)\n"

    /**
     * **The ruling itself, asserted structurally.** Both tiers are in the
     * required set, and they are *distinct* fingerprints — if `:364` had
     * been pinned with `:351`'s arguments (or simply omitted), this goes red
     * before any mutation is even attempted.
     */
    @Test
    fun bothTiersAreRequiredAndAreDistinctFingerprints() {
        val r5 = ComposerFingerprintSpecs.R5_CALLS
        assertEquals(
            "R5 must require exactly two pinned calls — the 600 cell and the 768 cell",
            2,
            r5.size,
        )
        assertEquals(
            "the two tiers must be distinct fingerprints, not one call pinned twice",
            2,
            r5.map { it.key() }.distinct().size,
        )
        assertEquals(
            "600 档 must be the pinned `Frame(600, 387, …)`",
            ComposerFingerprintSpecs.R5_CALL_600,
            r5[0].args.joinToString(", "),
        )
        assertEquals(
            "768 档 must be the pinned `Frame(768, 466, …)`",
            ComposerFingerprintSpecs.R5_CALL_768,
            r5[1].args.joinToString(", "),
        )
    }

    /**
     * **Mutation ①-768 — delete the 768 call only.** The 600 call sits right
     * next to it and is completely untouched, so a "≥ 1 live assertion" or
     * "some `Frame` call exists" criterion is perfectly satisfied. Must still
     * be reported, naming the 768 fingerprint specifically.
     */
    @Test
    fun deletingTheSevenSixEightCallIsReported() {
        val real = v106
        assertTrue("the 768 line must exist verbatim in the real source", real.contains(line768))
        assertTrue("the 600 line must be untouched by this mutation", real.contains(line600))
        val mutated = real.replace(line768, "")
        val violations = CallFingerprintValidator.validate(mutated, ComposerFingerprintSpecs.R5_CALLS)
        assertEquals(
            "exactly one violation: only the 768 pin is missing — got $violations",
            1,
            violations.size,
        )
        assertEquals("fingerprint-missing", violations.first().kind)
        assertTrue(
            "the violation must name the 768 fingerprint, not the 600 one: ${violations.first().id}",
            violations.first().id.contains("768, 466"),
        )
    }

    /**
     * **Both tiers deleted at once** ⇒ two violations, one per tier. This is
     * what "the contract covers both cells" means operationally: removing the
     * entire post-fix half of R5 cannot leave a single green light behind.
     */
    @Test
    fun deletingBothTiersIsReportedOncePerTier() {
        val mutated = v106.replace(line600, "").replace(line768, "")
        val violations = CallFingerprintValidator.validate(mutated, ComposerFingerprintSpecs.R5_CALLS)
        assertEquals("one violation per deleted tier — got $violations", 2, violations.size)
        assertEquals(
            listOf("fingerprint-missing", "fingerprint-missing"),
            violations.map { it.kind },
        )
        assertTrue(
            "600 must be reported: ${violations.map { it.id }}",
            violations.any { it.id.contains("600, 387") },
        )
        assertTrue(
            "768 must be reported: ${violations.map { it.id }}",
            violations.any { it.id.contains("768, 466") },
        )
    }

    /**
     * **Mutation ②-768 — the v115 hole, applied to the 768 cell.** A brand-new
     * live `Frame(...)` call is added and the pinned 768 one is deleted. The
     * 600 call survives, so every "at least one live assertion" criterion in
     * v115 is satisfied. The fingerprint check must refuse anyway, and it must
     * refuse *because of the 768 pin specifically*.
     */
    @Test
    fun addingANewCallAndDeletingTheSevenSixEightOneIsStillReported() {
        val real = v106
        assertTrue("the 768 line must exist verbatim in the real source", real.contains(line768))
        val replacement =
            "        val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_BEFORE_PX)\n" +
                "        @Suppress(\"unused\") val after900 = Frame(900, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)\n"
        val mutated = real.replace(line768, replacement)
        assertTrue(
            "the pinned 768 fingerprint must be gone",
            !mutated.contains("Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)"),
        )

        // The decoy is genuinely present — a count-based validator is content.
        val body = CallFingerprintScanner.funBody(
            CallFingerprintScanner.mask(mutated),
            ComposerFingerprintSpecs.R5_METHOD,
        )!!
        val newCalls = CallFingerprintScanner.callsIn(body, "Frame")
        assertTrue(
            "the replacement call must be present, otherwise this test proves nothing: $newCalls",
            newCalls.contains(listOf("900", "466", "rows=0", "oneRow=true", "bandHeightPx=BAND_AFTER_PX")),
        )
        assertTrue(
            "the untouched 600 call must still be there — v115 stays green: $newCalls",
            newCalls.contains(listOf("600", "387", "rows=0", "oneRow=true", "bandHeightPx=BAND_AFTER_PX")),
        )

        val violations = CallFingerprintValidator.validate(mutated, ComposerFingerprintSpecs.R5_CALLS)
        assertEquals("only the 768 pin may fail — got $violations", 1, violations.size)
        assertEquals("fingerprint-missing", violations.first().kind)
        assertTrue(
            "must name the 768 fingerprint: ${violations.first().id}",
            violations.first().id.contains("768, 466"),
        )
    }

    /**
     * **Mutation ③-768 — hardcode the 768 cell's only production argument.**
     *
     * `bandHeightPx = BAND_AFTER_PX` → `bandHeightPx = 48`, and
     * `BAND_AFTER_PX` **is** 48 (`V106:93`), so every geometric assertion in
     * `ComposerBandTallEnoughV106Test` still passes — v115 and v116's 600 pin
     * stay green. This is the numerically-equivalent variant the ruling has
     * to survive, on the tier v116 had left unpinned.
     */
    @Test
    fun hardcodingTheSevenSixEightCallsArgumentIsReported() {
        val mutated = v106.replace(
            "Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)",
            "Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = 48)",
        )
        assertTrue(
            "the mutation must have applied",
            mutated.contains("Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = 48)"),
        )

        // The 600 pin is unaffected — proving the red is about 768, not collateral.
        assertEquals(
            "the 600 pin must still pass under this mutation",
            emptyList<Violation>(),
            CallFingerprintValidator.validate(mutated, listOf(ComposerFingerprintSpecs.R5_CALLS[0])),
        )

        val violations = CallFingerprintValidator.validate(mutated, ComposerFingerprintSpecs.R5_CALLS)
        assertEquals("only the 768 pin may fail — got $violations", 1, violations.size)
        assertEquals("fingerprint-missing", violations.first().kind)

        // And the all-literal shape is refused on provenance grounds too.
        val allLiteral768 = listOf(
            CallFingerprint(
                scope = "fun ${ComposerFingerprintSpecs.R5_METHOD}",
                callee = "Frame",
                args = listOf("768", "466", "rows=0", "oneRow=true", "bandHeightPx=48"),
                minProductionArgs = 1,
            ),
        )
        assertTrue(
            "expected hardcoded-argument, got ${CallFingerprintValidator.validate(mutated, allLiteral768)}",
            CallFingerprintValidator.validate(mutated, allLiteral768).any { it.kind == "hardcoded-argument" },
        )
    }

    /**
     * **Neither tier substitutes for the other.** A pin set that contained
     * only `:351` would be satisfied by a source that keeps 600 and drops 768;
     * one that contained only `:364` would be satisfied by the mirror image.
     * Both directions are asserted on the real source, so the ruling cannot
     * quietly collapse back to "either tier will do".
     */
    @Test
    fun neitherTierSubstitutesForTheOther() {
        val no768 = v106.replace(line768, "")
        val no600 = v106.replace(line600, "")

        val keepOnly600 = CallFingerprintValidator.validate(no768, ComposerFingerprintSpecs.R5_CALLS)
        assertTrue(
            "keeping only the 600 tier must still fail the 768 pin — got $keepOnly600",
            keepOnly600.any { it.kind == "fingerprint-missing" && it.id.contains("768, 466") },
        )

        val keepOnly768 = CallFingerprintValidator.validate(no600, ComposerFingerprintSpecs.R5_CALLS)
        assertTrue(
            "keeping only the 768 tier must still fail the 600 pin — got $keepOnly768",
            keepOnly768.any { it.kind == "fingerprint-missing" && it.id.contains("600, 387") },
        )
    }

    /** A KDoc that merely *mentions* `Frame(768, 466, …)` buys nothing either. */
    @Test
    fun aCommentAdvertisingTheSevenSixEightCallDoesNotSatisfyThePin() {
        val skeleton = """
            fun ${ComposerFingerprintSpecs.R5_METHOD}() {
                // val after768 = Frame(768, 466, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
                assertEquals("真机 768 档 ①：296 > 294 ⇒ +2", 2, 296 - 294)
            }
        """.trimIndent()
        val violations = CallFingerprintValidator.validate(skeleton, ComposerFingerprintSpecs.R5_CALLS)
        assertTrue("a comment must not satisfy a fingerprint pin, got $violations", violations.any { it.kind == "fingerprint-missing" })
    }

    // ----------------------------------------------------- the primitives

    /** Argument splitting respects nesting, so pins are unambiguous. */
    @Test
    fun argumentSplittingRespectsNesting() {
        assertEquals(
            listOf("pane=pane", "composer=hostPx"),
            CallFingerprintScanner.splitArgs("\n            pane = pane,\n            composer = hostPx,\n        "),
        )
        assertEquals(
            listOf("ceilingPx=if(oneRow)bandHeightPx else STACKED_NATURAL_PX"),
            CallFingerprintScanner.splitArgs(" ceilingPx = if (oneRow) bandHeightPx else STACKED_NATURAL_PX "),
        )
    }

    /** Booleans count as literals: a call with no real input is not production. */
    @Test
    fun literalDetectionCoversNumbersUnitsAndBooleans() {
        for (lit in listOf("600", "rows=0", "oneRow=true", "bandHeightPx=48", "48.dp", "-1")) {
            assertTrue("must be a literal: $lit", CallFingerprintScanner.isLiteralArg(lit))
        }
        for (prod in listOf("BAND_AFTER_PX", "paneHeight768", "pane=pane", "band=bandPx.dp", "windowPx")) {
            assertTrue("must not be a literal: $prod", !CallFingerprintScanner.isLiteralArg(prod))
        }
    }

    /**
     * A KDoc that *advertises* the pinned call must not satisfy the pin —
     * inherited from v115's masking, asserted here because the whole
     * fingerprint scheme rests on it.
     */
    @Test
    fun aCommentAdvertisingTheCallDoesNotSatisfyThePin() {
        val skeleton = """
            fun ${ComposerFingerprintSpecs.R5_METHOD}() {
                // Frame(600, 387, rows = 0, oneRow = true, bandHeightPx = BAND_AFTER_PX)
                assertEquals("真机 600 档 ①：unionBottom 218 > hostBottom(imeTop) 213 ⇒ +5", 5, 218 - 213)
            }
        """.trimIndent()
        val violations = CallFingerprintValidator.validate(skeleton, ComposerFingerprintSpecs.R5_CALLS)
        assertTrue("a comment must not satisfy a fingerprint pin, got $violations", violations.any { it.kind == "fingerprint-missing" })
    }
}
