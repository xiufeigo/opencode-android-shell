package com.xiufeigo.opencodeshell.ui.session.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.settings.OcSelect
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.V2Api
import com.xiufeigo.opencodeshell.v2.WebSearchResponse
import com.xiufeigo.opencodeshell.v2.websearchQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of one manual websearch run ([WebSearchRunModel] owns the rules).
 */
data class WebSearchRunState(
    val providers: List<WebSearchOption> = emptyList(),
    val providersLoading: Boolean = false,
    val providersLoadFailed: Boolean = false,
    val running: Boolean = false,
    val failed: Boolean = false,
    val error: WebSearchRunModel.ErrorKind = WebSearchRunModel.ErrorKind.NONE,
    val hasRun: Boolean = false,
    val response: WebSearchResponse? = null,
)

/**
 * Behaviour half of [SessionWebSearchRunDock]: the provider catalog
 * (`GET /api/websearch/provider`, the same route the consent dock's picker
 * uses) plus `POST /api/websearch` ([websearchQuery]) through one read/write
 * handle. Self-served like `SummaryServiceSource.rememberSummaryCatalogs` —
 * `ChatViewModel` exposes the provider catalog but nothing that runs a query.
 */
class WebSearchRunHost internal constructor(
    initial: WebSearchRunState,
    private val loadProviders: suspend () -> List<WebSearchOption>,
    private val search: suspend (query: String, providerID: String?) -> WebSearchResponse?,
) {
    var state by mutableStateOf(initial)
        private set

    fun retryProviders(scope: CoroutineScope) {
        state = state.copy(providersLoading = true, providersLoadFailed = false)
        scope.launch {
            val rows = runCatching { loadProviders() }.getOrNull()
            state = if (rows == null) {
                state.copy(providersLoading = false, providersLoadFailed = true)
            } else {
                state.copy(providers = rows, providersLoading = false)
            }
        }
    }

    fun run(scope: CoroutineScope, query: String, providerID: String?) {
        if (!WebSearchRunModel.canRun(query, state.running)) return
        state = state.copy(running = true, failed = false, error = WebSearchRunModel.ErrorKind.NONE)
        scope.launch {
            val result = runCatching { search(query.trim(), providerID) }
            state = result.fold(
                onSuccess = { response ->
                    if (response == null) {
                        state.copy(running = false, failed = true, error = WebSearchRunModel.ErrorKind.OTHER)
                    } else {
                        state.copy(running = false, failed = false, hasRun = true, response = response)
                    }
                },
                onFailure = { error ->
                    val code = (error as? V2Api.ApiException)?.code ?: 0
                    state.copy(
                        running = false,
                        failed = true,
                        hasRun = true,
                        error = WebSearchRunModel.errorKind(code, error.message.orEmpty()),
                    )
                },
            )
        }
    }
}

/**
 * Self-served run host for one workspace [directory]; the provider catalog
 * loads on first composition, exactly like the consent dock's fetch-on-ask.
 */
@Composable
fun rememberWebSearchRunHost(directory: String?): WebSearchRunHost {
    val context = LocalContext.current
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    val scope = rememberCoroutineScope()
    val host = remember(serverStore, directory) {
        WebSearchRunHost(
            initial = WebSearchRunState(providersLoading = true),
            loadProviders = {
                withContext(Dispatchers.IO) {
                    val server = serverStore.server ?: error("no server")
                    val api = V2Api.forServer(server.url, server.username, server.password)
                        ?: error("no api")
                    api.websearchProviders(directory).map { WebSearchOption(it.id, it.name) }
                }
            },
            search = { query, providerID ->
                withContext(Dispatchers.IO) {
                    val server = serverStore.server ?: error("no server")
                    val api = V2Api.forServer(server.url, server.username, server.password)
                        ?: error("no api")
                    api.websearchQuery(query, providerID, directory)
                }
            },
        )
    }
    LaunchedEffect(serverStore, directory) {
        host.retryProviders(scope)
    }
    return host
}

/**
 * Manual websearch execution dock — the `POST /api/websearch` half the consent
 * dock ([SessionWebSearchDock]) never runs: a query field, the same provider
 * [OcSelect] + tray shell (`session-websearch-dock.tsx`), and the result list of
 * `WebSearch.Result` rows (title / host / snippet / published date).
 *
 * Upstream has no such surface (its websearch UI only consents to the agent's
 * own tool call, `session/requests/websearch.ts`); the shell keeps the dock's
 * visual language so the two read as one family. Mounted from the summary
 * panel's 「网络搜索」 card — see the wiring note on [com.xiufeigo.opencodeshell.
 * ui.session.summary.SessionSummaryPanel].
 */
@Composable
fun SessionWebSearchRunDock(
    host: WebSearchRunHost,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val anyLabel = stringResource(R.string.oc_websearch_any)
    val providerPlaceholder = stringResource(R.string.oc_websearch_provider)
    val scope = rememberCoroutineScope()
    val state = host.state
    var query by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf(WebSearchModel.ANY_VALUE) }
    val options = WebSearchModel.selectOptions(WebSearchKind.CONSENT, state.providers)
    val unavailable = WebSearchModel.unavailable(state.providers, state.providersLoading, state.providersLoadFailed)
    val canSubmit = WebSearchRunModel.canRun(query, state.running) && !unavailable
    val rows = remember(state.response) {
        WebSearchRunModel.rows(state.response?.results.orEmpty())
    }
    val status = WebSearchRunModel.status(
        running = state.running,
        failed = state.failed,
        hasRun = state.hasRun,
        resultCount = rows.size,
    )
    val errorText = when (state.error) {
        WebSearchRunModel.ErrorKind.DISABLED -> stringResource(R.string.oc_session_websearch_disabled)
        WebSearchRunModel.ErrorKind.PROVIDER_NOT_FOUND -> stringResource(R.string.oc_session_websearch_provider_missing)
        WebSearchRunModel.ErrorKind.PROVIDER_REQUIRED,
        WebSearchRunModel.ErrorKind.OTHER,
        WebSearchRunModel.ErrorKind.NONE,
        -> stringResource(R.string.oc_session_websearch_failed)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, c.borderBase, RoundedCornerShape(12.dp)),
    ) {
        // ------------------------------------------------- shell: query + provider
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.layer01)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.oc_session_websearch_title),
                color = c.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
                lineHeight = 20.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.bgBase)
                        .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.oc_session_websearch_query),
                            color = c.textFaint,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = c.textBase,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Default,
                        ),
                        cursorBrush = SolidColor(c.textBase),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                OcSelect(
                    value = options.firstOrNull { it.value == selection }?.label?.takeIf { it.isNotEmpty() }
                        ?: if (selection == WebSearchModel.ANY_VALUE) anyLabel else selection,
                    options = options.map { it.value to (it.label.ifEmpty { anyLabel }) },
                    enabled = !state.running && !unavailable,
                    onSelect = { selection = it },
                )
            }
        }

        // ------------------------------------- tray: status + run/retry
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bgBase)
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    state.providersLoadFailed -> stringResource(R.string.oc_websearch_load_failed)
                    status == WebSearchRunModel.Status.LOADING -> stringResource(R.string.oc_common_loading)
                    status == WebSearchRunModel.Status.FAILED -> errorText
                    status == WebSearchRunModel.Status.EMPTY -> stringResource(R.string.oc_session_websearch_empty)
                    else -> ""
                },
                color = c.textBase,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.providersLoadFailed) {
                RunButton(
                    label = stringResource(R.string.retry),
                    variant = RunButtonVariant.Ghost,
                    enabled = !state.running,
                    onClick = { host.retryProviders(scope) },
                )
            }
            RunButton(
                label = stringResource(R.string.oc_session_websearch_run),
                variant = RunButtonVariant.Submit,
                enabled = canSubmit,
                onClick = {
                    host.run(scope, query, WebSearchRunModel.providerArg(selection))
                },
            )
        }

        // --------------------------------------------------- results
        if (rows.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = RUN_RESULTS_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .background(c.bgBase)
                    .padding(horizontal = 12.dp),
            ) {
                rows.forEach { row -> RunResultRow(row) }
            }
        }
    }
}

@Composable
private fun ColumnScope.RunResultRow(row: WebSearchRunModel.Row) {
    val c = OcTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = row.title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(row.host, row.published).joinToString(" · "),
            color = c.textMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.snippet.isNotEmpty()) {
            Text(
                text = row.snippet,
                color = c.textMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private enum class RunButtonVariant { Ghost, Submit }

/** `button-v2 size="small"` — the consent dock's tray buttons, same metrics. */
@Composable
private fun RunButton(
    label: String,
    variant: RunButtonVariant,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(24.dp)
            .clip(shape)
            .background(if (variant == RunButtonVariant.Submit) c.inverse else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (variant == RunButtonVariant.Submit) c.textInverse else c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}

/** Result list height cap — long provider answers scroll inside the dock. */
private val RUN_RESULTS_MAX_HEIGHT = 260.dp
