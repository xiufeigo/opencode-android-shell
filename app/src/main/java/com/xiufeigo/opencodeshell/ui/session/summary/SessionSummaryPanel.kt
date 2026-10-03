package com.xiufeigo.opencodeshell.ui.session.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.dock.SessionWebSearchRunDock
import com.xiufeigo.opencodeshell.ui.session.dock.rememberWebSearchRunHost
import com.xiufeigo.opencodeshell.ui.settings.OcSwitch
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Session summary panel (A1) — the popover body: a project card and a server
 * card, both built from [SessionSummaryModel] rows.
 *
 * Upstream (`session/summary/panel.tsx:44-142`) puts this in a `bottom-end`
 * popover anchored right under the session header, `width: 280px` (mobile:
 * full width, 44px rows), cards with `radius: 6px`, `bg-base` + raised
 * elevation, rows `min-height: 32px` / `padding: 4px 12px` / `gap: 8px` /
 * `13px` at `--line-height-base` (`summary.css:6-116,299-307`). The composable
 * therefore takes a [width]-agnostic [modifier] and a [mobile] flag instead of
 * knowing about the popover: the integrator decides where it mounts (popover on
 * wide screens, the mobile view's details tab on narrow ones, matching
 * `screen.tsx:191-240`).
 *
 * Wiring needs are listed in `research/wave6_summary.md`; the panel takes every
 * value as a parameter so it never reaches into a ViewModel. Parameters are
 * **overrides**: when left null the panel falls back to the read-only V2
 * catalogs of [rememberSummaryCatalogs] (MCP / Plugins / Skills / config), so
 * the four submenus show real data even before a host wires them through.
 * Background tasks come from the host (`ChatViewModel.backgroundTasks`, the
 * `background.ts:85-115` derivation over shell/subagent tool parts + child
 * sessions); an empty list renders upstream's empty copy.
 *
 * The three sub-panels of `panel.tsx:44-142` sit next to the row lists:
 * **background-tasks** (project card tail, `summary/background.tsx`), the
 * **configured-LSP** list and the **server** service submenus MCP / Plugins /
 * Skills / LSP (`summary/server-panel.tsx:4-9,66-101`). Every service catalog
 * is an optional parameter: `null` = try the V2 fallback, and when that is
 * unavailable too the submenu shows upstream's empty copy instead of inventing
 * rows.
 *
 * The MCP submenu is also the one **write** surface of this panel: each row
 * carries upstream's `Switch` (`server-panel.tsx:237-265`) wired to
 * [rememberMcpToggle] (host-supplied [mcpToggle] wins), and every submenu
 * closes with `ServiceConfigLink`'s 「Copy configuration file path」 footer
 * (`server-panel.tsx:270,366,440-480`) fed by [onCopyConfigPath], falling back
 * to [rememberConfigPathCopy]. The Switches vanish when there is no configured
 * server to talk to — an inert control is worse than none — while the footer
 * stays and reports upstream's `configFileMissing` toast for a page with no
 * document.
 */
@Composable
fun SessionSummaryPanel(
    directory: String?,
    branch: String?,
    changedFiles: Int?,
    serverLabel: String?,
    serverUrl: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Session on screen — the `sessionSkillActivate` handle of the Skills
     * submenu rows (`POST /api/experimental/session/{id}/skill`). `null`
     * hides the activate affordance; see `research` notes on wiring.
     */
    sessionID: String? = null,
    additions: Int? = null,
    deletions: Int? = null,
    mobile: Boolean = false,
    /**
     * Initial project-card expansion. Upstream persists this in
     * `settings.sessionSummary.projectExpanded` (default `true`,
     * `summary/project-card.tsx:20-46`); the caller passes the stored value and
     * toggling stays local to the panel, so wiring the setting needs no change
     * here.
     */
    initiallyExpanded: Boolean = true,
    /** `background.tsx:5-10`; empty/null renders upstream's empty copy. */
    backgroundTasks: List<SessionSummaryModel.BackgroundTask>? = null,
    /** `GET /api/mcp` rows (`server-panel.tsx:147-152`). */
    mcpServers: List<SessionSummaryModel.ServiceItem>? = null,
    /** `GET /api/plugin` ids (`ServiceCatalog`, `server-panel.tsx:277+`). */
    pluginNames: List<String>? = null,
    /** `GET /api/skill` names (`ServiceCatalog`). */
    skillNames: List<String>? = null,
    /**
     * `GET /api/config` JSON — `configured-lsp.ts` derives the LSP names when
     * [lspNames] is left null, so a host can hand over the raw page it already
     * fetched.
     */
    configJson: String? = null,
    /** Explicit LSP names; overrides [configJson] when given. */
    lspNames: List<String>? = null,
    /**
     * The MCP submenu's write handle (`server-panel.tsx:234` `toggle.mutate`).
     * Host-supplied wins; `null` falls back to [rememberMcpToggle], and when
     * that is unavailable too (no configured server) the rows keep upstream's
     * status text and render no Switch — an inert control is worse than none.
     */
    mcpToggle: McpToggleHost? = null,
    /**
     * `ServiceConfigLink`'s footer (`server-panel.tsx:270,366,440-480`): the
     * panel resolves the configuration document path out of [configJson]
     * ([SessionSummaryModel.configPath]) and hands it over; `null` = the config
     * page listed no document, which the host reports as upstream's
     * `session.summary.configFileMissing`. Host-supplied wins; `null` falls back
     * to [rememberConfigPathCopy], the panel's own clipboard write.
     */
    onCopyConfigPath: ((String?) -> Unit)? = null,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    val projectRows = remember(directory, branch, changedFiles, additions, deletions) {
        SessionSummaryModel.projectRows(directory, branch, changedFiles, additions, deletions)
    }
    val serverRows = remember(serverLabel, serverUrl) {
        SessionSummaryModel.serverRows(serverLabel, serverUrl)
    }
    // Host-supplied catalogs win; null falls back to the read-only V2 page.
    // The revision re-reads it after an MCP write, like upstream's
    // `invalidate` + `sync` pair (`server-panel.tsx:234`).
    val toggleHost = mcpToggle ?: rememberMcpToggle()
    val copyConfigPath = onCopyConfigPath ?: rememberConfigPathCopy()
    val fallback = rememberSummaryCatalogs(directory, toggleHost?.revision ?: 0)
    val backgroundRows = remember(backgroundTasks) {
        SessionSummaryModel.backgroundTasks(backgroundTasks)
    }
    val mcpItems = remember(mcpServers, fallback) {
        SessionSummaryModel.serviceItems(mcpServers ?: fallback.mcp)
    }
    val pluginItems = remember(pluginNames, fallback) {
        SessionSummaryModel.nameItems(pluginNames ?: fallback.plugins)
    }
    val skillItems = remember(skillNames, fallback) {
        SessionSummaryModel.nameItems(skillNames ?: fallback.skills)
    }
    // The local shadows the parameter so the pure call below reads naturally.
    @Suppress("NAME_SHADOWING")
    val configJson = configJson ?: fallback.configJson
    val lspItems = remember(lspNames, configJson) {
        SessionSummaryModel.nameItems(lspNames ?: SessionSummaryModel.configuredLsps(configJson))
    }
    var projectExpanded by remember(initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    var websearchExpanded by remember { mutableStateOf(false) }
    val stats = rememberSessionStats()
    val onReloadLocations = rememberLocationReload()
    val onActivateSkill = rememberSkillActivate(sessionID)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, RoundedCornerShape(12.dp))
            .padding(8.dp)
            // The phone view mounts the panel in a bounded box and the desktop
            // popover on short screens: without this the websearch dock body,
            // skill activate rows and the close row below the fold are clipped
            // with no way to reach them. Collapse toggles are untouched.
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ------------------------------------------------ project card
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderMuted, shape)
                .padding(2.dp),
        ) {
            CardHeader(
                title = stringResource(R.string.oc_summary_project_title),
                expanded = projectExpanded,
                onToggle = { projectExpanded = !projectExpanded },
                rowHeight = if (mobile) 44.dp else 32.dp,
            )
            if (projectExpanded) {
                projectRows.forEach { row -> SummaryRow(row, mobile) }
                BackgroundTasksSection(tasks = backgroundRows, mobile = mobile)
            }
        }

        // ------------------------------------------------- server card
        // Upstream's `SessionServerPanel` always renders (its header is the
        // server/"Extensions" name) and hosts the four service submenus — the
        // card is not gated on the identity row being known.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderMuted, shape)
                .padding(2.dp),
        ) {
            CardHeader(
                title = stringResource(R.string.oc_summary_server_title),
                expanded = true,
                onToggle = null,
                rowHeight = if (mobile) 44.dp else 32.dp,
            )
            serverRows.forEach { row -> SummaryRow(row, mobile) }
            ServiceSubmenus(
                mcp = mcpItems,
                plugins = pluginItems,
                skills = skillItems,
                lsp = lspItems,
                mobile = mobile,
                onActivateSkill = onActivateSkill,
                mcpToggle = toggleHost,
                configJson = configJson,
                onCopyConfigPath = copyConfigPath,
            )
        }

        // ------------------------------------------------- stats card
        // `GET /api/experimental/session/stats` (upstream ships no screen for
        // the route — `SessionStatsModel` owns the row projection) plus the
        // manual `location.reload` action on its header.
        SessionStatsCard(
            mobile = mobile,
            onReload = onReloadLocations,
            stats = stats,
            modifier = Modifier.fillMaxWidth(),
        )

        // --------------------------------------------- websearch card
        // Manual `POST /api/websearch` execution (`SessionWebSearchRunDock`):
        // the query half the consent dock never runs. Collapsed by default so
        // the popover keeps its upstream shape.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderMuted, shape)
                .padding(2.dp),
        ) {
            CardHeader(
                title = stringResource(R.string.oc_session_websearch_title),
                expanded = websearchExpanded,
                onToggle = { websearchExpanded = !websearchExpanded },
                rowHeight = if (mobile) 44.dp else 32.dp,
            )
            if (websearchExpanded) {
                SessionWebSearchRunDock(
                    host = rememberWebSearchRunHost(directory),
                    modifier = Modifier.padding(4.dp),
                )
            }
        }

        // The panel is closed from the header affordance; a one-line hint keeps
        // the panel from looking truncated when every card is empty.
        if (
            projectRows.isEmpty() && serverRows.isEmpty() && backgroundRows.isEmpty() &&
            mcpItems.isEmpty() && pluginItems.isEmpty() && skillItems.isEmpty() && lspItems.isEmpty()
        ) {
            Text(
                text = stringResource(R.string.oc_summary_empty),
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (mobile) 44.dp else 32.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.oc_summary_close),
                color = c.textMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun CardHeader(
    title: String,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    rowHeight: androidx.compose.ui.unit.Dp,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = rowHeight)
            .then(
                if (onToggle != null) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.Button, onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_folder),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            Text(
                text = if (expanded) "▾" else "▸",
                color = c.iconMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun SummaryRow(row: SessionSummaryModel.Row, mobile: Boolean) {
    val c = OcTheme.colors
    val label = when (row.kind) {
        SessionSummaryModel.Kind.WORKSPACE -> stringResource(R.string.oc_summary_workspace)
        SessionSummaryModel.Kind.BRANCH -> stringResource(R.string.oc_summary_branch)
        SessionSummaryModel.Kind.CHANGES -> stringResource(R.string.oc_summary_changes)
        SessionSummaryModel.Kind.SERVER -> stringResource(R.string.oc_summary_server)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (mobile) 44.dp else 32.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.value,
            color = c.textBase,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontFamily = if (row.mono) OcTheme.monoFont else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The project card's background-tasks submenu (`summary/background.tsx`).
 * Upstream only mounts the trigger when a task exists (`Show when={tasks.length
 * > 0}`); Android keeps the row so the gap is visible instead of silent — an
 * empty list shows the empty line, a populated one lists up to ten rows with
 * their `Shell` / `Agent` / agent-name head.
 */
@Composable
private fun BackgroundTasksSection(tasks: List<SessionSummaryModel.BackgroundTask>, mobile: Boolean) {
    val c = OcTheme.colors
    var expanded by remember { mutableStateOf(false) }
    ServiceSubmenu(
        label = stringResource(R.string.oc_summary_background),
        count = tasks.size,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        mobile = mobile,
    ) {
        if (tasks.isEmpty()) {
            ServiceEmptyLine(R.string.oc_summary_background_empty)
        } else {
            tasks.forEach { task ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (mobile) 44.dp else 32.dp)
                        .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = task.typeLabel,
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = task.label,
                        color = c.textFaint,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The server card's four service submenus (`server-panel.tsx:4-9`), in
 * upstream order. Each expands to its configured list or upstream's empty copy
 * (`session.summary.<service>.empty`); the non-empty lists open with upstream's
 * `Configured …` title. Catalogs the host cannot reach stay empty — the row
 * remains, honest about the gap.
 *
 * Two upstream pieces ride along per submenu: the MCP rows' `Switch`
 * (`server-panel.tsx:237-265`) and the `ServiceConfigLink` footer every service
 * menu closes with (`server-panel.tsx:270,366,478`).
 */
@Composable
private fun ServiceSubmenus(
    mcp: List<SessionSummaryModel.ServiceItem>,
    plugins: List<SessionSummaryModel.ServiceItem>,
    skills: List<SessionSummaryModel.ServiceItem>,
    lsp: List<SessionSummaryModel.ServiceItem>,
    mobile: Boolean,
    onActivateSkill: ((String) -> Unit)? = null,
    mcpToggle: McpToggleHost? = null,
    configJson: String? = null,
    onCopyConfigPath: ((String?) -> Unit)? = null,
) {
    ServiceSubmenusOf(
        labelRes = R.string.oc_summary_mcp,
        headerRes = R.string.oc_summary_mcp_title,
        emptyRes = R.string.oc_summary_mcp_empty,
        items = mcp,
        mobile = mobile,
        onToggle = mcpToggle?.toggle,
        busy = mcpToggle?.busy.orEmpty(),
        configService = SessionSummaryModel.SERVICE_MCP,
        configJson = configJson,
        onCopyConfigPath = onCopyConfigPath,
    )
    ServiceSubmenusOf(
        labelRes = R.string.oc_summary_plugins,
        headerRes = R.string.oc_summary_plugins_configured,
        emptyRes = R.string.oc_summary_plugins_empty,
        items = plugins,
        mobile = mobile,
        configService = SessionSummaryModel.SERVICE_PLUGINS,
        configJson = configJson,
        onCopyConfigPath = onCopyConfigPath,
    )
    ServiceSubmenusOf(
        labelRes = R.string.oc_summary_skills,
        headerRes = R.string.oc_summary_skills_configured,
        emptyRes = R.string.oc_summary_skills_empty,
        items = skills,
        mobile = mobile,
        onItemAction = onActivateSkill,
        configService = SessionSummaryModel.SERVICE_SKILLS,
        configJson = configJson,
        onCopyConfigPath = onCopyConfigPath,
    )
    ServiceSubmenusOf(
        labelRes = R.string.oc_summary_lsp,
        headerRes = R.string.oc_summary_lsp_configured,
        emptyRes = R.string.oc_summary_lsp_empty,
        items = lsp,
        mobile = mobile,
        configService = SessionSummaryModel.SERVICE_LSP,
        configJson = configJson,
        onCopyConfigPath = onCopyConfigPath,
    )
}

@Composable
private fun ServiceSubmenusOf(
    labelRes: Int,
    headerRes: Int,
    emptyRes: Int,
    items: List<SessionSummaryModel.ServiceItem>,
    mobile: Boolean,
    onItemAction: ((String) -> Unit)? = null,
    /** `server-panel.tsx:237-265` `Switch`; only the MCP submenu passes one. */
    onToggle: ((String, Boolean) -> Unit)? = null,
    /** Names with a connect/disconnect in flight — their Switch is read-only. */
    busy: Set<String> = emptySet(),
    /** `info[service]` key of the footer's path pick. */
    configService: String? = null,
    configJson: String? = null,
    onCopyConfigPath: ((String?) -> Unit)? = null,
) {
    val c = OcTheme.colors
    var expanded by remember { mutableStateOf(false) }
    ServiceSubmenu(
        label = stringResource(labelRes),
        count = items.size,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        mobile = mobile,
    ) {
        if (items.isEmpty()) {
            ServiceEmptyLine(emptyRes)
        } else {
            Text(
                text = stringResource(headerRes),
                color = c.textMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight(530),
                modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            )
            items.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (mobile) 44.dp else 32.dp)
                        .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = item.name,
                        color = c.textBase,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Upstream keeps the failure text in the row's `title`; a
                    // phone has no hover, so a failed row shows the server's own
                    // message where the desktop shows the 「Failed」 label.
                    val statusLabel = when {
                        item.status == "failed" && !item.error.isNullOrBlank() -> item.error
                        else -> item.status?.let { mcpStatusLabel(it) }
                    }
                    if (statusLabel != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            color = c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (onItemAction != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.oc_summary_skill_activate),
                            color = c.textFaint,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable(role = Role.Button) { onItemAction(item.name) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (onToggle != null) {
                        Spacer(Modifier.width(8.dp))
                        // `readOnly` while the toggle or the server's own
                        // `pending` transition is in flight (`server-panel.tsx:228-232`).
                        OcSwitch(
                            checked = item.enabled,
                            enabled = item.name !in busy && !item.pending,
                            onCheckedChange = { onToggle(item.name, it) },
                        )
                    }
                }
            }
        }
        if (onCopyConfigPath != null && configService != null) {
            ServiceConfigFooter(
                service = configService,
                configJson = configJson,
                mobile = mobile,
                onCopy = onCopyConfigPath,
            )
        }
    }
}

/**
 * `ServiceConfigLink`'s phone form (`server-panel.tsx:440-480`): the desktop
 * reveals the configuration document in the OS file manager or copies its path
 * for a remote server; Android can only copy, so the row is always
 * 「Copy configuration file path」 and the panel hands over the path
 * [SessionSummaryModel.configPath] picked out of the `GET /api/config` page.
 */
@Composable
private fun ServiceConfigFooter(
    service: String,
    configJson: String?,
    mobile: Boolean,
    onCopy: (String?) -> Unit,
) {
    val c = OcTheme.colors
    val path = remember(configJson, service) { SessionSummaryModel.configPath(configJson, service) }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = c.borderMuted,
        modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (mobile) 44.dp else 32.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button) { onCopy(path) }
            .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_copy),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.oc_summary_copy_config_path),
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * `server-panel.tsx:180-187` status tokens → the localized
 * `session.summary.failed|connecting|needsAuth` labels; anything else is shown
 * as given (a host may pass display-ready copy).
 */
@Composable
private fun mcpStatusLabel(token: String): String = when (token) {
    "failed" -> stringResource(R.string.oc_summary_status_failed)
    "connecting" -> stringResource(R.string.oc_summary_status_connecting)
    "auth" -> stringResource(R.string.oc_summary_status_needs_auth)
    else -> token
}

/**
 * One expandable submenu row of a summary card: the label head (same geometry
 * as [CardHeader], chevron on the trailing edge) and, while open, its content.
 */
@Composable
private fun ServiceSubmenu(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    mobile: Boolean,
    content: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (mobile) 44.dp else 32.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count > 0) {
                Text(
                    text = count.toString(),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = if (expanded) "▾" else "▸",
                color = c.iconMuted,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
        if (expanded) content()
    }
}

@Composable
private fun ServiceEmptyLine(resId: Int) {
    val c = OcTheme.colors
    Text(
        text = stringResource(resId),
        color = c.textMuted,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    )
}
