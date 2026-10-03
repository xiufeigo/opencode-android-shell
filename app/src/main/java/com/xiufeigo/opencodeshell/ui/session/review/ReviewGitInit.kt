package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.v2.ShellCommandInfo
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.shellCreate
import com.xiufeigo.opencodeshell.v2.shellGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The action behind the 「Create a Git repository」 empty state
 * ([ReviewScopes.noGit]) — upstream `SessionReviewEmptyNoGitV2`'s button and
 * its `onInitGit` prop (`session-review-empty-no-git-v2.tsx:11-27`).
 *
 * There is no upstream path to copy: that component has no caller in the
 * desktop tree, the desktop's own no-git state renders copy without a button
 * (`session/review/view.tsx:263-272`), and the v2 server exposes no init route
 * (`protocol/src/groups/vcs.ts:23-95` = get / base / status / branch.list /
 * diff). The action therefore runs `git init` through the location-scoped
 * command route that does exist — `POST /api/shell` ([shellCreate]) with `cwd`
 * = the review root — and waits for the exit code on `GET /api/shell/{id}`
 * ([shellGet]). A dedicated init route on the server is the one change that
 * would make this a first-class API instead of a command.
 */
object ReviewGitInit {

    /** The command the CTA runs; the directory travels as `cwd`, never in the line. */
    const val COMMAND = "git init"

    /** Wait between `GET /api/shell/{id}` reads while the command is still running. */
    const val POLL_MS = 150L

    /** Give up waiting after this many reads — `git init` is a local, fast command. */
    const val POLL_ATTEMPTS = 40

    /** `git init` exited 0: the repository now exists. */
    fun succeeded(info: ShellCommandInfo?): Boolean = info?.exit == 0

    /**
     * Keep polling while the command is still `running` and the budget lasts.
     * A null read (a failed `GET`) stops the wait: the command was created, so
     * the repository may well exist, and the caller's re-read is the verdict.
     */
    fun keepPolling(info: ShellCommandInfo?, attempt: Int): Boolean =
        attempt < POLL_ATTEMPTS && info?.running == true

    /**
     * Blocking (OkHttp); runs on `Dispatchers.IO`. Creates the repository in
     * [directory] and answers whether `git init` exited 0.
     */
    fun run(serverStore: ServerConfigStore, directory: String): Boolean {
        // Re-read the prefs mirror before the call: this self-serve handle only
        // observes `MainActivity`'s WebView sync by re-restoring (the
        // `HomeProjectApi` precedent).
        serverStore.restore()
        val server = serverStore.server ?: return false
        val api = V2Api.forServer(server.url, server.username, server.password) ?: return false
        return try {
            val created = api.shellCreate(
                command = COMMAND,
                cwd = directory,
                locationDirectory = directory,
            )
            // No id means no way to read the exit code; the created row is all
            // the server gave us.
            if (created.id.isBlank()) return succeeded(created)
            var info: ShellCommandInfo? = created
            var attempt = 0
            while (keepPolling(info, attempt)) {
                Thread.sleep(POLL_MS)
                info = runCatching { api.shellGet(created.id, directory) }.getOrNull()
                attempt++
            }
            succeeded(info)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * [ReviewGitInit.run] as a callback: the blocking create + poll runs on
 * `Dispatchers.IO` and [onResult] is delivered back on the composition's main
 * thread. A blank directory (no review root yet) reports failure without a
 * request. Self-serve from the shared server prefs, exactly like
 * [rememberReviewProjectVcs] — the review model owns no client and the host
 * never hands it one.
 */
@Composable
fun rememberReviewGitInit(): (String?, (Boolean) -> Unit) -> Unit {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()
    return remember(serverStore, scope) {
        { directory, onResult ->
            val target = directory?.takeIf { it.isNotBlank() }
            if (target == null) {
                onResult(false)
            } else {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { ReviewGitInit.run(serverStore, target) }
                    onResult(ok)
                }
            }
        }
    }
}
