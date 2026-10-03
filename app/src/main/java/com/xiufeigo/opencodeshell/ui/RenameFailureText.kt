package com.xiufeigo.opencodeshell.ui

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.v2.SessionRenameOutcome

/**
 * v81 P1: the line a refused rename shows, chosen by **what actually happened**.
 *
 * Both rename surfaces (Home's row editor and the session route's inline title)
 * used to print [R.string.oc_rename_session_failed] for every outcome that was
 * not a success — 「Not renamed — the server did not confirm it. Check the
 * connection and try again.」 The measured 5556 failure
 * (`research/v81_verify/5556/25_renamed.png`; server log
 * `07:04:47.171 PATCH /api/session/ses_f0edfb53… http.status=500`) was the server
 * **refusing** the write because the session's project directory does not exist
 * on the server host: the connection was fine, and no retry could ever work, so
 * the one thing the copy told the user to do was the one thing that could not
 * help.
 *
 * Three lines now, one per shape a user can act on:
 *
 * - [SessionRenameOutcome.REFUSED] — the server answered with an error of its
 *   own; the folder sentence names the measured cause, and a retry is pointless
 *   until that is fixed;
 * - [SessionRenameOutcome.GONE] — the server no longer has the session, and its
 *   entry has just been dropped ([com.xiufeigo.opencodeshell.ChatViewModel.forgetSession]);
 * - everything else (no answer, no confirmation, nothing sent) keeps the
 *   connection copy, which is the one case it describes correctly.
 *
 * Pure (a resource id per outcome), so the mapping is pinned by unit test rather
 * than by reading three call sites.
 */
@StringRes
internal fun renameFailureRes(outcome: SessionRenameOutcome): Int? = when (outcome) {
    SessionRenameOutcome.RENAMED -> null
    SessionRenameOutcome.GONE -> R.string.oc_rename_session_gone
    SessionRenameOutcome.REFUSED -> R.string.oc_rename_session_refused
    SessionRenameOutcome.NOT_CONFIRMED,
    SessionRenameOutcome.UNREACHABLE,
    SessionRenameOutcome.NOT_SENT,
    -> R.string.oc_rename_session_failed
}

/**
 * The refusal, said out loud on every surface that can fail a rename: the
 * transient half of the report (the durable half is
 * [renameFailureText] + `RenameFailureDialog`). A rename that did not land must
 * never be silent, and it must say **which** of the shapes it was
 * ([renameFailureRes]).
 *
 * v83 P1: the surfaces are the session route (its inline title and its ⋮ rename
 * dialog) and **Home's row editor** — which used to be described here as the
 * surface that "keeps the draft" instead of saying anything durable, i.e. the
 * measured case of a Home rename whose only report was a 4 s toast and whose
 * draft stayed on the new name.
 *
 * Long, not short: the two copies that carry a cause (gone / refused) are full
 * sentences a short toast would cut off.
 */
internal fun renameFailureToast(context: Context, outcome: SessionRenameOutcome) {
    val res = renameFailureRes(outcome) ?: return
    Toast.makeText(context, context.getString(res), Toast.LENGTH_LONG).show()
}

/**
 * The same sentence as [renameFailureToast] without a `Toast`, or `null` for a
 * success — the **durable** half of the same report (v82 BUG-B).
 *
 * The device log of the rename behind `research/v82_verify/5558/35_ghost_rename_err.png`
 * shows the toast mechanism working exactly as designed and still failing the
 * reader: `09:04:06.363` a `VRI-Toast` surface was created for
 * `com.xiufeigo.opencodeshell`, `09:04:10.453` the platform killed it
 * (`Toast already killed`), and the screenshot that reported "no message at all"
 * was taken at `09:04:11.398` — 0.9 s after the card vanished. A sentence the
 * user must act on cannot live only in a transient system window, so the session
 * route parks this text on a card
 * ([com.xiufeigo.opencodeshell.ui.session.drawer.RenameFailureDialog]) that stays
 * until it is dismissed.
 *
 * v83 P1: the same card is Home's half of the fix — its row editor raised the
 * toast and nothing else (`research/v83_verify/5558/13_rename_t34.png`), which
 * is the v82 report all over again on the other rename surface.
 */
internal fun renameFailureText(context: Context, outcome: SessionRenameOutcome): String? =
    renameFailureRes(outcome)?.let { context.getString(it) }
