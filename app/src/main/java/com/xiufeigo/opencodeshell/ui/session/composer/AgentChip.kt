package com.xiufeigo.opencodeshell.ui.session.composer

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcEnterOnMount
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * W5-C B4: the composer's **agent chip**.
 *
 * Upstream renders one `ComposerEditorConfiguredSelect` per control
 * (`composer/editor/editor.tsx:284-292` agent, `:293-307` model + variant) — the
 * agent control is the `agent.cycle` command from `composer/commands.tsx:65-73`
 * with its options coming from `local.agent`. Here the options are the
 * `GET /agent` `mode == "primary"` entries ([OpenCodeClient.Agent.selectable]),
 * because only a primary agent can own a session.
 *
 * Chrome matches [ModelChip] (same `menu-v2-content` shell, same dropdown motion):
 * 28 dp tall, `max-w-[220px]`, icon + name + `▾`, muted while following the
 * server default.
 */
@Composable
fun AgentChip(
    current: String?,
    agents: List<OpenCodeClient.Agent>,
    overrideActive: Boolean,
    onSelect: (String) -> Unit,
    onUseDefault: () -> Unit,
    modifier: Modifier = Modifier,
    /** `/agent` from the command menu opens this menu (see [ModelChip.openRequest]). */
    openRequest: Int = 0,
) {
    val c = OcTheme.colors
    val options = remember(agents) { OpenCodeClient.Agent.selectable(agents) }
    if (options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    val label = current?.takeIf { it.isNotBlank() } ?: stringResource(R.string.oc_composer_agent)

    LaunchedEffect(openRequest) {
        if (openRequest > 0) expanded = true
    }

    Box(modifier = modifier.onSizeChanged { anchor = it }) {
        Row(
            modifier = Modifier
                .height(CHIP_HEIGHT)
                .widthIn(max = CHIP_MAX_WIDTH)
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_task),
                contentDescription = stringResource(R.string.oc_composer_choose_agent),
                tint = c.iconMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = if (overrideActive) c.textBase else c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(14.dp),
            )
        }

        if (expanded) {
            val provider = remember(anchor, gapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val below = anchorBounds.bottom + gapPx
                        val fitsBelow = below + popupContentSize.height <= windowSize.height
                        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        return IntOffset(
                            x = anchorBounds.left.coerceIn(0, maxX),
                            y = if (fitsBelow) below else anchorBounds.top - gapPx - popupContentSize.height,
                        )
                    }
                }
            }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                OcEnterOnMount(overlay = OcMotion.Overlay.DROPDOWN) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = MENU_MIN_WIDTH, max = MENU_MAX_WIDTH)
                            .clip(OcTheme.shapes.md)
                            .background(c.layer01)
                            .border(0.5.dp, c.borderBase, OcTheme.shapes.md),
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT)) {
                            items(items = options, key = { agent -> agent.name }) { agent ->
                                AgentRow(
                                    label = agent.name,
                                    description = agent.description,
                                    selected = overrideActive && agent.name == current,
                                    onClick = {
                                        expanded = false
                                        onSelect(agent.name)
                                    },
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(c.borderMuted),
                        )
                        AgentRow(
                            label = stringResource(R.string.oc_composer_agent_default),
                            description = null,
                            selected = !overrideActive,
                            onClick = {
                                expanded = false
                                onUseDefault()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** `[data-slot=menu-v2-item]` — name + muted description, check on the active row. */
@Composable
private fun AgentRow(
    label: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .height(MENU_ROW_HEIGHT)
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (selected) c.textBase else c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            description?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    color = c.textFaint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_check),
                contentDescription = null,
                tint = c.accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Matches [ModelChip]: 28 dp tall, `max-w-[220px]`. */
private val CHIP_HEIGHT = 28.dp
private val CHIP_MAX_WIDTH = 220.dp

/** `menu-v2-content`: `min-width: 160px; max-width: 23rem`. */
private val MENU_MIN_WIDTH = 160.dp
private val MENU_MAX_WIDTH = 368.dp

/** Scroll cap so a 200-agent server cannot push the menu off screen. */
private val LIST_MAX_HEIGHT = 260.dp

/** `[data-slot=menu-v2-item]` row height — a comfortable touch target. */
private val MENU_ROW_HEIGHT = 40.dp

/** Gap between the chip and its menu. */
private val MENU_GAP = 4.dp
