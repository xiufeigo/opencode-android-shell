package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.SpinnerDots
import com.xiufeigo.opencodeshell.ui.components.TextShimmer
import com.xiufeigo.opencodeshell.ui.markdown.OcMarkdown
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Dedicated tool renderers -- one branch per upstream `ToolRegistry` entry that
 * the generic diff/JSON `ToolBody` cannot express
 * (`_upstream/packages/session-ui/src/tools/tool-renderer.tsx`):
 *
 * | tool | upstream | here |
 * |---|---|---|
 * | error (any tool) | `ToolErrorCard` (`components/tool-error-card.tsx`) | [ToolErrorCard] |
 * | bash/shell/execute | `ConsoleOutput` copy + `followShellOutput` | [ShellBody] |
 * | subagent (+`task`) | task-tool-card / delegating pill (`:1555-1678`) | [SubagentCard] |
 * | question | Q/A echo (`:2122-2169`) | [QuestionCard] |
 * | skill | loaded-skill inline row (`:2171-2202`) | [SkillRow] |
 * | read (image) | `ReadImage` (`:1368-1390`) | [ReadImageRow] |
 * | read (loaded) | loaded-files row (`:1309-1334`) | [LoadedFilesRow] |
 * | websearch | `ExaOutput` link list (`:437-488`) | [WebLinksBlock] |
 * | compaction part | `SessionCompactionMessage` (`message-content.tsx:400-473`) | [CompactionBlock] |
 * | diagnostics | `DiagnosticsDisplay` (`:114-150`) | [DiagnosticsBlock] |
 * | P2: count chips | `AnimatedCountList` (`tool-count-summary.tsx`) | [ToolCountChips] |
 *
 * Pure text shaping lives in [ToolText]/[ToolModels]/[ToolPresentation]; this
 * file only composes. `SessionScreen` integration: every entry takes the same
 * `(part, ...)` shape as [ToolBody] so the main agent can swap the dispatch
 * without touching call sites (see `research/v2_audit/wave2.2b-notes.md`).
 *
 * Copy lives in [ToolRendererCopy], not `strings.xml`: the `res` tree is outside this
 * wave's scope. Each constant names the `R.string` key the main agent should
 * add (with the upstream key + English literal quoted) so the swap is
 * mechanical.
 */

/** English copy for the dedicated renderers (see the file kdoc for the res plan). */
internal object ToolRendererCopy {
    /** `oc_tool_copy_error` <- `ui.toolErrorCard.copyError` ("Copy error"). */
    const val COPY_ERROR = "Copy error"

    /** `oc_tool_delegating` <- `ui.sessionTurn.status.delegating` ("Delegating work"). */
    const val DELEGATING = "Delegating work"

    /** `oc_tool_open_session` -- a11y label of the sub-session jump chevron (no upstream key). */
    const val OPEN_SESSION = "Open sub-session"

    /** `oc_tool_questions` <- `ui.tool.questions` ("Questions"). */
    const val QUESTIONS = "Questions"

    /** `oc_tool_skill` <- `ui.tool.skill` ("Skill"). */
    const val SKILL = "Skill"

    /** `oc_tool_loaded` <- `ui.tool.loaded` ("Loaded"). */
    const val LOADED = "Loaded"

    /** `oc_tool_websearch` <- `ui.tool.websearch` ("Web Search"). */
    const val WEBSEARCH = "Web Search"

    /** `oc_compaction_running` <- `ui.messagePart.compaction.running` (upstream snapshot lacks the literal). */
    const val COMPACTION_RUNNING = "Compacting..."

    /** `oc_tool_error_card_failed` <- `ui.toolErrorCard.failed` ("Failed"). */
    const val FAILED = "Failed"

    /** `oc_question_answer_none` <- `ui.question.answer.none` ("(no answer)"). */
    const val ANSWER_NONE = "(no answer)"

    /** `oc_tool_more_links` <- `common.moreCountSuffix` (" (+{{count}} more)"). */
    fun moreLinks(remaining: Int): String = "(+$remaining more)"

    /** `oc_question_answered` <- `ui.question.subtitle.answered` ("{{count}} answered"). */
    fun answered(count: Int): String = "$count answered"
}

/**
 * Which dedicated renderer a tool part wants. The error card wins over every
 * content renderer (`ToolDisplay`'s `Switch`: `error()` first,
 * `tool-renderer.tsx:1218-1261`); `question` hides while streaming/running
 * (`hideQuestion`, `:1195`).
 */
enum class ToolRendererKind {
    HIDDEN,
    ERROR,
    SHELL,
    SUBAGENT,
    QUESTION,
    SKILL,
    READ_IMAGE,
    READ_LOADED,
    WEBSEARCH,
    GENERIC,
}

fun toolRendererKind(part: ChatStore.UiPart): ToolRendererKind {
    if (part.toolStatus == "error" && !part.toolError.isNullOrBlank()) return ToolRendererKind.ERROR
    val name = part.toolName
    if (name == "question" && (part.toolStatus == "streaming" || part.toolStatus == "running")) {
        return ToolRendererKind.HIDDEN
    }
    return when (name) {
        "bash", "shell", "execute" -> ToolRendererKind.SHELL
        "subagent", "task" -> ToolRendererKind.SUBAGENT
        "question" -> ToolRendererKind.QUESTION
        "skill" -> ToolRendererKind.SKILL
        "read" -> {
            val input = ToolPresentation.parseObject(part.toolInput)
            if (part.toolStatus == "completed" && ToolModels.readImagePath(input) != null) {
                ToolRendererKind.READ_IMAGE
            } else {
                ToolRendererKind.READ_LOADED
            }
        }
        "websearch" -> ToolRendererKind.WEBSEARCH
        else -> ToolRendererKind.GENERIC
    }
}

/**
 * Card-title override for the tools whose upstream title is not the generic
 * capitalised name: `websearch` reads "Web Search"
 * (`ui.tool.websearch` / `ui.tool.websearch.provider`). Null = the generic
 * `titleRes`/`title` path. (The subagent/question/skill titles live in their
 * dedicated cards, not here.)
 */
internal fun dedicatedCardTitle(toolName: String?): String? = when (toolName) {
    "websearch" -> ToolRendererCopy.WEBSEARCH
    else -> null
}

// -------------------------------------------------------------- error card

/**
 * `ToolErrorCard` (`components/tool-error-card.tsx:22-164`): the trigger shows
 * the tool title + subtitle + the `": "`-split summary head in danger colour;
 * expanding reveals the detail with a copy button (2 s check feedback, copying
 * the `Error:`-stripped text). `defaultOpen` is false upstream (`:25`).
 *
 * @param jumpTarget child-session id for the subagent jump affordance
 *   (`href`/`onSubtitleClick`, `:111-122`). **Null until wired** -- the store
 *   carries no `metadata.sessionID`, so there is nothing to navigate to yet
 *   (see `research/v2_audit/wave2.2b-notes.md`); the subtitle renders as plain
 *   text until the main agent passes a real id + hook.
 */
@Composable
fun ToolErrorCard(
    part: ChatStore.UiPart,
    jumpTarget: String? = null,
    onJumpToSession: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    // `cleaned` (`:65`): the copy payload is the `Error:`-stripped text.
    val cleaned = remember(part.toolError) {
        part.toolError.orEmpty().replaceFirst(Regex("^Error:\\s*"), "").trim()
    }
    val (head, detail) = remember(cleaned, part.toolName) {
        ToolText.errorHeadDetail(cleaned, part.toolName, fallback = ToolRendererCopy.FAILED)
    }
    val subtitle = ToolPresentation.subtitle(part, stringResource(R.string.oc_tool_error))
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        ErrorTriggerRow(
            toolName = part.toolName,
            subtitle = subtitle,
            head = head,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            jumpTarget = jumpTarget,
            onJumpToSession = onJumpToSession,
        )
        if (expanded && detail.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(OcTheme.shapes.md)
                        .background(c.bgBase)
                        .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = detail,
                            color = c.textBase,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                CopyButton(
                    copied = copied,
                    copyDescription = ToolRendererCopy.COPY_ERROR,
                    copiedDescription = stringResource(R.string.oc_meta_copied),
                    onCopy = {
                        if (cleaned.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(cleaned))
                            copied = true
                        }
                    },
                    onCopiedConsumed = { copied = false },
                )
            }
        }
    }
}

@Composable
private fun ErrorTriggerRow(
    toolName: String?,
    subtitle: String?,
    head: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    jumpTarget: String?,
    onJumpToSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_alert),
            contentDescription = null,
            tint = c.stateDangerFg,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = ToolPresentation.titleRes(toolName)?.let { stringResource(it) }
                ?: ToolPresentation.title(toolName),
            color = c.stateDangerFg,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            if (jumpTarget != null) {
                Text(
                    text = subtitle,
                    color = c.stateDangerFg,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable(role = Role.Button) { onJumpToSession(jumpTarget) },
                )
            } else {
                Text(
                    text = subtitle,
                    color = c.stateDangerFg,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = head,
            color = c.stateDangerFg,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Ghost copy/check button with 2 s feedback (shared by the error/shell bodies). */
@Composable
internal fun CopyButton(
    copied: Boolean,
    copyDescription: String,
    copiedDescription: String,
    onCopy: () -> Unit,
    onCopiedConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        kotlinx.coroutines.delay(2_000L)
        onCopiedConsumed()
    }
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onCopy),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (copied) R.drawable.ic_oc_check else R.drawable.ic_oc_copy),
            contentDescription = if (copied) copiedDescription else copyDescription,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ---------------------------------------------------------------- shell

/**
 * Shell/execute body (`ConsoleOutput`, `tool-renderer.tsx:1680-1718`): the
 * stripped command + output in the shared `bash-output` surface with a copy
 * button copying the **command** (`copy={command()}`).
 *
 * Live output follow (`followShellOutput`, `tools/shell-output.ts`) needs a
 * `shellOutput(id)` loader the store does not expose (see the wave2.2b
 * notes): [liveOutput] is the hook -- when non-null it replaces the snapshot
 * output while the tool runs, which is exactly the `saved ?? streamed`
 * precedence once the loader is wired.
 */
@Composable
fun ShellBody(
    part: ChatStore.UiPart,
    liveOutput: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val input = remember(part.toolInput) { ToolPresentation.parseObject(part.toolInput) }
    val command = remember(input) {
        input?.optString("command", "").orEmpty()
            .ifEmpty { input?.optString("code", "").orEmpty() }
    }
    val snapshot = remember(part.toolOutput) { ToolText.shellOutput(part.toolOutput) }
    val running = part.toolStatus == "streaming" || part.toolStatus == "running"
    val output = if (running && liveOutput != null) ToolText.shellOutput(liveOutput) else snapshot
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .heightIn(max = TOOL_BODY_MAX_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    if (command.isNotEmpty()) {
                        Text(
                            text = command,
                            color = c.textBase,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                    }
                    if (output.isNotEmpty()) {
                        if (command.isNotEmpty()) Spacer(Modifier.height(8.dp))
                        Text(
                            text = output,
                            color = c.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
        // `data-slot="bash-copy"`: top-trailing overlay copy of the command.
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            CopyButton(
                copied = copied,
                copyDescription = stringResource(R.string.oc_meta_copy_response),
                copiedDescription = stringResource(R.string.oc_meta_copied),
                onCopy = {
                    if (command.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(command))
                        copied = true
                    }
                },
                onCopiedConsumed = { copied = false },
            )
        }
    }
}

// --------------------------------------------------------------- subagent

/**
 * Subagent/task card (`task-tool-card`, `:1555-1678`): agent-tinted title +
 * description subtitle + a trailing chevron when the child session is
 * reachable. While streaming the whole card is the "Delegating..." pill
 * (`task-tool-delegating`); while running (or its child runs) the icon is the
 * agent-coloured spinner (`task-tool-spinner`).
 *
 * @param childSessionId upstream `metadata.sessionID`. **Null until wired** --
 *   same no-op policy as the error card (see the wave2.2b notes); without it
 *   the row is not clickable and shows no chevron.
 */
@Composable
fun SubagentCard(
    part: ChatStore.UiPart,
    childSessionId: String? = null,
    childRunning: Boolean = false,
    background: Boolean = false,
    onJumpToSession: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val input = remember(part.toolInput) { ToolPresentation.parseObject(part.toolInput) }
    val rawAgent = remember(input, part.toolName) {
        input?.optString("agent", "").orEmpty().ifEmpty { part.toolName }
    }
    val title = ToolModels.agentLabel(rawAgent)
        ?: stringResource(R.string.oc_step_agent)
    val tone = agentTone(rawAgent, c)
    val subtitle = remember(input, childSessionId, background) {
        ToolModels.subagentSubtitle(
            description = input?.optString("description", "").orEmpty().takeIf { it.isNotEmpty() },
            sessionId = childSessionId,
            background = background,
        )
    }
    val clickable = childSessionId != null
    val delegating = part.toolStatus == "streaming"
    val running = ToolModels.subagentRunning(part.toolStatus, childRunning)

    if (delegating) {
        // `task-tool-delegating`: layered surface, faint icon, shimmer label.
        Row(
            modifier = modifier
                .clip(OcTheme.shapes.md)
                .background(c.layer02)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_task),
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            TextShimmer(
                text = ToolRendererCopy.DELEGATING,
                active = true,
                baseColor = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
        }
        return
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(enabled = clickable, role = Role.Button) {
                childSessionId?.let(onJumpToSession)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            SpinnerDots(size = 16.dp, color = tone)
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_oc_task),
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = subtitle,
                color = c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (clickable) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = ToolRendererCopy.OPEN_SESSION,
                tint = c.textFaint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Agent tint (`agentPalette` / `agentTones`, `tool-renderer.tsx:190-256`):
 * known agents map to the theme's agent colours, unknown ones hash into the
 * palette deterministically ([agentSlot]).
 */
internal fun agentTone(rawAgent: String?, c: OcColors): Color {
    val key = rawAgent?.lowercase().orEmpty()
    when (key) {
        "plan" -> return c.agentPlan
        "build" -> return c.agentBuild
        "explore" -> return c.agentExplore
        "review" -> return c.agentReview
        "writer" -> return c.agentWriter
    }
    val palette = listOf(
        c.agentPlan,
        c.agentBuild,
        c.agentExplore,
        c.agentReview,
        c.agentWriter,
        c.iconAccent,
    )
    return palette[agentSlot(key, palette.size)]
}

/**
 * `tone(name)`: deterministic palette slot for an unknown agent name
 * (`tool-renderer.tsx:233-237`, `hash * 31 + charCode`). Pure so the mapping
 * is JVM-testable without a Compose runtime.
 */
fun agentSlot(key: String, size: Int): Int {
    var hash = 0
    for (char in key) hash = (hash * 31 + char.code)
    return Math.floorMod(hash, size)
}

// --------------------------------------------------------------- question

/**
 * Question card (`tool-renderer.tsx:2122-2169`): completed cards read
 * `{count} answered` and echo each question with its joined answers
 * (`question-answers`); unanswered cards keep the count subtitle and no body.
 * The card hides while streaming/running ([toolRendererKind] -> HIDDEN).
 *
 * @param answers one label array per question, in question order
 *   (`metadata.answers`). **Empty until wired** -- the store carries no
 *   metadata, so every card reads unanswered for now (see the wave2.2b notes).
 */
@Composable
fun QuestionCard(
    part: ChatStore.UiPart,
    answers: List<List<String>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val input = remember(part.toolInput) { ToolPresentation.parseObject(part.toolInput) }
    val questions = remember(input) {
        val arr = input?.optJSONArray("questions") ?: return@remember emptyList<String>()
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val q = item.optString("question", "")
                if (q.isNotEmpty()) add(q)
            }
        }
    }
    val model = remember(questions, answers) {
        ToolModels.QuestionModel(
            questions = questions.map { ToolModels.QuestionItem(it) },
            answers = answers,
        )
    }
    val subtitle = remember(model) {
        ToolModels.questionSubtitle(model, ToolRendererCopy::answered)
    }
    var expanded by rememberSaveable(part.id, model.completed) { mutableStateOf(model.completed) }

    Column(modifier = modifier.fillMaxWidth()) {
        QuestionTriggerRow(
            subtitle = subtitle,
            expandable = model.completed,
            onToggle = { expanded = !expanded },
        )
        if (expanded && model.completed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                // `question-answers`: column, 12 px gap, 8 px vertical padding.
            ) {
                ToolModels.questionAnswers(model, ToolRendererCopy.ANSWER_NONE).forEach { (question, answer) ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = question,
                            color = c.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = answer,
                            color = c.textBase,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionTriggerRow(
    subtitle: String?,
    expandable: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .clickable(enabled = expandable, role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_help),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = ToolRendererCopy.QUESTIONS,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            maxLines = 1,
        )
        if (!subtitle.isNullOrEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = subtitle,
                color = c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

// ----------------------------------------------------------------- skill

/**
 * Skill row (`tool-renderer.tsx:2171-2202`): the inline `tool-loaded-item` --
 * `Loaded {name}` with the name shimmering while running. Without a name the
 * row is the plain `Skill` shimmer (upstream's `fallback` branch).
 */
@Composable
fun SkillRow(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val running = part.toolStatus == "streaming" || part.toolStatus == "running"
    val name = remember(part.toolInput) {
        ToolModels.loadedSkillName(ToolPresentation.parseObject(part.toolInput))
    }
    Row(
        modifier = modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ToolRendererCopy.LOADED,
            color = c.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        TextShimmer(
            text = name ?: ToolRendererCopy.SKILL,
            active = running,
            baseColor = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
    }
}

// ------------------------------------------------------------------ read

/**
 * Completed `read` of an image path (`ReadImage`, `:1368-1390`): the store has
 * no image bytes, so the row is the labelled path (the bitmap fetch is a data
 * gap recorded in the wave2.2b notes -- the parallel markdown agent owns image
 * rendering and can take this over).
 */
@Composable
fun ReadImageRow(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val path = remember(part.toolInput) {
        ToolModels.readImagePath(ToolPresentation.parseObject(part.toolInput))
    } ?: return
    Row(
        modifier = modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_file),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = ToolPresentation.basename(path),
            color = c.textBase,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Completed `read` with loaded files (`:1309-1334`): `Loaded {paths}` inline
 * row. The store keeps no `metadata.loaded`, so [loadedPaths] is the hook the
 * main agent wires once the store carries it -- an empty list renders only the
 * generic card (never a guessed path).
 */
@Composable
fun LoadedFilesRow(
    loadedPaths: List<String>,
    directory: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val label = remember(loadedPaths, directory) {
        ToolModels.loadedFiles(loadedPaths, directory)
    } ?: return
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        )
    }
}

// -------------------------------------------------------------- websearch

/**
 * Websearch body (`:1529-1554`): the `ExaOutput` link list -- up to 10 unique
 * links, then a `(+{n} more)` row (`common.moreCountSuffix`). A tap opens the
 * link in the system browser; the block is selectable.
 */
@Composable
fun WebLinksBlock(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val uriHandler = LocalUriHandler.current
    val links = remember(part.toolOutput) { ToolModels.webLinks(part.toolOutput) }
    if (links.isEmpty()) return
    val shown = links.take(ToolModels.WEB_LINKS_SHOWN)
    val remaining = links.size - shown.size
    SelectionContainer {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
        ) {
            shown.forEach { url ->
                Text(
                    text = url,
                    color = c.textAccent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable(role = Role.Button) {
                            try {
                                uriHandler.openUri(url)
                            } catch (_: Exception) {
                            }
                        },
                )
            }
            if (remaining > 0) {
                Text(
                    text = ToolRendererCopy.moreLinks(remaining),
                    color = c.textFaint,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------- compaction

/**
 * Compaction block (`SessionCompactionMessage`, `message-content.tsx:400-473`):
 * the summary renders through markdown (paced upstream; the 80 ms coalesced
 * `OcMarkdown` is the native equivalent -- pacing itself belongs to the
 * parallel markdown agent's `MarkdownStream.kt`), a running compaction shows
 * the shimmer row, and a failed one shows the error card.
 *
 * @param running upstream's `status === "running"` shimmer (`compaction.running`).
 * @param error upstream's failed-status error card text (null = no error row).
 *   Both are caller-driven hooks: the store keeps no compaction status/error.
 */
@Composable
fun CompactionBlock(
    part: ChatStore.UiPart,
    running: Boolean = false,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val model = remember(part.id, part.text) { ToolModels.compaction(part) }
    Column(modifier = modifier.fillMaxWidth()) {
        val summary = model.summary
        if (summary != null) {
            OcMarkdown(
                markdown = summary,
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                muted = true,
            )
        } else {
            Text(
                text = stringResource(R.string.oc_compaction),
                color = c.textFaint,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        if (running) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextShimmer(
                    text = ToolRendererCopy.COMPACTION_RUNNING,
                    active = true,
                    baseColor = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(530),
                )
            }
        }
        if (!error.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(OcTheme.shapes.md)
                    .background(c.layer01)
                    .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = error,
                        color = c.stateDangerFg,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------ diagnostics

/**
 * LSP diagnostics under an edit/write card (`DiagnosticsDisplay`, `:114-150`):
 * severity-1 errors only, at most 3, as `Error [line:col] message` rows.
 * [diagnosticsJson] is `metadata.diagnostics` keyed by file -- a store data gap
 * (see the wave2.2b notes), so null renders nothing.
 */
@Composable
fun DiagnosticsBlock(
    filePath: String?,
    diagnosticsJson: String? = null,
    modifier: Modifier = Modifier,
) {
    val diagnostics = remember(diagnosticsJson, filePath) {
        ToolModels.diagnostics(diagnosticsJson, filePath)
    }
    if (diagnostics.isEmpty()) return
    val c = OcTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
    ) {
        diagnostics.forEach { diagnostic ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    text = stringResource(R.string.oc_tool_error),
                    color = c.stateDangerFg,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(530),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "[${diagnostic.line}:${diagnostic.character}] ${diagnostic.message}",
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ---------------------------------------------------------- P2 count chips

/**
 * P2: tool-count chips -- the static counterpart of upstream's
 * `AnimatedCountList` (`tool-count-summary.tsx`, animated number + plural
 * noun). Counts come from [ToolModels.countChips] (tool multiplicity -- the
 * store has no per-tool match counts); the animated number is deliberately a
 * plain digit on a touch shell. Unwired: the suggested slot is the expanded
 * group header in `ContextToolGroupRow` (main agent's call).
 */
@Composable
fun ToolCountChips(
    chips: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
) {
    if (chips.isEmpty()) return
    val c = OcTheme.colors
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = chips.joinToString(" . ") { (label, count) ->
                if (count > 1) "$count $label" else label
            },
            color = c.textFaint,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
