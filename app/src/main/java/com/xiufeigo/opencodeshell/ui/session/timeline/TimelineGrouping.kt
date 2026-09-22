package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore

/**
 * Pure (JVM-testable) timeline grouping — the port of upstream `groupContent()`
 * under the **`compact` timeline preset**, which is the default on the mobile
 * shell (`_upstream/packages/app/src/settings/model.tsx:248` →
 * `session-ui/src/timeline/detail.ts:41-49`: `tools`/`thinking`/`edit`/`shell`
 * are all `placement: "grouped", details: "collapsed"`).
 *
 * Rules, verified against `session-ui/src/timeline/projection.ts:593-688`:
 * - every tool merges into a group **except** the standalone ones — a failed
 *   tool (`currentToolFailed` → `undefined`) and `question`;
 * - reasoning merges into the same group (`detail.thinking.placement ===
 *   "grouped"`);
 * - a group is only ever built from *consecutive* members; any other part
 *   (text / file / compaction / the raw-JSON parts) flushes it;
 * - `todowrite` is hidden entirely (`tool-renderer.tsx:1197` → `null`);
 * - `step-start` / `step-finish` have no row of their own — they join an open
 *   group as *filler* and otherwise disappear (upstream drops them in the
 *   normalised content model); `snapshot` is dropped the same way;
 * - the group row is `busy` while the run works **or** one of its tools is
 *   running (`tool-renderer.tsx:513-515`);
 * - the row label is `Used {count} {names}` (`tool-renderer.tsx:535-558`).
 *
 * The grouping is a *display* concern: `ChatStore` keeps every part and the
 * message-level `appendFingerprint()` still sees the untouched part list, so
 * bottom-following keeps working across a group transition.
 */
object TimelineGrouping {

    /** `HIDDEN_TOOLS` — never rendered (`todowrite` → `null` upstream). */
    val HIDDEN_TOOLS = setOf("todowrite")

    /**
     * Part types that produce **no** timeline row on their own. Upstream never
     * sees them: `step-start` / `step-finish` / `snapshot` exist only on the
     * v1 wire (`schema/src/v1/session.ts:87-100,233-257`) and are folded into
     * the normalised message content.
     */
    val INVISIBLE_TYPES = setOf("step-start", "step-finish", "snapshot")

    /** Subset of [INVISIBLE_TYPES] that may still ride inside an open group. */
    val GROUP_FILLER_TYPES = setOf("step-start", "step-finish")

    /** One row of an assistant turn. */
    sealed interface Row {
        /** Stable `LazyColumn` key (a group is keyed by its first part). */
        val key: String

        data class Part(val part: ChatStore.UiPart) : Row {
            override val key: String get() = part.id
        }

        data class Context(
            val parts: List<ChatStore.UiPart>,
            val busy: Boolean,
        ) : Row {
            override val key: String get() = "ctx:" + parts.first().id
        }
    }

    /**
     * `Used {count} {tools}` label of a group (`tool-renderer.tsx:535-558`), kept
     * as raw pieces so the Compose layer can render the localized template:
     *
     * ```
     * names = distinct tool titles, joined ", "
     * thoughts = reasoning part count
     * title = names || plural("Thought", thoughts)
     * count = tool parts || thoughts
     * label = plural("ui.messagePart.tools.used", count, { tools: title })
     * ```
     * With neither tools nor thoughts upstream renders `ui.messagePart.context.updates`
     * ("Updates") instead.
     */
    data class Label(
        /** Distinct tool names, in order of first appearance (null = unknown name). */
        val toolNames: List<String?>,
        /** Number of `tool` parts in the group (hidden tools excluded). */
        val toolCount: Int,
        /** Number of `reasoning` parts in the group. */
        val thoughts: Int,
    ) {
        /** `count = toolParts || thoughts`. */
        val count: Int get() = if (toolCount > 0) toolCount else thoughts

        /** `!names && !thoughts` → the `Updates` fallback row. */
        val isUpdatesOnly: Boolean get() = toolNames.isEmpty() && thoughts == 0

        /** English tool titles ([toolLabel]) — used by the JVM tests. */
        val englishNames: List<String> get() = toolNames.map { toolLabel(it) }

        /** English title: the joined names, or `Thought` / `Thoughts`. */
        val englishTitle: String
            get() = when {
                englishNames.isNotEmpty() -> englishNames.joinToString(", ")
                thoughts == 1 -> "Thought"
                else -> "Thoughts"
            }

        /** The whole English row text, exactly as upstream composes it. */
        val englishText: String
            get() = if (isUpdatesOnly) "Updates" else "Used $count $englishTitle"
    }

    /**
     * Assistant part list → display rows.
     *
     * @param working the turn is still running; the group then shimmers even
     *   before any of its tools reports `running`.
     * @param reasoningSummaries `settings.general.row.reasoningSummaries`: when
     *   off, reasoning parts are dropped before grouping, so they neither form a
     *   group of their own nor inflate the `Thought` count of a tool group.
     */
    fun rowsFor(
        parts: List<ChatStore.UiPart>,
        working: Boolean = false,
        reasoningSummaries: Boolean = true,
    ): List<Row> {
        val rows = ArrayList<Row>(parts.size)
        var pending = ArrayList<ChatStore.UiPart>()

        fun flush() {
            if (pending.isEmpty()) return
            rows += Row.Context(parts = pending, busy = groupBusy(pending, working))
            pending = ArrayList()
        }

        for (part in parts) {
            if (part.type == "tool" && part.toolName in HIDDEN_TOOLS) continue
            if (part.type in GROUP_FILLER_TYPES) {
                // No row of its own; it only rides along inside an open group.
                if (pending.isNotEmpty()) pending.add(part)
                continue
            }
            if (part.type in INVISIBLE_TYPES) continue
            if (part.type == "reasoning") {
                // A reasoning part is only ever group content: with an empty body
                // (or with `reasoningSummaries` off) it disappears instead of
                // becoming a standalone row with nothing to show.
                if (isGroupMember(part, reasoningSummaries)) pending.add(part)
                continue
            }
            if (isGroupMember(part, reasoningSummaries)) {
                pending.add(part)
            } else {
                flush()
                rows += Row.Part(part)
            }
        }
        flush()
        return rows
    }

    /**
     * `toolGroupType()`'s "standalone" cases: a failed tool and `question` are
     * rendered as their own card even under the compact preset
     * (`projection.ts:664-667`).
     */
    fun isStandaloneTool(part: ChatStore.UiPart): Boolean =
        part.type == "tool" &&
            (part.toolStatus == "error" || part.toolName == "question")

    /** True when the part folds into a group (tool / reasoning). */
    fun isGroupMember(part: ChatStore.UiPart, reasoningSummaries: Boolean = true): Boolean = when {
        part.type == "tool" -> part.toolName !in HIDDEN_TOOLS && !isStandaloneTool(part)
        part.type == "reasoning" -> reasoningSummaries && part.text.isNotBlank()
        else -> false
    }

    /**
     * `props.busy || tools().some(status streaming | running)`
     * (`tool-renderer.tsx:513-515`).
     */
    fun groupBusy(parts: List<ChatStore.UiPart>, working: Boolean = false): Boolean =
        working || parts.any { it.type == "tool" && it.toolStatus == "running" }

    /**
     * `tool-renderer.tsx:516-543`: distinct tool names in order of appearance,
     * the tool count, and the reasoning count.
     */
    fun label(parts: List<ChatStore.UiPart>): Label {
        val tools = parts.filter { it.type == "tool" && it.toolName !in HIDDEN_TOOLS }
        return Label(
            toolNames = tools.map { it.toolName }.distinct(),
            toolCount = tools.size,
            thoughts = parts.count { it.type == "reasoning" },
        )
    }

    /**
     * `getToolInfo(name).title` for the handful of names whose upstream title
     * differs from the generic capitalized tool name (`tool-renderer.tsx:375-404`).
     */
    fun toolLabel(toolName: String?): String = when (toolName) {
        null, "" -> ToolPresentation.GENERIC_LABEL
        "patch", "apply_patch" -> "Patch"
        "skill" -> "Skill"
        "subagent" -> "Agent"
        "websearch" -> "Websearch"
        else -> ToolPresentation.title(toolName)
    }
}
