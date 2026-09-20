package com.xiufeigo.opencodeshell.ui.session.timeline

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Session error card (`research/02` §2.2 `.error-card` + §3.3
 * `tool-error-card.css`), rendered as the trailing timeline row when
 * `runStatus == ERROR` or the session carries an error.
 *
 * Numbers taken from §3.3 / §2.2:
 * - card surface `layer-01`, `md` radius, `0.5 dp` `border-base`;
 * - vertical padding **8 dp**, line padding **12 dp**;
 * - error body indented **24 dp** on the left, `margin-bottom 8 dp`,
 *   `max-height: 240px` with its own scroll, `user-select: text`;
 * - `[tool-error-card-icon]` is coloured with the accent — here
 *   `stateDangerFg`, which is the danger accent for a session failure.
 *
 * Two upstream details are intentionally **not** copied: the hover-only copy
 * button (no hover on touch) and the "left colour bar" some earlier plans
 * imagined — §3.1 ruled that no left bar exists in this design system, so the
 * danger accent rides on the **icon** (documented again in the M2 status note).
 */
@Composable
fun SessionErrorCard(
    message: String,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "error-chevron")
    // Failure text can be a wall of JSON; the header stays a single line and the
    // full body lives behind the chevron.
    val summary = remember(message) {
        message.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: message.trim()
    }
    val hasBody = message.trim().isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OcTheme.shapes.md)
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasBody, role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_alert),
                contentDescription = null,
                tint = c.stateDangerFg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.oc_error_title),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
                maxLines = 1,
            )
            if (summary.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = summary,
                    color = c.textMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = stringResource(R.string.oc_error_details),
                tint = c.textFaint,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = expanded && hasBody,
            enter = expandVertically(animationSpec = OcMotion.expand) + fadeIn(OcMotion.fade),
            exit = shrinkVertically(animationSpec = OcMotion.expand) + fadeOut(OcMotion.fade),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, bottom = 8.dp)
                    .heightIn(max = ERROR_BODY_MAX_HEIGHT)
                    .clip(OcTheme.shapes.md)
                    .background(c.layer02)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = message.trim(),
                        color = c.stateDangerFg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/** `.error-card { max-height: 240px }` (`research/02` §2.2). */
private val ERROR_BODY_MAX_HEIGHT = 240.dp
