package com.xiufeigo.opencodeshell.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcFonts
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay

/** How often the `MEM` card re-reads the runtime heap, upstream's 1 s poll. */
private const val HEAP_TICK_MS = 1_000L

/**
 * B-17: the Android debug bar — the `debugBar.toggle` surface.
 *
 * Upstream's bar (`_upstream/packages/app/src/shell/debug/debug-bar.tsx`) is a
 * floating `fixed bottom-3 right-3` card mounted by the shell on every route and
 * flipped by the `debugBar.toggle` command. The Android form is the same idea in
 * the layout the shell already has: one **full-width bar** that sits above the
 * bottom navigation, toggled by the palette row / the rebindable `debugBar.toggle`
 * chord, and closable from its own ✕.
 *
 * Content, and what each card can actually know here:
 *
 * - `TPS` / `TTFT` / `TTFA` / `E2E` — upstream's four provider cards, verbatim
 *   labels, tips and thresholds ([ProviderMetrics]). Live frames are the primary
 *   source; the history projection ([projectedProviderMetrics]) covers E2E when
 *   nothing live has landed. A metric neither source can produce renders the
 *   localized `debugBar.na`.
 * - `TOK` — the session's cumulative token total, which upstream has no card
 *   for. It is the one figure `session.usage.recorded` / `session.usage.updated`
 *   carry (`_upstream/packages/schema/src/session-event.ts:148-168` →
 *   `ChatStore.ChatState.usageTokens`); those frames have **no request timing**,
 *   so they cannot feed E2E/TTFT/TTFA/TPS and are used for this card instead.
 * - `MEM` — `Runtime` heap vs. its ceiling ([heapUsage]).
 * - `INP` — always `debugBar.na`. **Deliberate deviation:** INP is a Web Vitals
 *   interaction metric built on `PerformanceObserver("event")`
 *   (`debug-bar.tsx:440-460`); Android has no equivalent observation, so the card
 *   stays and reports that it has nothing rather than the panel losing upstream's
 *   shape. The rest of upstream's diagnostics grid (NAV / FPS / FRAME / JANK /
 *   LONG / DELAY / CLS / DIR / FOCUS) is omitted for the same reason — see
 *   [ProviderMetrics]' KDoc for the whole deviation list.
 *
 * The bar is only composed while it is open, so the heap poll's lifetime *is* the
 * visible gate.
 */
@Composable
fun DebugBar(
    live: ProviderMetrics?,
    messages: List<ChatStore.UiMessage>,
    sessionTokens: Long?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OcTheme.colors
    val na = stringResource(R.string.debugBar_na).uppercase()
    val metrics = debugBarMetrics(live, messages)

    val runtime = remember { Runtime.getRuntime() }
    var heap by remember {
        mutableStateOf(heapUsage(runtime.totalMemory(), runtime.freeMemory(), runtime.maxMemory()))
    }
    LaunchedEffect(runtime) {
        while (true) {
            heap = heapUsage(runtime.totalMemory(), runtime.freeMemory(), runtime.maxMemory())
            delay(HEAP_TICK_MS)
        }
    }

    val closeLabel = stringResource(R.string.common_close)
    // Upstream labels the aside itself (`debug-bar.tsx:529`). Not a merged node:
    // each cell keeps its own text for a screen reader, and the container's name
    // says what the strip is.
    val barLabel = stringResource(R.string.debugBar_providerAriaLabel)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.layer02)
            .semantics { contentDescription = barLabel },
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderBase))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            DebugBarCell(
                label = stringResource(R.string.debugBar_tps_label),
                value = formatTps(metrics?.tps) ?: na,
                dim = metrics?.tps == null,
            )
            DebugBarCell(
                label = stringResource(R.string.debugBar_ttft_label),
                value = formatDuration(metrics?.ttft) ?: na,
                dim = metrics?.ttft == null,
            )
            DebugBarCell(
                label = stringResource(R.string.debugBar_ttfa_label),
                value = formatDuration(metrics?.ttfa) ?: na,
                dim = metrics?.ttfa == null,
            )
            DebugBarCell(
                label = stringResource(R.string.debugBar_e2e_label),
                value = formatDuration(metrics?.e2e) ?: na,
                dim = metrics?.e2e == null,
            )
            DebugBarCell(
                label = stringResource(R.string.debugBar_tok_label),
                value = formatTokens(sessionTokens) ?: na,
                dim = sessionTokens == null,
            )
            DebugBarCell(
                label = stringResource(R.string.debugBar_mem_label),
                value = formatHeapPercent(heap) ?: na,
                dim = heap == null,
            )
            DebugBarCell(
                label = stringResource(R.string.debugBar_inp_label),
                value = na,
                dim = true,
            )
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\u2715",
                    color = colors.textFaint,
                    fontSize = 11.sp,
                    fontFamily = OcFonts.inter,
                )
            }
        }
    }
}

/**
 * One card: a 9 sp uppercase label over a mono value, `dim` when the value is
 * `debugBar.na`. Upstream's `Cell` (`debug-bar.tsx:76-132`) without its tooltip
 * layer — the tips are long desktop hover strings and this bar has no hover; the
 * label is the accessible name instead.
 */
@Composable
private fun DebugBarCell(label: String, value: String, dim: Boolean) {
    val colors = OcTheme.colors
    val valueColor: Color = if (dim) colors.textFaint else colors.textBase
    Column(
        modifier = Modifier.widthIn(min = 52.dp).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label.uppercase(),
            color = colors.textFaint,
            fontSize = 9.sp,
            fontWeight = FontWeight.Black,
            fontFamily = OcFonts.inter,
            textAlign = TextAlign.Center,
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OcFonts.mono,
            textAlign = TextAlign.Center,
        )
    }
}
