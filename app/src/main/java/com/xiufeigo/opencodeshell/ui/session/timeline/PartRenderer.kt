package com.xiufeigo.opencodeshell.ui.session.timeline

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.OcGrammarLocator
import com.xiufeigo.opencodeshell.OcPrismTheme
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.SpinnerDots
import com.xiufeigo.opencodeshell.ui.components.TextShimmer
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.syntax.SyntaxHighlightPlugin
import io.noties.prism4j.Prism4j
import kotlinx.coroutines.flow.collect

/**
 * Renders one [ChatStore.UiPart] inside the timeline. Dispatch mirrors the
 * View-era adapter (`ChatView.MessagesAdapter.AssistantHolder.bind`, L1000):
 * `text` → markdown, `tool` → tool card (todowrite hidden, its own dock lands
 * later), `reasoning` → collapsible thought, `compaction` → one-line notice,
 * `file` → attachment chip. Unknown part types render nothing.
 *
 * M1.5: the readable bodies (`text`, `reasoning`) are wrapped in a
 * [SelectionContainer] so the body text is selectable; the tool card is
 * deliberately **not** wrapped (`PLAN` §4.2 — the expanded body has its own
 * `verticalScroll`, and a selection gesture there would fight it).
 */
@Composable
fun PartRenderer(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
    /** Last part of the last assistant message: gates the shimmer/spinner. */
    isTrailing: Boolean = false,
    /**
     * Screen-level `toolOpen` entry for this part (`null` = the user has never
     * toggled it, so `partDefaultOpen` decides). Owned by `SessionScreen` so an
     * expanded card survives LazyColumn recycling, exactly like the upstream
     * timeline store.
     */
    userOpen: Boolean? = null,
    /**
     * Reports a card toggle as `(partId, newOpen)`: the callback carries the
     * resolved value so the owner does not have to re-derive it from
     * `partDefaultOpen` (a default-open card must collapse on the first tap).
     */
    onToggleTool: (String, Boolean) -> Unit = { _, _ -> },
    /** `settings.general.row.shellToolPartsExpanded` (M4 switch, M2 default false). */
    shellExpanded: Boolean = ToolCardPolicy.DEFAULT_SHELL_EXPANDED,
    /** `settings.general.row.editToolPartsExpanded` (M2 default true). */
    editExpanded: Boolean = ToolCardPolicy.DEFAULT_EDIT_EXPANDED,
) {
    when (part.type) {
        "text" -> if (part.text.isNotBlank()) {
            SelectableBody {
                MarkdownText(
                    markdown = part.text,
                    modifier = modifier,
                    color = OcTheme.colors.textBase,
                    fontSize = 14.sp,
                    lineSpacingMultiplier = 1.5f,
                )
            }
        }

        "tool" -> if (part.toolName != null && part.toolName !in TimelineGrouping.HIDDEN_TOOLS) {
            ToolCard(
                part = part,
                modifier = modifier,
                shimmer = isTrailing,
                userOpen = userOpen,
                onToggle = { open -> onToggleTool(part.id, open) },
                shellExpanded = shellExpanded,
                editExpanded = editExpanded,
            )
        }

        "reasoning" -> if (part.text.isNotBlank()) {
            ReasoningCard(part = part, modifier = modifier, shimmer = isTrailing)
        }

        "compaction" -> CompactionRow(modifier)

        "file" -> AttachmentChip(part = part, modifier = modifier)
    }
}

/**
 * Wraps a message body in a [SelectionContainer] (`PLAN` §4.2 "正文可选中").
 *
 * Caveat worth knowing before touching this: selection only works for text
 * rendered by Compose. The markdown body is an `AndroidView` (Markwon), and
 * Compose's selection pipeline does not see interop children — making that
 * `TextView` `textIsSelectable` instead would swallow vertical drags and break
 * LazyColumn scrolling (the M1 finding this replaced). The wrapper is therefore
 * the correct *structure* (and already covers every Compose-rendered body, e.g.
 * the user bubble); real markdown selection waits for the Compose-native
 * markdown renderer.
 */
@Composable
private fun SelectableBody(content: @Composable () -> Unit) {
    SelectionContainer(content = content)
}

// --------------------------------------------------------------------- text

/**
 * Markwon markdown in Compose: a shared [Markwon] instance driving a plain
 * `TextView` through `AndroidView` (the exact pipeline the View timeline uses,
 * `ChatView.buildMarkwon()` L734), with the View-era 80 ms render coalescing
 * restored on the Compose side.
 *
 * Two M1-review findings are handled here:
 * - **no per-frame re-parse**: the streaming text is funnelled through
 *   [coalesceLatest], so `setMarkdown` runs at most once per [COL_CHUNK_MS] with
 *   the newest value (the previous code re-ran `parse()+render()+Prism4j` on
 *   every recomposition, i.e. potentially every frame of a streamed answer);
 * - **palette follows the theme**: the Markwon instance is keyed by
 *   [isNightUiMode] of `LocalConfiguration`, because the palette (link, inline
 *   code, code-block background) is baked in when the builder runs and
 *   `OcActivity` is *not* recreated on a `uiMode` change.
 *
 * Deliberate M1 simplification (see `research/_M1_session_status.md`):
 * - `setTextIsSelectable(false)`: a selectable `TextView` swallows the vertical
 *   drag, which would break LazyColumn scrolling (View-era had its own gesture
 *   pipeline, so it could afford it). M1.5 keeps it off: the body is wrapped in
 *   a `SelectableBody`, which cannot reach into an interop `TextView` — see the
 *   caveat documented on `SelectableBody`.
 */
@Composable
internal fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = OcTheme.colors.textBase,
    fontSize: TextUnit = 14.sp,
    lineSpacingMultiplier: Float = 1.5f,
) {
    val context = LocalContext.current
    val isDark = isNightUiMode(LocalConfiguration.current.uiMode)
    val markwon = remember(context, isDark) { MarkwonHolder.get(context, isDark) }
    // A different Markwon means the spans already in the TextView were coloured
    // by the old palette: drop the applied-text memo so the next update re-renders.
    val applied = remember(markwon) { AppliedText() }
    val argb = color.toArgb()

    // Throttled mirror of the streaming text. Initialised with the current value
    // so history (a session switch / a completed answer) renders on the first
    // update, never one window later.
    var rendered by remember(markwon) { mutableStateOf(markdown) }
    val latest by rememberUpdatedState(markdown)
    LaunchedEffect(markwon) {
        snapshotFlow { latest }.coalesceLatest().collect { rendered = it }
    }
    // Read in composition scope (not inside `update`) so a throttled change is
    // guaranteed to recompose and therefore to reach `AndroidView.update`.
    val renderText = rendered

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            // A recreated view has no text yet: force the next update to apply.
            applied.value = null
            TextView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                includeFontPadding = false
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                movementMethod = null
                setTextIsSelectable(false)
                setTextColor(argb)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize.value)
                setLineSpacing(0f, lineSpacingMultiplier)
            }
        },
        update = { view ->
            if (view.currentTextColor != argb) view.setTextColor(argb)
            if (applied.value != renderText) {
                applied.value = renderText
                markwon.setMarkdown(view, renderText)
            }
        },
    )
}

/** Mutable holder so the `AndroidView.update` lambda can skip unchanged text. */
private class AppliedText {
    var value: String? = null
}

/**
 * Process-wide [Markwon] cache, **one instance per theme**: the colour set
 * (`oc_accent` links, `oc_code_inline_fg/bg`, `oc_layer_02` code blocks) is baked
 * into the builder, so a single frozen instance would keep rendering the old
 * palette after a dark⇄light switch. The colours themselves come from
 * `values` / `values-night`, which resolve against the process Configuration at
 * build time — hence the `isDark` key and the two cache slots.
 *
 * Built from the *application* context so the static reference can never leak an
 * Activity; identical configuration to `ChatView.buildMarkwon()` (same plugins,
 * same res colours, same theme knobs).
 *
 * Threading: Markwon is not documented as thread-safe and this cache is not
 * per-thread — call [get] from the main thread only (composition /
 * `AndroidView.update`, i.e. every call site today).
 */
internal object MarkwonHolder {

    private val instances = HashMap<Boolean, Markwon>()

    fun get(context: Context, isDark: Boolean): Markwon = synchronized(this) {
        instances.getOrPut(isDark) { build(context.applicationContext) }
    }

    private fun build(context: Context): Markwon {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density + 0.5f).toInt()
        return Markwon.builder(context)
            .usePlugin(CorePlugin.create())
            .usePlugin(SyntaxHighlightPlugin.create(Prism4j(OcGrammarLocator()), OcPrismTheme(context)))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .linkColor(context.getColor(R.color.oc_accent))
                        .isLinkUnderlined(true)
                        .blockMargin(px(32))
                        .blockQuoteWidth(1)
                        .blockQuoteColor(context.getColor(R.color.oc_border_strong))
                        .codeTextColor(context.getColor(R.color.oc_code_inline_fg))
                        .codeBackgroundColor(context.getColor(R.color.oc_code_inline_bg))
                        .codeBlockTextColor(context.getColor(R.color.oc_text_base))
                        .codeBlockBackgroundColor(context.getColor(R.color.oc_layer_02))
                        .codeBlockMargin(px(8))
                        .codeTypeface(Typeface.MONOSPACE)
                        .codeBlockTypeface(Typeface.MONOSPACE)
                        .codeTextSize(px(12))
                        .codeBlockTextSize(px(12))
                        .headingBreakHeight(0)
                        .headingTextSizeMultipliers(
                            floatArrayOf(17f / 14f, 15f / 14f, 13f / 14f, 1f, 1f, 1f),
                        )
                }
            })
            .build()
    }
}

// --------------------------------------------------------------- tool card

/**
 * Tool card: `layer01` surface, radius 6, 0.5 dp `borderBase`, icon + 14sp/500
 * title + 14sp muted one-line subtitle + chevron. Per `research/02` §3.1 the
 * accent is carried by the **icon colour**, not a left bar (the View-era bar was
 * a fidelity bug): running → `accent`, error → `stateDangerFg`, done → muted.
 *
 * Title and subtitle are the **same size** — §3.1
 * `[data-slot="basic-tool-tool-info"] { font-size:14px }` covers both
 * (`-tool-title` medium, `-tool-subtitle` regular + muted + single-line ellipsis),
 * which is the M1-review ruling for the earlier 14sp/13sp mismatch.
 *
 * M2 (§3.1 / §3.2 / §3.2b): the open state is resolved by [ToolCardPolicy]
 * (user toggle > `partDefaultOpen`), `pending`/`running` cards are locked shut
 * except for `shell`, and the body animates with the collapsible spring. The
 * chevron only exists for cards that can expand — a body-less card has no arrow.
 *
 * M6 motion: a running card carries the web's `TextShimmer` title
 * (`research/03` §2.3) and the 16×16 4×4 dot-matrix spinner (§2.4) in the
 * trailing slot, both of which freeze under reduced motion.
 */
@Composable
private fun ToolCard(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
    shimmer: Boolean,
    userOpen: Boolean?,
    onToggle: (Boolean) -> Unit,
    shellExpanded: Boolean,
    editExpanded: Boolean,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    val state = ToolCardPolicy.stateOf(part)
    val tint = when (state) {
        ToolCardPolicy.State.RUNNING -> c.accent
        ToolCardPolicy.State.ERROR -> c.stateDangerFg
        ToolCardPolicy.State.COMPLETED -> c.iconMuted
        ToolCardPolicy.State.PENDING -> c.iconMuted
    }
    val expandable = ToolCardPolicy.canExpand(part)
    val expanded = ToolCardPolicy.resolveOpen(part, userOpen, shellExpanded, editExpanded)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "tool-chevron")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = expandable, role = Role.Button) { onToggle(expanded) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(toolIconRes(ToolPresentation.icon(part.toolName))),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            // M6: the running title is the web's `TextShimmer` (sweep across the
            // glyphs), replacing M1's 1 s alpha pulse — `research/03` §2.3.
            TextShimmer(
                text = ToolPresentation.titleRes(part.toolName)?.let { stringResource(it) }
                    ?: ToolPresentation.title(part.toolName),
                active = shimmer && state == ToolCardPolicy.State.RUNNING,
                baseColor = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
            )
            val subtitle = ToolPresentation.subtitle(part, stringResource(R.string.oc_tool_error))
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = subtitle,
                    color = c.textMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (state == ToolCardPolicy.State.RUNNING) {
                // The tool card's 16×16 spinner (`research/03` §2.4/§3.5), not a
                // rotating ring: the 4×4 dot matrix breathing in place.
                Spacer(Modifier.width(8.dp))
                SpinnerDots(size = 16.dp, color = c.textFaint)
            }
            if (expandable) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_oc_chevron_down),
                    contentDescription = stringResource(R.string.oc_toggle_tool),
                    tint = c.textFaint,
                    modifier = Modifier.size(16.dp).rotate(rotation),
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = OcMotion.expand) + fadeIn(OcMotion.fade),
            exit = shrinkVertically(animationSpec = OcMotion.expand) + fadeOut(OcMotion.fade),
        ) {
            ToolBody(part)
        }
    }
}

/** Expanded body: coloured monospace lines, capped at 220 dp with inner scroll. */
@Composable
private fun ToolBody(part: ChatStore.UiPart) {
    val c = OcTheme.colors
    val lines = remember(part.id, part.toolInput, part.toolOutput, part.toolName) {
        ToolPresentation.bodyLines(part)
    }
    if (lines.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .heightIn(max = TOOL_BODY_MAX_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text(
            text = toolBodyAnnotated(lines),
            color = c.textBase,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun toolBodyAnnotated(lines: List<ToolPresentation.BodyLine>): AnnotatedString {
    val c = OcTheme.colors
    val addColor = colorResource(R.color.oc_diff_add_fg)
    val delColor = colorResource(R.color.oc_diff_del_fg)
    return remember(lines, c, addColor, delColor) {
        buildAnnotatedString {
            lines.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                when (line.kind) {
                    ToolPresentation.LineKind.ADD -> colored(line.text, addColor)
                    ToolPresentation.LineKind.DELETE -> colored(line.text, delColor)
                    ToolPresentation.LineKind.ERROR -> colored(line.text, c.stateDangerFg)
                    ToolPresentation.LineKind.JSON ->
                        appendJsonHighlighted(line.text, c.textMuted, c.syntaxString, c.textBase)

                    else -> colored(line.text, c.textBase)
                }
            }
        }
    }
}

private fun AnnotatedString.Builder.colored(text: String, color: Color) {
    withStyle(SpanStyle(color = color)) { append(text) }
}

/** Ports `ChatView.jsonSpannable`: keys muted, string values syntax-green. */
private fun AnnotatedString.Builder.appendJsonHighlighted(
    text: String,
    keyColor: Color,
    stringColor: Color,
    baseColor: Color,
) {
    var cursor = 0
    for (match in JSON_STRING_REGEX.findAll(text)) {
        if (match.range.first > cursor) {
            colored(text.substring(cursor, match.range.first), baseColor)
        }
        var after = match.range.last + 1
        while (after < text.length && text[after].isWhitespace()) after++
        val isKey = after < text.length && text[after] == ':'
        colored(match.value, if (isKey) keyColor else stringColor)
        cursor = match.range.last + 1
    }
    if (cursor < text.length) colored(text.substring(cursor), baseColor)
}

// ---------------------------------------------------------- reasoning card

/**
 * Reasoning part: same collapsible shell as the tool card but with the thought
 * label and a 13sp muted markdown body (upstream renders `ui.tool.thought` —
 * the same string as the existing `oc_thought` resource).
 */
@Composable
private fun ReasoningCard(
    part: ChatStore.UiPart,
    modifier: Modifier = Modifier,
    shimmer: Boolean,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "reasoning-chevron")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.md)
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // `ui.tool.thought` is a shimmering title while the model is still
            // thinking (`research/03` §2.3); the spin-off pulse of M1 is gone.
            TextShimmer(
                text = stringResource(R.string.oc_thought),
                active = shimmer,
                baseColor = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = stringResource(R.string.oc_toggle_tool),
                tint = c.textFaint,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .heightIn(max = TOOL_BODY_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                SelectableBody {
                    MarkdownText(
                        markdown = part.text,
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineSpacingMultiplier = 1.4f,
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------- compaction

/** `ui.messagePart.compaction` — a single muted separator line. */
@Composable
private fun CompactionRow(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.oc_compaction),
        color = OcTheme.colors.textFaint,
        fontSize = 12.sp,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

// -------------------------------------------------------------- file chip

/** Attachment chip (`layer02`, radius 6) — `ChatView.fileChip` L1156 semantics. */
@Composable
internal fun AttachmentChip(part: ChatStore.UiPart, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    // File parts may carry only a (possibly huge) data URL; never display that.
    val name = part.filename
        ?: part.url?.takeIf { !it.startsWith("data:") }
        ?: stringResource(R.string.oc_attachment_file)
    Row(
        modifier = modifier
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .padding(horizontal = 8.dp, vertical = 6.dp),
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
            text = name,
            color = c.textBase,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ shared

/** Drawable for a [ToolPresentation.Icon] slot (shared with the group rows). */
internal fun toolIconRes(icon: ToolPresentation.Icon): Int = when (icon) {
    ToolPresentation.Icon.SHELL -> R.drawable.ic_oc_console
    ToolPresentation.Icon.CODE -> R.drawable.ic_oc_code
    ToolPresentation.Icon.GLASSES -> R.drawable.ic_oc_glasses
    ToolPresentation.Icon.SEARCH -> R.drawable.ic_oc_search
    ToolPresentation.Icon.GLOBE -> R.drawable.ic_oc_globe
    ToolPresentation.Icon.TASK -> R.drawable.ic_oc_task
    ToolPresentation.Icon.CHECK -> R.drawable.ic_oc_check
    ToolPresentation.Icon.FILE -> R.drawable.ic_oc_file
}

private val JSON_STRING_REGEX = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

/** `ChatView.TOOL_BODY_MAX_HEIGHT_DP`. */
private val TOOL_BODY_MAX_HEIGHT = 220.dp
