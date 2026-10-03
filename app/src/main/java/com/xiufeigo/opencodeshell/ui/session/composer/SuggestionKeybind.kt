package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.SHORTCUT_ROWS
import com.xiufeigo.opencodeshell.ui.settings.ShortcutRow
import com.xiufeigo.opencodeshell.ui.settings.WIRED_SHORTCUT_IDS
import com.xiufeigo.opencodeshell.ui.settings.applyKeybindOverrides
import com.xiufeigo.opencodeshell.ui.settings.isKeybindUnassigned

/**
 * B-12: the two popover columns the View shell never had — the per-row keybind
 * and the empty state — as pure Kotlin, so both are JVM-testable.
 *
 * Upstream anchors:
 *
 * * every `/` command row carries `keybind: command.keybindParts(item.id)`
 *   (`composer/model.ts:249-260`) and the row draws it as the trailing
 *   `span.shrink-0` (`editor.tsx:813-815`, joined with `"+"`); the `@` context
 *   rows carry **no** keybind (`model.ts:195-225`), which is why
 *   [commandId] only answers for a `/name` label.
 * * the empty state is one line of muted text where the rows would be
 *   (`editor.tsx:790-792` `fallback={<div class="px-2 py-1 text-…-muted">}`).
 *
 * The chords come from [SHORTCUT_ROWS] — the same table the settings panel shows
 * and `ui/App.kt` matches events against — with the user's recorded overrides
 * applied ([effectiveRows]), so the popover cannot advertise a key the shell does
 * not run. A row whose chord is unassigned (`""`/`"none"`, [isKeybindUnassigned])
 * draws **nothing**: upstream renders the span only `when={item.keybind?.length}`
 * and the settings panel spells an empty binding「未分配」— the popover has no
 * column for that word.
 */
object SuggestionKeybind {

    /**
     * The registry id a row's chord belongs to, or null when the row has none.
     *
     * A `/` row is a command (upstream's `slashCommands` → `commands` memo); an
     * `@` row is context and never carries a keybind. [ComposerSuggestions.slashLabel]
     * is what makes the two distinguishable here — every command row is
     * `/name`, every context row a bare name.
     *
     * The alias rows already resolved their id against the palette registry
     * ([ComposerSuggestions.Item.commandId], F-1); the three local built-ins are
     * upstream's own registrations (`composer/commands.tsx:51-75` — `model.choose`
     * with `slash: "model"`, `agent.cycle` with `slash: "agent"`; `session.aside`
     * from the btw plugin, `btw.tsx:52-57`).
     */
    fun commandId(item: ComposerSuggestions.Item): String? {
        if (!item.label.startsWith("/")) return null
        item.commandId?.let { return it }
        if (!item.builtIn) return null
        return when (item.label.removePrefix("/")) {
            ComposerSuggestions.BUILT_IN_MODEL -> "model.choose"
            ComposerSuggestions.BUILT_IN_AGENT -> "agent.cycle"
            ComposerSuggestions.BTW_TRIGGER -> ComposerSuggestions.BTW_COMMAND_ID
            else -> null
        }
    }

    /**
     * The chord to draw on [item]'s row, or null for "draw nothing".
     *
     * [rows] is the effective table ([effectiveRows]) so a recorded override
     * shows here exactly as it fires. An id outside [WIRED_SHORTCUT_IDS] answers
     * null as well: the table is the shell's claim that the action exists, and
     * only the wired set makes that claim executable.
     */
    fun keybind(item: ComposerSuggestions.Item, rows: List<ShortcutRow> = SHORTCUT_ROWS): String? {
        val id = commandId(item) ?: return null
        if (id !in WIRED_SHORTCUT_IDS) return null
        val chord = rows.firstOrNull { it.id == id }?.keybind ?: return null
        return chord.takeIf { !isKeybindUnassigned(it) }
    }

    /** The rows a popover shows chords from: [SHORTCUT_ROWS] + the recorded overrides. */
    fun effectiveRows(overrides: Map<String, String>): List<ShortcutRow> =
        applyKeybindOverrides(overrides)

    /**
     * The empty-state copy: the `/` menu has no matching **commands**, the `@`
     * menu no matching **results** (`prompt.popover.emptyCommands` /
     * `prompt.popover.emptyResults`, `runtime/i18n/en.ts:348-349`).
     */
    fun emptyLabelRes(slash: Boolean): Int =
        if (slash) R.string.prompt_popover_emptyCommands else R.string.prompt_popover_emptyResults
}
