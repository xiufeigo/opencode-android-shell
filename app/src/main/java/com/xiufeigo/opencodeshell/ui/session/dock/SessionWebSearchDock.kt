package com.xiufeigo.opencodeshell.ui.session.dock

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.OcSelect
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Websearch request dock — upstream `session-websearch-dock.tsx:37-70` with
 * `session-websearch-dock.css`: one bordered surface whose shell holds the
 * provider [OcSelect] (`SettingsRow` = title + description left, control right)
 * and whose tray holds the status line plus Disable / Enable (`size="small"`
 * buttons, primary rightmost).
 *
 * Mounted from [SessionFormDock] whenever [WebSearchModel.detect] reads the
 * form as a websearch ask; it blocks the session until answered like every
 * other dock. The two asks behave differently (`websearch.ts`):
 *
 * - **consent** — options = "Any" + the fetched catalog, Disable offered,
 *   Enable answers `{choice: allow|disable|choose}` and a specific pick runs
 *   the two-step handoff ([WebSearchBridge]) into the follow-up provider form;
 * - **provider** — options come with the form, no "Any" and no Disable, Enable
 *   answers `{provider: …}` directly.
 *
 * [providers] is the consent form's catalog (`GET /api/websearch/provider`);
 * request construction belongs to `ChatViewModel`/`V2Api`, so until the host
 * wires the parameter the dock shows upstream's empty state
 * (`session.websearch.empty`) instead of pretending to work.
 */
@Composable
fun SessionWebSearchDock(
    kind: WebSearchKind,
    formID: String,
    formOptions: List<WebSearchOption>,
    responding: Boolean,
    onSubmit: (Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
    providers: List<WebSearchOption> = emptyList(),
    providersLoading: Boolean = false,
    providersLoadFailed: Boolean = false,
    onRetryProviders: () -> Unit = {},
) {
    val c = OcTheme.colors
    val anyLabel = stringResource(R.string.oc_websearch_any)
    val providerPlaceholder = stringResource(R.string.oc_websearch_provider)
    val catalog = if (kind == WebSearchKind.PROVIDER) formOptions else providers
    var requested by remember(formID) {
        mutableStateOf(WebSearchModel.selected(kind, catalog, null))
    }
    var failed by remember(formID) {
        mutableStateOf(WebSearchBridge.consumeSubmitFailure(formID))
    }
    val selection = WebSearchModel.selected(kind, catalog, requested)
    val unavailable = WebSearchModel.unavailable(catalog, providersLoading, providersLoadFailed)
    val canSubmit = WebSearchModel.canSubmit(
        kind = kind,
        providers = catalog,
        selection = selection,
        loading = providersLoading,
        loadFailed = providersLoadFailed,
        sending = responding,
    )
    val options = WebSearchModel.selectOptions(kind, catalog)
    fun labelOf(value: String): String =
        options.firstOrNull { it.value == value }?.label?.takeIf { it.isNotEmpty() }
            ?: if (value == WebSearchModel.ANY_VALUE) anyLabel else value

    // A provider form reached through the consent "choose" handoff answers
    // itself once (`replyWebSearch`'s `then` half); a changed catalog drops the
    // pick and waits for explicit confirmation.
    LaunchedEffect(formID) {
        if (kind != WebSearchKind.PROVIDER) return@LaunchedEffect
        val picked = WebSearchBridge.takeHandoff(catalog.map { it.value }) ?: return@LaunchedEffect
        WebSearchModel.handoffAnswer(catalog, picked)?.let { answer ->
            WebSearchBridge.markSubmitted(formID)
            onSubmit(answer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(0.5.dp, c.borderBase, RoundedCornerShape(12.dp)),
    ) {
        // ------------------------------------------------- shell: the setting
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.layer01)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.oc_websearch_title),
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(530),
                    lineHeight = 20.sp,
                )
                Text(
                    text = stringResource(R.string.oc_websearch_description),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(440),
                    lineHeight = 20.sp,
                )
            }
            Spacer(Modifier.width(24.dp))
            OcSelect(
                value = selection?.let { labelOf(it) } ?: providerPlaceholder,
                options = options.map { it.value to labelOf(it.value) },
                enabled = !responding && !unavailable,
                onSelect = { value ->
                    requested = value
                    failed = false
                },
            )
        }

        // ------------------------------------- tray: status + disable/enable
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.bgBase)
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val status = WebSearchModel.status(catalog, providersLoading, failed)
            if (providersLoadFailed) {
                Text(
                    text = stringResource(R.string.oc_websearch_load_failed),
                    color = c.textBase,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f),
                )
                WebSearchButton(
                    label = stringResource(R.string.retry),
                    variant = WebSearchButtonVariant.Ghost,
                    enabled = !responding,
                    onClick = onRetryProviders,
                )
            } else if (status != null) {
                Text(
                    text = when (status) {
                        WebSearchStatus.LOADING -> stringResource(R.string.oc_common_loading)
                        WebSearchStatus.FAILED -> stringResource(R.string.oc_websearch_failed)
                        WebSearchStatus.EMPTY -> stringResource(R.string.oc_websearch_empty)
                        WebSearchStatus.LOAD_FAILED -> stringResource(R.string.oc_websearch_load_failed)
                    },
                    color = c.textBase,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (WebSearchModel.offersDisable(kind)) {
                WebSearchButton(
                    label = stringResource(R.string.oc_websearch_disable),
                    variant = WebSearchButtonVariant.Ghost,
                    enabled = !responding,
                    onClick = {
                        val answer = WebSearchModel.answer(kind, null) ?: return@WebSearchButton
                        WebSearchBridge.markSubmitted(formID)
                        onSubmit(answer)
                    },
                )
            }
            WebSearchButton(
                label = stringResource(R.string.oc_websearch_enable),
                variant = WebSearchButtonVariant.Submit,
                enabled = canSubmit,
                onClick = {
                    val pick = selection ?: return@WebSearchButton
                    val answer = WebSearchModel.answer(kind, pick) ?: return@WebSearchButton
                    if (WebSearchModel.armsHandoff(kind, pick)) WebSearchBridge.armHandoff(pick)
                    WebSearchBridge.markSubmitted(formID)
                    failed = false
                    onSubmit(answer)
                },
            )
        }
    }
}

private enum class WebSearchButtonVariant { Ghost, Submit }

/** `button-v2 size="small"` (`button.css:43-47`): 24 tall, radius 4, padding-x 9. */
@Composable
private fun WebSearchButton(
    label: String,
    variant: WebSearchButtonVariant,
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
            .background(if (variant == WebSearchButtonVariant.Submit) c.inverse else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (variant == WebSearchButtonVariant.Submit) c.textInverse else c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}
