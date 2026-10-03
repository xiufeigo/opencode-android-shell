package com.xiufeigo.opencodeshell.ui.terminal.share

/**
 * Which tab's shell was started in a directory the session is no longer
 * pointed at — the v50 「移动后终端 cwd 不更新」 hint (evidence
 * `research/v50_verify/tD/v50_39_terminal_pwd.png`).
 *
 * A move re-points the session, so every **newly created** PTY is created with
 * the session's current directory as its `cwd`. A PTY that already exists keeps
 * the shell it was launched in (it may be running a build), so its chip is
 * marked instead of re-rooted: an unmarked stale `pwd` is how a user ends up
 * building the wrong repository copy.
 *
 * Pure projection (no Android APIs), so the rule is unit-tested directly.
 */
object TerminalCwd {

    /**
     * `true` when a PTY created in [createdIn] no longer matches the session's
     * current [current] directory.
     *
     * An **unknown** creation directory ([createdIn] = null — a tab revived from
     * the previous process, whose `cwd` was never recorded here) is never
     * stale: the server may well have started it in the current directory, and
     * a false warning on every revived tab would be worse than no warning.
     */
    fun isStale(createdIn: String?, current: String?): Boolean {
        val created = createdIn?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val now = current?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return created != now
    }

    /** The [ids] of [createdIn] that [isStale] holds for [current]. */
    fun staleIds(createdIn: Map<String, String?>, current: String?): Set<String> =
        createdIn.filterValues { isStale(it, current) }.keys
}
