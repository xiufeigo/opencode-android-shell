package com.xiufeigo.opencodeshell.ui.session.actions

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.v2.ServerGeneration

/**
 * Which of the surfaces a ⋮ row opened, if any.
 */
enum class SessionActionsDialog { FORK, MOVE }

/**
 * The behaviour half of the session's ⋮ actions (W6): the header renders the
 * rows and calls [start], everything else — the dialog state, the
 * `SessionTransport` writes and the toasts — happens here.
 *
 * Why a host object instead of parameters on `SessionHeader`: `SessionScreen`
 * (owned elsewhere) calls that composable with a fixed argument list, and the
 * session screen is the only place holding the view model. The shell therefore
 * builds this object in `ui/App.kt` and publishes it as
 * [LocalSessionActionsHost]; the header picks it up, which is also what keeps a
 * preview / a header rendered without a session working unchanged (a null host
 * simply means "no session-action rows").
 *
 * All the *rules* stay in [SessionActionsModel] — this class only carries them
 * out: which row is enabled comes from `SessionActionsModel.menu`, what 撤销 /
 * 重做 call comes from `undoStep` / `redoStep`, and the fork rows come from
 * `forkMessages`.
 *
 * State is `mutableStateOf` because it is read from composition; the writes run
 * on the view model's own scope, exactly like every other mutation it exposes.
 */
class SessionActionsHost(
    context: Context,
    val viewModel: ChatViewModel,
    /**
     * The session is gone from Home (归档), so the shell leaves the screen. The
     * caller (`ui/App.kt`) pops back to the previous destination; mirroring the
     * delete flow, nothing here navigates on its own.
     */
    private val onLeaveSession: () -> Unit = {},
) {
    /** App context: the host outlives no process, but the toast/clipboard glue should not pin an Activity. */
    private val appContext = context.applicationContext

    /** Open surface; null while the ⋮ menu is not asking for one. */
    var dialog by mutableStateOf<SessionActionsDialog?>(null)
        private set

    /**
     * Fork picker rows, snapshotted by [start] from
     * `ChatViewModel.forkMessages()` — the **raw** transcript, so a message the
     * revert currently hides is still a legal fork point (`fork-dialog.tsx:38-56`).
     */
    var forkRows by mutableStateOf<List<ForkMessage>>(emptyList())
        private set

    /** True while a write is in flight; the surfaces disable their buttons. */
    var busy by mutableStateOf(false)
        private set

    /**
     * Runs one ⋮ row. 撤销 / 重做 are immediate; 分叉 opens the picker first;
     * 移动到… (F-4) opens the directory picker first.
     */
    fun start(item: SessionActionsMenuItem) {
        when (item) {
            SessionActionsMenuItem.UNDO -> viewModel.undoSession { ok ->
                if (!ok) failed()
            }

            SessionActionsMenuItem.REDO -> viewModel.redoSession { ok ->
                if (!ok) failed()
            }

            SessionActionsMenuItem.FORK -> {
                forkRows = viewModel.forkMessages()
                dialog = SessionActionsDialog.FORK
            }

            SessionActionsMenuItem.MOVE -> {
                dialog = SessionActionsDialog.MOVE
            }
        }
    }

    /** Closes the open surface without acting. */
    fun dismiss() {
        dialog = null
    }

    /**
     * Fork **before** [messageID] (`fork-dialog.tsx:71-89`) and close the picker.
     * Navigation is not done here: the shell collects
     * `ChatViewModel.forkedSessionID` (`consumeForkedSession`), which is the one
     * place that owns the route helpers.
     */
    fun pickForkPoint(messageID: String) {
        dismiss()
        viewModel.forkSession(messageID) { forked ->
            if (forked == null) failed()
        }
    }

    /**
     * Move the tracked session to [directory] (F-4:
     * `POST /api/session/{id}/move`, upstream
     * `session/timeline/session-workspace-menu.tsx:59-87`) and close the picker.
     *
     * The failure toast mirrors upstream's `workspace.move.failed`
     * (`common.requestFailed` fallback included there); a success needs no
     * toast — the moved location is re-adopted from the server's next
     * `GET /session/:id` (`ChatViewModel.refreshSnapshot` re-reads
     * `info.directory` into `currentDirectory`, which the Review tree and REST
     * scoping already read live).
     *
     * [busy] guards a second submit while the first POST is still in flight:
     * every submit writes one durable `location-switched` row server-side, so
     * a double tap would duplicate the timeline row. The flag always resets —
     * `moveSessionTo` settles its promise on every path, including the
     * no-transport one — so a failure cannot wedge the row.
     */
    fun confirmMove(directory: String) {
        if (busy) return
        busy = true
        dismiss()
        viewModel.moveSessionTo(directory) { ok ->
            busy = false
            if (!ok) failed()
        }
    }

    /** A failed write: upstream's `common.requestFailed` (`fork-dialog.tsx:80-83`). */
    private fun failed() = toast(R.string.common_requestFailed)

    private fun toast(resId: Int) = toast(appContext.getString(resId))

    private fun toast(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }
}

/**
 * The host of the session on screen, published by `ui/App.kt`; null wherever no
 * session view model exists (previews, the legacy chat tab).
 */
val LocalSessionActionsHost = compositionLocalOf<SessionActionsHost?> { null }

/**
 * Enablement of the four rows, straight from `SessionActionsModel.menu`.
 *
 * `ChatViewModel.state` is handed in rather than its raw transcript: the
 * projection already dropped everything at or after the revert boundary, and
 * `menu()` re-filters by `< boundary` — a no-op on an already-cut list — so the
 * visible-user-message test sees exactly the same set.
 */
@Composable
fun rememberSessionActionsMenuState(host: SessionActionsHost): SessionActionsMenuState {
    val state by host.viewModel.state.collectAsState()
    val revert by host.viewModel.revertMessageID.collectAsState()
    return remember(state.messages, revert) {
        SessionActionsModel.menu(state.messages, revert)
    }
}
