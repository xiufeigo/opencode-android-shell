package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val FontWeight530 = FontWeight(530)

/**
 * Settings write-UI on top of the pure [SettingsConnectModel] rules: the
 * provider connect sheet (API key / OAuth / command), the stored-credential
 * list (rename + activate), the MCP add/resources dialogs and the wellknown
 * import row. Every network call goes through [SettingsServerData]; a v1
 * server answers `Unsupported` and the sheet says so instead of spinning.
 */

// ---------------------------------------------------------------- credentials

/**
 * `已存凭据` — every stored credential of every integration
 * (`GET /api/integration` → `connections[].type == "credential"`), with the
 * upstream rename (`PATCH /api/credential/{id}`) and activate
 * (`POST /api/credential/{id}/activate`) actions, and the upstream disconnect
 * (`providers.tsx:100-121` — `DELETE /api/credential/{id}` per credential of
 * the integration) on each row.
 *
 * The read is a three-state load: `Loading` only while the fetch is in flight,
 * then `Loaded` (list or empty) or `Failed` + retry — a throwing fetch used to
 * leave `rows == null` and spin forever.
 */
@Composable
fun CredentialsSection(
    source: SettingsServerData?,
    reloadKey: Int,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    onRename: (CredentialRow) -> Unit,
) {
    var state by remember(source, reloadKey) {
        mutableStateOf<SettingsPageLoad<List<CredentialRow>>?>(null)
    }
    var reload by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(source, reloadKey, reload) {
        state = settingsPageLoad {
            source?.integrationsJson()?.let(::credentialRows) ?: emptyList()
        }
    }

    // Pass the placeholder through as the format arg so the `.replace` below
    // can inject the per-call reason (`stringResource(id, "")` used to swallow it).
    val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "%1\$s")
    val disconnectedText = stringResource(R.string.oc_settings_disconnect_done)
    fun record(result: ProviderWriteResult) {
        val reason = writeFailureReason(result)
        if (reason == null) {
            feedback = disconnectedText
            onChanged()
        } else {
            failure = pageFailedFormat.replace("%1\$s", reason)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        failure?.let { OcBodyText(it, muted = false) }
        feedback?.let { OcBodyText(it, muted = true) }
        when (val loaded = state) {
            null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
            SettingsPageLoad.NoServer ->
                OcBodyText(stringResource(R.string.oc_settings_credentials_empty), muted = true)

            is SettingsPageLoad.Failed -> {
                OcBodyText(stringResource(R.string.oc_settings_page_failed, loaded.reason), muted = false)
                OcButton(
                    label = stringResource(R.string.oc_settings_page_reload),
                    primary = false,
                    onClick = { reload++ },
                )
            }

            is SettingsPageLoad.Loaded -> {
                val rows = loaded.rows
                if (rows.isEmpty()) {
                    OcBodyText(stringResource(R.string.oc_settings_credentials_empty), muted = true)
                } else {
                    SettingsList {
                        rows.forEachIndexed { index, row ->
                            CredentialRowUi(
                                row = row,
                                busy = busyId == row.id,
                                canAct = source != null && busyId == null,
                                onRename = { onRename(row) },
                                onActivate = {
                                    if (source == null || busyId != null) return@CredentialRowUi
                                    busyId = row.id
                                    failure = null
                                    feedback = null
                                    scope.launch {
                                        val result = source.activateCredential(row.id)
                                        busyId = null
                                        val reason = writeFailureReason(result)
                                        if (reason == null) {
                                            onChanged()
                                        } else {
                                            failure = pageFailedFormat.replace("%1\$s", reason)
                                        }
                                    }
                                },
                                onDisconnect = {
                                    if (source == null || busyId != null) return@CredentialRowUi
                                    busyId = row.id
                                    failure = null
                                    feedback = null
                                    scope.launch {
                                        val result = source.disconnectProvider(row.integrationID)
                                        busyId = null
                                        record(result)
                                    }
                                },
                            )
                            if (index != rows.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialRowUi(
    row: CredentialRow,
    busy: Boolean,
    canAct: Boolean,
    onRename: () -> Unit,
    onActivate: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.label,
                    color = OcTheme.colors.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight530,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.active) {
                    Spacer(Modifier.width(8.dp))
                    OcTag(stringResource(R.string.oc_settings_credentials_active), accent = true)
                }
            }
            Text(
                text = row.integrationName.ifEmpty { row.integrationID },
                color = OcTheme.colors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (busy) OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        }
        Spacer(Modifier.width(8.dp))
        OcButton(
            label = stringResource(R.string.common_rename),
            primary = false,
            enabled = canAct && !busy,
            onClick = onRename,
        )
        if (!row.active) {
            Spacer(Modifier.width(8.dp))
            OcButton(
                label = stringResource(R.string.oc_settings_credentials_activate),
                enabled = canAct && !busy,
                onClick = onActivate,
            )
        }
        Spacer(Modifier.width(8.dp))
        OcButton(
            label = stringResource(R.string.common_disconnect),
            primary = false,
            enabled = canAct && !busy,
            onClick = onDisconnect,
        )
    }
}

@Composable
internal fun CredentialRenameDialog(
    row: CredentialRow,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var label by remember(row.id) { mutableStateOf(row.label) }
    val valid = remember(label) { validateCredentialLabel(label) != null }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(R.string.oc_settings_credentials_rename_title), muted = false)
        OcTextField(
            value = label,
            onValueChange = { label = it },
            placeholder = stringResource(R.string.oc_settings_credentials_label_hint),
            imeAction = ImeAction.Done,
            // The label is a literal the store keeps verbatim (upstream's
            // `credential.label`); Gboard rewrote it as it was typed.
            autoCorrect = false,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.common_cancel),
                primary = false,
                enabled = !busy,
                onClick = onDismiss,
            )
            OcButton(
                label = stringResource(R.string.common_save),
                enabled = !busy && valid,
                onClick = { validateCredentialLabel(label)?.let(onSubmit) },
            )
        }
    }
}

// ------------------------------------------------------------- connect sheet

private enum class ConnectPhase { EDIT, RUNNING, DONE }

/**
 * Upstream `DialogConnectProvider` + `providers/connect/controller.ts`: one
 * sheet that picks the method from `integrationDetail`'s `Method` union and
 * drives the matching route — key (`connect/key`), OAuth (`connect/oauth` →
 * poll `…/status` → `…/complete`, cancel on failure) or command
 * (`connect/command` → poll `…/status`, cancel on failure).
 */
@Composable
fun IntegrationConnectSheet(
    providerID: String,
    source: SettingsServerData?,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    var detail by remember(providerID) { mutableStateOf<IntegrationDetailRow?>(null) }
    var loaded by remember(providerID) { mutableStateOf(false) }
    var methodIndex by remember(providerID) { mutableStateOf(0) }
    var keyText by remember(providerID) { mutableStateOf("") }
    var labelText by remember(providerID) { mutableStateOf("") }
    var codeText by remember { mutableStateOf("") }
    var formValues by remember(providerID) { mutableStateOf(emptyMap<String, String>()) }
    var phase by remember(providerID) { mutableStateOf(ConnectPhase.EDIT) }
    var oauthAttempt by remember { mutableStateOf<OauthAttemptRow?>(null) }
    var commandAttempt by remember { mutableStateOf<CommandAttemptRow?>(null) }
    var liveMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "%1\$s")
    val failedText = stringResource(R.string.oc_settings_connect_failed)
    val expiredText = stringResource(R.string.oc_settings_connect_expired)
    val unsupportedText = pageFailedFormat.replace("%1\$s", "unsupported")

    LaunchedEffect(providerID, source) {
        val page = settingsPageLoad {
            source?.integrationDetailJson(providerID)?.let(::integrationDetailRow)
        }
        detail = (page as? SettingsPageLoad.Loaded)?.rows
        loaded = true
    }
    val methods = remember(detail, loaded) { if (loaded) connectableMethods(detail) else emptyList() }
    val keyMethodLabel = stringResource(R.string.oc_settings_connect_method_key)
    val oauthMethodLabel = stringResource(R.string.oc_settings_connect_method_oauth)
    val commandMethodLabel = stringResource(R.string.oc_settings_connect_method_command)
    val methodLabels = remember(methods, keyMethodLabel, oauthMethodLabel, commandMethodLabel) {
        methods.map { method ->
            method.label ?: when (method.type) {
                "oauth" -> oauthMethodLabel
                "command" -> commandMethodLabel
                else -> keyMethodLabel
            }
        }
    }
    val method = methods.getOrNull(methodIndex.coerceAtMost(methods.lastIndex.coerceAtLeast(0)))

    fun fail(reason: String?) {
        busy = false
        phase = ConnectPhase.EDIT
        error = reason
        oauthAttempt = null
        commandAttempt = null
    }

    fun failWrite(result: ProviderWriteResult): String? = when (result) {
        ProviderWriteResult.Done -> null
        ProviderWriteResult.Unsupported -> unsupportedText
        is ProviderWriteResult.Failed -> pageFailedFormat.replace("%1\$s", result.reason)
    }

    fun runWrite(block: suspend () -> ProviderWriteResult, onDone: () -> Unit) {
        busy = true
        error = null
        scope.launch {
            val result = block()
            val problem = failWrite(result)
            busy = false
            if (problem == null) onDone() else fail(problem)
        }
    }

    // Poll loop: OAuth attempts report `mode`, command attempts report live output.
    LaunchedEffect(oauthAttempt?.attemptID, commandAttempt?.attemptID) {
        val oauth = oauthAttempt
        val command = commandAttempt
        if (source == null || (oauth == null && command == null)) return@LaunchedEffect
        while (isActive) {
            delay(1_000)
            if (oauth != null) {
                val status = oauthStatusRow(source.oauthStatusJson(providerID, oauth.attemptID))
                when (oauthPollOutcome(status?.status ?: "pending", oauth.mode)) {
                    OauthPollOutcome.COMPLETE -> {
                        busy = false
                        phase = ConnectPhase.DONE
                        onComplete()
                        return@LaunchedEffect
                    }
                    OauthPollOutcome.FAILED, OauthPollOutcome.EXPIRED -> {
                        source.oauthCancel(providerID, oauth.attemptID)
                        val expired = status?.status == "expired"
                        fail(status?.message ?: if (expired) expiredText else failedText)
                        return@LaunchedEffect
                    }
                    OauthPollOutcome.AWAIT_CODE -> liveMessage = status?.message ?: liveMessage
                    OauthPollOutcome.POLL -> liveMessage = status?.message ?: liveMessage
                }
            } else if (command != null) {
                val status = commandStatusRow(source.commandStatusJson(providerID, command.attemptID))
                liveMessage = status?.message ?: liveMessage
                when (commandPollOutcome(status?.status ?: "pending")) {
                    CommandPollOutcome.COMPLETE -> {
                        busy = false
                        phase = ConnectPhase.DONE
                        onComplete()
                        return@LaunchedEffect
                    }
                    CommandPollOutcome.FAILED, CommandPollOutcome.EXPIRED -> {
                        source.commandCancel(providerID, command.attemptID)
                        val expired = status?.status == "expired"
                        fail(status?.message ?: if (expired) expiredText else failedText)
                        return@LaunchedEffect
                    }
                    CommandPollOutcome.POLL -> Unit
                }
            }
        }
    }

    val unsupportedForm = remember(method) { method != null && formHasUnsupportedFields(method.form) }

    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(R.string.oc_settings_connect_title, providerID), muted = false)

        if (!loaded) {
            OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
            return@Column
        }

        if (methods.size > 1 && phase == ConnectPhase.EDIT) {
            OcBodyText(stringResource(R.string.oc_settings_connect_method), muted = true)
            OcSelect(
                value = methodLabels.getOrNull(methodIndex).orEmpty(),
                options = methodLabels.map { it to it },
                onSelect = { selected ->
                    methodIndex = methodLabels.indexOf(selected).coerceAtLeast(0)
                    formValues = emptyMap()
                    error = null
                },
            )
        }

        error?.let { OcBodyText(it, muted = false) }

        when {
            phase == ConnectPhase.DONE ->
                OcBodyText(stringResource(R.string.oc_settings_connect_done), muted = true)

            phase == ConnectPhase.RUNNING -> {
                oauthAttempt?.let { attempt ->
                    if (attempt.url.isNotEmpty()) {
                        OcBodyText(
                            stringResource(R.string.oc_settings_connect_open_url),
                            muted = false,
                            modifier = Modifier.clickable { uriHandler.openUri(attempt.url) },
                        )
                    }
                    if (attempt.instructions.isNotEmpty()) {
                        OcBodyText(attempt.instructions, muted = true)
                    }
                    if (oauthPollOutcome("pending", attempt.mode) == OauthPollOutcome.AWAIT_CODE) {
                        OcTextField(
                            value = codeText,
                            onValueChange = { codeText = it },
                            placeholder = stringResource(R.string.oc_settings_connect_code_hint),
                            imeAction = ImeAction.Done,
                        )
                        OcButton(
                            label = stringResource(R.string.oc_settings_connect_submit_code),
                            enabled = !busy && codeText.isNotBlank(),
                            onClick = {
                                runWrite(
                                    block = {
                                        source?.oauthComplete(providerID, attempt.attemptID, codeText)
                                            ?: ProviderWriteResult.Unsupported
                                    },
                                    onDone = {
                                        phase = ConnectPhase.DONE
                                        onComplete()
                                    },
                                )
                            },
                        )
                    }
                }
                commandAttempt?.let {
                    OcBodyText(stringResource(R.string.oc_settings_connect_command_output), muted = true)
                }
                liveMessage?.let { OcBodyText(it, muted = true) }
                if (busy || oauthAttempt != null || commandAttempt != null) {
                    OcBodyText(stringResource(R.string.oc_settings_connect_pending), muted = true)
                }
                OcButton(
                    label = stringResource(R.string.common_cancel),
                    primary = false,
                    enabled = source != null,
                    onClick = {
                        val oauth = oauthAttempt
                        val command = commandAttempt
                        busy = true
                        scope.launch {
                            if (oauth != null) source?.oauthCancel(providerID, oauth.attemptID)
                            if (command != null) source?.commandCancel(providerID, command.attemptID)
                            busy = false
                            onDismiss()
                        }
                    },
                )
            }

            else -> {
                val flow = method?.let { connectFlowOf(it.type) } ?: ConnectFlow.KEY
                method?.form.orEmpty().forEach { field ->
                    OcBodyText(field.title ?: field.key, muted = true)
                    OcTextField(
                        value = formValues[field.key].orEmpty(),
                        onValueChange = { formValues = formValues + (field.key to it) },
                        placeholder = field.key,
                        imeAction = ImeAction.Done,
                    )
                }
                if (unsupportedForm) {
                    OcBodyText(stringResource(R.string.oc_settings_connect_form_unsupported), muted = false)
                }
                OcBodyText(stringResource(R.string.oc_settings_connect_label_hint), muted = true)
                OcTextField(
                    value = labelText,
                    onValueChange = { labelText = it },
                    placeholder = stringResource(R.string.oc_settings_connect_label_hint),
                    imeAction = ImeAction.Done,
                )
                if (flow == ConnectFlow.KEY) {
                    OcBodyText(
                        stringResource(R.string.provider_connect_apiKey_label, providerID),
                        muted = true,
                    )
                    OcTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        placeholder = stringResource(R.string.provider_connect_apiKey_placeholder),
                        password = true,
                        imeAction = ImeAction.Done,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcButton(
                        label = stringResource(R.string.common_cancel),
                        primary = false,
                        enabled = !busy,
                        onClick = onDismiss,
                    )
                    val submitLabel = when (flow) {
                        ConnectFlow.COMMAND -> R.string.oc_settings_connect_start_command
                        else -> R.string.common_connect
                    }
                    OcButton(
                        label = stringResource(submitLabel),
                        enabled = !busy && source != null && !unsupportedForm && method != null,
                        onClick = {
                            val selected = method ?: return@OcButton
                            val answer = formAnswer(selected.form, formValues)
                            val label = labelText.trim().takeIf { it.isNotEmpty() }
                            when (connectFlowOf(selected.type)) {
                                ConnectFlow.KEY -> {
                                    val key = ProviderConnectForm.validateApiKey(keyText)
                                    if (key == null) {
                                        error = failedText
                                        return@OcButton
                                    }
                                    runWrite(
                                        block = {
                                            source?.connectIntegrationKey(providerID, key, answer, label)
                                                ?: ProviderWriteResult.Unsupported
                                        },
                                        onDone = {
                                            phase = ConnectPhase.DONE
                                            onComplete()
                                        },
                                    )
                                }
                                ConnectFlow.OAUTH -> {
                                    val methodID = methodRouteID(selected)
                                    if (methodID == null || source == null) {
                                        error = failedText
                                        return@OcButton
                                    }
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val attempt = source.oauthConnect(providerID, methodID, label)
                                            ?.let(::oauthAttemptRow)
                                        busy = false
                                        if (attempt == null) {
                                            error = failedText
                                        } else {
                                            oauthAttempt = attempt
                                            phase = ConnectPhase.RUNNING
                                        }
                                    }
                                }
                                ConnectFlow.COMMAND -> {
                                    val methodID = methodRouteID(selected)
                                    if (methodID == null || source == null) {
                                        error = failedText
                                        return@OcButton
                                    }
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val attempt = source.commandConnect(providerID, methodID, label)
                                            ?.let(::commandAttemptRow)
                                        busy = false
                                        if (attempt == null) {
                                            error = failedText
                                        } else {
                                            commandAttempt = attempt
                                            phase = ConnectPhase.RUNNING
                                        }
                                    }
                                }
                                ConnectFlow.ENV, ConnectFlow.OTHER -> error = unsupportedText
                            }
                        },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- mcp ui

/** Add-server form: name + local `command` / remote `url` (`Mcp.ServerConfig`). */
@Composable
fun McpAddDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (McpAddSpec) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val spec = remember(name, remote, command, url) { mcpAddSpec(name, remote, command, url) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(R.string.oc_settings_mcp_add_title), muted = false)
        OcTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.oc_settings_mcp_field_name),
        )
        OcSelect(
            value = stringResource(
                if (remote) R.string.oc_settings_mcp_kind_remote else R.string.oc_settings_mcp_kind_local,
            ),
            options = listOf(
                "local" to stringResource(R.string.oc_settings_mcp_kind_local),
                "remote" to stringResource(R.string.oc_settings_mcp_kind_remote),
            ),
            onSelect = { remote = it == "remote" },
        )
        if (remote) {
            OcTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = stringResource(R.string.oc_settings_mcp_field_url),
                keyboardType = KeyboardType.Uri,
            )
        } else {
            OcTextField(
                value = command,
                onValueChange = { command = it },
                placeholder = stringResource(R.string.oc_settings_mcp_field_command),
            )
        }
        OcBodyText(stringResource(R.string.oc_settings_mcp_runtime_hint), muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.common_cancel),
                primary = false,
                enabled = !busy,
                onClick = onDismiss,
            )
            OcButton(
                label = stringResource(R.string.oc_settings_mcp_add),
                enabled = !busy && spec != null,
                onClick = { spec?.let(onSubmit) },
            )
        }
    }
}

/** `GET /api/mcp/resource` browse: resources and templates of connected servers. */
@Composable
fun McpResourcesDialog(rows: McpResourceListRow?, loading: Boolean, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(R.string.oc_settings_mcp_resources_title), muted = false)
        when {
            loading -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
            rows == null -> OcBodyText(stringResource(R.string.oc_settings_mcp_resources_empty), muted = true)
            else -> {
                if (rows.resources.isEmpty() && rows.templates.isEmpty()) {
                    OcBodyText(stringResource(R.string.oc_settings_mcp_resources_empty), muted = true)
                }
                if (rows.resources.isNotEmpty()) {
                    SettingsList {
                        rows.resources.forEachIndexed { index, row ->
                            McpResourceRowUi(row.name, row.server, row.uri, row.description)
                            if (index != rows.resources.lastIndex) RowSeparator()
                        }
                    }
                }
                if (rows.templates.isNotEmpty()) {
                    OcBodyText(stringResource(R.string.oc_settings_mcp_resources_templates), muted = true)
                    SettingsList {
                        rows.templates.forEachIndexed { index, row ->
                            McpResourceRowUi(row.name, row.server, row.uri, row.description)
                            if (index != rows.templates.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
        OcButton(
            label = stringResource(R.string.common_close),
            primary = false,
            onClick = onDismiss,
        )
    }
}

@Composable
private fun McpResourceRowUi(name: String, server: String, uri: String, description: String?) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                color = OcTheme.colors.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight530,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (server.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                OcTag(server)
            }
        }
        if (uri.isNotEmpty()) {
            Text(
                text = uri,
                color = OcTheme.colors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        description?.let {
            Text(
                text = it,
                color = OcTheme.colors.textMuted,
                fontSize = 11.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------- wellknown

/** `POST /api/experimental/integration/wellknown {url}` entry row. */
@Composable
fun WellknownImportRow(
    busy: Boolean,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    val valid = remember(url) { validHttpUrl(url) != null }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OcBodyText(stringResource(R.string.oc_settings_wellknown_hint), muted = true)
        OcTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(R.string.oc_settings_wellknown_url),
            keyboardType = KeyboardType.Uri,
        )
        OcButton(
            label = stringResource(R.string.oc_settings_wellknown_add),
            enabled = !busy && valid,
            onClick = { validHttpUrl(url)?.let(onSubmit) },
        )
    }
}
