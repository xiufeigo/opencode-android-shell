package com.xiufeigo.opencodeshell.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboard options for the app's **literal** inputs: the rename editors, the
 * title fields, and the identifier fields (terminal name, worktree/branch name,
 * path, shell command).
 *
 * Device bug: Gboard rewrote what these fields were handed. `:` came out as the
 * full-width `：`, the pinyin IME swallowed ASCII while it was composing, and
 * autocorrect replaced identifiers with dictionary words — and the field's value
 * is not prose to be corrected: it is a name or a command the app sends on the
 * wire verbatim (a terminal/tab title, `git worktree add`, `POST /api/shell`).
 *
 * `autoCorrectEnabled = false` is the half that stops the rewrite: it becomes
 * `ImeOptions.autoCorrect = false` (`foundation 1.7.6` `KeyboardOptions.kt`:
 * `toImeOptions` → `getAutoCorrectOrDefault` → `autoCorrectEnabled ?: true`),
 * i.e. the IME's `IME_FLAG_NO_AUTO_CORRECT`. The ASCII layout is the second half
 * for the pure-identifier fields, which keeps the composing pinyin IME off them
 * entirely.
 *
 * Pure, so the matrix is JVM-testable (`OcKeyboardOptionsTest`) — the same split
 * the other Compose-glue helpers in this repo use.
 */
internal object OcKeyboard {

    /**
     * A literal the app sends verbatim but that may hold any script — titles,
     * tab names, credential labels. Autocorrect off, layout unchanged.
     */
    fun literal(imeAction: ImeAction = ImeAction.Default): KeyboardOptions =
        KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = imeAction,
        )

    /**
     * A pure-ASCII identifier — terminal name, path, worktree/branch name. Same
     * as [literal] plus the ASCII layout, so the IME does not compose over it.
     */
    fun identifier(imeAction: ImeAction = ImeAction.Default): KeyboardOptions =
        KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = imeAction,
        )
}
