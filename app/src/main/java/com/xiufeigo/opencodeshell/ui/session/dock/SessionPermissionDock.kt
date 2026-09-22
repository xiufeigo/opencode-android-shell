package com.xiufeigo.opencodeshell.ui.session.dock

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Permission confirmation dock, 1:1 with the upstream
 * `session-permission-dock.tsx` (`_upstream/packages/app/src/session/requests/`)
 * mounted inside the composer region above the composer
 * (`session-composer-region.tsx` `<SessionPermissionDock/>` before the prompt).
 *
 * Two stacked surfaces, mirroring `DockPrompt`/`DockShell`/`DockTray`
 * (`packages/session-ui/src/components/dock-prompt.tsx` +
 * `packages/ui/src/components/dock-surface.css`) and the permission slots in
 * `packages/session-ui/src/components/message-part.css:785-895`:
 * - **shell** (`surface-raised-stronger-non-alpha` ≈ `layer01`, radius 12,
 *   padding `12 12 0`, row grid `20px 1fr` + `column-gap 8`): warning icon
 *   (`--icon-warning-base` ≈ `stateWarningFg`) + title 14/500, optional tool
 *   description hint 14/400 `textMuted`, patterns as mono 14px rows
 *   (`gap 6`, `margin-bottom 16`). The shell has no border upstream
 *   (`--shadow-xs-border`); Compose uses the established 0.5 dp
 *   `borderMuted` hairline (same convention as `OcNeutralButton`).
 * - **tray** (`background-base` = `bgBase`, 1 px `border-weak-base` ≈
 *   `borderMuted`, radius 12) carries `margin-top: -24px` +
 *   `padding: 32px 8px 8px` (`[data-slot="permission-footer"]`), so its top
 *   24 dp hides behind the opaque shell (`z-index 10` over `0`) and the
 *   actions land 8 dp below the shell edge. Actions (`gap 8`): Deny =
 *   `ghost` (transparent), Allow always = `neutral` (`buttonNeutral` +
 *   hairline), Allow once = `submit` (contrast/inverse solid). Buttons are
 *   `button-v2` size normal: height 28, padding-x 12 (footer override),
 *   radius 6, 13/530/20.
 *
 * The shell/tray stack is a tiny custom [Layout] because upstream's negative
 * margin changes the laid-out height (a plain `offset` would leave 24 dp of
 * dead space below the tray); placement order puts the shell on top, exactly
 * like the `z-index` pair. All px → dp 1:1 (`research/02` §0.5). Copy lives
 * in `oc_permission_*` (upstream `notification.permission.title`,
 * `ui.permission.deny|allowOnce|allowAlways`,
 * `settings.permissions.tool.*.description`).
 */
@Composable
fun SessionPermissionDock(
    request: OpenCodeClient.PermissionRequest,
    responding: Boolean,
    onDecide: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val surfaceShape = RoundedCornerShape(12.dp)
    // `settings.permissions.tool.<permission>.description`; unknown tools hide
    // the hint (upstream returns "" when the key does not resolve).
    val hint = toolDescriptionRes(request.permission)?.let { stringResource(it) }
    val trayOverlap = 24.dp

    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            // -------------------------------------------------------- shell
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(surfaceShape)
                    .background(c.layer01)
                    .border(0.5.dp, c.borderMuted, surfaceShape)
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp),
            ) {
                // `[data-slot="permission-row"][data-variant="header"]` —
                // grid `20px 1fr`, `column-gap 8`, centered.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_oc_alert),
                            contentDescription = null,
                            tint = c.stateWarningFg,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.oc_permission_title),
                        color = c.textBase,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W500,
                        lineHeight = 21.sp, // --line-height-large: 150% of 14px
                    )
                }
                // `[data-slot="permission-content"]` — gap 4, 16 below header.
                if (hint != null || request.patterns.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (hint != null) {
                            PermissionRow {
                                Text(
                                    text = hint,
                                    color = c.textMuted,
                                    fontSize = 14.sp,
                                    lineHeight = 21.sp,
                                )
                            }
                        }
                        if (request.patterns.isNotEmpty()) {
                            // `margin-top 8` (+ the content gap), `margin-bottom 16`.
                            PermissionRow(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    request.patterns.forEach { pattern ->
                                        Text(
                                            text = pattern,
                                            color = c.textBase,
                                            fontFamily = OcTheme.monoFont,
                                            fontSize = 14.sp,
                                            lineHeight = 21.sp,
                                        )
                                    }
                                }
                            }
                        }
                        // Route C, `always` transparency: v1 answers the ask with
                        // `prefixes` (`always[]`) — the patterns a "always"
                        // reply persists — which may be wider than the
                        // `patterns[]` shown above. Upstream's dock renders only
                        // `resources` (= our `patterns`), so a user cannot see
                        // what "Allow always" actually remembers; this row spells
                        // it out when the two lists differ.
                        val remembered = permissionAlwaysHint(request)
                        if (remembered.isNotEmpty()) {
                            PermissionRow {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = stringResource(R.string.oc_permission_always_hint),
                                        color = c.textMuted,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                    remembered.forEach { pattern ->
                                        Text(
                                            text = pattern,
                                            color = c.textMuted,
                                            fontFamily = OcTheme.monoFont,
                                            fontSize = 13.sp,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // --------------------------------------------------------- tray
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(surfaceShape)
                    .background(c.bgBase)
                    .border(1.dp, c.borderMuted, surfaceShape)
                    .padding(start = 8.dp, end = 8.dp, top = 32.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                // Upstream order: Deny (ghost), Allow always (neutral), Allow
                // once (submit) — the primary action stays rightmost.
                DockButton(
                    label = stringResource(R.string.oc_permission_reject),
                    variant = DockButtonVariant.Ghost,
                    enabled = !responding,
                    onClick = { onDecide("reject") },
                )
                Spacer(Modifier.width(8.dp))
                DockButton(
                    label = stringResource(R.string.oc_permission_allow_always),
                    variant = DockButtonVariant.Neutral,
                    enabled = !responding,
                    onClick = { onDecide("always") },
                )
                Spacer(Modifier.width(8.dp))
                DockButton(
                    label = stringResource(R.string.oc_permission_allow_once),
                    variant = DockButtonVariant.Submit,
                    enabled = !responding,
                    onClick = { onDecide("once") },
                )
            }
        },
    ) { measurables, constraints ->
        val overlapPx = trayOverlap.roundToPx()
        val shell = measurables[0].measure(constraints)
        val tray = measurables[1].measure(constraints)
        // The tray tucks `overlapPx` under the shell; total height shrinks by
        // the same amount (upstream `margin-top: -24px`). The tray is placed
        // first so the shell paints over the overlap (`z-index 10` vs `0`).
        layout(shell.width, shell.height + tray.height - overlapPx) {
            tray.place(0, shell.height - overlapPx)
            shell.place(0, 0)
        }
    }
}

/** Content row under the icon column: `grid 20px 1fr`, `column-gap 8`. */
@Composable
private fun PermissionRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.width(20.dp))
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

private enum class DockButtonVariant { Ghost, Neutral, Submit }

/**
 * `button-v2` size normal (`button.css`): height 28, radius 6, 13/530/20; the
 * permission footer overrides padding-x to 12. Variants: `ghost` transparent,
 * `neutral` `buttonNeutral` + the project's hairline stand-in for
 * `--v2-elevation-button-neutral`, `submit` solid contrast (inverse bg,
 * `textInverse` fg). Disabled renders at 50 % like upstream.
 */
@Composable
private fun DockButton(
    label: String,
    variant: DockButtonVariant,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(28.dp)
            .clip(shape)
            .background(
                when (variant) {
                    DockButtonVariant.Ghost -> Color.Transparent
                    DockButtonVariant.Neutral -> c.buttonNeutral
                    DockButtonVariant.Submit -> c.inverse
                },
            )
            .then(
                when (variant) {
                    DockButtonVariant.Neutral -> Modifier.border(0.5.dp, c.borderMuted, shape)
                    else -> Modifier
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when (variant) {
                DockButtonVariant.Submit -> c.textInverse
                else -> c.textBase
            },
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}

/** `settings.permissions.tool.<name>.description`; null hides the hint. */
@StringRes
private fun toolDescriptionRes(permission: String): Int? = when (permission) {
    "read" -> R.string.oc_permission_tool_read
    "edit" -> R.string.oc_permission_tool_edit
    "glob" -> R.string.oc_permission_tool_glob
    "grep" -> R.string.oc_permission_tool_grep
    "list" -> R.string.oc_permission_tool_list
    "bash" -> R.string.oc_permission_tool_bash
    "task" -> R.string.oc_permission_tool_task
    "skill" -> R.string.oc_permission_tool_skill
    "lsp" -> R.string.oc_permission_tool_lsp
    "todowrite" -> R.string.oc_permission_tool_todowrite
    "webfetch" -> R.string.oc_permission_tool_webfetch
    "websearch" -> R.string.oc_permission_tool_websearch
    "external_directory" -> R.string.oc_permission_tool_external_directory
    "doom_loop" -> R.string.oc_permission_tool_doom_loop
    else -> null
}

/**
 * Patterns an "allow always" reply would persist and that the user cannot
 * already infer from [OpenCodeClient.PermissionRequest.patterns].
 *
 * v1's reply body takes `prefixes` (`always[]`) while the request's own
 * `patterns[]` is what matched *this* ask; the two differ whenever the server
 * widens the rule (e.g. ask for `cmd.exe /c dir`, remember `cmd.exe /c *`).
 * Pure so the rule is JVM-testable without a Compose runtime.
 */
internal fun permissionAlwaysHint(request: OpenCodeClient.PermissionRequest): List<String> =
    request.always.filter { it.isNotBlank() && it !in request.patterns }

