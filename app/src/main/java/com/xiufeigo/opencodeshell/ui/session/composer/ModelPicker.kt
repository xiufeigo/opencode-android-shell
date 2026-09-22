package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.settings.ModelVisibility
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Pure picker data logic, extracted for JVM tests (`ModelPickerTest`). Mirrors
 * the upstream model selector (`_upstream/packages/app/src/providers/models/`
 * `select-dialog.tsx` + `search.ts`): connected providers only, popular
 * providers first, local search over name/id/provider.
 */
object ModelPickerItems {

    /** One provider section of the picker (upstream `sortModelGroups` input). */
    data class Group(
        val providerID: String,
        val providerName: String,
        val models: List<OpenCodeClient.Providers.Model>,
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
     * Connected providers with models, in picker order: popular first (in
     * [POPULAR] order), the rest alphabetical by provider name; models inside a
     * group are sorted by name, like upstream's flat sort before grouping.
     *
     * WAVE 2.3a consumer: [visibility] is `SettingsStore.modelVisibility()` —
     * models hidden on the Models tab never reach the picker (upstream
     * `select-dialog.tsx:270` filters on `visible()`); empty keeps everything.
     */
    fun groups(
        providers: OpenCodeClient.Providers?,
        visibility: Map<String, String> = emptyMap(),
    ): List<Group> {
        if (providers == null) return emptyList()
        val connected = providers.connected.toSet()
        if (connected.isEmpty()) return emptyList()
        return providers.all
            .filter { it.id in connected && it.models.isNotEmpty() }
            .map { provider ->
                val models = provider.models
                    .filter { ModelVisibility.isVisible(provider.id, it.id, visibility) }
                    .sortedBy { it.name }
                Group(provider.id, provider.name, models)
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
                matches(query, model.name, model.id, group.providerName)
            }
            if (models.isEmpty()) null else group.copy(models = models)
        }
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
) {
    val c = OcTheme.colors
    var search by remember { mutableStateOf("") }
    // WAVE 2.3a consumer: the Models tab Switch map, read live so a toggle
    // takes effect the next time the picker opens (no composer changes needed).
    val context = LocalContext.current
    val visibility = remember(context) { SettingsStore(context).modelVisibility() }
    val allGroups = remember(providers, visibility) { ModelPickerItems.groups(providers, visibility) }
    val visibleGroups = remember(allGroups, search) { ModelPickerItems.filter(allGroups, search) }
    // The variant control follows the *effective* model, exactly like the chip:
    // the pinned model when the user picked one, else the server default.
    val effectiveModel = selectedModel?.let { (providerID, modelID) ->
        providers?.model(providerID, modelID)
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
                modifier = Modifier.weight(1f),
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
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(OcTheme.shapes.sm)
                        .clickable(role = Role.Button) { search = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_oc_close),
                        contentDescription = stringResource(R.string.oc_home_clear_search),
                        tint = c.iconMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Hairline()

        // W5-C B4: variant group (only while the effective model advertises one).
        if (variants.isNotEmpty()) {
            Text(
                text = stringResource(R.string.oc_composer_variant_group),
                color = c.textFaint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
            )
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
                        Text(
                            text = group.providerName,
                            color = c.textFaint,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        items = group.models,
                        key = { model -> "model:${group.providerID}/${model.id}" },
                    ) { model ->
                        ModelPickerRow(
                            label = model.name,
                            selected = selectedModel != null &&
                                selectedModel.first == group.providerID &&
                                selectedModel.second == model.id,
                            onClick = { onSelect(group.providerID, model.id) },
                        )
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

/** `ScrollView max-h-[220px]` of `ModelSelectorPopoverView`. */
private val LIST_MAX_HEIGHT = 280.dp

/** `[data-slot=menu-v2-item]` row height — a comfortable touch target. */
private val MENU_ROW_HEIGHT = 32.dp
