package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * v67 BUG-5: **which** row a popover tap means, decided from the row itself and
 * never from its position.
 *
 * The rows used to carry their `itemsIndexed` **index** into the click, and the
 * host resolved it against whatever list was current when the tap arrived. Three
 * things move that list while a finger is down — the debounced `/find/file`
 * answer, the `selected` reset every keystroke does, and the pane re-parenting
 * documented in [Composer]'s v67 overlay block — so the index named a row of one
 * list while a row of another was drawn under the finger. On device the tap on
 * `explore` inserted `build`, the new list's first row, and only a minute later,
 * when a second tap landed on an already-stable list
 * (`research/v67_verify/5558/s28-s47`, `5556/41-42`).
 *
 * So the handler receives the [ComposerSuggestions.Item] its own row was composed
 * with, and this is the one place that decides whether that item is still a live
 * pick:
 * - **the session moved** → the pick is dropped. A command or a model chosen for
 *   a session that is no longer on screen used to be applied to whichever session
 *   became active in the meantime, which is how session A's model became
 *   `MiniMax-M3.1` from a `/model` tapped in session B
 *   (`research/v67_verify/5558/s31`);
 * - **the row is gone** → dropped too, rather than applied by position;
 * - otherwise the row **as the current list describes it** is returned, so the
 * host acts on fresh data.
 *
 * The `session` argument is the composable's current session key and `openSession`
 * the one captured when the popover opened (null = the click is the opener, which
 * cannot have crossed a session switch).
 */
object PopoverPick {

    /** A row's identity: its category plus its label, as the `LazyColumn` key. */
    fun key(item: ComposerSuggestions.Item): String = "${item.category}:${item.label}"

    /**
     * The row [requested] means, or null when the pick must be dropped.
     *
     * @param rows the list the popover is drawing right now.
     * @param openSession the session the popover was opened in, null if unknown.
     * @param session the session the composer is bound to right now.
     */
    fun resolve(
        requested: ComposerSuggestions.Item?,
        rows: List<ComposerSuggestions.Item>,
        openSession: String?,
        session: String,
    ): ComposerSuggestions.Item? {
        if (requested == null) return null
        if (openSession != null && openSession != session) return null
        val key = key(requested)
        return rows.firstOrNull { key(it) == key }
    }

    /**
     * v68: which **generation** of the `@` rows the panel draws while one query
     * stays open.
     *
     * The `@` list is assembled from catalogs that stream in sequentially
     * (`ChatViewModel.refreshV2Catalogs`: agents → commands → skills →
     * references → resources) plus the debounced `/find/file` answer the
     * composer's own search effect writes. Every one of those arrivals
     * recomputes the list while the panel is already painted — and a row that
     * is inserted at the top or in the middle (references sit first in
     * [ComposerSuggestions.mentionItems]) shoves every painted row to a new
     * absolute position. A tap committed to a painted position then lands on a
     * different row (`research/v68_verify/5560/21-35`: a stable-looking `@`
     * panel inserting the row two below the tapped one, 4/4 on `emulator-5560`,
     * while the fully synchronous `/` menu stays exact).
     *
     * So the open panel holds its painted generation: while the anchor is the
     * same object ([MentionText.Anchor] carries the query, so any keystroke or
     * caret move already resets through the anchor inequality below), only a
     * **pure bottom-append** flows through. Anything else (a top/middle
     * insertion, a removal, a reorder) is held back until the query changes or
     * the panel reopens, when no finger can be committed to the old positions
     * yet. A settled list is therefore always shown as-is: the hold changes
     * nothing observable once every source has arrived.
     *
     * v69: the caller passes the menu's **fixed** block only
     * ([ComposerSuggestions.mentionRowSets]), never the joined list. The
     * `/find/file` wave is not a bottom-append of the row before it — it
     * *replaces* the whole file block whenever the query changed under it — so
     * holding the joined list kept the previous query's paths on screen until
     * the next keystroke released the hold, and `@` filtering read one
     * keystroke late (`research/v68_verify/5558/19_at_ex.png`).
     *
     * @param previous the generation the panel paints now (empty before first
     * paint or after the trigger died).
     * @param previousAnchor the anchor [previous] was computed for.
     * @param fresh the just-recomputed list for [currentAnchor].
     * @param currentAnchor the live anchor, null when the trigger is gone.
     */
    fun steadyRows(
        previous: List<ComposerSuggestions.Item>,
        previousAnchor: MentionText.Anchor?,
        fresh: List<ComposerSuggestions.Item>,
        currentAnchor: MentionText.Anchor?,
    ): List<ComposerSuggestions.Item> {
        if (currentAnchor == null) return emptyList()
        if (currentAnchor != previousAnchor) return fresh
        if (previous.isEmpty()) return fresh
        if (fresh.size >= previous.size &&
            fresh.subList(0, previous.size) == previous
        ) {
            return fresh
        }
        return previous
    }
}
