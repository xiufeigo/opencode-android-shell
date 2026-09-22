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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import kotlinx.coroutines.flow.StateFlow
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
 *
 * **W4-D:** the nav column gained upstream's settings **search**
 * (`settings/search.tsx`) above the tab list, and the root tabs upstream has but
 * Android does not implement are rendered through [SETTINGS_NAV] as disabled
 * entries instead of disappearing.
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
    var query by remember { mutableStateOf("") }
    val wide = LocalConfiguration.current.screenWidthDp >= 640

    BoxWithCappedSize(modifier) {
        Column(Modifier.fillMaxSize().background(c.bgBase)) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    SettingsNavList(
                        current = tab,
                        onSelect = { tabValue = it.value },
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    )
                    Box(Modifier.fillMaxHeight().width(1.dp).background(c.borderBase))
                    PanelSlot(tab, settings, store, extra, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                SettingsNavTopBar(
                    current = tab,
                    onSelect = { tabValue = it.value },
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderBase))
                PanelSlot(tab, settings, store, extra, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

/**
 * Extra wiring a host may supply (client-backed Providers/Models, server IO).
 *
 * The two catalog flows are `StateFlow`s, not snapshots: the settings dialog is
 * pushed from a key handler *before* the `GET /provider` round-trip may have
 * landed, so a snapshot would freeze the panel on its empty state forever. A
 * null flow (previews, tests) renders the documented empty states.
 */
data class SettingsPanelExtras(
    /** `ChatViewModel.availableProviders` — `GET /provider` catalog. */
    val providers: StateFlow<OpenCodeClient.Providers?>? = null,
    /** `ChatViewModel.selectedModel` — the effective model (override or default). */
    val selectedModel: StateFlow<Pair<String, String>?>? = null,
    val serverBridge: ServerBridge? = null,
)

/** Server list read/write hooks (implemented against `ServerConfigStore`). */
interface ServerBridge {
    /** Changes whenever the underlying server list is mutated. */
    val revision: StateFlow<Int>
    val servers: List<ServerListItem>
    val activeUrl: String?
    fun upsert(server: ServerListItem, password: String?)

    /**
     * Save of the add/edit dialog: [originalUrl] is the row being edited
     * (null for add). When the URL itself changed, the old row is replaced —
     * and the default flag follows the row — instead of producing a duplicate
     * entry (upstream `dialog-server-v2` semantics).
     */
    fun replace(originalUrl: String?, server: ServerListItem, password: String?)
    fun delete(url: String)
    fun setDefault(url: String)
}

/**
 * Every tab's page. Kept as one `when` so a future tab cannot be added to the
 * navigation without a page (and a missing branch is a compile error, not an
 * empty screen).
 *
 * The read-only server pages (projects / worktrees / extensions) take the
 * bridge's optional [SettingsServerData] capability; a host that passes a plain
 * [ServerBridge] gets the documented "no server" state instead of a blank page.
 */
@Composable
private fun PanelSlot(
    tab: SettingsTab,
    settings: SettingsStore.Settings,
    store: SettingsStore,
    extra: SettingsPanelExtras,
    modifier: Modifier,
) {
    val serverData = extra.serverBridge as? SettingsServerData
    Box(modifier) {
        when (tab) {
            SettingsTab.GENERAL -> GeneralPanel(settings, store)
            SettingsTab.APPEARANCE -> AppearancePanel(settings, store)
            SettingsTab.NOTIFICATIONS -> NotificationsPanel(settings, store)
            SettingsTab.SHORTCUTS -> ShortcutsPanel(settings, store)
            SettingsTab.PROJECTS -> ProjectsPanel(serverData)
            SettingsTab.WORKSPACES -> WorktreesPanel(serverData)
            SettingsTab.PROVIDERS -> ProvidersPanel(extra, store)
            SettingsTab.MODELS -> ModelsPanel(extra, store)
            SettingsTab.EXTENSIONS -> ExtensionsPanel(serverData)
            SettingsTab.SERVERS -> ServersPanel(extra.serverBridge)
            SettingsTab.ABOUT -> AboutPanel()
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
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier
            .background(c.layer01)
            .padding(12.dp),
    ) {
        SettingsSearchField(query = query, onQueryChange = onQueryChange)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (query.isBlank()) {
                for (section in SETTINGS_NAV) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        section.titleResId?.let { titleResId ->
                            Text(
                                text = stringResource(titleResId),
                                color = c.textMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight(500),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (item in section.items) {
                                val target = item.tab
                                if (target == null) {
                                    SettingsDisabledTrigger(item.titleResId)
                                } else {
                                    SettingsTabTrigger(target, target == current) { onSelect(target) }
                                }
                            }
                        }
                    }
                }
            } else {
                SettingsSearchResults(query = query, onSelect = onSelect, modifier = Modifier.fillMaxWidth())
            }
        }
        // W4-E F-5: no `settings-v2-nav-footer`. Upstream's settings navigation
        // has no app-name/version footer (`audit_settings_palette.md` A-1: the
        // class does not exist in `_upstream/packages`; the version lives on the
        // About page). The Android-only footer was dropped for parity.
    }
}

/** `<639dp` override: the list becomes a top strip (vendored `05` §5.3). */
@Composable
private fun SettingsNavTopBar(
    current: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(modifier.background(c.layer01)) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            SettingsSearchField(query = query, onQueryChange = onQueryChange)
        }
        if (query.isBlank()) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(PaddingValues(horizontal = 8.dp, vertical = 6.dp)),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (section in SETTINGS_NAV) {
                    for (item in section.items) {
                        val target = item.tab
                        if (target == null) {
                            SettingsDisabledTrigger(item.titleResId, compact = true)
                        } else {
                            SettingsTabTrigger(target, target == current, compact = true) { onSelect(target) }
                        }
                    }
                }
            }
        } else {
            SettingsSearchResults(
                query = query,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
            )
        }
    }
}

/** `settings.search` input (`settings/search.tsx`) above the navigation. */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    OcTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.oc_settings_search),
        imeAction = ImeAction.Search,
    )
}

/**
 * `settings/search.tsx`'s result list: page hits first, then the matching rows.
 * Selecting a hit opens the owning tab (row-level scroll-to is a later
 * milestone).
 */
@Composable
private fun SettingsSearchResults(
    query: String,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val titleOf = rememberTitleResolver()
    val hits = remember(query, titleOf) { searchSettings(query, titleOf) }
    if (hits.isEmpty()) {
        Text(
            text = stringResource(R.string.oc_settings_search_empty),
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        return
    }
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (hit in hits) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { onSelect(hit.tab) }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = titleOf(hit.titleResId),
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(440),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(hit.tab.titleResId()),
                    color = c.textFaint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight(440),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Resolves every searchable title once per composition. `stringResource` is
 * composable, so the ids come from the pure [searchCatalogTitleIds] and are
 * resolved in a stable order.
 */
@Composable
private fun rememberTitleResolver(): (Int) -> String {
    val ids = remember { searchCatalogTitleIds() }
    val texts = ids.map { stringResource(it) }
    val byId = remember(ids, texts) { ids.indices.associate { ids[it] to texts[it] } }
    return remember(byId) { { id: Int -> byId[id].orEmpty() } }
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
        // A page whose sprite glyph is not in [SettingsIcon] keeps the same
        // 14dp slot so every label stays on one vertical line.
        val icon = tab.icon()
        if (icon == null) Spacer(Modifier.size(14.dp)) else OcIcon(icon, if (selected) c.textBase else c.textMuted, size = 14.dp)
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

/**
 * A root tab upstream has and the native shell does not implement yet: the same
 * 28dp geometry, muted to `text-faint`, **not clickable**, with an explicit
 * "not available" tag so the row cannot be mistaken for a working page.
 */
@Composable
private fun SettingsDisabledTrigger(titleResId: Int, compact: Boolean = false) {
    val c = OcTheme.colors
    Row(
        Modifier
            .then(if (compact) Modifier else Modifier.fillMaxWidth())
            .height(28.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(titleResId),
            color = c.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight(440),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            OcTag(stringResource(R.string.oc_settings_tab_unavailable))
        }
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
