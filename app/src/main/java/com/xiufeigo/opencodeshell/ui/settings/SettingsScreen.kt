package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
import com.xiufeigo.opencodeshell.ui.nav.desktopCaptionReserve
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogController
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
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
 *   bottom 1px border and hides `settings-v2-nav-footer` — the shell keeps all
 *   14 tabs reachable via a horizontal strip that scrolls to the selection, so
 *   every value stays addressable at any window size.
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
    onClose: (() -> Unit)? = null,
    /**
     * B-13: the page the dialog opens on. The model picker's「管理模型」row asks
     * for [SettingsTab.MODELS] (`select-dialog.tsx:544-556`); every other opener
     * keeps upstream's default landing page.
     */
    initialTab: SettingsTab = SettingsTab.Default,
) {
    val c = OcTheme.colors
    val settings by store.state.collectAsState()
    var tabValue by remember { mutableStateOf(initialTab.value) }
    val tab = SettingsTab.fromValue(tabValue)
    var query by remember { mutableStateOf("") }
    // v48 (`research/v48_verify/tE/v48_10_reasoning_searchjump.png`): picking a
    // row hit only re-selected the tab, so a hit on the page you were already
    // on — 「Model reasoning」 on General — did nothing at all: the panel stayed
    // where it was and never showed the row the hit named. The hit's
    // `data-action` rides to the panel, which scrolls that row into view and
    // marks it (see `SettingsPanelRows`); `null` once the mark has been shown.
    var jumpAction by remember { mutableStateOf<String?>(null) }
    val mobile = MobileForm.isMobile(LocalConfiguration.current.screenWidthDp)
    val onSearchHit: (SettingsSearchHit) -> Unit = { hit ->
        tabValue = hit.tab.value
        // Only a row this form actually draws is worth jumping to
        // ([settingsRowJump]); otherwise the tab switch is all a hit can do.
        jumpAction = settingsRowJump(hit, mobile)?.action
        query = ""
    }
    // Upstream's `<640px` breakpoint, the one that turns the side nav into the
    // top strip — and the same threshold `SettingsRow` reads for its own wrap
    // (`SettingsRowStackedViewportWidth`, `settings.css:446-463`): below it the
    // nav is a strip *and* every row is stacked, above it the nav is a 240 dp
    // sidebar and the panel's own 520 px container decides the row.
    val wide = LocalConfiguration.current.screenWidthDp >= SettingsRowStackedViewportWidth
    // Short-window + keyboard: the band above the IME the collapsed chrome is
    // measured against ([settingsTopChromeCollapsed]). The strip branch below
    // is the only consumer — the wide sidebar keeps its search and column.
    val imeBottomDp = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    var searchFocused by remember { mutableStateOf(false) }
    val chromeCollapsed = !wide && settingsTopChromeCollapsed(
        windowHeightDp = LocalConfiguration.current.screenHeightDp.toFloat(),
        imeBottomDp = imeBottomDp.value,
        queryBlank = query.isBlank(),
        searchFocused = searchFocused,
    )
    // The settings layer ignores ESC/BACK/scrim by policy (DialogLayerPolicy),
    // so it must offer its own discoverable exit. Prefer an explicit callback;
    // otherwise close through the hosting DialogController when present
    // (previews mount without one and simply show no button).
    val controller = LocalDialogController.current
    val close = onClose ?: controller?.let { host -> { host.close() } }

    BoxWithCappedSize(modifier) {
        Column(Modifier.fillMaxSize().background(c.bgBase)) {
            if (close != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    OcButton(
                        label = stringResource(R.string.oc_settings_close),
                        primary = false,
                        onClick = close,
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderBase))
            }
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SettingsNavList(
                        current = tab,
                        onSelect = { tabValue = it.value },
                        onSelectHit = onSearchHit,
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.width(240.dp).fillMaxHeight(),
                    )
                    Box(Modifier.fillMaxHeight().width(1.dp).background(c.borderBase))
                    PanelSlot(
                        tab, settings, store, extra,
                        jumpAction = jumpAction,
                        onJumpHandled = { jumpAction = null },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                SettingsNavTopBar(
                    current = tab,
                    onSelect = { tabValue = it.value },
                    onSelectHit = onSearchHit,
                    query = query,
                    onQueryChange = { query = it },
                    chromeCollapsed = chromeCollapsed,
                    onSearchFocusChange = { searchFocused = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderBase))
                PanelSlot(
                    tab, settings, store, extra,
                    jumpAction = jumpAction,
                    onJumpHandled = { jumpAction = null },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
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
    /**
     * The panel calls this after a successful provider connect/disconnect
     * (or any credential write that may change `connected` membership) so the
     * host can re-emit [providers]. Null (previews, tests, unwired hosts)
     * keeps the previous behavior: the panel's local `connectionDelta` covers
     * the current visit, the catalog flow stays the source of truth.
     */
    val onProvidersChanged: (() -> Unit)? = null,
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
    jumpAction: String? = null,
    onJumpHandled: () -> Unit = {},
) {
    val serverData = extra.serverBridge as? SettingsServerData
    // v46 (`research/v46_verify/tE/tE_05_termfont_first.png`): an `OcSelect`
    // deep in a panel decided its open side against the *window* bottom, while
    // its list is clipped by this slot's box — on a wide window the card ends
    // far above the screen edge, so below looked roomy and the list opened
    // down under the card edge. The slot reports its own visible box in window
    // dp through [LocalOcSelectContainerBounds], and the selects below decide
    // their side and their height cap against it instead. The box sits outside
    // the panels' scrollers, so it does not move while they scroll — only the
    // triggers' positions do, which is exactly the quantity the decision reads.
    val density = LocalDensity.current
    var containerBounds by remember { mutableStateOf<OcSelectContainerBounds?>(null) }
    Box(
        modifier
            // v54 (`research/v54_verify/tB/tB_42_ocselect_lang_open.png` /
            // `tB_44_lang_scrolled.png` / `tB_45`): the slot's own box was
            // reported as-is, but the shell draws edge to edge, so in a
            // desktop / freeform window the box runs **under the system
            // navigation bar** — its bottom edge is hundreds of dp past the
            // last pixel the user can see. Every select in the panel then read
            // "there is room below" and opened its list down into the bar:
            // General → Language showed one clipped row, refused to flip, and
            // no amount of scrolling the panel brought the rest of the options
            // into view. The same missing inset is what cut the last row of a
            // scrolled page off with no scroll room left (`tB_87` / `tB_10`).
            //
            // `windowInsetsPadding` sits **outside** `onGloballyPositioned` on
            // purpose: the position node then reports the padded, actually
            // visible box, which is what the side and cap decisions must read.
            // On a window that already excludes the navigation bar the inset is
            // zero and every number is byte-identical to before.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .onGloballyPositioned { coords ->
                val topDp = coords.positionInWindow().y / density.density
                containerBounds = OcSelectContainerBounds(
                    topDp = topDp,
                    bottomDp = topDp + coords.size.height / density.density,
                )
            },
    ) {
        CompositionLocalProvider(LocalOcSelectContainerBounds provides containerBounds) {
            when (tab) {
            SettingsTab.GENERAL -> GeneralPanel(settings, store, jumpAction, onJumpHandled)
            SettingsTab.APPEARANCE -> AppearancePanel(settings, store, jumpAction, onJumpHandled)
            SettingsTab.NOTIFICATIONS -> NotificationsPanel(settings, store, jumpAction, onJumpHandled)
            SettingsTab.SHORTCUTS -> ShortcutsPanel(settings, store)
            SettingsTab.PAIRING -> PairingPanel()
            SettingsTab.PROJECTS -> ProjectsPanel(serverData)
            SettingsTab.WORKSPACES -> WorktreesPanel(serverData)
            SettingsTab.PROVIDERS -> ProvidersPanel(extra, store)
            SettingsTab.MODELS -> ModelsPanel(extra, store)
            SettingsTab.EXTENSIONS -> ExtensionsPanel(serverData)
            SettingsTab.SERVERS -> ServersPanel(extra.serverBridge)
            SettingsTab.SHELL -> ShellPanel(serverData)
            SettingsTab.EXPERIMENTAL -> ExperimentalPanel(serverData, settings, store)
            SettingsTab.ABOUT -> AboutPanel()
            }
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
 *
 * v82: the fixed box also **gives way to the soft keyboard**. `screenHeightDp`
 * is the display height and does not change while the IME is up (see
 * [settingsDialogCardHeightDp]), and the layer centres its content in the
 * *window*, so the card used to keep its full 600 dp with the keyboard covering
 * everything below its top 258 px: the Shortcuts rows and the server form's Save
 * were behind the IME with no scroller to reach them (the card fitted the window,
 * so nothing overflowed). While the keyboard is up the card is sized to the space
 * above it, lifted out of the layer's window-centring by `imePadding`, and
 * cleared of the status bar / window caption at the top — which is what turns the
 * panel's own body into the scroll region that reaches every row. With the
 * keyboard down the chain is absent and the box is upstream's centred
 * `min(100vh − 92, 600)` card, byte-identical to the v47/v48 geometry.
 */
@Composable
private fun BoxWithCappedSize(modifier: Modifier, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val width = minOf(config.screenWidthDp.dp - 32.dp, 980.dp)
    val height = settingsDialogCardHeightDp(
        windowHeightDp = config.screenHeightDp.toFloat(),
        imeBottomDp = imeBottom.value,
    ).dp
    val imeUp = imeBottom > 0.dp
    Box(
        modifier
            .width(width)
            // The lift and the top clearance ride **outside** the fixed height:
            // the card keeps `settingsDialogCardHeightDp`'s box, and the padding
            // is the empty band the layer's window-centring would otherwise have
            // spent behind the keyboard. Applied inside `.height` the same padding
            // would eat the card instead — it is a band, not a border.
            .then(
                if (imeUp) {
                    Modifier
                        .imePadding()
                        // The lifted card starts at the layer's top edge, so the
                        // two top insets are what keep its own Close row (and the
                        // settings search field) tappable. They are mutually
                        // exclusive by construction — `desktopCaptionReserve` is
                        // non-zero only where the status bar reports zero.
                        .statusBarsPadding()
                        .padding(top = desktopCaptionReserve())
                } else {
                    Modifier
                },
            )
            .height(height)
            .background(OcTheme.colors.bgBase, RoundedCornerShape(8.dp))
            .border(0.5.dp, OcTheme.colors.borderBase, RoundedCornerShape(8.dp)),
    ) { content() }
}

// ------------------------------------------------------------------- nav

@Composable
private fun SettingsNavList(
    current: SettingsTab,
    onSelect: (SettingsTab) -> Unit,
    onSelectHit: (SettingsSearchHit) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    // v47 (`research/v47_verify/tE/tE_21_search_typed.png`): typing the first
    // character of the search crashed the process. The result list is a
    // vertically scrollable column, and it was mounted **inside** the nav
    // body's own `verticalScroll` — a scroller measured with an unbounded
    // max height, which Compose rejects at runtime. The body therefore stops
    // being the scroller while a query is live and lets the results own the
    // scroll inside the bounded `weight(1f)` slot.
    val layout = SettingsSearchLayoutRules.of(query, topBar = false)
    Column(
        modifier
            .background(c.layer01)
            .padding(12.dp),
    ) {
        SettingsSearchField(query = query, onQueryChange = onQueryChange)
        Spacer(Modifier.height(12.dp))
        // Short windows clip the trailing tabs without this: the 14-tab
        // column is ~540dp tall while the dialog can be as short as ~320dp,
        // so the list scrolls vertically instead of stranding tabs below
        // the fold with no way to reach them.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (layout.outerScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                SettingsSearchResults(
                    query = query,
                    onSelect = onSelectHit,
                    modifier = Modifier.fillMaxWidth().then(layout.resultsMaxHeightModifier()),
                )
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
    onSelectHit: (SettingsSearchHit) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    /**
     * Short-window + keyboard ([settingsTopChromeCollapsed]): the search field
     * and the tab strip yield so the panel slot keeps its band. The strip's
     * scroll state stays hoisted, so the selection tracking below survives the
     * collapse and the strip returns to the selected tab with the keyboard.
     */
    chromeCollapsed: Boolean = false,
    /** Reports the search field's focus so collapsing never unmounts it mid-typing. */
    onSearchFocusChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val layout = SettingsSearchLayoutRules.of(query, topBar = true)
    Column(modifier.background(c.layer01)) {
        if (!chromeCollapsed) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                SettingsSearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onFocusChange = onSearchFocusChange,
                )
            }
        }
        if (query.isBlank()) {
            // Every tab stays reachable on narrow windows: the row fills the
            // bar and scrolls horizontally past the viewport edge instead of
            // clipping the trailing tabs (Models/Shell/Experimental/Pairing),
            // and it tracks the selection — picking a tab through the search
            // results scrolls the strip to it, so a tab selected off-screen
            // never leaves the strip parked at the wrong end.
            val navItems = remember { SETTINGS_NAV.flatMap { it.items } }
            val stripState = rememberLazyListState()
            val selectedIndex = navItems.indexOfFirst { it.tab == current }
            LaunchedEffect(selectedIndex) {
                if (selectedIndex >= 0) stripState.scrollToItem(selectedIndex)
            }
            if (!chromeCollapsed) {
                LazyRow(
                    state = stripState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(PaddingValues(horizontal = 8.dp, vertical = 6.dp)),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(navItems, key = { it.tab?.value ?: ("disabled:${it.titleResId}") }) { item ->
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
                onSelect = onSelectHit,
                modifier = Modifier.fillMaxWidth().then(layout.resultsMaxHeightModifier()),
            )
        }
    }
}

/**
 * How the navigation body is laid out for the current [query].
 *
 * The v47 crash (`research/v47_verify/tE/tE_21_search_typed.png` — typing one
 * character into「Search settings」force-finished the activity) was a nested
 * vertical scroll: the result list is a `verticalScroll` column and the wide
 * sidebar mounted it inside the nav body's own `verticalScroll`. A vertically
 * scrollable child of an unbounded (infinite max height) container is the
 * classic "Vertically scrollable component was measured with an infinity
 * maximum height constraints" — it throws on first composition of the results,
 * i.e. exactly on the first keystroke. Re-typing re-triggered it (2/2).
 *
 * So the two scroll roles are exclusive: while a query is live the **results**
 * are the scroller and the body is a plain bounded slot (`weight(1f)` in the
 * sidebar, `heightIn(max = 180.dp)` in the top bar, which has no body of its
 * own). While the query is blank the body scrolls the tab list as before. The
 * rule is pure so the contract is pinned by JVM tests rather than by a device
 * crash.
 */
internal data class SettingsSearchLayout(
    /** The nav body itself scrolls (the blank-query tab list). */
    val outerScroll: Boolean,
    /** A finite cap for the results list, or null to fill the remaining body. */
    val resultsMaxHeightDp: Int?,
) {
    /** [resultsMaxHeightDp] as a finite `heightIn(max = …)`, or no cap at all. */
    fun resultsMaxHeightModifier(): Modifier =
        resultsMaxHeightDp?.let { Modifier.heightIn(max = it.dp) } ?: Modifier
}

internal object SettingsSearchLayoutRules {
    /**
     * The top bar's results cap. The strip version has no scrolling body of its
     * own, so the result list needs a finite height of its own to stay
     * scrollable; 180dp ≈ six 28dp hits, matching the pre-v47 value.
     */
    const val TOP_BAR_RESULTS_MAX_DP = 180

    fun of(query: String, topBar: Boolean): SettingsSearchLayout = when {
        query.isBlank() -> SettingsSearchLayout(outerScroll = true, resultsMaxHeightDp = null)
        topBar -> SettingsSearchLayout(outerScroll = false, resultsMaxHeightDp = TOP_BAR_RESULTS_MAX_DP)
        // Sidebar: `weight(1f)` already bounds the body, and the results fill it.
        else -> SettingsSearchLayout(outerScroll = false, resultsMaxHeightDp = null)
    }
}

/** `settings.search` input (`settings/search.tsx`) above the navigation. */
@Composable
private fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    OcTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.oc_settings_search),
        imeAction = ImeAction.Search,
        modifier = if (onFocusChange == null) {
            Modifier
        } else {
            Modifier.onFocusChanged { onFocusChange(it.isFocused) }
        },
    )
}

/**
 * `settings/search.tsx`'s result list: page hits first, then the matching rows.
 * Selecting a hit opens the owning tab and, for a row hit, hands the row's
 * `data-action` to the panel, which scrolls to it and marks it (v48: the tab
 * switch alone left a same-page row hit looking like a dead link).
 */
@Composable
private fun SettingsSearchResults(
    query: String,
    onSelect: (SettingsSearchHit) -> Unit,
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
                    .clickable { onSelect(hit) }
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

/**
 * Small-window policy for the shared panel header/body.
 *
 * The trap: every panel is `header (fixed) + body (scroll)`. On a short window
 * (≈400dp dialog ⇒ ≈492dp screen, the dialog is `100vh − 92`) the header's
 * `top:40 + bottom:32` insets plus title/description eat ~150dp before the
 * scrollable body even starts, so the Pairing QR sat in a ~0dp scroll region —
 * reachable in theory, invisible in practice, while the whole layer still fit
 * the viewport so the host's over-viewport fallback never engaged.
 *
 * The fix is two halves: [SettingsPanelLayout.compactHeader] shrinks the
 * header insets on short windows (pure on window-height dp so JVM unit tests
 * can pin it), and panels with a tall must-see block (Pairing's QR) may pass
 * `scroll = false` to [SettingsPanelBody] and own one outer scroll instead, so
 * the header scrolls away with the content.
 */
internal object SettingsPanelLayout {
    /**
     * Windows shorter than this get the compact header. The dialog is
     * `100vh − 92`, so a 400dp dialog means a ≈492dp window — well below this
     * line — while normal phones/tablets (≥600dp) keep the documented insets.
     */
    const val COMPACT_HEADER_MAX_WINDOW_HEIGHT_DP = 560

    val headerTop = 40.dp
    val headerBottom = 32.dp
    val compactHeaderTop = 16.dp
    val compactHeaderBottom = 12.dp

    /** Short real windows compact; unknown (0/negative) windows keep the full header. */
    fun compactHeader(windowHeightDp: Int): Boolean =
        windowHeightDp in 1 until COMPACT_HEADER_MAX_WINDOW_HEIGHT_DP

    /** Header insets for [windowHeightDp]: full on normal windows, shrunk on short ones. */
    fun headerPadding(windowHeightDp: Int): PaddingValues =
        if (compactHeader(windowHeightDp)) {
            PaddingValues(start = 40.dp, end = 40.dp, top = compactHeaderTop, bottom = compactHeaderBottom)
        } else {
            PaddingValues(start = 40.dp, end = 40.dp, top = headerTop, bottom = headerBottom)
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
    val windowHeightDp = LocalConfiguration.current.screenHeightDp
    Column(modifier.fillMaxWidth().padding(SettingsPanelLayout.headerPadding(windowHeightDp))) {
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

/**
 * `settings-v2-tab-body` — `gap:36px; padding: 0 40px 40px`.
 *
 * @param scroll false when the caller owns one outer scroll for the whole
 * panel (header included). The Pairing page does this so its QR scrolls with
 * the header instead of hiding in a ~0dp inner scroll region on short windows,
 * and since v32 BUG-2 the row panels (`SettingsPanelRows`: General /
 * Appearance / Notifications) do too — a header outside the scroll is a band
 * where a swipe is silently dropped, and it hides the panel's scroll position
 * from an accessibility dump. True (default) keeps the historical
 * fixed-header layout byte-identical for the panels that own their whole body.
 */
@Composable
internal fun SettingsPanelBody(
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .then(if (scroll) Modifier.verticalScroll(scrollState) else Modifier)
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
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Modal layer for settings dialogs (MCP add/resources, credential rename).
 * Mounted at the tab root above the panel — never appended at the end of the
 * scroll body, where a dialog only appears screens below the fold and its
 * button looks completely dead (the same trap `ProviderConnectOverlay` fixed
 * for Connect). Scrim tap dismisses.
 */
@Composable
internal fun SettingsModalOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = OcTheme.colors
    val scrim = rememberDialogScrimState()
    // v92: the card's height cap gives way to the keyboard — the visible band
    // above it, measured the same way the server cards measure theirs
    // (`config.screenHeightDp` minus the window's own IME bottom). With the
    // keyboard down (or on a roomy window) the band clears 480 dp and the cap
    // is the 480 dp it has always been; the whole card stays one scroller, so
    // every call site's own buttons stay reachable without restructuring them.
    val imeBottomDp = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val overlayMaxDp = settingsModalOverlayMaxDp(
        windowHeightDp = LocalConfiguration.current.screenHeightDp.toFloat(),
        imeBottomDp = imeBottomDp.value,
    ).dp
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DialogScrim(scrim, onDismiss)
        Column(
            Modifier.fillMaxWidth()
                .padding(24.dp)
                .heightIn(max = overlayMaxDp)
                .background(c.layer01, RoundedCornerShape(8.dp))
                .border(0.5.dp, c.borderBase, RoundedCornerShape(8.dp))
                .dialogCardBounds(scrim)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/**
 * The [SettingsModalOverlay] card's height cap, in dp: the visible band above
 * the keyboard, capped at the 480 dp the card has always used.
 *
 * With the keyboard down [imeBottomDp] is 0 and the value is 480 — the
 * keyboard-down geometry is byte-identical on every window. With the keyboard
 * up the card may never be taller than what is left above it, so its
 * whole-card scroller can always reach the call site's own buttons (MCP add /
 * resources, credential rename, bulk-delete confirm, project rename) instead
 * of drawing them below the fold. A nonsense inset is "no keyboard"; an inset
 * that ate the window leaves no room, not a negative cap.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun settingsModalOverlayMaxDp(
    windowHeightDp: Float,
    imeBottomDp: Float = 0f,
): Float {
    val ime = imeBottomDp.coerceAtLeast(0f)
    if (ime <= 0f) return 480f
    return minOf(480f, (windowHeightDp - ime).coerceAtLeast(0f))
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
