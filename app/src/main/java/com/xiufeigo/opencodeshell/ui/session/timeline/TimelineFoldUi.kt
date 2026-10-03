package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.markdown.OcMarkdown
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * U-4 fold-threshold UI: the shared expander row and the foldable markdown
 * body. Policy lives in [TimelineFold] (pure, JVM-tested); this file only
 * composes.
 *
 * Folding never hides live content: every entry takes a `streaming`/`running`
 * gate and renders the full body while the turn streams.
 */
@Composable
fun FoldToggleRow(
    hiddenLines: Int,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, label = "fold-chevron")
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the chevron `Icon` inside it.
    val toggleLabel = stringResource(R.string.oc_toggle_tool)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onToggle(!expanded) }
            .semantics { contentDescription = toggleLabel }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_chevron_down),
            contentDescription = null,
            tint = c.textFaint,
            modifier = Modifier.size(16.dp).rotate(rotation),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (!expanded) {
                pluralStringResource(R.plurals.oc_timeline_more_lines, hiddenLines, hiddenLines)
            } else {
                stringResource(R.string.oc_timeline_show_less)
            },
            color = c.accent,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Assistant/reasoning markdown body with the U-4 head+expander treatment:
 * short bodies render exactly as before (a bare `OcMarkdown` — same layout,
 * same selection behaviour); long ones render the fence-safe head
 * ([TimelineFold.markdownHead]) plus [FoldToggleRow].
 *
 * @param stateKey `rememberSaveable` key of the expand flag (the part id).
 * @param streaming live tail — never folded, rendered in full.
 */
@Composable
fun FoldableMarkdown(
    markdown: String,
    stateKey: String,
    modifier: Modifier = Modifier,
    color: Color = OcTheme.colors.textBase,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = fontSize * 1.6f,
    muted: Boolean = false,
    streaming: Boolean = false,
) {
    val long = remember(stateKey, markdown, streaming) {
        !streaming && TimelineFold.isLongText(markdown)
    }
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    val head = remember(markdown) { TimelineFold.markdownHead(markdown) }
    val fold = remember(markdown) { TimelineFold.foldText(markdown) }
    if (!long) {
        SelectionContainer {
            OcMarkdown(
                markdown = markdown,
                modifier = modifier,
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                muted = muted,
            )
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            OcMarkdown(
                markdown = if (expanded) markdown else head,
                color = color,
                fontSize = fontSize,
                lineHeight = lineHeight,
                muted = muted,
            )
        }
        FoldToggleRow(
            hiddenLines = fold.hiddenLines,
            expanded = expanded,
            onToggle = { expanded = it },
        )
    }
}
