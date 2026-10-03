package com.xiufeigo.opencodeshell.ui.terminal.share

import com.xiufeigo.opencodeshell.v2.ShellCommandInfo

/**
 * Background-shell list refresh: `BackgroundShellPanel` polls `GET /api/shell`
 * while its list is visible, so shells created elsewhere (`POST /api/shell`
 * from another surface) appear within seconds instead of waiting for a
 * remount. The detail viewer already polls `shellOutput` itself, so the list
 * poll pauses while a detail is open.
 */
const val SHELL_LIST_POLL_MS = 3_000L

/**
 * Running-first, newest-first ordering of the shell list (same rule the panel
 * renders). Pure so the ordering is unit-testable without a server.
 */
fun sortShellRows(rows: List<ShellCommandInfo>): List<ShellCommandInfo> =
    rows.sortedWith(
        compareByDescending<ShellCommandInfo> { it.status == ShellCommandInfo.STATUS_RUNNING }
            .thenByDescending { it.timeStarted },
    )

/**
 * One poll's pin bookkeeping: the snapshot the fetch was started from, with the
 * `shellGet` resolutions applied (`null` = the id is unknown here, so the pin is
 * dropped), **plus** the pins a create added while the fetch was in flight.
 *
 * That last term is the whole point: the poll snapshots `pinned` before its IO
 * round trip and writes the result back afterwards, so a create that lands in
 * between (the user pressed Run) would otherwise be overwritten by the older
 * snapshot — the row disappears and, because the server lists **running**
 * commands only, an already-exited one never comes back from the list.
 */
fun applyShellResolutions(
    snapshot: Map<String, ShellCommandInfo>,
    resolutions: Map<String, ShellCommandInfo?>,
    addedSince: Map<String, ShellCommandInfo>,
): Map<String, ShellCommandInfo> {
    var next = snapshot
    for ((id, resolved) in resolutions) {
        next = if (resolved == null) next - id else next + (id to resolved)
    }
    for ((id, pin) in addedSince) next = next + (id to pin)
    return next
}
