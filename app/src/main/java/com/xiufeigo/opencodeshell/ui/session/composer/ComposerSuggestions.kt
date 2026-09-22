package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.OpenCodeClient

/**
 * W5-C: the composer's two suggestion surfaces — the `/` command menu (B1) and
 * the `@` context popover's category set (B9) — as pure Kotlin.
 *
 * Upstream anchors:
 * - `/^\/(\S*)$/` opens the inline command list and only that shape does
 *   (`composer/suggestions/machine.ts:107-114`): the whole editor value must be
 *   a single `/word` with no whitespace, so typing `/model ` closes the menu.
 * - the `@` popover is opened by `/(?:^|\s)@([^\s@]*)$/`
 *   (`machine.ts:98-105`); our caret-side detector stays [MentionText.anchorAt].
 * - filtering is a normalized "contains" over `[trigger, title]`
 *   (`composer/editor/interaction.ts:132-136`), the menu is capped and rows keep
 *   the source order agents → commands → skills → files
 *   (`interaction.ts:107-131`).
 * - the two built-ins are registered by `composer/commands.tsx:49-82`
 *   (`slash: "model"` opens the model dialog, `slash: "agent"` cycles the agent);
 *   selecting one inline clears the draft instead of inserting its label
 *   (`interaction.ts:169-177`).
 * - a submitted `/name args` line is a **server command**, not a prompt:
 *   `findCommand` (`composer/submit.ts:300-306`) only matches commands the server
 *   lists, and `sendCommand` posts `{command, arguments}` to
 *   `POST /session/:id/command` (`submit.ts:309-322`). [expandTemplate] is the
 *   documented client-side fallback when that route is unavailable.
 *
 * Everything here is asserted by `ComposerSuggestionsTest` / `SlashCommandTest`.
 */
object ComposerSuggestions {

    /** `@` context entry categories (upstream `groupBy`, `interaction.ts:119-126`). */
    enum class Category { AGENT, COMMAND, SKILL, FILE }

    /**
     * One popover row. [label] is both the drawn text and (for context entries)
     * the inserted plain-text mention — W5-C keeps mentions unstructured, exactly
     * like `ChatView`'s `@path ` completion.
     */
    data class Item(
        val category: Category,
        val label: String,
        val description: String? = null,
        /** True for `/model` and `/agent`: they run locally instead of being sent. */
        val builtIn: Boolean = false,
    )

    /** `/` + name — the label a server command is inserted as. */
    fun slashLabel(name: String): String = "/$name"

    /** The two built-in commands (`commands.tsx:49-82`). */
    const val BUILT_IN_MODEL = "model"
    const val BUILT_IN_AGENT = "agent"

    /** `20` rows, the same cap the file mention uses ([MentionText.LIMIT]). */
    const val LIMIT = 20

    /**
     * The inline command query, or null when the editor is not in command
     * position. Mirrors `/^\/(\S*)$/` (`machine.ts:107`): the entire value, one
     * leading slash, and no whitespace anywhere after it.
     *
     * W5-F: the value is read through [HalfWidthText.toAscii] first, so a
     * full-width `／` (a Chinese IME's `/` key, U+FF0F — the measured device
     * failure, `research/wave5_composer.md` §8) opens the menu exactly like the
     * ASCII trigger, and a fully full-width query (`／ｍｏｄｅｌ`) still filters
     * the real commands. The fold is 1:1 and the value the editor *holds* is
     * already folded by [HalfWidthText.normalize] at the input boundary.
     */
    fun commandQuery(text: String): String? {
        val ascii = HalfWidthText.toAscii(text)
        if (!ascii.startsWith("/")) return null
        val query = ascii.substring(1)
        if (query.any { it.isWhitespace() }) return null
        return query
    }

    /**
     * The command menu for [query]: the built-ins first (they are what
     * `commands.tsx` registers for the composer), then the server's own
     * `GET /command` list. A blank query lists everything, like the popover's
     * "open with `/`" state (`machine.ts:122-134`).
     */
    fun commandItems(
        query: String,
        commands: List<OpenCodeClient.CommandInfo>,
        limit: Int = LIMIT,
    ): List<Item> = buildList {
        if (matches(query, BUILT_IN_MODEL)) {
            add(
                Item(
                    category = Category.COMMAND,
                    label = slashLabel(BUILT_IN_MODEL),
                    // Built-in rows carry no description here: the popover fills it
                    // from `oc_slash_model` / `oc_slash_agent`, because this object
                    // is pure Kotlin and owns no resources.
                    description = null,
                    builtIn = true,
                ),
            )
        }
        if (matches(query, BUILT_IN_AGENT)) {
            add(
                Item(
                    category = Category.COMMAND,
                    label = slashLabel(BUILT_IN_AGENT),
                    description = null,
                    builtIn = true,
                ),
            )
        }
        for (command in commands) {
            val name = command.name.trim()
            if (name.isEmpty()) continue
            if (!matches(query, name, command.description)) continue
            add(
                Item(
                    category = commandCategory(command),
                    label = slashLabel(name),
                    description = command.description.takeIf { it.isNotBlank() },
                ),
            )
        }
    }.distinctBy { it.label }.take(limit)

    /** `source == "skill"` rows render with the skill icon (`GET /command` mixes both). */
    private fun commandCategory(command: OpenCodeClient.CommandInfo): Category =
        if (command.source.equals("skill", ignoreCase = true)) Category.SKILL else Category.COMMAND

    /**
     * The `@` context menu for [query]: agents, then the server's commands and
     * skills, then the `/find/file` hits ([files]) — the same
     * `[...fixed, ...recent, ...files]` order upstream builds in
     * `interaction.ts:107-136` with the non-file kinds first.
     */
    fun mentionItems(
        query: String,
        agents: List<OpenCodeClient.Agent>,
        commands: List<OpenCodeClient.CommandInfo>,
        skills: List<OpenCodeClient.SkillInfo>,
        files: List<String>,
        limit: Int = LIMIT,
    ): List<Item> {
        val fixed = buildList {
            for (agent in agents) {
                val name = agent.name.trim()
                if (name.isEmpty() || agent.hidden) continue
                if (!matches(query, name, agent.description)) continue
                add(
                    Item(
                        category = Category.AGENT,
                        label = name,
                        description = agent.description.takeIf { it.isNotBlank() },
                    ),
                )
            }
            for (command in commands) {
                val name = command.name.trim()
                if (name.isEmpty()) continue
                if (!matches(query, name, command.description)) continue
                add(
                    Item(
                        category = commandCategory(command),
                        label = name,
                        description = command.description.takeIf { it.isNotBlank() },
                    ),
                )
            }
            for (skill in skills) {
                val name = skill.name.trim()
                if (name.isEmpty()) continue
                if (!matches(query, name, skill.description)) continue
                add(
                    Item(
                        category = Category.SKILL,
                        label = name,
                        description = skill.description.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }.distinctBy { it.label }

        val fileItems = files.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { path -> Item(category = Category.FILE, label = path) }
            .toList()

        return (fixed + fileItems).take(limit)
    }

    /**
     * `/name arguments` → the server command invocation (`submit.ts:300-306`).
     * Only names the server actually lists are commands; `/model`, `/agent` and
     * any other unlisted slash line stay ordinary prompts.
     *
     * W5-F: both halves are read through [HalfWidthText.toAscii], so a line typed
     * on a Chinese IME (`／ｉｎｉｔ　focus on tests`) is submitted as the command
     * the server knows — `{command: "init", arguments: "focus on tests"}` — with
     * the name/argument split done on the folded text (a full-width space U+3000
     * separates just like an ASCII one).
     */
    fun invocation(text: String, known: Set<String>): Invocation? {
        val ascii = HalfWidthText.toAscii(text).trim()
        if (!ascii.startsWith("/")) return null
        val space = ascii.indexOf(' ')
        val name = if (space < 0) ascii.substring(1) else ascii.substring(1, space)
        if (name.isEmpty() || name !in known) return null
        val arguments = if (space < 0) "" else ascii.substring(space + 1).trim()
        return Invocation(name, arguments)
    }

    /** A submitted server command (`submit.ts:300-306`). */
    data class Invocation(val name: String, val arguments: String)

    /**
     * Client-side expansion of a `GET /command` template — the fallback path when
     * `POST /session/:id/command` is not available. `$ARGUMENTS` is the
     * documented placeholder; a template without one gets the arguments appended
     * on their own line so nothing is silently dropped.
     */
    fun expandTemplate(template: String, arguments: String): String {
        if (template.contains(ARGUMENTS_PLACEHOLDER)) {
            return template.replace(ARGUMENTS_PLACEHOLDER, arguments)
        }
        if (arguments.isBlank()) return template
        return template.trimEnd() + "\n\n" + arguments
    }

    private const val ARGUMENTS_PLACEHOLDER = "\$ARGUMENTS"

    /**
     * Upstream's `useFilteredList` matching rule: normalized "contains" over the
     * candidate's label (and, for the server lists, its description), so
     * "plan" finds `plan`, `/plan` and "Plan mode".
     *
     * W5-F: both sides are folded through [HalfWidthText.toAscii] as well, so a
     * full-width query — `／ＭＯＤＥＬ`, or a `@` query typed on a Chinese IME —
     * filters the ASCII candidates instead of matching nothing.
     */
    fun matches(query: String, vararg values: String): Boolean {
        val needle = HalfWidthText.toAscii(query).trim().lowercase()
        if (needle.isEmpty()) return true
        return values.any { HalfWidthText.toAscii(it).lowercase().contains(needle) }
    }
}
