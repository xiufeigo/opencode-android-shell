package com.xiufeigo.opencodeshell.ui.servers

/**
 * v98 D3 — 「Add/Edit SSH 表单的字段不可达：口令字段落在裁切线外、被剔出 a11y 树，
 * 导致表单根本提交不了」(`research/v97_verify/5554_v98d3/REPORT_COVERAGE4.md`
 * §2.1/§2.5、`REPORT_COVERAGE5.md` §4.3).
 *
 * **缺陷定性（代码级）.** [SshDialog]'s card is capped at the band
 * (`serverDialogBandDp`, floored at [SERVER_DIALOG_WANTED_BAND_DP]'s 80.5 dp
 * pinned footer + its own form floor) and its body is a `weight`ed column
 * carrying the only `verticalScroll` in the card. The scroll range is therefore
 * real but narrow, and the card is itself drawn inside the Servers panel's own
 * scroller — so a field can be inside the scroller's box and still sit below the
 * clip that actually applies. Two things then follow, and both were measured on
 * `emulator-5554` (1080x2424):
 *
 * 1. **几何**：the password field is the last drawn field of the `PASSWORD`
 *    branch, i.e. the deepest one. Every scroll position that still shows the
 *    head of the form puts it below the clip; 11 positions across two rounds
 *    never surfaced it (`REPORT_COVERAGE5.md` §4.3).
 * 2. **无障碍**：a node fully outside the clip is **pruned from the semantics
 *    tree**, so it is not focusable, not clickable and not addressable by any
 *    a11y client (TalkBack, `uiautomator`) — the field is not merely hard to
 *    reach, it is absent.
 *
 * [validateSshForm] refuses a `PASSWORD` submit with no password, the refusal
 * line renders, and the user is told to enter a password into a field that is
 * not there ⇒ `onSubmit` is never reached ⇒ no entry is persisted and no tunnel
 * row is created (`REPORT_COVERAGE5.md` §4.3's causal chain, `ServersPanel`'s
 * `putSshEntry` / `submit` never running).
 *
 * **修法（不改安全边界）.** Every required field carries its own
 * [androidx.compose.foundation.relocation.BringIntoViewRequester], and a
 * refusal reveals the field it points at. The request walks the **whole**
 * responder chain — the form's own `verticalScroll` *and* the panel's outer
 * one — so the field ends up inside the region that is both visible and in the
 * semantics tree. This is the same shape as the row-delete fix
 * (`SshTunnelForget.kt`, `research/v97_fix/SSH_DELETE_FIX.md`).
 *
 * **What deliberately does not move**: the gate ([validateSshForm]) is
 * untouched, `Add server` keeps `enabled = !busy && target.isNotBlank()`, the
 * band / floor / lift arithmetic is untouched, and not one string is added. The
 * fix makes the fields *reachable*; it does not make the password *optional*.
 */

/**
 * One drawable field of the SSH form, as a key into the dialog's per-field
 * bring-into-view handles.
 *
 * The enum is the reach vocabulary: a field the form can draw but not reveal is
 * exactly the v98 defect, so every branch below that composes a field also
 * names it here.
 */
enum class SshFormField {
    /** `Host or SSH command` — the target. */
    Target,

    /** `Server name (optional)`. */
    Name,

    /** The `Password` field of the `PASSWORD` branch — the field the gate demands. */
    Password,

    /** The `Key path` field of the `KEY` branch. */
    PrivateKeyPath,

    /** The `Key passphrase` field of the `KEY` branch. */
    Passphrase,
}

/**
 * The field a refusal is about — the one the user has to fill, and therefore the
 * one a refused submit must bring back into the viewport.
 *
 * Exhaustive over [SshFormError] on purpose: a new gate branch that adds a
 * refusal **cannot compile** without deciding which field it points at, so a
 * refusal can never again be a line of red text with nothing to act on.
 */
fun sshFormErrorField(error: SshFormError): SshFormField = when (error) {
    SshFormError.TargetRequired -> SshFormField.Target
    SshFormError.PasswordRequired -> SshFormField.Password
    SshFormError.KeyRequired -> SshFormField.PrivateKeyPath
}

/**
 * Every field the gate **demands** for [auth], in draw order.
 *
 * Read straight off [validateSshForm]'s own branches: the target always, the
 * password for [SshAuthMethod.PASSWORD], the key path for [SshAuthMethod.KEY].
 * `name` and the passphrase are drawn but never demanded, so they are
 * [SshFormDrawnFields] and not required — this list is the security boundary
 * restated as data, which is what the pin compares against.
 */
fun sshFormRequiredFields(auth: SshAuthMethod): List<SshFormField> = when (auth) {
    SshAuthMethod.PASSWORD -> listOf(
        SshFormField.Target,
        SshFormField.Password,
    )

    SshAuthMethod.KEY -> listOf(
        SshFormField.Target,
        SshFormField.PrivateKeyPath,
    )
}

/**
 * Every field the form actually **draws** for [auth], in draw order — the reach
 * set each of those fields must be inside.
 *
 * A drawn field is not a required one: `name` is optional and the passphrase is
 * optional, and both still have to be reachable to be filled, which is why the
 * two sets are separate rather than one.
 */
fun sshFormDrawnFields(auth: SshAuthMethod): List<SshFormField> = when (auth) {
    SshAuthMethod.PASSWORD -> listOf(
        SshFormField.Target,
        SshFormField.Name,
        SshFormField.Password,
    )

    SshAuthMethod.KEY -> listOf(
        SshFormField.Target,
        SshFormField.Name,
        SshFormField.PrivateKeyPath,
        SshFormField.Passphrase,
    )
}

/**
 * Whether every field [SshDialog] draws for [auth] has a bring-into-view handle
 * to be revealed through.
 *
 * The v98 defect is precisely a **drawn-but-unreachable** field, so this is the
 * predicate the pin drives: the dialog builds one handle per [SshFormField] and
 * every branch's fields must find theirs. `null` for an unknown field keeps the
 * answer honest — a handle set that is missing an entry returns `false`, not a
 * silent success.
 */
fun sshFormFieldsAllReachable(
    auth: SshAuthMethod,
    reachable: Set<SshFormField>,
): Boolean {
    val drawn = sshFormDrawnFields(auth)
    return drawn.isNotEmpty() && drawn.all { reachable.contains(it) }
}
