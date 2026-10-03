package com.xiufeigo.opencodeshell.v2

/**
 * v81 P1 (5556 `research/v81_verify/5556/24_rename_dlg.png` → `25_renamed.png`):
 * what a rename attempt ended with, with the failure shapes kept **apart**.
 *
 * The device run: 长按 → Rename → type a name → Enter toasted 「Not renamed — the
 * server did not confirm it. Check the connection and try again.」 **2/2** while
 * the server was healthy. What the app did not know — and what the transport
 * threw away by answering a bare `null` for every failure shape — is recoverable
 * from the server log for the second attempt
 * (`07:04:47.171 PATCH /api/session/ses_f0edfb53effegnrQBkdNGsl4Sr`):
 *
 * ```
 * cause="PlatformError: NotFound: FileSystem.realPath (/mnt/c/x) … ENOENT …"
 * http.method=PATCH … http.status=500
 * ```
 *
 * The renamed session lives in `/mnt/c/x`, a project directory the server host
 * does not have, so the write was **refused by the server** — every
 * location-scoped route on such a session answers `500` (measured:
 * `/provider`, `/vcs`, `/mcp`, `/form`, `/permission/request`,
 * `/model/default`, and the `PATCH` itself), which the toast reported as a
 * connection problem the user could never fix by checking the connection. A
 * `404` is the other shape that used to hide in the same `null`: the server no
 * longer has the session at all, so its entry has to go rather than be retried.
 *
 * Encoding the shapes is the whole fix: the caller can only say the right thing
 * — and make the right repair — if the transport tells it which one it got.
 */
enum class SessionRenameOutcome {
    /** The server holds the requested title; the row may mirror it. */
    RENAMED,

    /** The server no longer has the session (`404`): the entry is dropped. */
    GONE,

    /** The server answered, and its own code refused the write (`5xx` measured). */
    REFUSED,

    /** The write was accepted but the re-read never confirmed the exact title. */
    NOT_CONFIRMED,

    /** No HTTP answer at all (socket, timeout, missing transport). */
    UNREACHABLE,

    /** Nothing was sent: the id or the title was blank. */
    NOT_SENT,
}
