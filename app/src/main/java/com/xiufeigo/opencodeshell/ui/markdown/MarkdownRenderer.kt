package com.xiufeigo.opencodeshell.ui.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.timeline.coalesceLatest
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

/**
 * Compose-native markdown body for the timeline, laid out against
 * `_upstream/packages/session-ui/src/components/markdown.css`
 * (`[data-component="markdown"]`):
 *
 * | element   | upstream                                            | here |
 * |-----------|-----------------------------------------------------|------|
 * | body      | 14 px, line-height 160 %                            | [fontSize] 14 sp, [lineHeight] 22.4 sp |
 * | h1        | 17 / 600, mt 28 mb 12                               | same (sp/dp) |
 * | h2        | 15 / 600, mt 24 mb 10                               | same |
 * | h3–h6     | 13 / 500 (h4+ muted), mt 20 mb 8                    | same |
 * | p         | mb 12 (last: 0)                                     | bottom padding, trimmed on the last block |
 * | ul/ol     | mt 8 mb 12, marker muted, li mb 8                   | same |
 * | blockquote| 0.5 px start border, pad-start 8, my 16, muted      | same |
 * | pre       | mt 12 mb 24, `.shiki` 13 px mono, pad 12, r 6,      | [CodeBlockView]; language label + |
 * |           | 0.5 px border, copy button (2000 ms "copied")       | copy button, tap copies |
 * | table     | my 16, th W500 + base border, td muted border,     | [TableView]; horizontal scroll |
 * |           | cell pad 12, `display: block` horizontal scroll     |      |
 * | inline code | mono 500, bg = text-base 8 %                      | span style |
 * | a         | `--text-interactive-base`, no underline             | [LinkAnnotation.Url], accent colour |
 * | img       | `markdown-image.ts` (local paths read through the    | [MarkdownImageView] via |
 * |           | server, remote ones browser-owned) + `image-preview` | [LocalMarkdownImageReader]; tap = preview |
 * | strong    | weight medium (500), colour = body                  | W500 spans |
 * | hr        | height 0, my 32 (invisible spacing)                 | spacer |
 *
 * Streaming: the markdown string is funnelled through the same 80 ms
 * `coalesceLatest` window the Markwon body used, so a token-per-frame answer
 * re-parses at most once per window, and the parsed block list is memoised on
 * the coalesced text.
 *
 * [muted] is the reasoning-part mode
 * (`message-part.css` `[data-component="reasoning-part"] [data-component="markdown"]`):
 * headings and strong lose their size/weight/colour overrides and inherit the
 * 13 sp muted body.
 */
@Composable
fun OcMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = OcTheme.colors.textBase,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = fontSize * 1.6f,
    muted: Boolean = false,
) {
    // Throttled mirror of the streaming text (see the old `MarkdownText`).
    var rendered by remember { mutableStateOf(markdown) }
    val latest by rememberUpdatedState(markdown)
    LaunchedEffect(Unit) {
        snapshotFlow { latest }.coalesceLatest().collect { rendered = it }
    }
    val blocks = remember(rendered) { MarkdownParser.parse(rendered) }

    val palette = OcMarkdownPalette.current(color)
    Column(modifier = modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            BlockView(
                block = block,
                palette = palette,
                fontSize = fontSize,
                lineHeight = lineHeight,
                muted = muted,
                first = index == 0,
                last = index == blocks.lastIndex,
            )
        }
    }
}

/** Resolved colours for one body (base text colour flows in from the caller). */
private class OcMarkdownPalette(
    val base: Color,
    val muted: Color,
    val faint: Color,
    val link: Color,
    val codeBackground: Color,
    val codeBorder: Color,
    val quoteBorder: Color,
    val tableHeaderBorder: Color,
    val tableCellBorder: Color,
    val keyword: Color,
    val string: Color,
    val constant: Color,
    val primitive: Color,
    val property: Color,
    val type: Color,
) {
    companion object {
        @Composable
        fun current(base: Color): OcMarkdownPalette {
            val c = OcTheme.colors
            return OcMarkdownPalette(
                base = base,
                muted = c.textMuted,
                faint = c.textFaint,
                link = c.textAccent,
                // `color-mix(in oklch, var(--v2-text-text-base) 8%, transparent)`.
                codeBackground = c.textBase.copy(alpha = 0.08f),
                codeBorder = c.borderBase,
                quoteBorder = c.borderBase,
                // `th` bottom border is `border-base`, `td` bottom borders are
                // `border-muted` (`markdown.css` table rules).
                tableHeaderBorder = c.borderBase,
                tableCellBorder = c.borderMuted,
                // v2 syntax ramp, landed by the theme wave per the wave2.1
                // handoff §1 (`oc-2.json` light/dark overrides in v2-ramp terms
                // from `default-primitives.ts`): light pink-800 / green-800 /
                // orange-800 / purple-800, dark pink-400 / green-400 /
                // orange-400 / purple-400, constant teal in both.
                keyword = c.syntaxKeyword,
                string = c.syntaxString,
                constant = c.syntaxConstant,
                primitive = c.syntaxPrimitive,
                property = c.syntaxProperty,
                type = c.syntaxType,
            )
        }
    }

    /** [CodeHighlight.Kind] → the palette entry for one body. */
    fun highlight(kind: CodeHighlight.Kind): Color = when (kind) {
        CodeHighlight.Kind.KEYWORD -> keyword
        CodeHighlight.Kind.STRING -> string
        CodeHighlight.Kind.CONSTANT -> constant
        CodeHighlight.Kind.PRIMITIVE -> primitive
        CodeHighlight.Kind.PROPERTY -> property
        CodeHighlight.Kind.TYPE -> type
        CodeHighlight.Kind.COMMENT -> muted
    }
}

@Composable
private fun BlockView(
    block: MdBlock,
    palette: OcMarkdownPalette,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    muted: Boolean,
    first: Boolean,
    last: Boolean,
) {
    when (block) {
        is MdBlock.Paragraph -> Text(
            text = annotate(block.spans, palette, muted),
            color = palette.base,
            fontSize = fontSize,
            lineHeight = lineHeight,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = if (last) 0.dp else 12.dp),
        )

        is MdBlock.Heading -> HeadingView(block, palette, fontSize, muted, first, last)

        // A-1: `![alt](src)` (`markdown-image.ts`) — the bytes come from
        // `LocalMarkdownImageReader`, the alt text is the fallback.
        is MdBlock.Image -> MarkdownImageView(
            alt = block.alt,
            src = block.src,
            color = palette.base,
            fontSize = fontSize,
            lineHeight = lineHeight,
            first = first,
            last = last,
        )

        is MdBlock.CodeBlock -> CodeBlockView(block, palette, first, last)

        is MdBlock.BulletList -> ListView(block, palette, fontSize, lineHeight, muted, first, last)

        is MdBlock.BlockQuote -> QuoteView(block, palette, fontSize, lineHeight, muted, first, last)

        is MdBlock.Table -> TableView(block, palette, fontSize, lineHeight, muted, first, last)

        MdBlock.ThematicBreak -> Spacer(
            Modifier
                .fillMaxWidth()
                .padding(top = if (first) 0.dp else 32.dp, bottom = if (last) 0.dp else 32.dp),
        )
    }
}

/** h1 17/600 · h2 15/600 · h3 13/500 · h4–h6 13/500 muted (all inherit in [muted] mode). */
@Composable
private fun HeadingView(
    block: MdBlock.Heading,
    palette: OcMarkdownPalette,
    bodySize: TextUnit,
    muted: Boolean,
    first: Boolean,
    last: Boolean,
) {
    val size: TextUnit
    val weight: FontWeight
    val color: Color
    if (muted) {
        size = bodySize
        weight = FontWeight.Normal
        color = palette.base
    } else {
        when (block.level) {
            1 -> {
                size = 17.sp; weight = FontWeight.W600; color = palette.base
            }

            2 -> {
                size = 15.sp; weight = FontWeight.W600; color = palette.base
            }

            3 -> {
                size = 13.sp; weight = FontWeight.W500; color = palette.base
            }

            else -> {
                size = 13.sp; weight = FontWeight.W500; color = palette.muted
            }
        }
    }
    Text(
        text = annotate(block.spans, palette, muted),
        color = color,
        fontSize = size,
        fontWeight = weight,
        lineHeight = size * 1.6f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (first) 0.dp else when (block.level) {
                    1 -> 28.dp
                    2 -> 24.dp
                    else -> 20.dp
                },
                bottom = if (last) 0.dp else when (block.level) {
                    1 -> 12.dp
                    2 -> 10.dp
                    else -> 8.dp
                },
            ),
    )
}

/**
 * Fenced code (`pre` / `.shiki`): 13 sp mono on `layer02`, radius 6, 0.5 dp
 * border, 12 dp padding, horizontal scroll for long lines.
 *
 * Chrome per upstream `markdown.css` `[data-component="markdown-code"]`: the
 * web shows a hover copy button (icon button top-end, 2000 ms "copied"
 * confirmation — `research/02` §3.5b `markdown-copy-button` `data-copied`
 * timeout) and records the language in `data-language`; here that is a visible
 * mono language label (touch has no hover to reveal a hidden button) plus a
 * tap-to-copy icon button, both muted 11 sp. The label row doubles as the
 * graceful-degradation slot for `mermaid` fences: upstream renders those as SVG
 * diagrams (`decorateMermaid`, `markdown-mermaid.ts`); with no diagram renderer
 * on hand the fence keeps its code chrome with a `mermaid` label instead of
 * vanishing (`research/v2_audit/wave2.2a-notes.md`).
 *
 * Highlight: [CodeHighlight.tokenize] maps the fence through the Prism4j
 * grammar for its language into v2 syntax colors (keyword pink / string green /
 * property orange / type purple / constant teal — the `OcColors.syntax*` ramp
 * the theme wave landed per the wave2.1 handoff §1). The tokenize runs inside
 * the same `remember(rendered)` pass as the parse, memoized per `(code,
 * language)` keys plus the palette — and the whole markdown string already
 * arrives through the 80 ms `coalesceLatest` window — so streaming deltas
 * re-tokenize at most once per window from the full text (deterministic:
 * identical prefixes yield identical colors, no incremental-token flicker like
 * the upstream stable/unstable split guards against). `OcPrismTheme` keeps the
 * View-era `ChatView` body on the same syntax-ramp subset (it additionally maps
 * variable/punctuation kinds, which stay plain here — see [CodeHighlight]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeBlockView(
    block: MdBlock.CodeBlock,
    palette: OcMarkdownPalette,
    first: Boolean,
    last: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_RESET_MS)
            copied = false
        }
    }
    val copy: () -> Unit = {
        clipboard.setText(AnnotatedString(block.code))
        copied = true
    }
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // IconButton's own modifier chain, not the `Icon` inside it — M3's
    // `IconButton` is a clickable Box, and Compose announces only the node that
    // carries ACTION_CLICK (a descendant's description is not merged into it).
    val copyLabel = stringResource(R.string.oc_md_copy_code)
    // Tokens are memoized with the syntax colours, so a dark⇄light switch
    // re-resolves without re-tokenizing (the tokenize itself is theme-free).
    // Mermaid fences degrade to a labeled code block (A3 #5): upstream renders
    // those as SVG diagrams (`decorateMermaid`, `markdown-mermaid.ts`); with no
    // diagram renderer on hand the fence keeps its code chrome with its
    // `mermaid` language label instead of vanishing — no extra string needed
    // (`res/**` is theme-wave owned; see `research/v2_audit/wave2.2a-notes.md`).
    val languageLabel = block.language?.trim()?.takeIf { it.isNotEmpty() }
    val highlighted = remember(block.code, block.language, palette) {
        highlightAnnotated(block.code, block.language, palette)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 12.dp, bottom = if (last) 0.dp else 24.dp)
            .clip(OcTheme.shapes.md)
            .background(OcTheme.colors.layer02)
            .border(0.5.dp, palette.codeBorder, OcTheme.shapes.md)
            .combinedClickable(
                onClick = {},
                onLongClick = copy,
                onLongClickLabel = copyLabel,
            ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (languageLabel != null || copied) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 4.dp),
                ) {
                    if (languageLabel != null) {
                        Text(
                            text = languageLabel,
                            color = palette.muted,
                            fontFamily = OcTheme.monoFont,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (copied) {
                        Text(
                            text = stringResource(R.string.oc_md_copied),
                            color = palette.muted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(OcTheme.shapes.sm)
                                .background(OcTheme.colors.layer01)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                    IconButton(
                        onClick = copy,
                        modifier = Modifier
                            .size(28.dp)
                            .semantics { contentDescription = copyLabel },
                    ) {
                        Icon(
                            painter = painterResource(
                                if (copied) R.drawable.ic_oc_check else R.drawable.ic_oc_copy
                            ),
                            contentDescription = null,
                            tint = palette.muted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Text(
                text = highlighted,
                color = palette.base,
                fontFamily = OcTheme.monoFont,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = if (languageLabel != null || copied) 0.dp else 12.dp,
                        bottom = 12.dp,
                    ),
            )
        }
    }
}

/**
 * Token spans → [AnnotatedString]. Plain gaps keep the body colour (a plain
 * `Text` default), so un-highlighted languages, shell blocks and oversized
 * blocks render exactly like the pre-highlight body.
 */
private fun highlightAnnotated(
    code: String,
    language: String?,
    palette: OcMarkdownPalette,
): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    val spans = CodeHighlight.tokenize(code, language)
    if (spans.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code.substring(0, spans.first().start.coerceIn(0, code.length)))
        for ((index, span) in spans.withIndex()) {
            val start = span.start.coerceIn(0, code.length)
            val end = span.end.coerceIn(start, code.length)
            withStyle(SpanStyle(color = palette.highlight(span.kind))) {
                append(code.substring(start, end))
            }
            val nextStart = spans.getOrNull(index + 1)?.start?.coerceIn(end, code.length)
                ?: code.length
            if (nextStart > end) append(code.substring(end, nextStart))
        }
    }
}

/**
 * ul/ol: mt 8 mb 12, `padding-inline-start` 32 px (ul) / 2.25 rem (ol), the
 * marker in `--v2-text-text-muted` (`li::marker`), li mb 8. The marker column
 * (bullet / `1.`) is separate text so wrapped lines align with the content.
 */
@Composable
private fun ListView(
    block: MdBlock.BulletList,
    palette: OcMarkdownPalette,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    muted: Boolean,
    first: Boolean,
    last: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 8.dp, bottom = if (last) 0.dp else 12.dp),
    ) {
        block.items.forEachIndexed { index, spans ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (index == block.items.lastIndex) 0.dp else 8.dp),
            ) {
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (block.ordered) "${index + 1}." else "•",
                    color = palette.muted,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    maxLines = 1,
                    modifier = Modifier.width(if (block.ordered) 24.dp else 20.dp),
                )
                Text(
                    text = annotate(spans, palette, muted),
                    color = palette.base,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * GFM pipe table (`markdown.css` `table`): `margin: 16px 0`, base-size text;
 * `th` = W500 body colour with a `border-base` bottom border, `td` a
 * `border-muted` bottom border, every cell 12 dp padding, start-aligned,
 * top-aligned (`text-align: start`, `vertical-align: top`). Column widths are
 * equal and fixed ([tableColumnWidth], viewport-sized via `BoxWithConstraints`
 * with a minimum so wide tables scroll) — `weight(1f)` must not be used here
 * because the horizontal-scroll wrapper measures with unbounded width, where
 * weighted children resolve to zero width and the table renders blank. Cell
 * bottom rules are full-width 0.5 dp lines (upstream `border-bottom` spans the
 * table). Wide tables scroll horizontally (upstream
 * `table { display: block; overflow-x: auto }`); the scroll wrapper is outside
 * the column grid so header and body rows always share the same column widths.
 * Cells carry their inline spans (bold/code/link), so the header renders
 * through [annotate] with [forceBold] and body cells plain.
 */
@Composable
private fun TableView(
    block: MdBlock.Table,
    palette: OcMarkdownPalette,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    muted: Boolean,
    first: Boolean,
    last: Boolean,
) {
    val columns = block.header.size
    if (columns == 0) return
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 16.dp, bottom = if (last) 0.dp else 16.dp),
    ) {
        val colWidth = tableColumnWidth(maxWidth, columns)
        val gridWidth = colWidth * columns.toFloat()
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Row {
                block.header.forEach { spans ->
                    Text(
                        text = annotate(spans, palette, muted, forceBold = true),
                        color = palette.base,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontWeight = FontWeight.W500,
                        modifier = Modifier
                            .width(colWidth)
                            .padding(12.dp),
                    )
                }
            }
            Box(
                Modifier
                    .width(gridWidth)
                    .height(0.5.dp)
                    .background(palette.tableHeaderBorder),
            )
            block.rows.forEach { row ->
                Row {
                    repeat(columns) { index ->
                        val spans = row.getOrNull(index) ?: emptyList()
                        Text(
                            text = annotate(spans, palette, muted),
                            color = palette.base,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            modifier = Modifier
                                .width(colWidth)
                                .padding(12.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .width(gridWidth)
                        .height(0.5.dp)
                        .background(palette.tableCellBorder),
                )
            }
        }
    }
}

/**
 * blockquote: 0.5 px `border-base` start border, 8 dp start padding, 16 dp
 * vertical margin, muted text. Children render recursively (nested quotes and
 * lists inside a quote keep working).
 */
@Composable
private fun QuoteView(
    block: MdBlock.BlockQuote,
    palette: OcMarkdownPalette,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    muted: Boolean,
    first: Boolean,
    last: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (first) 0.dp else 16.dp, bottom = if (last) 0.dp else 16.dp),
    ) {
        Box(
            Modifier
                .width(0.5.dp)
                .fillMaxHeight()
                .background(palette.quoteBorder),
        )
        Column(Modifier.padding(start = 8.dp)) {
            block.children.forEachIndexed { index, child ->
                BlockView(
                    block = child,
                    palette = palette,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    muted = true,
                    first = index == 0,
                    last = index == block.children.lastIndex,
                )
            }
        }
    }
}

/**
 * Inline spans → [AnnotatedString]: strong = W500 body colour (upstream
 * `strong { font-weight: var(--font-weight-medium) }`, not bold-face), inline
 * code = mono W500 on the 8 % text-base wash, links = accent with no
 * underline (the web underlines on hover only) carried by [LinkAnnotation.Url]
 * so `Text` handles the tap itself. [forceBold] renders every span W500 — the
 * table-header path (`th { font-weight: medium }` applies to the whole cell).
 */
@Composable
private fun annotate(
    spans: List<MdSpan>,
    palette: OcMarkdownPalette,
    muted: Boolean,
    forceBold: Boolean = false,
): AnnotatedString =
    buildAnnotatedString {
        for (span in spans) {
            val style = SpanStyle(
                color = palette.base,
                fontWeight = if ((forceBold || span.bold) && !muted) FontWeight.W500 else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) OcTheme.monoFont else null,
                background = if (span.code) palette.codeBackground else Color.Unspecified,
            )
            if (span.link != null) {
                withLink(LinkAnnotation.Url(span.link)) {
                    withStyle(style.copy(color = palette.link)) { append(span.text) }
                }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }

/** `data-copied="true"` lifetime of the web copy button (`research/02` §3.5b). */
private const val COPIED_RESET_MS = 2000L
