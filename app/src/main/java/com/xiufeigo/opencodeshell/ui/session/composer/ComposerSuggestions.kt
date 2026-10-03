package com.xiufeigo.opencodeshell.ui.session.composer

import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.ui.palette.fuzzySubsequence

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
 * - filtering is normalized fuzzy-subsequence over `[trigger, title]`
 *   for the `/` menu ([slashMatches] — upstream's `useFilteredList`
 *   (`packages/ui/src/hooks/use-filtered-list.tsx`) runs every command and
 *   context list through `fuzzysort`, so `trm` finds `/terminal`; the prefix
 *   rule this used to be narrowed `/e` to one row on device, which upstream
 *   never does) and normalized contains for the `@` menu ([matches],
 *   `composer/editor/interaction.ts:132-136`), the menu is capped and rows keep
 *   the source order references → agents → commands → skills → resources → files
 *   (`interaction.ts:107-131`; the two F-1 sources sit where upstream puts them
 *   in `composer/model.ts:195-…`).
 * - the two built-ins are registered by `composer/commands.tsx:49-82`
 *   (`slash: "model"` opens the model dialog, `slash: "agent"` cycles the agent);
 *   selecting one inline clears the draft instead of inserting its label
 *   (`interaction.ts:169-177`).
 * - a submitted `/name args` line is a **server command**, not a prompt:
 *   `findCommand` (`composer/submit.ts:300-306`) only matches commands the server
 *   lists, and `sendCommand` posts `{command, arguments}` to
 *   `POST /session/:id/command` (`submit.ts:309-322`). [expandTemplate] is the
 *   documented client-side fallback when that route is unavailable.
 * - F-1: upstream's `/` menu also lists every registered command carrying a
 *   `slash` field (`composer/model.ts:230-247` `slashCommands`), and picking one
 *   runs the palette row (`command.trigger(id, "slash")`,
 *   `model.ts:339-344`) instead of sending anything. [SessionSlashRow] carries
 *   those session aliases ([commandItems] merges them after the server catalog
 *   so a same-named server custom keeps its row, exactly like the submit-time
 *   precedence in [aliasCommandId]); [Item.commandId] routes the pick back to
 *   the palette row's `onSelect`.
 *
 * Everything here is asserted by `ComposerSuggestionsTest` / `SlashCommandTest`.
 */
object ComposerSuggestions {

    /**
     * `@` context entry categories (upstream `groupBy`, `interaction.ts:119-126`
     * — plus the two F-1 sources `references` and `resources`).
     */
    enum class Category { REFERENCE, AGENT, COMMAND, SKILL, RESOURCE, FILE }

    /**
     * One popover row. [label] is both the drawn text and (for context entries)
     * the inserted plain-text mention — W5-C keeps mentions unstructured, exactly
     * like `ChatView`'s `@path ` completion.
     */
    data class Item(
        val category: Category,
        val label: String,
        val description: String? = null,
        /**
         * True for `/model`, `/agent` and `/btw`: they run locally instead of
         * being sent (`commands.tsx:49-82`; [BTW_TRIGGER] opens the btw panel).
         */
        val builtIn: Boolean = false,
        /**
         * F-1: the palette command id for a session-slash alias row
         * (`/undo` → `session.undo`). Null for built-ins and server commands.
         * The composer invokes the palette row's `onSelect` for these instead
         * of inserting or sending the label.
         */
        val commandId: String? = null,
        /**
         * F-1: the mention target of a [Category.REFERENCE] / [Category.RESOURCE]
         * row — the reference's `path` (`model.ts:156`) or the MCP resource's
         * `uri` (`model.ts:172`). The two categories are the only ones whose
         * label (a bare name) is not itself the target.
         */
        val path: String? = null,
        /**
         * F-1: the MCP server a [Category.RESOURCE] row came from — upstream's
         * `source.clientName` (`model.ts:184`), shown in the row description
         * when the resource carries none.
         */
        val server: String? = null,
        /**
         * F-1/v15: a [Category.FILE] row that names a **directory** rather than
         * a file. `/find/file` answers with bare paths for both kinds — v1 marks a
         * directory only with a trailing separator, v2 does not mark it at all
         * (`v2/SessionTransport.searchFiles`) — so the row's own path shape is the
         * only classification the composer gets ([MentionText.isDirectoryPath]).
         * It drives the folder icon and the "Directory" content description in
         * [MentionPopover]; [Category.REFERENCE] draws `ic_oc_folder` regardless
         * (upstream's `ComposerSuggestionIcon`, `editor.tsx:904-913`).
         */
        val directory: Boolean = false,
    )

    /**
     * F-1: one session-slash alias as the composer sees it — already resolved
     * against the palette registry (the Compose layer maps
     * `PaletteCommand` + `SessionCommands.SESSION_SLASH_COMMAND_IDS` to these,
     * so this object keeps owning no resources).
     */
    data class SessionSlashRow(
        val trigger: String,
        val commandId: String,
        val title: String,
        val description: String? = null,
    )

    /** `/` + name — the label a server command is inserted as. */
    fun slashLabel(name: String): String = "/$name"

    /** The two built-in commands (`commands.tsx:49-82`). */
    const val BUILT_IN_MODEL = "model"
    const val BUILT_IN_AGENT = "agent"

    /**
     * The `/btw` side-question trigger — upstream registers it as a command with
     * a slash field (`packages/tui/src/feature-plugins/prompt/btw.tsx:52,57`
     * `id: "session.aside"`, `slash: { name: "btw", arguments: true }`), which is
     * what puts it in the `/` menu next to the two built-ins above.
     *
     * It runs locally like they do, but instead of a picker it hands the
     * question to the btw panel (`ui/session/btw`): upstream's `run(input)`
     * answers from the session's own context through `session.generate` and
     * never adds anything to the conversation — so this is **not** a prompt
     * submission and must not queue a turn.
     */
    const val BTW_TRIGGER = "btw"

    /** Upstream's registry id of that command (`btw.tsx:52`). */
    const val BTW_COMMAND_ID = "session.aside"

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
     * v64: the shape the editor's own text is in — the one open `/` query, or the
     * one open `@` anchor (`machine.ts:86-119`), derived from the **text and the
     * caret** rather than from the state that mirrors them.
     *
     * The mirrors are plain `remember` state while the editor's value is
     * `rememberSaveable`, so a composer that is rebuilt while its draft survives
     * comes back with the text and neither trigger: the `/` card then opened on
     * [isCommandTrigger] alone and drew the empty state, and the `@` card did not
     * open at all (`research/v64_verify/5556/02_24_slash_only.png` — 「No
     * matching results」 under a bare `/`; `5558/07b_mention_panel.png`,
     * `08b_context_panel.png` — nothing under `@` or after + → Context). Reading
     * the text makes the two surfaces a function of what the editor holds, so
     * every render agrees with the caret, whichever state survived.
     */
    data class Trigger(val slashQuery: String? = null, val mention: MentionText.Anchor? = null) {

        /** The editor is in `/` command position, whatever the query. */
        val inSlashPosition: Boolean get() = slashQuery != null

        /** The editor is in `@` context position, whatever the query. */
        val inMentionPosition: Boolean get() = mention != null
    }

    /**
     * [triggerAt] with both halves null — the idle composer, where the popover
     * can only be open if rows somehow exist.
     */
    val IDLE_TRIGGER = Trigger()

    /**
     * The editor's trigger shape, read off [text] and [caret] ([Trigger]).
     *
     * The text is folded first ([HalfWidthText.toAscii], the same 1:1 read view
     * the editor's own `onValueChange` uses), so a Chinese IME's `／` / `＠`
     * opens the same surfaces an ASCII `/` / `@` does. The fold is 1:1, so
     * [caret] still addresses the same character.
     *
     * v67 B4: the empty editor is [IDLE_TRIGGER] — no caret position and no
     * invisible-character shape of an emptied line invents a trigger, so
     * deleting the last `@` of `@build` collapses the panel the same keystroke
     * that empties the field (`research/v67_verify/5558/s48`).
     */
    fun triggerAt(text: String, caret: Int): Trigger {
        val ascii = HalfWidthText.toAscii(text)
        return Trigger(
            slashQuery = commandQuery(ascii),
            // v66 BUG-1: the caret first, then the text's own tail. A composer
            // whose value came back from a session switch can hold a non-empty
            // text with the caret still at 0 (measured on 5556 / 5558, see
            // `MentionText.anchorAtEndOfLine`), and a caret-only read made the
            // whole `@` surface dead there — no panel, no empty state, and every
            // keystroke landing in front of the line. Upstream's shape is
            // end-anchored (`machine.ts:98-105`), so the tail is a faithful
            // second reading, not a widening.
            mention = MentionText.anchorAt(ascii, caret)
                ?: MentionText.anchorAtEndOfLine(ascii),
        )
    }

    /**
     * The rows the popover draws for [trigger]: the `/` command menu while the
     * editor is in command position, the `@` context menu otherwise
     * (`machine.ts:86-119` — the editor is in one shape at a time).
     */
    fun rowsFor(
        trigger: Trigger,
        commandRows: List<Item>,
        mentionRows: List<Item>,
    ): List<Item> = if (trigger.inSlashPosition) commandRows else mentionRows

    /**
     * Is the editor sitting in `/` command position ([commandQuery] answers)?
     *
     * The composer keeps the open `/` menu as **state** ([slashQuery], set in
     * `onValueChange`), but the panel's placeholder promises 「/ for commands」,
     * so the first `/` a person types has to open the command menu — and on
     * device it did not: nothing appeared above the editor, so the only way
     * into any command surface was ⋮ → Command palette
     * (`research/v63_verify/5556/25_palette3.png`, `26_palette4.png`).
     *
     * The trigger is therefore re-read from the editor's **own text** rather
     * than from the state that mirrors it, so the menu opens on every shape of
     * `/` the value can reach — an IME commit, a full-width `／`, a restored
     * draft — and not only on the path that happened to set the state.
     */
    fun isCommandTrigger(text: String): Boolean = commandQuery(text) != null

    /**
     * The command menu for [query]: the built-ins first (they are what
     * `commands.tsx` registers for the composer), then the server's own
     * `GET /command` list, then `/btw` ([BTW_TRIGGER]), then the session slash
     * aliases ([sessionAliases]). A blank query lists everything, like the
     * popover's "open with `/`" state (`machine.ts:122-134`).
     *
     * `/btw` and the alias block come after the server catalog so a same-named
     * server custom keeps its row under `distinctBy` — mirroring the submit-time
     * precedence (server customs win in [invocation] before [btwQuestion] /
     * [aliasCommandId] are consulted).
     *
     * P1-C revisited (5554 `trm` finds no `/terminal`, 5556 prefix fine —
     * both true): filtering here is [slashMatches] (fuzzy subsequence, the
     * `fuzzysort` rule upstream's `useFilteredList` applies to this menu over
     * `[trigger, title]`), not the `@`-menu's [matches] (contains). A prefix
     * is itself a subsequence, so `/op` → `/open`, `/te` → `/terminal` and
     * `/fo` → `/fork` keep narrowing like a launcher — and an abbreviation
     * like `trm` now reaches `/terminal` the way upstream's menu does. A
     * one-letter `/e` matches half the menu again, exactly like upstream;
     * typing on narrows it.
     *
     * Server customs match on the trigger alone (upstream builds them with
     * `title: name`, so the description is not a filter key); the session
     * aliases match trigger + title like upstream's builtins.
     */
    fun commandItems(
        query: String,
        commands: List<OpenCodeClient.CommandInfo>,
        sessionAliases: List<SessionSlashRow> = emptyList(),
        limit: Int = LIMIT,
    ): List<Item> = buildList {
        if (slashMatches(query, BUILT_IN_MODEL)) {
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
        if (slashMatches(query, BUILT_IN_AGENT)) {
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
            // Server customs match on the trigger alone: upstream builds them
            // with `trigger: item.name, title: item.name` (`model.ts:230-237`),
            // so the description never participates in `filterKeys:
            // ["trigger", "title"]` — matching it here made `gsd` answer
            // `/init` through "guided AGENTS.md setup", noise upstream's menu
            // never shows.
            if (!slashMatches(query, name)) continue
            add(
                Item(
                    category = commandCategory(command),
                    label = slashLabel(name),
                    description = command.description.takeIf { it.isNotBlank() },
                ),
            )
        }
        // `/btw` (upstream `session.aside`, a registered command with a `slash`
        // field). Listed after the server catalog so a same-named custom keeps
        // its row under `distinctBy` — the precedence [btwQuestion] gives
        // `invocation` at submit time — and before the palette aliases, which
        // the server also outranks.
        if (slashMatches(query, BTW_TRIGGER)) {
            add(
                Item(
                    category = Category.COMMAND,
                    label = slashLabel(BTW_TRIGGER),
                    // Resource-filled by the popover (`oc_btw_description`), like
                    // the two built-ins.
                    description = null,
                    builtIn = true,
                ),
            )
        }
        for (alias in sessionAliases) {
            val trigger = alias.trigger.trim()
            if (trigger.isEmpty() || alias.commandId.isBlank()) continue
            if (!slashMatches(query, trigger, alias.title)) continue
            add(
                Item(
                    category = Category.COMMAND,
                    label = slashLabel(trigger),
                    description = alias.description,
                    commandId = alias.commandId,
                ),
            )
        }
    }.distinctBy { it.label }.take(limit)

    /** `source == "skill"` rows render with the skill icon (`GET /command` mixes both). */
    private fun commandCategory(command: OpenCodeClient.CommandInfo): Category =
        if (command.source.equals("skill", ignoreCase = true)) Category.SKILL else Category.COMMAND

    /**
     * The `@` context menu for [query]: the F-1 references, then agents, then the
     * server's commands and skills, then the F-1 MCP resources, then the
     * `/find/file` hits ([files]).
     *
     * Upstream builds `context = [...references, ...skills, ...agents,
     * ...resources, ...recent]` (`composer/model.ts:195-…`) and
     * `[...fixed, ...recent, ...files]` in the editor
     * (`interaction.ts:107-136`). The four pre-existing categories keep their
     * shipped order here (agents → commands → skills, an Android-only grouping
     * since `GET /command` mixes commands and skills); F-1 inserts the two new
     * sources where upstream has them relative to those: **references first**
     * and **resources after the skills**.
     *
     * A [MentionSources.Reference] row carries `path = reference.path` and the
     * `application/x-directory` mime (`model.ts:156-160`), a
     * [MentionSources.Resource] row carries `path = resource.uri` and its server
     * (`model.ts:168-188`) — [MentionTokens] turns those into the prompt's
     * `files[]` entries.
     *
     * A [Category.FILE] row is one `/find/file` path and carries the kind that
     * path names ([Item.directory], v15) — the source mixes directories into the
     * same list, so the row's shape is what tells a directory from a file.
     *
     * v69: the filter is the **label alone** — upstream's
     * `useFilteredList({ filterKeys: ["label"] })`
     * (`composer/editor/interaction.ts:105-107`, fuzzysort over `label`; the
     * search rows are excluded from filtering by `skipFilter` on the next line).
     * This used to match the description too, and every description in the
     * catalog is a sentence — so a two-letter query matched nearly everything
     * and the panel read as *unfiltered* for its first keystrokes: `@ex` still
     * listed `build` / `general` / `plan` / `Report` / `jira-SCB-Collector`
     * because their blurbs contain an `e` (`research/v68_verify/5558/19_at_ex.png`
     * against `24_at_exp.png`, where the same query finally narrows).
     *
     * The two halves are also what the composer's row-generation hold needs (see
     * [mentionRowSets]); [mentionItems] is the joined form both this class's own
     * callers and its JVM tests use.
     */
    fun mentionItems(
        query: String,
        agents: List<OpenCodeClient.Agent>,
        commands: List<OpenCodeClient.CommandInfo>,
        skills: List<OpenCodeClient.SkillInfo>,
        files: List<String>,
        references: List<MentionSources.Reference> = emptyList(),
        resources: List<MentionSources.Resource> = emptyList(),
        limit: Int = LIMIT,
    ): List<Item> {
        val rows = mentionRowSets(query, agents, commands, skills, files, references, resources)
        return joinMentionRows(rows.fixed, rows.files, limit)
    }

    /**
     * v69: the `@` menu's two halves, apart — the **fixed** rows the catalogs
     * already answer for, and the **file** rows the debounced `/find/file`
     * answer carries.
     *
     * Upstream's list is one async function (`interaction.ts:100-105`): a query
     * asks for `fixed + recent`, then appends `await searchContextFiles(query)`.
     * The shell cannot — the search wave lands 200 ms + a round trip **after**
     * the query changed — so the composer has to render the fixed half the
     * keystroke it is on, and the file half as it arrives. Splitting the two
     * here is what makes that possible, and it is also the split
     * [com.xiufeigo.opencodeshell.ui.session.composer.PopoverPick.steadyRows]
     * needs: the generation the open panel holds covers the fixed rows only,
     * because those are the ones a late catalog wave can insert into the middle
     * of, while the file rows are a **bottom** wave that the panel is expected
     * to follow.
     */
    data class MentionRows(val fixed: List<Item>, val files: List<Item>)

    /** [mentionItems] as the two lists it is built from, each uncapped. */
    fun mentionRowSets(
        query: String,
        agents: List<OpenCodeClient.Agent>,
        commands: List<OpenCodeClient.CommandInfo>,
        skills: List<OpenCodeClient.SkillInfo>,
        files: List<String>,
        references: List<MentionSources.Reference> = emptyList(),
        resources: List<MentionSources.Resource> = emptyList(),
    ): MentionRows {
        val fixed = buildList {
            for (reference in references) {
                val name = reference.name.trim()
                if (name.isEmpty() || reference.hidden) continue
                if (!matches(query, name)) continue
                add(
                    Item(
                        category = Category.REFERENCE,
                        label = name,
                        description = reference.description.takeIf { it?.isNotBlank() == true },
                        path = reference.path,
                    ),
                )
            }
            for (agent in agents) {
                val name = agent.name.trim()
                if (name.isEmpty() || agent.hidden) continue
                if (!matches(query, name)) continue
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
                if (!matches(query, name)) continue
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
                if (!matches(query, name)) continue
                add(
                    Item(
                        category = Category.SKILL,
                        label = name,
                        description = skill.description.takeIf { it.isNotBlank() },
                    ),
                )
            }
            for (resource in resources) {
                val name = resource.name.trim()
                if (name.isEmpty()) continue
                if (!matches(query, name)) continue
                add(
                    Item(
                        category = Category.RESOURCE,
                        label = name,
                        // `model.ts:171` leaves the description as the server
                        // sent it; the server name is the fallback column so a
                        // description-less row still says where it came from.
                        description = resource.description?.takeIf { it.isNotBlank() }
                            ?: resource.server.takeIf { it.isNotBlank() },
                        path = resource.uri,
                        server = resource.server,
                    ),
                )
            }
        }.distinctBy { it.label }

        val fileItems = files.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { path ->
                Item(
                    category = Category.FILE,
                    label = path,
                    // v15: `/find/file` mixes files and directories into one
                    // category, so the row keeps the kind its path shape names —
                    // the menu otherwise drew the same document glyph (and said
                    // "File") for `/home/xiufeigo/v11diff` and `…/a.txt`.
                    directory = MentionText.isDirectoryPath(path),
                )
            }
            .toList()

        return MentionRows(fixed = fixed, files = fileItems)
    }

    /**
     * The rows the panel draws: the fixed block first, the file block under it
     * (upstream's `[...filtered, ...skipped]`, `use-filtered-list.tsx:36-38`),
     * capped together at [limit].
     *
     * Kept separate from [mentionRowSets] so the composer can hold one half at a
     * time — a row-generation hold over the joined list would freeze the file
     * wave as well, and the query that wave answers is the one just typed.
     */
    fun joinMentionRows(
        fixed: List<Item>,
        fileRows: List<Item>,
        limit: Int = LIMIT,
    ): List<Item> = (fixed + fileRows).take(limit)

    /**
     * `/name arguments` → the server command invocation (`submit.ts:300-306`).
     * Only names the server actually lists are commands; `/model`, `/agent`,
     * `/btw` ([btwQuestion]) and any slash neither the server nor
     * [aliasCommandId] knows stay ordinary prompts.
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
     * `/btw` or `/btw <question>` → the side question, `null` when the line is
     * not a btw invocation.
     *
     * The bare form yields the **empty string**: upstream's `run(input)` falls
     * back to its question dialog when the command carries no input
     * (`btw.tsx:64-66`), and here the btw panel's own field is that dialog, so
     * the caller opens the panel instead of asking.
     *
     * A server custom named `btw` wins: the composer consults [invocation]
     * first, exactly as the menu lists the server catalog before the `/btw` row.
     */
    fun btwQuestion(text: String): String? =
        argumentCommand(text)?.takeIf { it.trigger == BTW_TRIGGER }?.arguments

    /**
     * The slash commands upstream registers with `slash: { name, arguments:
     * true }` — the set its `argumentSlash` resolves
     * (`tui/src/component/prompt/index.tsx:173-184`):
     *
     * - `cd` (`component/prompt/index.tsx:268`),
     * - `btw` (`feature-plugins/prompt/btw.tsx:57`),
     * - `rename` (`routes/session/index.tsx:823`).
     *
     * Only [BTW_TRIGGER] is registered here: `/cd` has no working-directory
     * switch in the composer and `/rename` is the session menu's title dialog, so
     * those two lines stay ordinary prompts on Android. Every **other** upstream
     * slash (`model`, `agent`, `undo`, …) carries no `arguments` field at all — a
     * `/model gpt` line is therefore *not* an argument command either: upstream
     * sends it as a prompt and so do we, the completion menu being the only
     * `/model` entry.
     */
    val ARGUMENT_SLASH_TRIGGERS: Set<String> = setOf(BTW_TRIGGER)

    /**
     * Upstream's `argumentSlash` (`tui/src/component/prompt/index.tsx:173-184`):
     * a `/name <text>` line whose `name` is an [ARGUMENT_SLASH_TRIGGERS] entry →
     * the trigger plus everything after it, trimmed (the bare `/name` form yields
     * the empty string). `null` for every other line, so nothing else changes.
     *
     * The head/argument split is upstream's `parseSlashHead(text, /\s/)`
     * (`tui/src/prompt/parse.ts:18-26`): **any** whitespace separates, not only
     * the space — a pasted `/btw` line (tab or newline before the question) routes
     * exactly like the typed one. `findCommand`, the server-command half, splits
     * on a plain space instead; the two rules are deliberately different and
     * [invocation] keeps the narrower one.
     *
     * Read through [HalfWidthText.toAscii] like [invocation] /
     * [aliasCommandId], so a `／ｂｔｗ why` typed on a Chinese IME still routes.
     */
    fun argumentCommand(text: String): ArgumentInvocation? {
        val ascii = HalfWidthText.toAscii(text).trim()
        if (!ascii.startsWith("/")) return null
        val split = ascii.indexOfFirst { it.isWhitespace() }
        val trigger = if (split < 0) ascii.substring(1) else ascii.substring(1, split)
        if (trigger !in ARGUMENT_SLASH_TRIGGERS) return null
        val arguments = if (split < 0) "" else ascii.substring(split + 1).trim()
        return ArgumentInvocation(trigger, arguments)
    }

    /** A `/name <arguments>` line for an [ARGUMENT_SLASH_TRIGGERS] command. */
    data class ArgumentInvocation(val trigger: String, val arguments: String)

    /**
     * F-1: `/trigger` or `/trigger args` → the palette command id, where
     * [aliases] is the trigger → command-id table (normally
     * `SessionCommands.SESSION_SLASH_COMMAND_IDS`).
     *
     * The composer consults [invocation] first, so a same-named server custom
     * keeps winning on submit — the same precedence the menu's `distinctBy`
     * gives it in [commandItems]. Anything unlisted here (and unknown to the
     * server) stays an ordinary prompt. Like [invocation] both halves are read
     * through [HalfWidthText.toAscii], so a full-width `／ｕｎｄｏ` still hits.
     */
    fun aliasCommandId(text: String, aliases: Map<String, String>): String? {
        if (aliases.isEmpty()) return null
        val ascii = HalfWidthText.toAscii(text).trim()
        if (!ascii.startsWith("/")) return null
        val space = ascii.indexOf(' ')
        val trigger = if (space < 0) ascii.substring(1) else ascii.substring(1, space)
        if (trigger.isEmpty()) return null
        return aliases[trigger]
    }

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
     * candidate's label, so "plan" finds `plan` and `/plan`.
     *
     * W5-F: both sides are folded through [HalfWidthText.toAscii] as well, so a
     * full-width query — `／ＭＯＤＥＬ`, or a `@` query typed on a Chinese IME —
     * filters the ASCII candidates instead of matching nothing.
     *
     * v69: the description is **not** a filter key any more. Upstream's `@` list
     * runs fuzzysort over `filterKeys: ["label"]` only
     * (`composer/editor/interaction.ts:105-107`) — the description is a column,
     * not a match target — and matching it here made every one of the catalog's
     * prose sentences a hit for any letter in it, so the panel showed the whole
     * catalog through the first two keystrokes
     * (`research/v68_verify/5558/19_at_ex.png`).
     *
     * The `@` context menu ([mentionItems]) keeps this rule; the `/` command
     * menu ([commandItems]) uses [slashMatches] instead (fuzzy subsequence).
     */
    fun matches(query: String, vararg values: String): Boolean {
        val needle = HalfWidthText.toAscii(query).trim().lowercase()
        if (needle.isEmpty()) return true
        return values.any { HalfWidthText.toAscii(it).lowercase().contains(needle) }
    }

    /**
     * The `/` menu's matching rule — normalized fuzzy subsequence over the
     * candidate's trigger (and, for aliases and builtins, its title — the two
     * keys of upstream's `useFilteredList`
     * (`packages/ui/src/hooks/use-filtered-list.tsx`, `filterKeys:
     * ["trigger", "title"]`, `interaction.ts:132-136`); server customs carry
     * `title: name`, so the description is not matched. A contains match is
     * itself a subsequence, so this is strictly wider than both the old
     * starts-with rule and [matches]: `trm` reaches `/terminal`, and every
     * query that answered before still answers. Same fold + case rules as
     * [matches], so a full-width `／ｍｏ` still finds `/model`.
     */
    fun slashMatches(query: String, vararg values: String): Boolean {
        val needle = HalfWidthText.toAscii(query).trim().lowercase()
        if (needle.isEmpty()) return true
        return values.any { fuzzySubsequence(needle, HalfWidthText.toAscii(it).lowercase()) }
    }
}
