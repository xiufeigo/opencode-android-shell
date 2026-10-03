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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The project's VCS verdict behind the 「Create a Git repository」 empty state
 * (`ReviewScopes.noGit`), read from `GET /api/project` — upstream's
 * `project().vcs` (`session/review/view.tsx:252`) comes from the same table
 * through `location.get`.
 *
 * The route is the one read that is **not** location-scoped: it answers a bare
 * array of project rows (no `location` envelope, no `LocationServiceMap`
 * lookup), so it reflects the database rather than a location graph that may
 * have been built before a `git init` — the state in which `GET /api/vcs`
 * claims there is no repository (`ReviewVcsReprobe`). That makes it the only
 * read-only way to tell "this directory really has no repository" (→ CTA) from
 * "the server's cached location is stale" (→ no CTA).
 */
object ReviewProjectVcs {

    /**
     * `vcs` of the project covering [directory], or null when no row covers it
     * — or only the root project does, which every path matches and which
     * carries no VCS, so it can never answer the question.
     *
     * Several rows can share a canonical (a directory that became a repository
     * gets a fresh project id): the most specific covering canonical wins, and
     * among those rows a non-empty `vcs` wins, because "some resolve of this
     * directory found a repository" is the fact the panel asks about.
     */
    fun verdict(projects: List<V2Api.V2Project>, directory: String?): Boolean? {
        val dir = normalize(directory) ?: return null
        val covering = projects
            .mapNotNull { project -> normalize(project.canonical)?.let { canonical -> project to canonical } }
            .filter { (_, canonical) -> !isRoot(canonical) && covers(canonical, dir) }
        val longest = covering.maxOfOrNull { (_, canonical) -> canonical.length } ?: return null
        val specific = covering.filter { (_, canonical) -> canonical.length == longest }
        return specific.any { (project, _) -> project.raw.optString("vcs").isNotEmpty() }
    }

    /** `canonical` is [dir] itself or one of its ancestors. */
    private fun covers(canonical: String, directory: String): Boolean =
        canonical == directory || directory.startsWith("$canonical/")

    private fun isRoot(canonical: String): Boolean =
        canonical.isEmpty() || canonical == "/" || Regex("^[A-Za-z]:$").matches(canonical)

    /** `C:\x\` → `C:/x`; null for a blank path. */
    private fun normalize(path: String?): String? =
        path?.trim()?.takeIf { it.isNotEmpty() }?.replace('\\', '/')?.trimEnd('/')
}

/**
 * One-shot `GET /api/project` for [directory] (same self-serve pattern as
 * [rememberVcsBase] — the review model owns no project read). `null` means
 * "no verdict": no row covers the directory, only the root project does, or
 * the request failed. The panel keeps its legible empty state on `null` and
 * only claims 「Create a Git repository」 on a `false`.
 *
 * [reloadKey] forces a re-read: the panel's 「刷新」 evicts the directory's
 * cached location and bumps it, and the fresh read is what proves the evict
 * worked (the project row is not location-scoped, so it is the read that sees
 * a `git init` the cached graph missed).
 */
@Composable
fun rememberReviewProjectVcs(directory: String?, reloadKey: Int = 0): Boolean? {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    var verdict by remember(directory) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(serverStore, directory, reloadKey) {
        if (directory.isNullOrBlank()) return@LaunchedEffect
        verdict = withContext(Dispatchers.IO) {
            val server = serverStore.server ?: return@withContext null
            val api = V2Api.forServer(server.url, server.username, server.password)
                ?: return@withContext null
            runCatching { ReviewProjectVcs.verdict(api.projects(), directory) }.getOrNull()
        }
    }
    return verdict
}
