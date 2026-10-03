package com.xiufeigo.opencodeshell.ui.home

import com.xiufeigo.opencodeshell.data.ProjectPaths

// Pure (JVM-testable) path algebra for the add-project directory browser —
// the Kotlin port of `_upstream/packages/app/src/workspaces/selection/domain.ts`
// (`pickerRoot` / `pickerAbsoluteInput` / `absoluteTreePath` / trailing-separator
// directory detection) plus the v1 escape check that decides when the parent
// walk must stop. No Android types: every function is deterministic string in,
// string out, so `FsBrowseTest` pins the wire-facing edge cases. The one
// non-pure reference is `ProjectPaths.posixDriveToWindows`, the MSYS/Cygwin
// drive fold the picker and the project store must agree on (see `absolute`).
object FsBrowse {

    /**
     * `pickerRoot` (domain.ts:289): `//host/share` for a UNC share, `/` for a
     * POSIX path, `C:/` for a drive-absolute path, `""` for anything relative.
     * A bare `C:` is normalised to `C:/` first so it reads as a root.
     */
    fun root(path: String): String {
        val value = normalizeDrive(path)
        if (value.startsWith("//")) {
            val parts = value.substring(2).split("/")
            val server = parts.getOrNull(0).orEmpty()
            val share = parts.getOrNull(1).orEmpty()
            return if (server.isNotEmpty() && share.isNotEmpty()) "//$server/$share" else "//"
        }
        if (value.startsWith("/")) return "/"
        if (value.length >= 3 && value[1] == ':' && value[2] == '/') return value.substring(0, 3)
        return ""
    }

    /**
     * `pickerAbsoluteInput` (domain.ts:77): `~` → [home], `~/x` → [home] + `x`,
     * a rooted path as-is, anything else joined onto [current]; the result is
     * canonicalised (`.`/`..` resolved). [home] null/blank leaves a leading
     * `~` unexpanded (the caller shows the browse hint instead).
     */
    fun resolveInput(input: String, home: String?, current: String): String {
        val value = normalizeDrive(input.trim())
        if (value == "~" || value.startsWith("~/")) {
            // Blank home: keep the leading `~` (contract above) instead of
            // joining it onto [current] — `~/proj` must never become
            // `current/proj`, and bare `~` must never become [current].
            if (home.isNullOrBlank()) return value
            val expanded = if (value == "~") normalizeDrive(home) else join(home, value.substring(2))
            return absolute(expanded, current)
        }
        return absolute(value, current)
    }

    /**
     * `pickerAbsolutePath` (domain.ts:82): a **rooted** input is taken as-is and
     * a relative one is joined onto [current]; the result is canonicalised.
     *
     * The one Windows-specific addition: on Windows a `/`-rooted path with **no
     * drive** is drive-relative — `/sensorsdata/x` means "`\sensorsdata\x` on the
     * current drive", not a POSIX `/sensorsdata/x` — so when [current] is a
     * Windows path (a drive root, or a UNC share) a POSIX-rooted input is
     * resolved against that path's own root instead of being kept verbatim.
     *
     * Keeping it verbatim is the unfaithful rewrite the v36 device run hit
     * (`research/v36_verify/tB/v36tb_bug_addproject.png`): a rooted
     * `/sensorsdata/…/v36projB` reaching this function against the
     * `C:\sensorsdata\…` location was stored as
     * `/sensorsdata/main/program/opencode-android-shell/v36projB` — the drive
     * gone and a bare leading `/` in its place. That spelling is then a
     * *different* project identity from the `C:\…` one the same directory
     * already had, so one project rendered twice and 「Close」 could only dismiss
     * one of the rows. On a POSIX location (root `/`, or no root at all)
     * nothing changes: there, `/x` really is `/x`.
     */
    fun absolute(input: String, current: String): String {
        val value = normalizeDrive(input)
        val target = when {
            root(value).isEmpty() -> join(current, value)
            isPosixRooted(value) -> windowsLocationRoot(current)?.let { windowsInput(value, it) } ?: value
            else -> value
        }
        return canonical(target)
    }

    /**
     * A rooted **POSIX** input against a Windows location, in Win32 terms: the
     * location's own root, then the input's tail. `/mnt/c/x` and `/cygdrive/c/x`
     * name a drive outright, so they become `C:/x` (the fold
     * [ProjectPaths.posixDriveToWindows] owns); anything else is drive-relative,
     * i.e. `\x` on the location's drive — `/sensorsdata/x` on `C:` is
     * `C:/sensorsdata/x`, **not** a drive-less `/sensorsdata/x`.
     */
    private fun windowsInput(value: String, locationRoot: String): String {
        val folded = ProjectPaths.posixDriveToWindows(value)
        if (folded != value) return folded
        return joinRoot(locationRoot, value)
    }

    /** [base] + [value] with exactly one separator between them (a drive root ends `/`, a UNC share does not). */
    private fun joinRoot(base: String, value: String): String =
        base + (if (base.endsWith("/")) "" else "/") + value.trimStart('/')

    /**
     * `/x`, `/x/y`, `/` — rooted by a bare leading `/` rather than by a drive or
     * a UNC head. `//…` is a UNC share (already rooted), and `C:/…`/`c:\…` is
     * rooted by its drive; neither is drive-relative.
     */
    private fun isPosixRooted(value: String): Boolean = value.startsWith("/") && !value.startsWith("//")

    /**
     * [path]'s own root when that root names a **Windows** volume — `C:/` or a
     * UNC share — else null. A POSIX root (`/`) returns null, which is what
     * keeps the Windows resolution above off a POSIX server: on Linux
     * `/sensorsdata/x` is not "relative to the current drive", it is `/`.
     */
    private fun windowsLocationRoot(path: String): String? =
        root(path).takeIf { it.startsWith("//") || (it.length == 3 && it[1] == ':' && it[0].isLetter()) }

    /**
     * The server user's home, as far as a path the server reported can name it.
     *
     * v2 has **no home field**: `GET /api/location` answers `{directory,
     * project}` (`_upstream/packages/server/src/handlers/location.ts:19-25`)
     * and `directory` is the *location* — the project the session runs in, not
     * `$HOME`. The picker's `~` button used that value directly, so with the
     * browser already sitting on the location (the usual case: the picker starts
     * on the remembered last-used directory, which *is* the project) the tap
     * re-assigned the same path and nothing moved — the silent no-op of v32
     * `tB/18_picker_home.png`.
     *
     * The home is derived from the path instead: the first `home` / `Users`
     * segment plus the user segment after it, prefixed with the path's own root.
     * `/home/u` → `/home/u`, `/home/u/proj` → `/home/u`, `/Users/u/x` →
     * `/Users/u`, `C:/Users/u/proj` → `C:/Users/u`, `/mnt/c/Users/u/proj` →
     * `/mnt/c/Users/u` (WSL's view of a Windows home). `/root` is the
     * superuser's home. `null` when the path names no home — a bare root,
     * `/srv/app`, a relative path — so the caller keeps its own fallback rather
     * than being handed a guess.
     */
    fun homeOf(path: String?): String? {
        val value = canonical(path?.trim().orEmpty())
        val root = root(value)
        // A relative path (no root) has no home to name, and `join("", …)` would
        // hand back another relative path as if it were one.
        if (value.isEmpty() || root.isEmpty()) return null
        val parts = value.substring(root.length).split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        if (root == "/" && parts.size == 1 && parts[0] == "root") return "/root"
        for (index in parts.indices) {
            val segment = parts[index].lowercase()
            if (segment != "home" && segment != "users") continue
            val user = parts.getOrNull(index + 1)?.takeIf { it.isNotEmpty() }
            // `home`/`Users` as the last segment names no user (`/srv/home`).
            if (user == null) return null
            return join(root, parts.subList(0, index + 2).joinToString("/"))
        }
        return null
    }

    /**
     * `absoluteTreePath` (domain.ts:228), except the join separator follows
     * [relative] (`\` when the server sent a Windows-style child, else `/` or
     * the base's separator) instead of always `/` — v2 `FsEntry.path` is
     * relative to the request's `location[directory]` and keeps that server's
     * separators (measured: `code/.ref/` on Linux, `app\src\` on Windows).
     * Trailing separators on both sides are dropped; the result never carries one.
     */
    fun joinRelative(base: String, relative: String): String {
        val rel = relative.trim('/', '\\')
        if (rel.isEmpty()) return trimEnd(base).ifEmpty { "/" }
        val rootPart = base.trimEnd('/', '\\')
        if (rootPart.isEmpty()) {
            return if (base.startsWith("/") && !base.startsWith("//")) "/$rel" else rel
        }
        val separator = when {
            relative.contains('\\') -> '\\'
            relative.contains('/') -> '/'
            base.contains('\\') -> '\\'
            else -> '/'
        }
        return rootPart + separator + rel
    }

    /**
     * `joinPickerPath` (domain.ts:280): [base] + [relative] with the pair's
     * trailing/leading separators collapsed to one. A blank base yields the
     * relative alone; a blank relative yields the trimmed base.
     */
    fun join(base: String, relative: String): String {
        val root = trimEnd(normalizeDrive(base))
        val path = trimEnd(normalizeDrive(relative)).trimStart('/')
        if (root.isEmpty()) return path
        if (path.isEmpty()) return root
        return if (root.endsWith("/")) root + path else "$root/$path"
    }

    /** `trimPickerPath` (domain.ts:274) after drive normalisation: roots keep their trailing `/`. */
    fun trimEnd(input: String): String {
        val value = normalizeDrive(input)
        if (value == "/" || value == "//" || isDriveRoot(value)) return value
        return value.trimEnd('/')
    }

    /** `normalizePickerPath` + `normalizePickerDrive` (domain.ts:262/268): `\`→`/`, collapse `//`, `C:`→`C:/`. */
    fun normalizeDrive(input: String): String {
        val value = input.replace('\\', '/')
        val collapsed = if (value.startsWith("//") && !value.startsWith("///")) {
            "//" + value.substring(2).replace(Regex("/+"), "/")
        } else {
            value.replace(Regex("/+"), "/")
        }
        return if (collapsed.length == 2 && collapsed[1] == ':' && collapsed[0].isLetter()) "$collapsed/" else collapsed
    }

    /**
     * `canonicalPickerPath` (domain.ts:92): `.`/`..` resolved against [root];
     * `..` at the root is a no-op (never escapes it).
     */
    fun canonical(path: String): String {
        val value = normalizeDrive(path)
        val root = root(value)
        val resolved = ArrayList<String>()
        for (part in value.substring(root.length).split("/")) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                else -> resolved.add(part)
            }
        }
        return join(root, resolved.joinToString("/"))
    }

    /**
     * `pickerRelativePath` ≠ undefined (domain.ts:108): [path] is [root] or
     * inside it. Drive and UNC comparisons are case-insensitive (Windows), POSIX
     * is case-sensitive — same rule as upstream. Blank [root] is "no scope" = false.
     */
    fun isWithin(root: String, path: String): Boolean {
        if (root.isBlank()) return false
        val rootPath = canonical(root)
        val targetPath = canonical(path)
        val insensitive = isDriveRoot(rootPath) ||
            (rootPath.length >= 3 && rootPath[1] == ':' && rootPath[2] == '/') ||
            rootPath.startsWith("//")
        val a = if (insensitive) rootPath.lowercase() else rootPath
        val b = if (insensitive) targetPath.lowercase() else targetPath
        if (a == b) return true
        val prefix = if (a.endsWith("/")) a else "$a/"
        return b.startsWith(prefix)
    }

    /**
     * v1 `/find/file` directory flag: directories carry a trailing `\` or `/`
     * (`"AMD\\"`), files do not (`"at.exe"`) — measured 2026-09-22 on 1.18.31.
     */
    fun isDirectoryPath(path: String): Boolean = path.endsWith('\\') || path.endsWith('/')

    /** Last path segment without a trailing separator; roots yield `""`. */
    fun name(path: String): String = path.trimEnd('\\', '/').split('\\', '/').lastOrNull().orEmpty()

    /**
     * Absolute [path] → the `<rel>` segment of v2 `GET /api/fs/read/<rel>`,
     * relative to the request's `location[directory]` ([location]).
     *
     * Measured rules this encodes (2.0.11, 2026-09-22): the route takes **no**
     * absolute path (`/api/fs/read//gradlew` answered 500 while
     * `/api/fs/read/gradlew` answered 200), a file outside the location is
     * `404 FileNotFoundError`, and `\` must be normalised to `/` (the client
     * percent-encodes a raw backslash into one unusable segment).
     *
     * Returns **null** when no honest relative exists: blank [location],
     * [path] not inside [location] (guessing would silently preview a
     * *different* file under the location), or [path] == [location] (a
     * directory is not a file). Drive/UNC prefixes compare case-insensitively,
     * POSIX stays case-sensitive — same rule as [isWithin].
     */
    fun fsReadRelative(location: String?, absolute: String): String? {
        val base = location?.trim().orEmpty()
        if (base.isBlank() || !isWithin(base, absolute)) return null
        val root = canonical(base)
        val path = canonical(absolute)
        if (root.isEmpty() || path.length <= root.length) return null
        val relative = if (root == "/") path.trimStart('/') else path.substring(root.length).trimStart('/')
        return relative.takeIf { it.isNotEmpty() }
    }

    private fun isDriveRoot(value: String): Boolean =
        value.length == 3 && value[1] == ':' && value[2] == '/' && value[0].isLetter()
}
