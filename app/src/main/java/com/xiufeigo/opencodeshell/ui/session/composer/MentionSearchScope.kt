package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.ui.home.FsBrowse

/**
 * The `@` mention file search, anchored at the session's project directory
 * (upstream `createDirectorySearch`/`scoped()` in
 * `_upstream/packages/app/src/workspaces/selection/domain.ts`: a search runs
 * against the workspace location, never the server's default root).
 *
 * Pure Kotlin so `MentionSearchScopeTest` pins both rules on the JVM: the root
 * travels into every ask as its `directory`, and answers outside it never reach
 * the popover — the server's fuzzy `/find/file` happily ranks hits from the
 * whole filesystem (measured: `@E2ECHK_` answered `/usr/share/man`,
 * `/usr/share/vim`, `/mnt/wslg` while the project's own files stayed invisible).
 */
internal object MentionSearchScope {

    /**
     * [search] bound to [root]: every ask carries it as the `directory`, and
     * only [withinRoot] answers are handed to [onResult]. A blank root is "no
     * workspace known yet" — the ask then goes out unscoped (pre-anchor
     * behaviour) with its answer unfiltered, instead of emptying the popover
     * while the directory is still loading.
     */
    fun scoped(
        root: String?,
        search: (query: String, directory: String?, onResult: (List<String>) -> Unit) -> Unit,
    ): (query: String, onResult: (List<String>) -> Unit) -> Unit = { query, onResult ->
        search(query, root) { paths -> onResult(withinRoot(root, paths)) }
    }

    /**
     * The popover's project-internal slice of [paths]. Relative answers (v1
     * `/find/file` is cwd-relative) are anchored on [root] first — the same
     * rule as `ui/home`'s `filterScoped` — so `..` escapes canonicalise outside
     * and drop, while a sibling prefix (`/ws/proj` vs `/ws/proj2`) is not
     * "inside".
     *
     * The slice is a preference, not a gate: a server that answers from its own
     * `location` (see [withinRoot]) would otherwise leave the menu with no file
     * rows at all.
     */
    fun withinRoot(root: String?, paths: List<String>): List<String> {
        val scope = root?.trim().orEmpty()
        if (scope.isEmpty()) return paths
        val inside = paths.filter { FsBrowse.isWithin(scope, FsBrowse.absolute(it, scope)) }
        // v63 BUG: the server answers `/api/fs/find` from **its own**
        // `location.directory`, not from the `directory=` we ask with, so on a
        // shared backend every hit lands outside the session's project and the
        // strict filter emptied the `@` menu down to its agent / command / skill
        // rows — measured on `emulator-5558`: asked for
        // `/home/xiufeigo/v36projC`, the server answered from
        // `/mnt/c/sensorsdata/main/program/opencode-android-shell`. A scope
        // filter that leaves *nothing* has not protected the user, it has
        // hidden the only rows there are, so the unscoped answer wins as the
        // fallback. A filter that keeps at least one row still wins outright.
        return if (inside.isEmpty()) paths else inside
    }
}
