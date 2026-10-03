package com.xiufeigo.opencodeshell.ui.session.header

/**
 * v97 N3 fix2: **who authorized a save?** The inline title editor's blur used to be
 * a save, unconditionally — the upstream reading of
 * `message-timeline.tsx:485` (`onBlur` → `void saveTitleEditor()`).
 *
 * That reading is sound on the web, where a blur means "the user clicked somewhere
 * else on this page". It is wrong in this shell, because the shell's navigation
 * chrome is *outside* the session's own composition and is made of
 * `Modifier.clickable` boxes — and a `clickable` **takes focus**. Tapping 首页/Home
 * (or a tab in the strip) therefore blurs the title field *while the session is
 * still composed*, which the editor cannot tell apart from a tap-away inside the
 * session. The editor did save, and the write left the shell before navigation
 * was even under way.
 *
 * The device evidence, twice, on two builds (`research/v97_verify/5556_v98/REPORT_FIXES.md`
 * B4, `12:26:07` and `12:39:01`): tap title → type `E2ECHK5556v98_discard` →
 * tap Home → `GET /api/session` already carries the draft. And the teardown guard
 * installed for it (`InlineTitleEdit.leaving` + `onDispose { edit.leave() }`) could
 * not stop it, because by the time the composition is torn down the save has
 * **already** been dispatched: the blur was a *focus steal*, not a detach.
 *
 * So the fence is an **authorization**, not a latch. A save needs a positive
 * "an in-session user action asked for this" signal:
 *
 * - Enter / the IME's `Done` — allowed without any arm: the field is still focused
 *   when the key arrives, which is checked at the field, not here;
 * - the session screen's own tap-away — [arm]ed immediately **before** it calls
 *   `focusManager.clearFocus()`, so the blur it provokes is served with the arm
 *   already in place (synchronous, same call — not a frame of luck);
 * - anything else — a focus steal by the chrome's Home button, a tab in the
 *   strip, the back chevron, the drawer entry; the teardown detach — arrives with
 *   nothing armed and is therefore **discarded**.
 *
 * [consumeBlurSave] is single-use, which is what makes the fence safe rather than
 * merely lucky: an arm cannot survive the blur it was meant for, so a later
 * unrelated blur can never inherit an earlier tap-away's permission. [disarm] drops
 * any arm that was never spent — a `clearFocus()` on a field that was not focused
 * produces no blur at all, and that unused arm must not outlive the editor.
 *
 * Pure and framework-free on purpose: the whole matrix is JVM-drivable
 * (`SessionTitleDiscardV97Test`), the way [InlineTitleEdit] is.
 */
class TitleEditFence {

    /** True while an unspent in-session authorization exists. */
    var armed: Boolean = false
        private set

    /**
     * The session screen's outside-tap handler arms immediately before it clears
     * focus. Arming on a consumed press would authorize a blur that never comes,
     * so the caller's `event.changes.none { it.isConsumed }` gate is load-bearing.
     */
    fun arm() {
        armed = true
    }

    /**
     * Drop an unspent arm. Called when the editor takes focus again and when its
     * composition goes away, so a permission can never outlive the editor it was
     * given to.
     */
    fun disarm() {
        armed = false
    }

    /**
     * The blur gate: `true` when an in-session tap-away asked for this save, and
     * `false` for every other way focus can leave the field. Consuming is what
     * makes the answer single-use.
     */
    fun consumeBlurSave(): Boolean {
        val wasArmed = armed
        armed = false
        return wasArmed
    }
}
