package com.xiufeigo.opencodeshell.v2

/**
 * Why the tracked session's route is not usable, at the transport seam — the
 * second shape the old `sessionMissing` boolean could not carry.
 *
 * v82 BUG-A (emulator-5558): a session whose project directory does not exist on
 * the server host (`/mnt/c/nonexist_v82`) answered **`500`** on its own
 * session-scoped reads — measured on 2.0.18/4095 for exactly that shape:
 *
 * ```
 * GET  /api/session/{id}/permission  → 500   (48 ms)
 * GET  /api/session/{id}/form        → 500   (36 ms)
 * PATCH /api/session/{id}            → 500   (34 ms)
 * GET  /api/session/{id}             → 200   (the row itself is fine)
 * GET  /api/session/{id}/message     → 200   (an empty transcript)
 * ```
 *
 * The row read and the message read answer, so the app drew a **normal empty
 * session** — with every other session-scoped read failing silently in the log
 * (`form list failed: HTTP 500`, `permission list failed: HTTP 500`, the device
 * log of `research/v82_verify/5558/33_ghost_open.png`) — and the entry had no
 * exit at all: the 「删除该条目」 way out lives on the not-found page, which the
 * 404-only latch never reached. The tester's reading of that screen was "frozen
 * for 20 s", because nothing on it could do anything.
 *
 * So the latch gets a reason:
 *
 * - [GONE] — the server no longer has the session (`404`): upstream's not-found
 *   page, the v81 P2 shape;
 * - [REFUSED] — the server answered, and its answer was **5xx**: the session
 *   exists, but every scoped route over it fails, which is the measured
 *   missing-project-directory signature. Same page and the same three exits
 *   (Back / Retry / delete-this-entry), different copy.
 *
 * A socket failure or a timeout is deliberately **not** a reason: that is the
 * link, not the session, and latching a route failure over a slow network would
 * replace a session the user can still use with an error page. Reads already
 * retry those ([TransportRetry]) and the connection copy owns them.
 *
 * A `5xx` is taken at its word even though a transient server-side error could
 * also produce one. The trade-off is deliberate: the reads that latch are the
 * session's own structural lists (`…/permission`, `…/form`, the message
 * snapshot), a healthy session answers them (`200 []`), and the failure is not
 * silent either way — the measured bad-directory session answers `500` on
 * *both* of them while its row and its transcript read `200`. The page's
 * 「重试」 ([com.xiufeigo.opencodeshell.ChatViewModel.observeSession] +
 * `start()`) clears the latch, so a one-off hiccup costs one tap and never a
 * restart.
 */
enum class SessionOpenFailure {
    /** `404` on a session-scoped read: the server forgot this session. */
    GONE,

    /** `5xx` on a session-scoped read: the server refuses this session. */
    REFUSED,
}

/**
 * The transport-level verdict of one session-scoped read failure: `null` when
 * neither shape applies (the caller keeps its current behavior — a transient
 * failure is not a route failure).
 *
 * Pure, so the mapping is pinned by a JVM test rather than by reading the four
 * catch blocks that use it.
 */
fun sessionOpenFailure(code: Int?): SessionOpenFailure? = when {
    code == null -> null
    code == 404 -> SessionOpenFailure.GONE
    code in 500..599 -> SessionOpenFailure.REFUSED
    else -> null
}
