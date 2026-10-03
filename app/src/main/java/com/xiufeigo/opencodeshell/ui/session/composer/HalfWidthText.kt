package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.ui.text.input.TextFieldValue

/**
 * W5-F: full-width (全角) → half-width input normalization for the composer.
 *
 * **Why this file exists** (measured on `emulator-5554`, see
 * `research/wave5_composer.md` §8): a Chinese IME rewrites the punctuation keys
 * into their full-width forms, so both `adb shell input keyevent 76`
 * (KEYCODE_SLASH) and `adb shell input text "/"` land in the editor as `／`
 * (U+FF0F) — never as ASCII `/`. The composer's syntax is keyed on the ASCII
 * characters (`ComposerSuggestions.commandQuery` mirrors upstream's
 * `/^\/(\S*)$/`, `composer/suggestions/machine.ts:107`), so with a Chinese IME
 * the `/` menu (B1) and the `@` popover (B9) could never open: the trigger never
 * matched. This is the one and only reason B1 "did not pop up on the device".
 *
 * Two rules, deliberately different:
 *
 * - [normalize] is the **editing** rule. It rewrites only the three characters
 *   the composer *reads as syntax* — `／`→`/`, `＠`→`@`, `U+3000`→`' '` — and
 *   leaves everything else the user typed alone, CJK punctuation (`，。！？`)
 *   included: rewriting a user's prose is not the composer's business.
 *   It is strictly 1:1, so caret, selection and the IME's composition range all
 *   survive the fold.
 * - [toAscii] is the **comparison** rule: the whole `U+FF01`–`U+FF5E` block plus
 *   the ideographic space folds onto ASCII. It is only ever used where the text
 *   is *read* (candidate filtering, `/name` lookup), never written back, so a
 *   user whose IME is fully in 全角 mode (`／ｍｏｄｅｌ`) finds and submits
 *   `/model` while their prose stays exactly as typed.
 *
 * Precedent: `ui/settings/ServersPanel.kt:297` folds pasted server URLs the same
 * way (`ch in '！'..'～' -> append(ch - 0xFEE0)`).
 *
 * Everything here is asserted by `Wave5HalfWidthInputTest`.
 */
object HalfWidthText {

    /** `U+FF01` `！` … `U+FF5E` `～`. */
    private const val FF_FIRST = '\uFF01'
    private const val FF_LAST = '\uFF5E'

    /** Every half-width ASCII printable has its full-width twin exactly this far above it. */
    const val FULL_WIDTH_OFFSET = 0xFEE0

    /** `U+3000` — the ideographic space a Chinese IME's space bar produces. */
    const val IDEOGRAPHIC_SPACE = '\u3000'

    /** `／` — what a Chinese IME sends for the `/` key. */
    const val FULL_WIDTH_SLASH = '\uFF0F'

    /** `＠` — what a Chinese IME sends for the `@` key. */
    const val FULL_WIDTH_AT = '\uFF20'

    /** `！` — what a Chinese IME sends for the `!` key. */
    const val FULL_WIDTH_BANG = '\uFF01'

    /** True for the full-width twin of a half-width ASCII printable. */
    fun isFullWidthAscii(ch: Char): Boolean = ch in FF_FIRST..FF_LAST

    /** The half-width twin of [ch], or [ch] itself when it has none. */
    fun halfWidth(ch: Char): Char = when {
        isFullWidthAscii(ch) -> ch - FULL_WIDTH_OFFSET
        ch == IDEOGRAPHIC_SPACE -> ' '
        else -> ch
    }

    /**
     * The editing rule (see the type doc): `／`→`/`, `＠`→`@`, `U+3000`→`' '`.
     *
     * Identity-preserving: when there is nothing to rewrite — every keystroke of
     * a half-width or CJK input — the very same [text] instance comes back, so
     * the editor's state write stays reference-equal and schedules no extra
     * recomposition.
     */
    fun normalize(text: String): String {
        var hit = false
        for (ch in text) {
            if (isEditingTarget(ch)) {
                hit = true
                break
            }
        }
        if (!hit) return text
        return buildString(text.length) {
            for (ch in text) {
                append(
                    when (ch) {
                        FULL_WIDTH_SLASH -> '/'
                        FULL_WIDTH_AT -> '@'
                        IDEOGRAPHIC_SPACE -> ' '
                        else -> ch
                    },
                )
            }
        }
    }

    /**
     * The editing rule for a whole editor value. 1:1 (see [normalize]), so the
     * selection and the IME's composing region keep their offsets; the value is
     * returned unchanged when there is nothing to fold.
     */
    fun normalize(value: TextFieldValue): TextFieldValue {
        val text = normalize(value.text)
        return if (text === value.text) value else TextFieldValue(text, value.selection, value.composition)
    }

    /**
     * The **name** rule (v40 BUG-2): the whole `U+FF01`–`U+FF5E` block and
     * `U+3000` fold onto ASCII.
     *
     * [normalize] is deliberately narrow — it rewrites only what the composer
     * *reads as syntax*, so a user's CJK prose (`，`, `。`, `！`) is left alone.
     * A **name** is not prose: it is an identifier the app sends on the wire and
     * shows back in a row, a tab and a header, so a Chinese IME's full-width
     * `：` where the user pressed `:` is a bug in every one of those places
     * (`research/v40_verify/tC/h4_typed.png`), and `OcKeyboard.literal` only
     * sets `autoCorrectEnabled = false`, which does not stop it. This is the
     * title-field input boundary: Home's row rename editor, the header's inline
     * title, the row rename dialog and the tab rename field all fold through it.
     *
     * Reference-preserving like [normalize], so an untouched keystroke schedules
     * no extra recomposition.
     */
    fun normalizeName(text: String): String {
        var hit = false
        for (ch in text) {
            if (isFullWidthAscii(ch) || ch == IDEOGRAPHIC_SPACE) {
                hit = true
                break
            }
        }
        if (!hit) return text
        return buildString(text.length) {
            for (ch in text) append(halfWidth(ch))
        }
    }

    /** [normalizeName] for a whole editor value. 1:1, so the selection survives. */
    fun normalizeName(value: TextFieldValue): TextFieldValue {
        val text = normalizeName(value.text)
        return if (text === value.text) value else TextFieldValue(text, value.selection, value.composition)
    }

    /**
     * The comparison rule (see the type doc): `U+FF01`–`U+FF5E` fold onto ASCII
     * and `U+3000` becomes a plain space. Reference-preserving like [normalize],
     * and used by the suggestion filters / the `/name` lookup only.
     */
    fun toAscii(text: String): String {
        var hit = false
        for (ch in text) {
            if (isFullWidthAscii(ch) || ch == IDEOGRAPHIC_SPACE) {
                hit = true
                break
            }
        }
        if (!hit) return text
        return buildString(text.length) {
            for (ch in text) append(halfWidth(ch))
        }
    }

    /**
     * `machine.ts:93-97`'s lone-`!` shell trigger (`Composer.kt` B3), widened to
     * the character a Chinese IME produces for it: exactly `!` or exactly `！`.
     * A mixed string is never a trigger, so prose that merely ends in an
     * exclamation mark cannot switch the editor into shell mode.
     */
    fun isShellTrigger(text: String): Boolean =
        text == "!" || (text.length == 1 && text[0] == FULL_WIDTH_BANG)

    private fun isEditingTarget(ch: Char): Boolean =
        ch == FULL_WIDTH_SLASH || ch == FULL_WIDTH_AT || ch == IDEOGRAPHIC_SPACE
}
