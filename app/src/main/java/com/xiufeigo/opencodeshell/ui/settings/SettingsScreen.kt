package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.BuildConfig
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.components.OcIcon
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The `Dialog size="x-large" variant="settings"` shell
 * (`dialog-settings-v2.tsx:44-113`, verified in `research/05` §5.1).
 *
 * Layout rules transcribed from the vendored CSS (`research/05` §5.3):
 * - container  `min(100vw-32, 980) × min(100vh-92, 600)`
 * - >639dp: `TabsV2 orientation=vertical` → 240dp list (144dp on <640dp… see
 *   below), `layer-01` background, 12dp padding, 4dp gap, 1px right border
 * - ≤639dp: the vendored build turns the side nav into a **top bar** with a
 *   bottom 1px border and hides `settings-v2-nav-footer` — M4 keeps the 5 tabs
 *   reachable via a horizontal scroller so the same 5 values stay addressable.
 */
@Composable
fun SettingsDialogContent(
    store: SettingsStore,
    modifier: Modifier = Modifier,
    extra: SettingsPanelExtras = SettingsPanelExtras(),
) {
    val c = OcTheme.colors
    val settings by store.state.collectAsState()
    var tabValue by remember { mutableStateOf(SettingsTab.Default.value) }
    val tab = SettingsTab.fromValue(tabValue)
    val wide = LocalConfiguration.current.screenWidthDp >= 640

    BoxWithCappedSize(modifier) {
        Column(Modifier.fillMaxSize().background(c.bgBase)) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    SettingsNavList(
                        current = tab,
                        onSelect = { tabValue = it.value },
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    )
                    Box(Modifier.fillMaxHeight().width(1.dp).background(c.borderBase))
                    PanelSlot(tab, settings, store, extra, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                SettingsNavTopBar(
                    current = tab,
                    onSelect = { tabValue = it.value },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderBase))
                PanelSlot(tab, settings, store, extra, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/** Extra wiring a host may supply (client-backed Providers/Models, server IO). */
data class SettingsPanelExtras(
    val providers: List<Pair<String, String>> = emptyList(),
    val models: List<Pair<String, String>> = emptyList(),
    val loading: Boolean = false,
    val serverBridge: ServerBridge? = null,
)

/** Server list read/write hooks (implemented against `ServerConfigStore`). */
interface ServerBridge {
    val servers: List<ServerListItem>
    val activeUrl: String?
    fun upsert(server: ServerListItem, password: String?)
    fun delete(url: String)
    fun setDefault(url: String)
}

@Composable
private fun PanelSlot(
    tab: SettingsTab,
    settings: SettingsStore.Settings,
    store: SettingsStore,
    extra: SettingsPanelExtras,
    modifier: Modifier,
) {
    Box(modifier) {
        when (tab) {
            SettingsTab.GENERAL -> GeneralPanel(settings, store)
            SettingsTab.SHORTCUTS -> ShortcutsPanel()
            SettingsTab.SERVERS -> ServersPanel(extra.serverBridge)
            SettingsTab.PROVIDERS -> ProvidersPanel(extra)
            SettingsTab.MODELS -> ModelsPanel(extra)
        }
    }
}

/**
 * The dialog container: `width: min(100vw − 32, 980)` and
 * `height: min(100vh − 92, 600)` (global `dialog-v2` `x-large`), radius 8 with
 * the `border-base` hairline.
 *
 * The height is **fixed** (not `heightIn`) so the side navigation and the
 * scrolling panel always get the documented box — a max-only constraint would
 * let the shell shrink to its intrinsic content and break the 600dp cap on tall
 * phones.
 */
@Composable
private fun BoxWithCappedSize(modifier: Modifier, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val width = minOf(config.screenWidthDp.dp - 32.dp, 980.dp)
    val height = minOf(config.screenHeightDp.dp - 92.dp, 600.dp)
    Box(
        modifier
            .width(width)
            .height(maxOf(height, 240.dp))
            .background(OcTheme.colors.bgBase, RoundedCornerShape(8.dp))
            .border(0.5.dp, OcTheme.colors.borderBase, RoundedCornerShape(8.dp)),
    ) { content() }
}

// ------------------------------------------------------------------- nav

@Composable
private fun SettingsNavList(
    current: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier
            .background(c.layer01)
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (section in SETTINGS_NAV) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(section.titleResId),
                        color = c.textMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight(500),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (tab in section.tabs) {
                            SettingsTabTrigger(tab, tab == current) { onSelect(tab) }
                        }
                    }
                }
            }
        }
        SettingsNavFooter()
    }
}

/** `<639dp` override: the list becomes a top strip (vendored `05` §5.3). */
@Composable
private fun SettingsNavTopBar(
    current: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Row(
        modifier
            .background(c.layer01)
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = 8.dp, vertical = 8.dp)),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (section in SETTINGS_NAV) {
            for (tab in section.tabs) SettingsTabTrigger(tab, tab == current, compact = true) { onSelect(tab) }
        }
    }
}

/** `tabs-v2-trigger-wrapper`: 28dp tall, radius 4, hover/selected `layer-03`. */
@Composable
private fun SettingsTabTrigger(
    tab: SettingsTab,
    selected: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Row(
        Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .height(28.dp)
            .background(if (selected) c.layer03 else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OcIcon(tab.icon(), if (selected) c.textBase else c.textMuted, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(tab.titleResId()),
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight(440),
            maxLines = 1,
        )
    }
}

/** `settings-v2-nav-footer`: `app.name.desktop` + `v{version}`, 11px faint. */
@Composable
private fun SettingsNavFooter() {
    val c = OcTheme.colors
    Column(Modifier.padding(PaddingValues(start = 4.dp, top = 4.dp, bottom = 4.dp))) {
        Text(
            text = stringResource(R.string.oc_settings_app_name),
            color = c.textFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight(440),
        )
        Text(
            text = "v" + BuildConfig.VERSION_NAME,
            color = c.textFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight(440),
        )
    }
}

/** Shared `settings-v2-tab-header` (title + optional trailing action). */
@Composable
internal fun SettingsPanelHeader(
    titleResId: Int,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    below: @Composable (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    Column(modifier.fillMaxWidth().padding(PaddingValues(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 32.dp))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(titleResId),
                color = c.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight(640),
                modifier = Modifier.weight(1f),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            trailing?.invoke()
        }
        if (below != null) {
            Spacer(Modifier.height(12.dp))
            below()
        }
    }
}

/** `settings-v2-tab-body` — `gap:36px; padding: 0 40px 40px`. */
@Composable
internal fun SettingsPanelBody(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 40.dp, end = 40.dp, bottom = 40.dp)),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) { content() }
}

/** Small pill used for the `Default` / `Current` tags on server rows. */
@Composable
internal fun OcTag(text: String, accent: Boolean = false) {
    val c = OcTheme.colors
    Box(
        Modifier
            .background(if (accent) c.stateInfoBg else c.layer02, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = if (accent) c.stateInfoFg else c.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight(530),
        )
    }
}

/** Small circular accent button used by the add/edit server dialog footer. */
@Composable
internal fun OcButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier
            .height(32.dp)
            .background(
                if (primary) (if (enabled) c.accent else c.layer03) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .then(
                if (primary) Modifier else Modifier.border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp)),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (primary) c.textInverse else c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(500),
        )
    }
}
