package com.xiufeigo.opencodeshell.ui.session.composer

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcEnterOnMount
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/** One entry of the model menu; `provider` is reserved for the M2 provider icon. */
data class ModelOption(
    val id: String,
    val label: String,
    val provider: String? = null,
)

/**
 * The model data available *today*. `ChatViewModel` exposes the configured
 * default model (`GET /config` → `model`, `/config`'s `"provider/modelID"`
 * trimmed to the model id) and nothing else — the provider/model catalog is M2.
 * So the picker gets a one-row menu for the configured model, and an empty list
 * (no config yet) makes the chip explain that the picker lands in M2.
 */
object ModelOptions {

    fun fromLabel(label: String?): List<ModelOption> {
        val trimmed = label?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return listOf(ModelOption(id = trimmed, label = trimmed))
    }
}

/**
 * Composer model chip (`prompt-input-v2.tsx` `PromptInputV2ModelControl`,
 * `research/02` §4.5): 28 dp tall, `max-w-[220px]`, `ProviderIcon` (unavailable
 * here — the asset set has no provider icons yet) + name + `▾`.
 *
 * Always rendered, falling back to the `oc_model_label` ("Model") string like
 * `ChatView.renderModelLabel`; clicking opens the model menu, or the
 * "model picker lands later" toast while the catalog is unavailable.
 *
 * M6 replaces M3's `material3.DropdownMenu` with the v2 `menu-v2-content` shell:
 * `layer-01`, radius 6, `min-w 160` / `max-w 23rem`, `elevation-floating`
 * (`research/03` §3.5) plus the dropdown enter/exit pair — `dropdown-menu-open`
 * `.15s ease-out` with `scale(.96) → 1`, `dropdown-menu-close` `.1s ease-in`
 * (`research/03` §2.1/§3.4). The material menu could not carry either: it paints
 * the M3 surface tokens and hard-codes its own transition.
 */
@Composable
fun ModelChip(
    current: String?,
    options: List<ModelOption>,
    onSelect: (ModelOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    val label = current?.takeIf { it.isNotBlank() } ?: stringResource(R.string.oc_model_label)

    Box(modifier = modifier.onSizeChanged { anchor = it }) {
        Row(
            modifier = Modifier
                .height(CHIP_HEIGHT)
                .widthIn(max = CHIP_MAX_WIDTH)
                .clip(OcTheme.shapes.md)
                .clickable {
                    if (options.isEmpty()) {
                        Toast.makeText(
                            context,
                            R.string.oc_model_picker_soon,
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        expanded = true
                    }
                }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_model),
                contentDescription = stringResource(R.string.oc_model_label),
                tint = c.iconMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = c.textMuted,
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
            // Prefers opening below the chip and flips above it when it would
            // run off the bottom edge — the usual case, because the composer
            // sits at the bottom of the screen. Horizontally it stays inside the
            // window so a wide model id cannot push the menu off-screen.
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
                            .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                            .padding(vertical = 4.dp),
                    ) {
                        for (option in options) {
                            ModelMenuRow(
                                label = option.label,
                                selected = option.id == current,
                                onClick = {
                                    expanded = false
                                    onSelect(option)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `[data-slot=menu-v2-item]` — 13px/440, hover = `overlay-hover`, radius 6. */
@Composable
private fun ModelMenuRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(MENU_ROW_HEIGHT)
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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

/** `style={{height:"28px"}}`. */
private val CHIP_HEIGHT = 28.dp

/** `max-w-[220px]`. */
private val CHIP_MAX_WIDTH = 220.dp

/** `menu-v2-content`: `min-width: 160px; max-width: 23rem`. */
private val MENU_MIN_WIDTH = 160.dp
private val MENU_MAX_WIDTH = 368.dp

/** `[data-slot=menu-v2-item]` row height — a comfortable touch target. */
private val MENU_ROW_HEIGHT = 32.dp

/** Gap between the chip and its menu. */
private val MENU_GAP = 4.dp
