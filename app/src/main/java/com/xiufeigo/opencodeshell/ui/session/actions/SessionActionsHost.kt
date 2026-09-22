package com.xiufeigo.opencodeshell.ui.session.actions

import android.content.ClipData
import android.content.ClipboardManager
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
 * Which of the three non-menu surfaces a ⋮ row opened, if any.
 */
enum class SessionActionsDialog { FORK, SHARE, ARCHIVE }

/**
 * The behaviour half of the session's ⋮ actions (W6): the header renders the
 * five rows and calls [start], everything else — the dialog state, the
 * `OpenCodeClient.session*` writes, the clipboard and the toasts — happens here.
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
     * Runs one ⋮ row. 撤销 / 重做 / 分叉 are immediate (the last one opens the
     * picker first); 分享会话 is a **toggle** like upstream's share
     * command — while the session is private it publishes, copies and shows the
     * link, while it is public it revokes; 归档会话 confirms first.
     */
    fun start(item: SessionActionsMenuItem) {
        // Wave 2.4a: share/archive are v1-only routes (no v2 equivalent). The
        // menu hides these rows on v2 (`SessionActionsModel.menu` visibility
        // flags); this guard is the backstop for callers that bypass the menu,
        // and for the header until it adopts the flags.
        if ((item == SessionActionsMenuItem.SHARE || item == SessionActionsMenuItem.ARCHIVE) &&
            viewModel.generation.value != ServerGeneration.V1
        ) {
            failed()
            return
        }
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

            SessionActionsMenuItem.SHARE -> if (viewModel.shareUrl.value.isNullOrEmpty()) {
                share()
            } else {
                unshare()
            }
            SessionActionsMenuItem.ARCHIVE -> dialog = SessionActionsDialog.ARCHIVE
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
     * 分享会话: publishes the session, copies the returned URL and opens the
     * confirmation surface that holds the link — copying is the whole point of
     * the row (`toast.session.share.success.description`: "Share URL copied to
     * clipboard!").
     */
    fun share() {
        busy = true
        viewModel.shareSession { url ->
            busy = false
            if (url == null) {
                toast(R.string.toast_session_share_failed_title)
                return@shareSession
            }
            copy(url)
            dialog = SessionActionsDialog.SHARE
        }
    }

    /**
     * 取消分享: revokes the link and reports it (`toast.session.unshare.*`).
     * The DELETE response keeps carrying the old `share.url` (measured live), so
     * the session stops reading as public because the view model cleared its own
     * flow — nothing here may trust that payload.
     */
    fun unshare() {
        dismiss()
        busy = true
        viewModel.unshareSession { ok ->
            busy = false
            if (ok) {
                toast(R.string.toast_session_unshare_success_title)
            } else {
                toast(R.string.toast_session_unshare_failed_title)
            }
        }
    }

    /**
     * 归档会话: archives and leaves the screen. The row disappears from Home
     * because `HomeSessions.isArchived` filters it there — the server keeps
     * returning the archived row from `GET /session` (verified live).
     */
    fun archive() {
        busy = true
        viewModel.archiveSession { ok ->
            busy = false
            dismiss()
            if (ok) {
                toast(R.string.oc_toast_session_archive_success)
                onLeaveSession()
            } else {
                failed()
            }
        }
    }

    /** `session.share.copy.copyLink`: the link on the clipboard, with a toast. */
    fun copyShareLink(url: String) = copy(url)

    private fun copy(url: String) {
        val clipboard = appContext.getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            toast(R.string.toast_session_share_copyFailed_title)
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(SHARE_CLIP_LABEL, url))
        toast(R.string.toast_session_share_success_description)
    }

    /** A failed write: upstream's `common.requestFailed` (`fork-dialog.tsx:80-83`). */
    private fun failed() = toast(R.string.common_requestFailed)

    private fun toast(resId: Int) = toast(appContext.getString(resId))

    private fun toast(text: String) {
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Clip label; Android shows it to clipboard previews. */
        const val SHARE_CLIP_LABEL = "opencode share url"
    }
}

/**
 * The host of the session on screen, published by `ui/App.kt`; null wherever no
 * session view model exists (previews, the legacy chat tab).
 */
val LocalSessionActionsHost = compositionLocalOf<SessionActionsHost?> { null }

/**
 * Enablement of the five rows, straight from `SessionActionsModel.menu`.
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
    val share by host.viewModel.shareUrl.collectAsState()
    // Wave 2.4a: the v1-only rows (share/archive) hide on a v2 server.
    val generation by host.viewModel.generation.collectAsState()
    return remember(state.messages, revert, share, generation) {
        SessionActionsModel.menu(state.messages, revert, share, generation)
    }
}
