package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.components.OcIcon
import com.xiufeigo.opencodeshell.ui.overlay.ModalImePolicy
import com.xiufeigo.opencodeshell.ui.settings.ModelVisibility
import com.xiufeigo.opencodeshell.ui.settings.SettingsIcon
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Pure picker data logic, extracted for JVM tests (`ModelPickerTest`). Mirrors
 * the upstream model selector (`_upstream/packages/app/src/providers/models/`
 * `select-dialog.tsx` + `search.ts`): popular providers first, local search
 * over name/id/provider.
 *
 * v2 divergence: the picker lists **every provider that has models**, whether
 * or not it reports a stored credential. `Providers.connected` is "has
 * credentials" (`v2ConnectedProviderIds`), not "selectable" — filtering the
 * catalog on it hid `opencode`/`opencode-go` behind a single-provider picker
 * and left no fallback when the current model was region-blocked. [Group]
 * carries the flag so the UI can mark unconnected rows without hiding them.
 */
object ModelPickerItems {

    /** One provider section of the picker (upstream `sortModelGroups` input). */
    data class Group(
        val providerID: String,
        val providerName: String,
        val models: List<OpenCodeClient.Providers.Model>,
        val connected: Boolean = true,
    )

    /** Upstream `catalog/order.ts` `popularProviders` — group ordering anchor. */
    private val POPULAR = listOf(
        "opencode-go",
        "opencode",
        "anthropic",
        "github-copilot",
        "openai",
        "google",
        "openrouter",
        "vercel",
    )

    /**
     * Providers with models, in picker order: popular first (in
     * [POPULAR] order), the rest alphabetical by provider name; models inside a
     * group are sorted by name, like upstream's flat sort before grouping.
     *
     * [includeUnconnected] lists providers without a stored credential as well
     * (flagged via [Group.connected], never hidden) — the v2 picker path. The
     * default keeps the legacy connected-only view.
     *
     * WAVE 2.3a consumer: [visibility] is `SettingsStore.modelVisibility()` —
     * models hidden on the Models tab never reach the picker (upstream
     * `select-dialog.tsx:270` filters on `visible()`); empty keeps everything.
     * The filter is **per catalog entry** (`Model.entryID`, falling back to the
     * legacy `providerID:modelID` key), so hiding `gpt-5.6-fast` leaves the base
     * and `-pro` entries in the picker — see [ModelVisibility].
     */
    fun groups(
        providers: OpenCodeClient.Providers?,
        visibility: Map<String, String> = emptyMap(),
        includeUnconnected: Boolean = false,
    ): List<Group> {
        if (providers == null) return emptyList()
        val connected = providers.connected.toSet()
        if (!includeUnconnected && connected.isEmpty()) return emptyList()
        return providers.all
            .filter { (includeUnconnected || it.id in connected) && it.models.isNotEmpty() }
            .map { provider ->
                val models = provider.models
                    .filter {
                        ModelVisibility.isVisible(
                            provider.id,
                            it.entryID,
                            visibility,
                            modelID = it.id,
                        )
                    }
                    .sortedBy { it.name }
                Group(provider.id, provider.name, models, provider.id in connected)
            }
            .filter { it.models.isNotEmpty() }
            .sortedWith { a, b ->
                val aPopular = POPULAR.indexOf(a.providerID)
                val bPopular = POPULAR.indexOf(b.providerID)
                when {
                    aPopular >= 0 && bPopular >= 0 -> aPopular - bPopular
                    aPopular >= 0 -> -1
                    bPopular >= 0 -> 1
                    else -> a.providerName.compareTo(b.providerName, ignoreCase = true)
                }
            }
    }

    /** Local search filter; a group drops out when no model matches. */
    fun filter(groups: List<Group>, query: String): List<Group> {
        if (query.trim().isEmpty()) return groups
        return groups.mapNotNull { group ->
            val models = group.models.filter { model ->
                matches(query, model.name, model.id, model.entryID, group.providerName)
            }
            if (models.isEmpty()) null else group.copy(models = models)
        }
    }

    // ------------------------------------------------- v2 catalog freshness
    //
    // WAVE 2.4d (device v11): the picker's catalog is a full-replacement read
    // of `GET /api/model`, and one short answer used to be enough to destroy a
    // good catalog — the picker collapsed to the handful of rows that came
    // back, the chip lost its model name (falling back to the raw ref id) and
    // nothing re-read it, so only leaving and reopening the session helped.

    /** Total catalog entries (all providers), the size signal of [plausibleCatalog]. */
    fun modelCount(providers: OpenCodeClient.Providers?): Int =
        providers?.all?.sumOf { it.models.size } ?: 0

    /**
     * Below this many entries a catalog is small enough that a smaller answer
     * may simply be the truth (a provider drop, a trimmed test catalog), so the
     * shrink rule stays out of the way.
     */
    const val PLAUSIBLE_BASELINE = 16

    /**
     * Whether the freshly fetched [next] catalog may replace the one already
     * loaded ([previous]). Same-name jitter — the same providers answering with
     * far fewer models — must not blank the picker, drop the current selection
     * or freeze the chip's label until the session is reopened; the caller
     * re-reads instead ([ChatViewModel.refreshV2Providers]) and only keeps the
     * old catalog as a last resort.
     *
     * An **empty** answer is never plausible against a non-empty catalog: with
     * no models the picker shows nothing at all, which is strictly worse than
     * the (possibly stale) catalog on screen.
     */
    fun plausibleCatalog(
        previous: OpenCodeClient.Providers?,
        next: OpenCodeClient.Providers,
    ): Boolean {
        if (previous == null) return true
        val before = modelCount(previous)
        val after = modelCount(next)
        if (after == 0) return before == 0
        if (before < PLAUSIBLE_BASELINE) return true
        // Losing more than half of a real catalog in one read is a short
        // response, not a catalog edit.
        return after * 2 >= before
    }

    /**
     * `matchesModelSearch` (`providers/models/search.ts`): normalized contains
     * OR compact contains, so "gpt 5", "gpt-5" and "gpt5" all match "GPT-5.5".
     */
    fun matches(query: String, vararg values: String): Boolean {
        val search = normalize(query)
        if (search.isEmpty()) return true
        val compactSearch = compact(query)
        return values.any { value ->
            normalize(value).contains(search) || compact(value).contains(compactSearch)
        }
    }

    // --------------------------------------------------------------- variant (B4)
    //
    // `_upstream/packages/app/src/providers/models/variant.ts`: a model's
    // reasoning-effort variants are the **keys of `Model.variants`**, the value
    // `"default"` means "no override", and a selected value only counts while the
    // model actually advertises it.

    /** The value that means "inherit the server/agent default" (`variant.ts:34`). */
    const val VARIANT_DEFAULT = "default"

    /** Picker options for a model: `default` first, then the advertized variants. */
    fun variantOptions(model: OpenCodeClient.Providers.Model?): List<String> {
        val variants = model?.variants.orEmpty()
        if (variants.isEmpty()) return emptyList()
        return listOf(VARIANT_DEFAULT) + variants
    }

    /** True when the variant control should be drawn (`options().length > 1`). */
    fun variantVisible(model: OpenCodeClient.Providers.Model?): Boolean =
        variantOptions(model).size > 1

    /**
     * `resolveModelVariant` (`variant.ts:31-35`): `selected ?? preferred ??
     * configured`, accepted only when the model advertises it and it is not
     * `"default"`. A null [selected] means "the user never pinned one", which the
     * caller answers with null — never a fabricated label.
     */
    fun resolveVariant(
        selected: String?,
        variants: List<String>,
        configured: String? = null,
        preferred: String? = null,
    ): String? {
        if (selected == null) return null
        val value = selected.ifEmpty { preferred ?: configured ?: "" }
        return value.takeIf { it.isNotEmpty() && it != VARIANT_DEFAULT && it in variants }
    }

    /** `cycleModelVariant` (`variant.ts:37-40`): the next label, wrapping around. */
    fun cycleVariant(current: String?, variants: List<String>): String? {
        if (variants.isEmpty()) return null
        val resolved = current?.takeIf { it in variants }
        val index = resolved?.let { variants.indexOf(it) + 1 } ?: 0
        return variants[index % variants.size]
    }

    /**
     * Effective model: the user's explicit pick wins, else the server's
     * `config.model` default (upstream `selection.tsx` `current()` — scope
     * draft first, configured model last).
     */
    fun resolveSelected(
        override: Pair<String, String>?,
        serverDefault: Pair<String, String>?,
    ): Pair<String, String>? = override ?: serverDefault

    /**
     * Provider-scoped ref for a tapped row: the **entry** id
     * ([OpenCodeClient.Providers.Model.entryID]) pinned to the **owning
     * group's** providerID. The row handler must go through here — never a bare
     * model id — because Zen and Go catalog the same model ids
     * (`space-bunny-free` lives under both `opencode` and `opencode-go`, and
     * only the Zen copy carries a usable key): resolving by id alone lands on
     * whichever provider sorts first (`opencode-go` heads [POPULAR]), and the
     * session then runs keyless into `Missing API key`.
     *
     * The id half is the entry id, not the wire `modelID`, for the same reason
     * one step down: `openai` catalogs `gpt-5.6` / `gpt-5.6-fast` /
     * `gpt-5.6-pro` as three entries that all report `modelID: "gpt-5.6"`, and
     * the server matches a session's `Model.Ref.id` against `Model.Info.id`
     * (`_upstream/packages/core/src/session/runner/model.ts`). Sending the
     * `modelID` would run the base model whichever tier the user tapped.
     *
     * The pair is exactly what `ModelPickerContent`'s `onSelect` hands on
     * (`selectModel(providerID, refID)`), and lands on the wire as
     * `POST /api/session/{id}/model` `{model:{id,providerID,variant?}}`. The
     * **variant** is deliberately not part of it: it is the reasoning-effort
     * axis the picker's variant group pins separately
     * ([ModelPickerItems.variantOptions]) and the transport adds at switch
     * time, never an entry discriminator (`withVariant` rejects any label the
     * entry does not advertise, `model-resolver.ts:137-151`).
     */
    fun selectionRef(
        group: Group,
        model: OpenCodeClient.Providers.Model,
    ): Pair<String, String> = group.providerID to model.entryID

    /**
     * One `LazyColumn` row key per **catalog entry**. Keyed on the entry id, not
     * the wire `modelID`: the live catalog holds 10 `(providerID, modelID)` pairs
     * more than once — every one of them under `openai`, 2 or 3 entries per pair
     * (`gpt-5.4`/`gpt-5.4-fast`, `gpt-5.6`/`gpt-5.6-fast`/`gpt-5.6-pro`, …) — so
     * a `modelID` key repeats inside one group and Compose throws
     * `Key "model:openai/gpt-5.6" was already used` while two sibling rows
     * compose in the same frame.
     */
    fun rowKey(group: Group, model: OpenCodeClient.Providers.Model): String =
        "model:${group.providerID}/${model.entryID}"

    /**
     * Whether the tapped row is the current selection, by the same resolution
     * [OpenCodeClient.Providers.model] uses: the ref id (`selected.second`) is
     * matched against the row's entry id, so exactly one row of a duplicated
     * `modelID` group carries the check mark (the one the chip names).
     */
    fun isSelected(
        group: Group,
        model: OpenCodeClient.Providers.Model,
        selected: Pair<String, String>?,
    ): Boolean =
        selected != null && selected.first == group.providerID && selected.second == model.entryID

    /**
     * Fold one provider group, or unfold it. The group header is a **control**,
     * not a label: it sits inside the same list as the model rows, at the same
     * size and the same inset, so a tap on it that does nothing reads as a dead
     * row — `research/v58_verify/5556/26_ocg.png` `30_ocg3.png` `42_ocg4.png`,
     * three taps on 「OpenCode Go」 that never moved the picker. Folding is the
     * one action a provider row can honestly own (it selects nothing: a
     * provider is not a model, and sending `POST /api/session/{id}/model`
     * without a model id would drop the session's model).
     */
    fun toggleCollapsed(collapsed: Set<String>, providerID: String): Set<String> =
        if (providerID in collapsed) collapsed - providerID else collapsed + providerID

    /** Whether a provider group is folded away. */
    fun isCollapsed(collapsed: Set<String>, providerID: String): Boolean =
        providerID in collapsed

    /** `normalizeModelSearch`: lowercase, non letter/number runs → one space. */
    private fun normalize(value: String): String =
        NON_WORD.replace(value.lowercase(), " ").trim().replace(MULTI_SPACE, " ")

    private fun compact(value: String): String = normalize(value).replace(" ", "")

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val MULTI_SPACE = Regex("\\s+")
}

/**
 * Model picker popup content (`ModelSelectorPopoverView`): search row on top,
 * a capped scroll list of provider-grouped models with the current selection
 * checked, and the bottom「使用服务器默认」row that clears the local override.
 * Chrome (popup window, motion, border) stays with [ModelChip].
 *
 * W5-C B4 adds the **variant sub-selection** (`providers/models/variant.ts` +
 * the `ComposerEditorConfiguredSelect` variant control,
 * `composer/editor/editor.tsx:295-306`): while the effective model advertises
 * `variants`, a `default` + one-row-per-variant group sits above the catalog, so
 * the reasoning effort can be pinned without a second chip on the toolbar.
 *
 * Rows are one per **catalog entry** ([ModelPickerItems.rowKey]) and
 * `onSelect` hands back that entry's ref ([ModelPickerItems.selectionRef]) —
 * `selectedModel` is `(providerID, refID)`, where `refID` is the entry id, so a
 * `modelID` the catalog lists once per tier (`openai`'s `gpt-5.6` /
 * `gpt-5.6-fast`) gets one independently selectable, independently checked row
 * instead of a duplicate Compose key.
 */
@Composable
fun ModelPickerContent(
    providers: OpenCodeClient.Providers?,
    selectedModel: Pair<String, String>?,
    overrideActive: Boolean,
    onSelect: (providerID: String, modelID: String) -> Unit,
    onUseServerDefault: () -> Unit,
    modifier: Modifier = Modifier,
    selectedVariant: String? = null,
    onSelectVariant: (String?) -> Unit = {},
    /**
     * B-13: upstream's「管理模型」button at the bottom of the select dialog
     * (`select-dialog.tsx:544-556`, label `dialog.model.manage`, leading
     * `outline-sliders` icon). Null hides the row.
     */
    onManageModels: (() -> Unit)? = null,
    /**
     * v51 `tE` (`v51_47→48`): the popup owns the initial focus instead of the
     * popover root, so the field opens with the keyboard up and
     * `input text`/typing reaches it. Ignored by hosts that put this content in
     * a full-screen dialog whose own field owns the focus.
     */
    autoFocusSearch: Boolean = false,
) {
    val c = OcTheme.colors
    var search by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    // P1（`research/v53_verify/tE/v53_19_model2.png` vs `v53_20_model_nokb.png`）:
    // 短窗上打开弹层即自动聚焦搜索框 → 键盘升起 → 弹层只剩 Variant 两行，模型
    // 列表全被盖住（ESC 收一次键盘才恢复）。窗口够高时保持原样，空间不够则不
    // 自动拉键盘；此时焦点归属改由弹层根节点承担（`ModelChip` 把
    // `requestInitialFocus` 取反），ESC 与外点穿透两条行为都不变，用户点搜索框
    // 键盘照常升起。
    val focusSearch = ModalImePolicy.autoFocusOnOpen(
        requested = autoFocusSearch,
        windowHeightDp = LocalConfiguration.current.screenHeightDp,
    )
    // CommandPalette's search has taken this route since W5 and works for the
    // same reason (`CommandPalette.kt:118-121`).
    LaunchedEffect(focusSearch) {
        if (!focusSearch) return@LaunchedEffect
        // The popup window attaches a frame or two after the composition does,
        // so a single attempt can land on a node that is not attached yet.
        repeat(SEARCH_FOCUS_ATTEMPTS) {
            if (runCatching { searchFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    // WAVE 2.3a consumer: the Models tab Switch map, read live so a toggle
    // takes effect the next time the picker opens (no composer changes needed).
    val context = LocalContext.current
    val visibility = remember(context) { SettingsStore(context).modelVisibility() }
    // v2: every cataloged provider is selectable — `connected` is only
    // "has credentials", never a reason to hide a model.
    val allGroups = remember(providers, visibility) {
        ModelPickerItems.groups(providers, visibility, includeUnconnected = true)
    }
    val visibleGroups = remember(allGroups, search) { ModelPickerItems.filter(allGroups, search) }
    // v58: which provider groups the user folded away. Lives for one open — the
    // popup mounts its content while open and drops it on close.
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    // The variant group folds the same way (its header swallowed no taps
    // before — a model with a long variant list left no room for the catalog
    // and the section could not be put away).
    var variantCollapsed by remember { mutableStateOf(false) }
    // The variant control follows the *effective* model, exactly like the chip:
    // the pinned model when the user picked one, else the server default. The
    // lookup takes the ref id, so the variants shown are the tapped entry's own.
    val effectiveModel = selectedModel?.let { (providerID, refID) ->
        providers?.model(providerID, refID)
    }
    val variants = remember(effectiveModel) { ModelPickerItems.variantOptions(effectiveModel) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Search row: magnifier + field + clear (`dialog.model.search.placeholder`).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEARCH_ROW_HEIGHT)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_search),
                contentDescription = null,
                tint = c.iconMuted,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, lineHeight = 20.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocus),
                decorationBox = { innerTextField ->
                    Box {
                        if (search.isEmpty()) {
                            Text(
                                text = stringResource(R.string.oc_model_picker_search),
                                color = c.textFaint,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (search.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                // Hoisted: `semantics {}` is not a composable scope. The label
                // rides the clickable Box, not the `Icon` inside it.
                val clearLabel = stringResource(R.string.oc_home_clear_search)
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(OcTheme.shapes.sm)
                        .clickable(role = Role.Button) { search = "" }
                        .semantics { contentDescription = clearLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_oc_close),
                        contentDescription = null,
                        tint = c.iconMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Hairline()

        // W5-C B4: variant group (only while the effective model advertises one).
        if (variants.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { variantCollapsed = !variantCollapsed }
                    .padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 2.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.oc_composer_variant_group),
                    color = c.textFaint,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_oc_chevron_down),
                    contentDescription = null,
                    tint = c.textFaint,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(if (variantCollapsed) -90f else 0f),
                )
            }
            if (!variantCollapsed) {
                for (variant in variants) {
                    // `default` is the wire value for "no override" (`variant.ts:34`).
                    val effective = selectedVariant?.takeIf { it in variants } ?: ModelPickerItems.VARIANT_DEFAULT
                    ModelPickerRow(
                        label = if (variant == ModelPickerItems.VARIANT_DEFAULT) {
                            stringResource(R.string.oc_composer_variant_default)
                        } else {
                            variant
                        },
                        selected = variant == effective,
                        onClick = {
                            onSelectVariant(variant.takeIf { it != ModelPickerItems.VARIANT_DEFAULT })
                        },
                    )
                }
            }
            Hairline()
        }

        if (visibleGroups.isEmpty()) {
            Text(
                text = stringResource(R.string.oc_model_picker_empty),
                color = c.textFaint,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LIST_MAX_HEIGHT),
            ) {
                for (group in visibleGroups) {
                    item(key = "provider:${group.providerID}") {
                        val folded = ModelPickerItems.isCollapsed(collapsed, group.providerID)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // v58 (`research/v58_verify/5556/26_ocg.png`): the
                                // header reads as a row and swallowed taps.
                                // `.clickable` before the padding so the whole
                                // band is the target.
                                .clickable(role = Role.Button) {
                                    collapsed = ModelPickerItems.toggleCollapsed(
                                        collapsed,
                                        group.providerID,
                                    )
                                }
                                .padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = 8.dp,
                                    bottom = 2.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = group.providerName,
                                color = c.textFaint,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // v2 lists every cataloged provider, so rows without
                            // a stored credential get a faint marker — `Group`
                            // carries `connected` exactly for this, never to
                            // hide the row.
                            if (!group.connected) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(OcTheme.shapes.sm)
                                        .border(0.5.dp, c.borderMuted, OcTheme.shapes.sm)
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(R.string.oc_model_picker_unconnected),
                                        color = c.textFaint,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_oc_chevron_down),
                                contentDescription = null,
                                tint = c.textFaint,
                                modifier = Modifier
                                    .size(14.dp)
                                    .rotate(if (folded) -90f else 0f),
                            )
                        }
                    }
                    if (!ModelPickerItems.isCollapsed(collapsed, group.providerID)) {
                        items(
                            items = group.models,
                            key = { model -> ModelPickerItems.rowKey(group, model) },
                        ) { model ->
                            ModelPickerRow(
                                label = model.name,
                                selected = ModelPickerItems.isSelected(group, model, selectedModel),
                                onClick = {
                                    // The ref carries the tapped row's own group
                                    // provider and its entry id (see
                                    // `selectionRef`): same-name models — and
                                    // siblings sharing one `modelID` — stay
                                    // independently selectable.
                                    val (providerID, modelID) =
                                        ModelPickerItems.selectionRef(group, model)
                                    onSelect(providerID, modelID)
                                },
                            )
                        }
                    }
                }
            }
        }
        Hairline()

        // Bottom action: drop the local override, follow `config.model` again.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .height(MENU_ROW_HEIGHT)
                .clip(OcTheme.shapes.sm)
                .clickable(role = Role.Button, onClick = onUseServerDefault)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.oc_model_picker_use_default),
                color = if (!overrideActive) c.textBase else c.textMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!overrideActive) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_check),
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // B-13: `select-dialog.tsx:544-556` — a `border-t` block under the list
        // whose whole width is one button: `outline-sliders` + `dialog.model.manage`
        // (`h-9`, radius 6, `px-3`, `gap-2`, 13px medium). Tapping it leaves the
        // picker for Settings→Models, which owns the visibility toggles and the
        // connect entry — this row deliberately copies none of that UI.
        if (onManageModels != null) {
            Hairline()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .height(MANAGE_ROW_HEIGHT)
                    .clip(OcTheme.shapes.sm)
                    .clickable(role = Role.Button, onClick = onManageModels)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OcIcon(icon = SettingsIcon.SLIDERS, tint = c.textBase, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.dialog_model_manage),
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** `[data-slot=menu-v2-item]` — 13px, hover `overlay-hover`, radius 6. */
@Composable
private fun ModelPickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
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

/** `h-px bg-v2-border-border-muted` separator of the upstream popover. */
@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(OcTheme.colors.borderMuted),
    )
}

/** Upstream search row (`h-7` + padding) at a touch-friendly height. */
private val SEARCH_ROW_HEIGHT = 36.dp

/** Frames to keep retrying the popup search autofocus while the window attaches. */
private const val SEARCH_FOCUS_ATTEMPTS = 4

/** `ScrollView max-h-[220px]` of `ModelSelectorPopoverView`. */
private val LIST_MAX_HEIGHT = 280.dp

/** `[data-slot=menu-v2-item]` row height — a comfortable touch target. */
private val MENU_ROW_HEIGHT = 32.dp

/** `h-9` — the「管理模型」button (`select-dialog.tsx:546`). */
private val MANAGE_ROW_HEIGHT = 36.dp
