package com.xiufeigo.opencodeshell.ui.settings

import org.json.JSONObject

/**
 * P0 Models — per-model visibility + group collapse state.
 *
 * Upstream (`settings/models/models.tsx:45-71,130-207`,
 * `providers/models/models.tsx:95-113`):
 *
 * - visibility is a per-`{providerID,modelID}` map (`show`/`hide`/unset);
 *   unset means "visible iff in the latest set or without a parseable release
 *   date". The model picker (`providers/models/select-dialog.tsx:270`)
 *   filters on `visible()`, so the Models tab exists *to drive* that picker.
 * - group collapse is a per-provider-id `collapsed` map persisted in
 *   `settings-v2.models.providers` (`ModelProvidersSchema`), and groups sort
 *   popular-first (`popularProviders` order) then by provider name.
 *
 * This file is the pure layer: [ModelVisibility] encodes/decodes the override
 * map (the shape [SettingsStore] persists), [isModelVisible] applies the
 * upstream default rule, and [modelGroupOrder]/[sortedModelGroups] apply the
 * group ordering. Collapse state is keyed by provider id in the same map
 * shape.
 */
object ModelVisibility {

    /** One visibility override — the JSON value upstream stores per key. */
    enum class Override(val wire: String) {
        SHOW("show"),
        HIDE("hide"),
        ;

        companion object {
            fun fromWire(raw: String?): Override? = entries.firstOrNull { it.wire == raw }
        }
    }

    /** `providerID:modelID` — the same key encoding `models.tsx` uses. */
    fun key(providerID: String, modelID: String): String = "$providerID:$modelID"

    /**
     * Upstream `visible()` (`models.tsx:95-103`): an explicit override wins;
     * otherwise a model is visible when it is in the latest set or has no
     * parseable release date. [inLatestSet] and [hasReleaseDate] carry the two
     * signals the native picker can compute; both default to the visible side
     * so unknown models never vanish.
     */
    fun isVisible(
        providerID: String,
        modelID: String,
        overrides: Map<String, String>,
        inLatestSet: Boolean = true,
        hasReleaseDate: Boolean = false,
    ): Boolean {
        return when (Override.fromWire(overrides[key(providerID, modelID)])) {
            Override.SHOW -> true
            Override.HIDE -> false
            null -> inLatestSet || !hasReleaseDate
        }
    }

    fun setVisible(
        overrides: Map<String, String>,
        providerID: String,
        modelID: String,
        visible: Boolean,
    ): Map<String, String> {
        val next = overrides.toMutableMap()
        next[key(providerID, modelID)] = if (visible) Override.SHOW.wire else Override.HIDE.wire
        return next
    }

    /** Inverse of [encode]: malformed input reads as "no overrides". */
    fun decode(raw: String?): Map<String, String> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyMap()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.optString(key, "")
            if (key.isNotBlank() && Override.fromWire(value) != null) out[key] = value
        }
        return out
    }

    fun encode(overrides: Map<String, String>): String {
        val obj = JSONObject()
        for ((key, value) in overrides) {
            if (key.isBlank()) continue
            if (Override.fromWire(value) == null) continue
            obj.put(key, value)
        }
        return obj.toString()
    }

    /** Collapse map (`ModelProvidersSchema.collapsed`) codec — same envelope. */
    fun decodeCollapsed(raw: String?): Map<String, Boolean> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyMap()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyMap()
        val out = linkedMapOf<String, Boolean>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.isNotBlank()) out[key] = obj.optBoolean(key, false)
        }
        return out
    }

    fun encodeCollapsed(collapsed: Map<String, Boolean>): String {
        val obj = JSONObject()
        for ((key, value) in collapsed) {
            if (key.isNotBlank()) obj.put(key, value)
        }
        return obj.toString()
    }
}

/**
 * Popular-first group ordering (`models.tsx:57-70`): groups whose provider is
 * in [ProviderConnectCatalog.POPULAR_ORDER] sort by that index, then provider
 * display name. A group is collapsed when its provider id is in [collapsed]
 * (`models.tsx:132-133`); an active search forces every group expanded.
 */
fun modelGroupOrder(
    providerIDs: List<String>,
    providerNames: Map<String, String> = emptyMap(),
): List<String> {
    val popular = ProviderConnectCatalog.POPULAR_ORDER
    return providerIDs.sortedWith(
        compareBy(
            {
                val index = popular.indexOf(it)
                if (index >= 0) 0 else 1
            },
            {
                val index = popular.indexOf(it)
                if (index >= 0) index else Int.MAX_VALUE
            },
            { (providerNames[it] ?: it).lowercase() },
            { it },
        ),
    )
}

/** A provider group of [ModelRow]s, in upstream render order. */
data class ModelGroup(
    val providerID: String,
    val providerName: String,
    val rows: List<ModelRow>,
    val collapsed: Boolean,
)

fun sortedModelGroups(
    rows: List<ModelRow>,
    collapsed: Map<String, Boolean> = emptyMap(),
    searching: Boolean = false,
): List<ModelGroup> {
    if (rows.isEmpty()) return emptyList()
    val names = rows.groupBy { it.providerID }.mapValues { (_, group) -> group.first().providerName }
    return modelGroupOrder(rows.map { it.providerID }.distinct(), names).map { providerID ->
        val group = rows.filter { it.providerID == providerID }
        ModelGroup(
            providerID = providerID,
            providerName = names[providerID] ?: providerID,
            rows = group.sortedBy { it.name.lowercase() },
            collapsed = if (searching) false else collapsed[providerID] == true,
        )
    }
}
