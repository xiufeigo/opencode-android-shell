package com.xiufeigo.opencodeshell.ui.session.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.VcsBase
import com.xiufeigo.opencodeshell.v2.vcsBase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The review diff's **base line** — `GET /api/vcs/base` ([V2Api.vcsBase],
 * `vcs.base`, `protocol/src/groups/vcs.ts:39-42`): the `{name, ref, source}`
 * triple the VCS diff merges against, shown as 「相对 `<name>@<ref>`」 above the
 * change list (upstream's review header names its base the same way; the app
 * routes the request itself because the review model here has no base query).
 *
 * `data: null` is the honest no-base answer (before the first commit, a
 * provider without base metadata, or no VCS at all — measured live on a
 * default-branch checkout), and then the line stays hidden rather than
 * inventing a base.
 */
object ReviewVcsBase {

    /**
     * `name@ref` of one base. A half-missing pair degrades to whichever half
     * exists; an empty base is `null` (nothing to show).
     */
    fun label(base: VcsBase?): String? {
        base ?: return null
        val name = base.name.trim()
        val ref = base.ref.trim()
        return when {
            name.isNotEmpty() && ref.isNotEmpty() -> "$name@$ref"
            name.isNotEmpty() -> name
            ref.isNotEmpty() -> ref
            else -> null
        }
    }
}

/**
 * One-shot `GET /api/vcs/base` for [directory] (same self-serve pattern as
 * `SummaryServiceSource.rememberSummaryCatalogs` — `ChatViewModel` exposes no
 * vcs-base route). `null` means "no base known", which includes "the request
 * failed": the line is optional decoration, so both cases stay silent.
 *
 * [reloadKey] forces a re-read, like [rememberReviewProjectVcs] — the panel's
 * 「刷新」 evicts the directory's cached location and re-asks, so the base line
 * picks up a repository the stale location graph could not see.
 */
@Composable
fun rememberVcsBase(directory: String?, reloadKey: Int = 0): VcsBase? {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    var base by remember(directory) { mutableStateOf<VcsBase?>(null) }
    LaunchedEffect(serverStore, directory, reloadKey) {
        base = withContext(Dispatchers.IO) {
            val server = serverStore.server ?: return@withContext null
            val api = V2Api.forServer(server.url, server.username, server.password)
                ?: return@withContext null
            runCatching { api.vcsBase(directory) }.getOrNull()
        }
    }
    return base
}
