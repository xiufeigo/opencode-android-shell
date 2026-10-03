package com.xiufeigo.opencodeshell.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The path algebra two upstream modules own between them:
 * `workspaces/path-key.ts` (`pathKey`) and `workspaces/paths.ts` (`containsDirectory`,
 * whose local `normalize` is the case-folding [matchKey] adds).
 *
 * It lives behind this one object so every caller compares directories the same
 * way — the client-local project store ([HomeProjectRecords]) for "is this the
 * same directory" and the Home session filter
 * ([com.xiufeigo.opencodeshell.ui.home.HomeSessions]) for "does this project
 * contain this session". [com.xiufeigo.opencodeshell.ui.home.HomeSessions] keeps
 * its historical `pathKey` name as a delegate so its published behaviour (and
 * the tests pinning it) is unchanged.
 */
object ProjectPaths {

    /**
     * `pathKey` (`_upstream/packages/app/src/workspaces/path-key.ts:18-24`) — the
     * normalisation that decides whether two *spellings* are the same directory.
     *
     * A **Windows** value (drive letter in position 2, or a UNC `\\host`) folds
     * `\` to `/` first; only `/` is a trailing trim, exactly like upstream, and
     * the two roots survive: an all-separator POSIX path stays `/` and a bare
     * drive becomes `C:/`.
     *
     * Android addition (upstream does not do this, and the server is free to
     * hand back the verbatim Win32 form): a leading extended-length prefix
     * (`\\?\C:\src`, `//?/C:/src`, and the UNC variant `\\?\UNC\host\share`) is
     * stripped before the fold.
     *
     * `null` for a missing/blank directory: a session the server gave no
     * directory for is not attributable to any project, so it never matches a
     * selection (and `/` never collapses into it).
     */
    fun pathKey(path: String?): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = stripExtendedPrefix(raw)
        val normalized = if (isWindowsPath(value)) value.replace('\\', '/') else value
        val trimmed = normalized.trimEnd('/')
        // Only `/`, `//`, … reach this: the POSIX root stays one separator
        // (upstream `path-key.ts:21`) so it cannot read as "no directory".
        if (trimmed.isEmpty()) return "/"
        return if (isDrive(trimmed)) "$trimmed/" else trimmed
    }

    /**
     * [pathKey] with the case-fold of upstream's `containsDirectory` `normalize`
     * (`workspaces/paths.ts:89-92`): a **Windows** key is lowercased because a
     * Windows filesystem is case-insensitive, while a POSIX key keeps its case
     * (two genuinely different directories must not merge).
     *
     * Android addition (upstream has no Windows server in mind): the MSYS2 /
     * Git-Bash / WSL spelling `/mnt/<letter>/…` and the Cygwin spelling
     * `/cygdrive/<letter>/…` address the same directory as the Win32 spelling
     * `<letter>:\…` (measured live: `GET /project` reports
     * `/mnt/c/sensorsdata/…` while the picker and `session.directory` report
     * `C:\…`), so both are folded to the Win32 form before [pathKey] runs —
     * case-insensitively, like the rest of the Windows fold. [pathKey] itself
     * stays upstream-faithful (`/mnt/c` without a drive pattern keeps its
     * POSIX spelling there); only this key merges the twins.
     *
     * UNC twin: `\\wsl.localhost\…` and `//wsl$/…` (backslash- or
     * slash-separated, any case) are the same WSL share, so the `wsl$` host
     * is canonicalised to `wsl.localhost` and a `//`-led path folds its
     * separators — again matchKey-only, [pathKey] keeps the verbatim spelling.
     *
     * This is the key the project→session filter compares, the one the project
     * column uses to find what the server catalog knows about a worktree, and
     * the base of the identity the client-local store dedupes on
     * ([sameDirectory] adds the drive-less fold) — so `C:\repo`, `c:/repo/`, `/mnt/c/repo` and
     * `/cygdrive/c/repo` are one project everywhere.
     */
    fun matchKey(path: String?): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val key = identityKey(raw) ?: return null
        // A drive root (`C:/`) or a UNC share (`//host/share`) is Windows; so is
        // every descendant of one, because the drive/UNC head survives pathKey.
        // A POSIX drive spelling already became `X:/…` above, so it folds here too.
        return if (isWindowsKey(key)) key.lowercase() else key
    }

    /**
     * [pathKey] after the spellings that name a *different root* are folded onto
     * the volume they belong to: a `/mnt/c|cygdrive/c` drive view, a `wsl$` UNC
     * alias, and — the mixed form [embeddedWindowsPath] recognises — a Windows
     * path that was glued onto the tail of a POSIX one. The one thing it never
     * does is change what a path *says*: the extracted Windows tail keeps its
     * drive letter, its case and its spelling, so only the identity the callers
     * compare collapses, never the string the store persists.
     */
    private fun identityKey(value: String): String? =
        pathKey(uncToCanonical(posixDriveToWindows(embeddedWindowsPath(value) ?: value)))

    /**
     * A Windows path that was appended to a POSIX one, or null when [value] is
     * not one.
     *
     * Measured on the server catalogue (emulator-5556, v59): `GET /project`
     * listed `/home/xiufeigo/C:\sensorsdata\main\program\opencode-android-shell`
     * **beside** the correct `C:\sensorsdata\…\opencode-android-shell` for the
     * same repository. A Win32 path joined onto a POSIX root can name no
     * directory on either side (the backslashes are not separators on the WSL
     * server, and the whole string is not a Win32 path either), so the prefix
     * before the drive letter is noise and the drive letter onward is the
     * directory the user meant.
     *
     * A drive letter only qualifies at a **segment boundary** and only when a
     * separator or the end of the string follows the colon, so an ordinary POSIX
     * segment that happens to contain one (`/var/log/app:1/x`, `/srv/a:b`) is
     * left exactly as it is, and a value that is already a Windows path
     * ([isWindowsPath]: a drive in position 2 or a `\\` head) never matches at
     * all. The result keeps its own separators, so `C:\repo` and `C:/repo` both
     * survive to the folds that already meet them.
     */
    fun embeddedWindowsPath(path: String?): String? {
        val value = path?.trim().orEmpty()
        if (value.isEmpty() || isWindowsPath(value)) return null
        for (index in 1 until value.length - 1) {
            if (value[index - 1] != '/' && value[index - 1] != '\\') continue
            if (!isDriveLetter(value[index])) continue
            if (value[index + 1] != ':') continue
            val after = index + 2
            if (after < value.length && value[after] != '\\' && value[after] != '/') continue
            return value.substring(index)
        }
        return null
    }

    /**
     * The spelling a reader should be shown: the mixed form collapsed onto the
     * Windows path inside it, canonicalised by [normalizeLocation] (so
     * `C:/repo/` reads as `C:\repo`'s canonical sibling) — and everything else
     * left exactly as it was, because a path with no drive letter inside it has
     * no hidden Windows half to recover.
     *
     * **Read side only.** The client-local store persists the verbatim worktree
     * it was handed (a display normalisation that rewrote the stored spelling
     * was rolled back once already), and nothing here writes.
     */
    fun displayLocation(path: String?): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val embedded = embeddedWindowsPath(raw) ?: return raw
        return normalizeLocation(embedded) ?: embedded
    }

    /**
     * The client-local **store's** identity test: do [a] and [b] name the same
     * directory?
     *
     * [matchKey] equality is the first answer, and it covers every spelling that
     * *names its volume*: a drive (`C:\repo`), a UNC share, or an MSYS/Cygwin
     * view of a drive (`/mnt/c/repo`). The add-project picker produced one more
     * shape (`research/v36_verify/tB/v36tb_bug_addproject.png`): the location
     * `C:\sensorsdata\…\opencode-android-shell` was browsed as
     * `/sensorsdata/…/opencode-android-shell/v36projB` — the drive gone and a
     * leading `/` in its place. [matchKey] alone leaves the record holding that
     * directory twice, so the 项目 column renders **two rows for one project**
     * and 「Close」 can only dismiss one spelling at a time.
     *
     * The second answer is therefore the same path with the **volume stripped
     * from both sides**, compared **case-insensitively** — but only for the one
     * pair that needs it: a **drive**-bearing spelling and a **drive-less** one.
     * The case-fold is what a Windows filesystem does and is why the drive-less
     * spelling can meet its Win32 twin at all ([matchKey] has already lowercased
     * the Windows side); the pairing guard is what keeps the fold from inventing
     * identities — two POSIX paths stay case-sensitive (`/work/api` ≠
     * `/work/API`), a UNC share never folds into a drive-less `/host/share/…`,
     * and two different volumes never meet through their tails
     * (`//host/share/x` ≠ `C:/x`).
     *
     * A POSIX path that is not a Windows tail still keeps its own identity
     * (`/mnt/data` ≠ `d:/ata`, their tails differ).
     *
     * Scope: this is the **store's** membership/de-duplication/tombstone
     * predicate ([HomeProjectRecords]). The session→project scope keeps
     * upstream's [containsDirectory] rule verbatim, and [matchKey] itself is
     * unchanged.
     */
    fun sameDirectory(a: String?, b: String?): Boolean {
        val left = matchKey(a) ?: return false
        val right = matchKey(b) ?: return false
        if (left == right) return true
        val tailA = volumeLessTail(a) ?: return false
        val tailB = volumeLessTail(b) ?: return false
        val driveAndNone = (tailA.volume == Volume.DRIVE && tailB.volume == Volume.NONE) ||
            (tailA.volume == Volume.NONE && tailB.volume == Volume.DRIVE)
        if (!driveAndNone) return false
        return tailA.path.equals(tailB.path, ignoreCase = true)
    }

    /**
     * [path] in Win32 terms with its **volume** removed: the drive of `C:/repo`
     * (`repo`), the host+share head of `//host/share/repo` (`repo`), and — the
     * case this exists for — a drive-less POSIX path as itself (`repo` for
     * `/repo`, which is how the picker spelled the Windows directory above).
     *
     * Case is preserved (the fold belongs to [sameDirectory], which knows
     * whether a volume was involved), a bare drive or share collapses to `""`,
     * and `null` is a missing/blank path.
     */
    fun storeKey(path: String?): String? = volumeLessTail(path)?.path

    /** Which volume a spelling named — the fact the fold's pairing guard needs. */
    private enum class Volume { DRIVE, SHARE, NONE }

    private data class VolumeTail(val path: String, val volume: Volume)

    private fun volumeLessTail(path: String?): VolumeTail? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val key = identityKey(raw) ?: return null
        if (key.startsWith("//")) {
            // `//host/share/rest` → `rest`: the share head is the volume, and a
            // bare share names no directory of its own.
            val parts = key.removePrefix("//").split('/')
            return VolumeTail(if (parts.size <= 2) "" else parts.drop(2).joinToString("/"), Volume.SHARE)
        }
        if (isWindowsKey(key)) {
            // `c:/rest` → `rest`; a bare drive (`c:/`) is the volume itself.
            return VolumeTail(key.substring(2).trimStart('/'), Volume.DRIVE)
        }
        return VolumeTail(key.trimStart('/'), Volume.NONE)
    }

    /**
     * `containsDirectory` (`_upstream/packages/app/src/workspaces/paths.ts:88-96`):
     * `child` is `parent` **or below it**. `isProjectDirectory` (`:83-86`) is the
     * caller that matters — a session whose directory is nested inside a project
     * worktree (a worktree session, a package subdirectory) belongs to that
     * project, while a same-prefix sibling (`/repo/a-old` vs `/repo/a`) does not.
     *
     * A blank/absent side never matches, so an unknown session directory belongs
     * to no project.
     */
    fun containsDirectory(parent: String?, child: String?): Boolean {
        val root = matchKey(parent) ?: return false
        val target = matchKey(child) ?: return false
        return target == root || target.startsWith(if (root.endsWith("/")) root else "$root/")
    }

    /** `directories.some { containsDirectory(it, child) }` — `false` for an empty set. */
    fun containsAny(directories: Collection<String>, child: String?): Boolean =
        directories.any { containsDirectory(it, child) }

    /**
     * The canonical form of a directory the shell is about to **write** (a
     * session's `createSession` location, a project row in the client-local
     * store): extended-length prefix stripped, separators collapsed, `.`/`..`
     * resolved, and — the v58 P0 fold — an **immediately repeated** segment
     * dropped.
     *
     * The fold answers the measured growth loop (`research/v58_verify/5556_b/REPORT.md`
     * §0): opencode 2.0.18 answers a session created in `<repo>/app/src` with
     * `location.directory=<repo>/app/src` **and** `subpath=app/src` (verified
     * live: `POST /api/session {"location":{"directory":"…/app/src"}}` →
     * `{"subpath":"app/src","location":{"directory":"…/app/src"}}`), so joining
     * the two — as the subpath-aware attribution must — handed back
     * `…/app/src/app/src`, and that value went straight into the next
     * `createSession`, growing one `app/src` per session until every request on
     * that session answered `500 FileSystem.realPath → ENOENT`. A directory that
     * already carries the segment twice cannot name a real directory (no
     * filesystem has `a/b/a/b` next to `a/b` twice under one root), so folding
     * the repeat is lossless for every real path.
     *
     * The spelling survives (drive letter, `/mnt/c/…` vs `C:\…`, case, trailing
     * separator dropped like [pathKey]); only separators, `.`/`..` and the repeats
     * ([foldRepeats]) change. A **relative** value keeps its relativity (a
     * leading `..` is kept rather than resolved away) — normalizing must never
     * invent a root, since a relative directory is resolved against whatever the
     * caller holds.
     */
    fun normalizeLocation(path: String?): String? {
        val raw = path?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val value = stripExtendedPrefix(raw)
        // `C:\repo` keeps its backslashes; `/mnt/c/repo` and `C:/repo` keep `/`.
        val backslash = value.contains('\\') && !value.contains('/')
        val folded = value.replace('\\', '/')
        val unc = folded.startsWith("//")
        val root = when {
            unc -> "//"
            folded.startsWith("/") -> "/"
            isDriveLetter(folded[0]) && folded.length > 2 && folded[1] == ':' && folded[2] == '/' ->
                folded.substring(0, 3)
            else -> ""
        }
        val relative = folded.substring(root.length)
        val resolved = ArrayList<String>()
        for (part in relative.split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." ->
                    if (resolved.isNotEmpty() && resolved.last() != "..") resolved.removeAt(resolved.size - 1) else if (root.isEmpty()) resolved.add(part)
                else -> resolved.add(part)
            }
        }
        foldRepeats(resolved, ignoreCase = root.isNotEmpty() && isWindowsKey(root))
        val separator = if (unc || !backslash) '/' else '\\'
        val tail = resolved.joinToString(separator.toString())
        if (root.isEmpty()) return tail.ifEmpty { "." }
        // A `//`-led value re-joins with `/` whatever its backslash count said:
        // its separator is a UNC head, not a directory separator.
        if (unc) return "//" + tail
        val head = if (backslash && root != "/") root.replace('/', '\\') else root
        if (tail.isEmpty()) return if (root == "/") "/" else head
        return head + tail
    }

    /**
     * Drop every run of segments that repeats the run before it, so
     * `<repo>/app/src/app/src/app/src` becomes `<repo>/app/src`.
     *
     * The v58 P0 growth is an **alternating** repeat of a multi-segment block
     * (`app/src` once per created session), which is why the fold works on
     * blocks of any width and not on one repeated segment: a single-segment
     * adjacent duplicate (`a/a`) is the width-1 case of the same rule, and a
     * directory that named the same segment twice in a row would be the only
     * path this changes besides the polluted one.
     *
     * Comparison is case-insensitive under a Windows root and case-sensitive
     * under a POSIX one ([matchKey]'s rule: `C:\A\a` is one directory,
     * `/work/api` ≠ `/work/API`).
     */
    private fun foldRepeats(segments: MutableList<String>, ignoreCase: Boolean) {
        var folded = true
        while (folded && segments.size >= 2) {
            folded = false
            for (width in 1..segments.size / 2) {
                var index = width
                while (index + width <= segments.size) {
                    val same = (0 until width).all { offset ->
                        segments[index + offset].equals(segments[index - width + offset], ignoreCase)
                    }
                    if (same) {
                        for (offset in width - 1 downTo 0) segments.removeAt(index + offset)
                        folded = true
                    } else {
                        index += 1
                    }
                }
            }
        }
    }

    private fun isWindowsPath(value: String): Boolean =
        (value.length > 1 && value[1] == ':') || value.startsWith("\\\\")

    private fun isWindowsKey(value: String): Boolean =
        (value.length > 2 && value[1] == ':') || value.startsWith("//")

    private fun isDrive(value: String): Boolean =
        value.length == 2 && value[1] == ':' && isDriveLetter(value[0])

    private fun isDriveLetter(value: Char): Boolean = value in 'A'..'Z' || value in 'a'..'z'

    /**
     * `/mnt/<letter>/…` (MSYS2 / Git-Bash / WSL) and `/cygdrive/<letter>/…`
     * (Cygwin) → `<letter>:/…`: the POSIX spellings of a Win32 drive path.
     * Only a single drive letter directly under `/mnt/` or `/cygdrive/`
     * qualifies (`/mnt/cfoo/…`, `/cygdrive/cfoo/…` and the bare prefixes stay
     * POSIX); the match is case-insensitive because the Win32 side folds case
     * anyway. The `/` rest is kept verbatim — [pathKey] owns separator and
     * trailing-slash normalisation, so every spelling converges there.
     *
     * Internal rather than private because the add-project picker needs the same
     * fold for the same reason ([com.xiufeigo.opencodeshell.ui.home.FsBrowse]'s
     * drive-relative resolution): a Windows location plus a `/mnt/c/…` input is
     * the Win32 directory in a POSIX spelling, and one definition of the rule
     * keeps the picker and the store from disagreeing about which directory that
     * is.
     */
    private val POSIX_DRIVE = Regex("^/(mnt|cygdrive)/([A-Za-z])(/.*)?$", RegexOption.IGNORE_CASE)

    internal fun posixDriveToWindows(value: String): String {
        val match = POSIX_DRIVE.matchEntire(value) ?: return value
        return "${match.groupValues[2]}:${match.groupValues[3]}"
    }

    /**
     * UNC twin fold: a `\\…`/`//…` path folds `\` to `/` (so `//host\share`
     * meets `\\host\share`), and the WSL alias host `wsl$` becomes
     * `wsl.localhost` — `\\wsl$\Ubuntu\…` and `\\wsl.localhost\Ubuntu\…` are
     * the same share. Anything else (drive paths, POSIX paths, other UNC
     * hosts) passes through; [pathKey] + the [matchKey] case-fold do the rest.
     */
    private fun uncToCanonical(value: String): String {
        if (!value.startsWith("\\\\") && !value.startsWith("//")) return value
        val folded = value.replace('\\', '/')
        val head = folded.removePrefix("//")
        val slash = head.indexOf('/')
        val host = if (slash < 0) head else head.substring(0, slash)
        if (!host.equals("wsl$", ignoreCase = true)) return folded
        return "//wsl.localhost" + if (slash < 0) "" else head.substring(slash)
    }

    /** `\\?\` / `//?/` — the Win32 extended-length prefix, plus its `UNC\` form. */
    private val EXTENDED_PREFIXES = listOf("\\\\?\\" to "\\\\", "//?/" to "//")

    private fun stripExtendedPrefix(value: String): String {
        for ((prefix, uncRoot) in EXTENDED_PREFIXES) {
            if (!value.startsWith(prefix)) continue
            val rest = value.substring(prefix.length)
            if (rest.length > 4 && (rest.startsWith("UNC\\", true) || rest.startsWith("UNC/", true))) {
                return uncRoot + rest.substring(4)
            }
            return rest
        }
        return value
    }
}

/**
 * Pure half of the client-local opened-project store — the Android counterpart
 * of `createServerProjects` (`_upstream/packages/app/src/runtime/server/registry.tsx:34-99`).
 *
 * Upstream's 项目 list is **client-local persisted state**, not the server's
 * `GET /project`: `home/model.ts:20-28` reads
 * `focusedServerCtx().projects.list()` / `.recentlyClosed()`, and that store is
 * the per-server record `{ list, hidden, projects, lastProject, recentlyClosed }`
 * of `registry.tsx:41-74`. `GET /project` is only ever *enriched* with what the
 * server knows; it must not add rows. (Verified live: the server's list is every
 * worktree it has ever registered — `workspace` six times, `/`,
 * `C:\Windows\System32` — which is exactly the bug this replaces.)
 *
 * The five operations mirror `createServerProjects` one for one:
 *
 * | upstream | here |
 * | --- | --- |
 * | `open(directory)` | [open] — drops a matching `recentlyClosed` entry, then prepends; a project already open keeps its position |
 * | `close(directory)` | [close] — removes it and pushes it onto `recentlyClosed` (most recent first) |
 * | `touch(directory)` | [touch] — records the MRU marker (`lastProject`) |
 * | `move(worktree, index)` | [move] |
 * | `list()` / `last()` / `recentlyClosed()` | [State.list] / [State.lastProject] / [State.recentlyClosed] |
 *
 * Upstream's `expanded` flag and its worktree tree have no counterpart in this
 * shell (no project tree UI), so the record carries the worktree and the
 * sandbox directories only. `hidden` (hidden servers) and `list` (connections)
 * of the same persisted record are server-registry state, not project state.
 *
 * Everything here is `SharedPreferences`-free so it stays JVM-testable;
 * [HomeProjectStore] is the only part that touches Android.
 */
object HomeProjectRecords {

    /**
     * `LocalProject` (`shell/state/layout`): one opened project. [sandboxes] is
     * `project.sandboxes` — the extra directories that belong to the project, so
     * `directories(project) = [worktree, ...sandboxes]`
     * (`home/projects/controller.tsx:41-43`) is what the session filter is scoped
     * by. `OpenCodeClient.Project` does not parse the server's `sandboxes` field,
     * so today they are recorded locally when a project is opened.
     */
    data class Project(
        val worktree: String,
        val sandboxes: List<String> = emptyList(),
        /**
         * The last name saved for this worktree (edit dialog → `PATCH
         * /api/project/{id}`): the offline/immediate half of upstream
         * `displayName`'s `project.name`. The server catalogue wins whenever
         * it names the worktree ([HomeProjects.displayName]); this only fills
         * the gap before the next catalogue read and after a restart without
         * one. Blank = unset (a cleared name falls back to the folder name).
         */
        val name: String = "",
        /**
         * `ProjectMeta.icon.color` (`runtime/server/persistence.ts:119-132`):
         * the client-local icon tint, written by `sync.project.meta(worktree,
         * patch)` (`global-sync/child-store.ts:335-351`) for a worktree the
         * server has no `project.id` for. Null = the swatches are untouched and
         * the avatar keeps the neutral surface.
         *
         * Appended with a default so every existing named or positional
         * construction of this record still compiles.
         */
        val iconColor: String? = null,
        /**
         * `ProjectMeta.icon.override` — the client-local custom icon image, the
         * same `data:` URL the SAF pick produces and the PATCH body would
         * carry. Potentially large (a downsampled PNG), which is why upstream
         * keeps it client-side and why [encode] omits it while it is null.
         */
        val iconOverride: String? = null,
        /**
         * `ProjectMeta.commands.start` — the worktree's startup command as the
         * client-local record has it. Null = unset (the field shows the folder
         * name and an empty command line).
         */
        val startup: String? = null,
    )

    /**
     * The four fields upstream persists per worktree in its client-local
     * `ProjectMeta` (`persistence.ts:119-132`): `{name?, icon:{override?,color?},
     * commands:{start?}}` — the read side of [Project]. Defaults are the
     * "nothing saved locally" values, so [NONE] is what an unregistered
     * worktree the user never edited reads as.
     */
    data class LocalMeta(
        val name: String = "",
        val iconColor: String? = null,
        val iconOverride: String? = null,
        val startup: String? = null,
    ) {
        companion object {
            /** No local meta at all (a worktree this client has never saved). */
            val NONE = LocalMeta()
        }
    }

    /**
     * One `sync.project.meta(worktree, patch)` call
     * (`global-sync/child-store.ts:335-351`): **null = "not in this patch"**,
     * `""` = clear the field, any other value = the new one. Identical
     * tri-state to the `Patch` the server route sends as an omitted key, so
     * one [Project]-shaped diff drives both write channels.
     */
    data class MetaPatch(
        val name: String? = null,
        val iconColor: String? = null,
        val iconOverride: String? = null,
        val startup: String? = null,
    ) {
        val isEmpty: Boolean
            get() = name == null && iconColor == null && iconOverride == null && startup == null
    }

    /**
     * One server's slice of upstream's `ServerState` — the same field names
     * (`projects` / `lastProject` / `recentlyClosed`) so the persisted record
     * reads like the upstream one. [recentlyClosed] holds worktree strings, not
     * project records, exactly like upstream's `[directory, ...closed]`.
     */
    data class State(
        val list: List<Project> = emptyList(),
        val lastProject: String? = null,
        val recentlyClosed: List<String> = emptyList(),
    )

    // ------------------------------------------- the five store operations

    /**
     * `open(directory)` (`registry.tsx:54-67`): if the directory is in
     * [State.recentlyClosed] it is taken out of it (re-opening a closed project
     * must not leave it in both sections, or the closed tombstone would hide the
     * row again), and unless it is already open it becomes the **first** row.
     *
     * Identity is [ProjectPaths.sameDirectory], not the verbatim worktree
     * string: the same directory reaches this store under several spellings (the
     * add-project picker normalises to `C:/repo`, `session.directory` and
     * `GET /project` report `C:\repo`, worktrees may carry a trailing
     * separator, and the v36 device run produced a drive-less `/repo` — see
     * [ProjectPaths.sameDirectory]) and [decode] already collapses those on read
     * — the live operations must agree or one directory renders as several twin
     * rows and [close] can only dismiss one spelling at a time. A re-open of an
     * already listed project is a no-op that keeps its position (only a user
     * `move` reorders).
     */
    fun open(state: State, directory: String): State {
        val worktree = directory.trim()
        if (worktree.isEmpty()) return state
        val closed = state.recentlyClosed.filterNot { ProjectPaths.sameDirectory(it, worktree) }
        if (state.list.any { ProjectPaths.sameDirectory(it.worktree, worktree) }) {
            val next = if (closed == state.recentlyClosed) state else state.copy(recentlyClosed = closed)
            return next.healed()
        }
        return state.copy(list = listOf(Project(worktree)) + state.list, recentlyClosed = closed).healed()
    }

    /**
     * `close(directory)` (`registry.tsx:70-75`): a **user-initiated** close —
     * every spelling of the directory leaves the list (see [open] on identity)
     * and the given spelling lands at the head of [State.recentlyClosed],
     * de-duplicated by [ProjectPaths.sameDirectory] so closing the same
     * directory twice — in any spelling — moves one entry instead of stacking
     * two.
     */
    fun close(state: State, directory: String): State {
        val worktree = directory.trim()
        if (worktree.isEmpty()) return state
        val list = state.list.filterNot { ProjectPaths.sameDirectory(it.worktree, worktree) }
        val closed = listOf(worktree) + state.recentlyClosed.filterNot { ProjectPaths.sameDirectory(it, worktree) }
        if (list == state.list && closed == state.recentlyClosed) return state.healed()
        return state.copy(list = list, recentlyClosed = closed).healed()
    }

    /**
     * `touch(directory)` (`registry.tsx:95-97`): the MRU marker behind
     * `newSessionProject() = selectedProject() ?? projects.last()` (`home/model.ts:24-29`).
     * Upstream records it even when the directory is not open, and so does this.
     */
    fun touch(state: State, directory: String): State {
        val worktree = directory.trim()
        if (worktree.isEmpty() || state.lastProject == worktree) return state
        return state.copy(lastProject = worktree)
    }

    /**
     * Records the last-saved display name for [directory]
     * ([ProjectPaths.sameDirectory] identity, like [open]/[close]): the edit
     * dialog calls this on a successful save so the row renames immediately and
     * survives a restart even before the next catalogue read. A blank [name]
     * clears the override (the server name, or the folder name when the server
     * has none, shows again). A directory that is not open is left alone —
     * membership is [open]'s job.
     */
    fun setName(state: State, directory: String, name: String): State {
        val worktree = directory.trim()
        if (worktree.isEmpty()) return state
        val next = name.trim()
        val list = state.list.map { project ->
            if (ProjectPaths.sameDirectory(project.worktree, worktree) && project.name != next) {
                project.copy(name = next)
            } else {
                project
            }
        }
        if (list == state.list) return state
        return state.copy(list = list)
    }

    /**
     * `sync.project.meta(worktree, patch)` (`child-store.ts:335-351`) — the
     * client-local write upstream performs for a project with no server
     * `project.id` (`project-model.ts:41-46`, `project-row.tsx:60-65`). A
     * missing `project.id` is a **transport** choice upstream, not an error
     * state: the same three fields stay editable and simply land on the client
     * record instead of `PATCH /api/project/{id}`.
     *
     * The merge is **field-level and shallow**, exactly as upstream's
     * `icon`/`commands` spread is: a field the [patch] does not mention keeps
     * the value it had, so a rename never clears the icon image and a colour
     * change never clears the image (upstream `icon = {...previous.icon,
     * ...patch.icon}`). `""` clears, null leaves it alone, any other value is
     * the new one ([MetaPatch]).
     *
     * Identity is [ProjectPaths.sameDirectory] (like [open] / [close] /
     * [setName]) and a directory that is not open is left alone — membership is
     * [open]'s job, and a save must not conjure a row the user never opened.
     */
    fun setMeta(state: State, directory: String, patch: MetaPatch): State {
        val worktree = directory.trim()
        if (worktree.isEmpty() || patch.isEmpty) return state
        var changed = false
        val list = state.list.map { project ->
            if (!ProjectPaths.sameDirectory(project.worktree, worktree)) return@map project
            val next = project.copy(
                // A blank name is already this record's "unset" (the display
                // chain falls back to the folder name), so `""` clears it
                // without a separate null.
                name = patch.name?.trim() ?: project.name,
                iconColor = mergeField(patch.iconColor, project.iconColor),
                iconOverride = mergeField(patch.iconOverride, project.iconOverride),
                startup = mergeField(patch.startup, project.startup),
            )
            if (next == project) project else { changed = true; next }
        }
        if (!changed) return state
        return state.copy(list = list)
    }

    /**
     * The client-local meta `childStore.projectMeta` holds for [directory]
     * (`runtime.tsx:151`'s read side): [LocalMeta.NONE] for a blank directory,
     * a directory this client has no record of, or one that is not open.
     */
    fun localMeta(state: State, directory: String): LocalMeta {
        val worktree = directory.trim()
        if (worktree.isEmpty()) return LocalMeta.NONE
        val project = state.list.firstOrNull { ProjectPaths.sameDirectory(it.worktree, worktree) }
            ?: return LocalMeta.NONE
        return LocalMeta(
            name = project.name,
            iconColor = project.iconColor,
            iconOverride = project.iconOverride,
            startup = project.startup,
        )
    }

    /**
     * `open(directory)` for a record that already carries fields: the row joins
     * the list (and leaves the closed stack) exactly like [open], then the
     * record's own non-default fields are re-applied on top of whatever the
     * row already held.
     *
     * This is what [mergeRestored] folds in-memory rows through. It used to
     * fold them through [open] alone, which rebuilt each row as a bare
     * `Project(worktree)` — correct while a record carried nothing but its
     * worktree, and silently lossy the moment the client's rows can carry a
     * saved name / icon / startup command that the on-disk record does not have
     * (a write that beat the restore read).
     *
     * Asymmetric on purpose: a field the in-memory record leaves at its default
     * is inherited from disk, and one it sets wins. A "cleared" field (blank
     * name / null icon) is therefore inherited rather than blanked, which is the
     * conservative direction for a one-shot startup read and unreachable in
     * practice — a save needs the dialog, and the dialog needs frames.
     */
    fun adopt(state: State, record: Project): State {
        val opened = open(state, record.worktree)
        if (record.name.isBlank() && record.iconColor == null &&
            record.iconOverride == null && record.startup == null
        ) {
            return opened
        }
        val list = opened.list.map { project ->
            if (!ProjectPaths.sameDirectory(project.worktree, record.worktree)) return@map project
            project.copy(
                name = record.name.ifBlank { project.name },
                iconColor = record.iconColor ?: project.iconColor,
                iconOverride = record.iconOverride ?: project.iconOverride,
                startup = record.startup ?: project.startup,
            )
        }
        return if (list == opened.list) opened else opened.copy(list = list)
    }

    /** The `ProjectMeta` merge rule: null = untouched, `""` = cleared, else the new value. */
    private fun mergeField(patch: String?, previous: String?): String? = when {
        patch == null -> previous
        patch.isEmpty() -> null
        else -> patch
    }

    /**
     * `move(directory, toIndex)` (`registry.tsx:84-91`): moves the project at
     * `worktree` to `index`, shifting the rest. An unknown worktree, a no-op move
     * and an out-of-range index (clamped, like `Array.splice`) all leave the
     * state alone. The desktop app drives this from drag-and-drop; the native
     * column has no pointer drag yet, so the operation is exposed for the store
     * contract (and its tests) rather than for a live gesture.
     */
    fun move(state: State, worktree: String, index: Int): State {
        val target = worktree.trim()
        val from = state.list.indexOfFirst { ProjectPaths.sameDirectory(it.worktree, target) }
        if (from < 0) return state
        val to = index.coerceIn(0, state.list.size - 1)
        if (from == to) return state
        val next = ArrayList(state.list)
        next.add(to, next.removeAt(from))
        return state.copy(list = next)
    }

    /**
     * The record's invariant, applied on every write: **one row per directory**.
     *
     * [open] and [close] already refuse to create a second row for a directory
     * they can see, so this is the repair half — a record an older build
     * persisted with one directory under two spellings (the drive-less
     * `/sensorsdata/…` beside `C:\sensorsdata\…`, or two case/separator
     * variants) collapses to the first row, keeping its sandboxes and name.
     * [decode] applies the same fold on read, so a dirty preference heals on the
     * first frame after the update instead of waiting for the next write.
     */
    private fun State.healed(): State {
        val deduped = list.dedupe()
        return if (deduped == list) this else copy(list = deduped)
    }

    /** `directories(project) = [project.worktree, ...(project.sandboxes ?? [])]`. */
    fun directories(project: Project): List<String> =
        listOf(project.worktree) + project.sandboxes.filter { it.isNotBlank() }

    // ---------------------------------------------------- persistence (JSON)

    /**
     * The persisted form: `{"projects":[{worktree,sandboxes,name?,iconColor?,iconOverride?,startup?}],
     * "recentlyClosed":[…],"lastProject":"…"}`. The three meta keys are the
     * flattened form of upstream's `ProjectMeta`
     * (`{name?, icon:{override?,color?}, commands:{start?}}` —
     * `persistence.ts:119-132`); they are written **only when set**, so a
     * record that never saved any of them is byte-identical to what an older
     * build wrote and a long `iconOverride` is never carried for nothing.
     * `org.json` is on the unit-test classpath (`app/build.gradle.kts:108`), so
     * this pair is covered by `HomeProjectStoreTest`.
     */
    fun encode(state: State): String {
        val root = JSONObject()
        root.put(
            "projects",
            JSONArray().apply { state.list.forEach { put(projectJson(it)) } },
        )
        root.put("recentlyClosed", JSONArray().apply { state.recentlyClosed.forEach { put(it) } })
        state.lastProject?.let { root.put("lastProject", it) }
        return root.toString()
    }

    /**
     * Reads a record back. Lenient on purpose: the value is a preference on a
     * user's device, so a truncated/garbage blob (or a record a *future* version
     * wrote with fields this one does not know) degrades to "no opened projects"
     * instead of crashing Home, blank worktrees are dropped and duplicates
     * collapse on [ProjectPaths.sameDirectory] — the same normalisation [open]
     * writes with, so a record an older build persisted as two spellings of one
     * directory (including the drive-less spelling the v36 add-project picker
     * produced) heals to a single row on the next read (keeping the most recent
     * entry and the union of their sandboxes).
     */
    fun decode(raw: String?): State {
        if (raw.isNullOrBlank()) return State()
        return runCatching { parse(JSONObject(raw)) }.getOrNull() ?: State()
    }

    private fun parse(obj: JSONObject): State = State(
        list = obj.optJSONArray("projects").projects().dedupe(),
        lastProject = obj.optString("lastProject", "").trim().takeIf { it.isNotEmpty() },
        recentlyClosed = obj.optJSONArray("recentlyClosed").strings().dedupeKeys(),
    )

    private fun projectJson(project: Project): JSONObject = JSONObject().apply {
        put("worktree", project.worktree)
        if (project.sandboxes.isNotEmpty()) {
            put("sandboxes", JSONArray().apply { project.sandboxes.forEach { put(it) } })
        }
        if (project.name.isNotBlank()) {
            put("name", project.name)
        }
        project.iconColor?.takeIf { it.isNotBlank() }?.let { put("iconColor", it) }
        project.iconOverride?.takeIf { it.isNotEmpty() }?.let { put("iconOverride", it) }
        project.startup?.takeIf { it.isNotEmpty() }?.let { put("startup", it) }
    }

    private fun JSONArray?.projects(): List<Project> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            // A bare string is tolerated: the worktree alone is a valid record.
            when (val entry = opt(index)) {
                is String -> entry.trim().takeIf { it.isNotEmpty() }?.let { Project(it) }
                is JSONObject -> entry.optString("worktree", "").trim().takeIf { it.isNotEmpty() }?.let { worktree ->
                    // JSON-null must not surface as the literal "null"
                    // (org.json's optString quirk) in the Home display chain —
                    // the same guard the `name` below has.
                    Project(
                        worktree = worktree,
                        sandboxes = entry.optJSONArray("sandboxes").strings(),
                        name = entry.metaString("name").orEmpty(),
                        iconColor = entry.metaString("iconColor"),
                        iconOverride = entry.metaString("iconOverride"),
                        startup = entry.metaString("startup"),
                    )
                }

                else -> null
            }
        }
    }

    /**
     * One optional persisted string: absent, blank or JSON null all read as
     * null (nothing saved), never as the literal `"null"` org.json hands back
     * for a null.
     */
    private fun JSONObject.metaString(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            (opt(index) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * One row per directory, by [ProjectPaths.sameDirectory]: the first
     * occurrence wins (the list is most-recent-first) and the later twins
     * contribute their sandboxes and every field the first one lacks.
     *
     * "Lacks" is the same default-means-unset rule everywhere else in this
     * file: a blank name, a null icon tint / image / startup command. So a
     * record persisted before the client-local meta existed (`{worktree}` only)
     * cannot erase the values a newer record beside it carries.
     *
     * A scan rather than a keyed map on purpose — the identity is a *predicate*
     * (the drive-less fold is case-insensitive only against a volume-bearing
     * spelling, see [ProjectPaths.sameDirectory]), and the lists here are a
     * handful of rows.
     */
    private fun List<Project>.dedupe(): List<Project> {
        val kept = ArrayList<Project>()
        for (project in this) {
            val index = kept.indexOfFirst { ProjectPaths.sameDirectory(it.worktree, project.worktree) }
            if (index < 0) {
                kept.add(project)
            } else {
                val prior = kept[index]
                kept[index] = prior.copy(
                    sandboxes = (prior.sandboxes + project.sandboxes).dedupePaths(),
                    name = prior.name.ifEmpty { project.name },
                    iconColor = prior.iconColor ?: project.iconColor,
                    iconOverride = prior.iconOverride ?: project.iconOverride,
                    startup = prior.startup ?: project.startup,
                )
            }
        }
        return kept
    }

    private fun List<String>.dedupeKeys(): List<String> {
        val kept = ArrayList<String>()
        for (value in this) {
            if (kept.none { ProjectPaths.sameDirectory(it, value) }) kept.add(value)
        }
        return kept
    }

    private fun List<String>.dedupePaths(): List<String> {
        val kept = ArrayList<String>()
        for (value in map { it.trim() }) {
            if (value.isEmpty()) continue
            if (kept.none { ProjectPaths.sameDirectory(it, value) }) kept.add(value)
        }
        return kept
    }
}

/**
 * The record a deferred [HomeProjectStore.restore] publishes: `loaded` as it
 * came off disk, with anything this client already wrote in front of it.
 *
 * Normally [current] is empty — nothing can be written before the first frame —
 * and the disk record is adopted whole. When a write did beat the read (the
 * user closed a project on the frame Home first appeared), the on-disk record
 * is still the truth for every directory this client has *not* touched, so the
 * in-memory rows are [HomeProjectRecords.open]ed on top of it rather than
 * replacing it: that keeps the rows and names the read brought, and `open`'s
 * identity test ([ProjectPaths.sameDirectory]) means a row both sides know
 * about is not duplicated and a closed tombstone does not hide it.
 */
internal fun mergeRestored(
    current: HomeProjectRecords.State,
    loaded: HomeProjectRecords.State,
): HomeProjectRecords.State {
    if (current.list.isEmpty() && current.lastProject == null && current.recentlyClosed.isEmpty()) {
        return loaded
    }
    // A tombstone this client recorded is newer than the same directory on disk,
    // so it is re-applied through `close` — the same operation that wrote it,
    // which also keeps the stack most-recent-first and deduped by directory.
    val closed = current.recentlyClosed.fold(loaded) { acc, directory ->
        HomeProjectRecords.close(acc, directory)
    }
    // `adopt`, not `open`: an in-memory row can carry a saved name / icon tint
    // / icon image / startup command the on-disk record does not have (a write
    // that beat this read), and `open` would rebuild it as a bare
    // `Project(worktree)`. `adopt` re-opens it the same way and then puts the
    // record's own fields back on top.
    val list = current.list.fold(closed) { acc, project ->
        HomeProjectRecords.adopt(acc, project)
    }
    return list.copy(lastProject = current.lastProject ?: loaded.lastProject)
}

/**
 * The client-local opened-project store, persisted per server.
 *
 * Upstream keys the same record by `ServerConnection.key(conn)` (`registry.tsx:34-41`);
 * the single-server native shell passes the same stable key `OcApp` already uses
 * for its session routes ([com.xiufeigo.opencodeshell.ui.nav.NavRestore.serverKeyOf]),
 * so switching servers switches records and nothing leaks across them.
 *
 * Persistence is plain `SharedPreferences` + a JSON blob, following
 * [com.xiufeigo.opencodeshell.ui.nav.LastRouteStore] (and `AppearancePrefs`):
 * a list of directory paths is not a secret, so the encrypted store's key
 * derivation would only add cold-start latency. The exposed surface is exactly
 * what a `DataStore` re-implementation would offer ([state] + the five
 * mutations), so swapping the backend stays a one-file change.
 *
 * **The record is not read in the constructor.** The store is created from a
 * `remember` in the root composition, so a read here is a synchronous disk
 * access plus a JSON parse on the main thread *before the first frame* — on the
 * v64 cold start (`research/v64_verify/5558/00_launch.png`, the
 * 「OpenCode isn't responding」 at 3:16:57) the window had not been drawn at all
 * 25 s after the process started. [restore] is the read; the shell runs it off
 * the main thread and the record fills in a frame later. Until it lands the
 * state is [HomeProjectRecords.State]'s empty default, which is the same answer
 * a first run gives.
 */
class HomeProjectStore(
    context: Context,
    serverScope: String = DEFAULT_SCOPE,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = serverScope.trim().ifEmpty { DEFAULT_SCOPE }

    private val _state = MutableStateFlow(HomeProjectRecords.State())
    private val _restored = MutableStateFlow(false)

    /** The live record; recomposition-friendly (one immutable snapshot per change). */
    val state: StateFlow<HomeProjectRecords.State> = _state.asStateFlow()

    /** True once [restore] has published the record read off the main thread. */
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    /**
     * Read the persisted record. Idempotent, and safe to call from any thread:
     * a write that lands first is never overwritten by the read, because
     * [merged] keeps what this client already knows when the two overlap.
     */
    fun restore() {
        if (_restored.value) return
        _restored.value = true
        _state.value = mergeRestored(_state.value, HomeProjectRecords.decode(prefs.getString(key(scope), null)))
    }

    /** `projects.open()` — re-open a closed project or prepend a new one. */
    fun open(directory: String) = update { HomeProjectRecords.open(it, directory) }

    /** `projects.touch()` — mark the most recently used directory ([last]). */
    fun touch(directory: String) = update { HomeProjectRecords.touch(it, directory) }

    /** `projects.move()` — reorder the list. */
    fun move(worktree: String, index: Int) = update { HomeProjectRecords.move(it, worktree, index) }

    /** `projects.close()` — move a project into [recentlyClosed]. */
    fun close(directory: String) = update { HomeProjectRecords.close(it, directory) }

    /** Record the last-saved display name ([HomeProjectRecords.setName]). */
    fun setName(directory: String, name: String) = update { HomeProjectRecords.setName(it, directory, name) }

    /**
     * `sync.project.meta(worktree, patch)` — the client-local write behind a
     * project the server has no `project.id` for
     * ([HomeProjectRecords.setMeta]). Field-level shallow merge, same identity,
     * same `StateFlow` the Home column observes, so a rename / colour / icon /
     * startup change lands on the row in the frame after the save.
     */
    fun setMeta(directory: String, patch: HomeProjectRecords.MetaPatch) =
        update { HomeProjectRecords.setMeta(it, directory, patch) }

    /**
     * The client-local meta for [directory]
     * ([HomeProjectRecords.localMeta]) — the read side `childStore.projectMeta`
     * feeds the editor's load and the column's avatar.
     *
     * Reads the live [state], never the disk, so it is the same snapshot the
     * column is drawing: a save that has not been flushed yet still answers
     * here.
     */
    fun localMeta(directory: String): HomeProjectRecords.LocalMeta =
        HomeProjectRecords.localMeta(_state.value, directory)

    /** `projects.list()` — the opened projects, most recently opened first. */
    fun list(): List<HomeProjectRecords.Project> = _state.value.list

    /** `projects.last()` — the last directory a session was opened/newed in. */
    fun last(): String? = _state.value.lastProject

    /** `projects.recentlyClosed()` — most recently closed first. */
    fun recentlyClosed(): List<String> = _state.value.recentlyClosed

    @Synchronized
    private fun update(block: (HomeProjectRecords.State) -> HomeProjectRecords.State) {
        val next = block(_state.value)
        if (next == _state.value) return
        _state.value = next
        prefs.edit().putString(key(scope), HomeProjectRecords.encode(next)).apply()
    }

    companion object {
        /** Preference file. Plain prefs, like `oc_nav_state` / `oc_appearance`. */
        const val PREFS = "oc_home_projects"

        /** Key of the record used before a server URL is known (single-server shell). */
        const val DEFAULT_SCOPE = "default"

        /** One record per server: upstream `store.projects[ServerConnection.key(conn)]`. */
        fun key(scope: String): String = "projects:$scope"
    }
}
