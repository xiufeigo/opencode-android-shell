package com.xiufeigo.opencodeshell.ui.settings

import org.json.JSONObject

/**
 * P0 Models — per-entry visibility + group collapse state.
 *
 * Upstream (`settings/models/models.tsx:45-71,130-207`,
 * `providers/models/models.tsx:95-113`):
 *
 * - visibility is a per-entry map (`show`/`hide`/unset); unset means "visible
 *   iff in the latest set or without a parseable release date". The model picker
 *   (`providers/models/select-dialog.tsx:270`) filters on `visible()`, so the
 *   Models tab exists *to drive* that picker. Upstream's key is
 *   `{providerID, modelID: item.id}` (`models.tsx:186-199`) — the **catalog
 *   entry** id (`Model.Info.id` → `Model.entryID`), not the wire `modelID`:
 *   one provider can list several entries under one `modelID` (measured on the
 *   live v2 backend: `openai` ships `gpt-5.6`, `gpt-5.6-fast` and
 *   `gpt-5.6-pro`, all reporting `modelID: "gpt-5.6"`), and keying by the wire
 *   id made one Switch toggle every sibling. [key] is therefore
 *   `providerID:entryID`.
 * - group collapse is a per-provider-id `collapsed` map persisted in
 *   `settings-v2.models.providers` (`ModelProvidersSchema`), and groups sort
 *   popular-first (`popularProviders` order) then by provider name.
 *
 * This file is the pure layer: [ModelVisibility] encodes/decodes the override
 * map (the shape [SettingsStore] persists), [isVisible] applies the upstream
 * default rule, and [modelGroupOrder]/[sortedModelGroups] apply the group
 * ordering. Collapse state is keyed by provider id in the same map shape.
 *
 * Legacy keys: builds before entry keys wrote `providerID:modelID` (the wire
 * id), so every override persisted back then still resolves — [isVisible] falls
 * back to that key when the entry has none of its own.
 *
 * The fallback has one sharp edge worth naming: the **base** entry's key *is* the
 * legacy key (`gpt-5.6`'s entry id equals its model id), so a plain write to the
 * base would leak to its siblings through the fallback. [toggleWrites] is what the
 * Models tab calls instead: it pins every sibling to its current state first, which
 * keeps a flip entry-level without giving up the legacy read.
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

    /**
     * `providerID:entryID` — the same encoding `models.tsx:186-199` builds from
     * the catalog entry id ([ModelRow.entryID], `Model.Info.id`).
     */
    fun key(providerID: String, entryID: String): String = "$providerID:$entryID"

    /**
     * Upstream `visible()` (`models.tsx:95-103`): an explicit override wins;
     * otherwise a model is visible when it is in the latest set or has no
     * parseable release date. [inLatestSet] and [hasReleaseDate] carry the two
     * signals the native picker can compute; both default to the visible side
     * so unknown models never vanish.
     *
     * [modelID] is the entry's wire model id, used only as the **legacy
     * fallback** key (see the file header); it defaults to [entryID], which is
     * what a v1 catalog (no separate entry id) and the pre-entry-key build both
     * wrote.
     */
    fun isVisible(
        providerID: String,
        entryID: String,
        overrides: Map<String, String>,
        inLatestSet: Boolean = true,
        hasReleaseDate: Boolean = false,
        modelID: String = entryID,
    ): Boolean {
        return when (overrideFor(providerID, entryID, overrides, modelID)) {
            Override.SHOW -> true
            Override.HIDE -> false
            null -> inLatestSet || !hasReleaseDate
        }
    }

    /**
     * The override that applies to one entry: its own entry key first, then the
     * legacy `providerID:modelID` key an older build wrote. Null = unset (the
     * caller applies the upstream default rule).
     */
    fun overrideFor(
        providerID: String,
        entryID: String,
        overrides: Map<String, String>,
        modelID: String = entryID,
    ): Override? =
        Override.fromWire(overrides[key(providerID, entryID)])
            ?: Override.fromWire(overrides[key(providerID, modelID)])

    /**
     * Writes the override for one **entry** (`providerID:entryID`). A write never
     * touches the legacy key: the base entry's entry id equals its model id, so
     * for that one row the two keys are the same string, and clearing it would
     * throw away the user's own base override.
     */
    fun setVisible(
        overrides: Map<String, String>,
        providerID: String,
        entryID: String,
        visible: Boolean,
    ): Map<String, String> {
        val next = overrides.toMutableMap()
        next[key(providerID, entryID)] = if (visible) Override.SHOW.wire else Override.HIDE.wire
        return next
    }

    /** One Models-tab write: `providerID:entryID` ← [visible]. */
    data class EntryWrite(val providerID: String, val entryID: String, val visible: Boolean)

    /**
     * The writes one Models-tab Switch performs: [row] flipped to [visible], plus —
     * only when [row] is the **base** entry (`entryID == modelID`, whose key is
     * also the legacy key) — one explicit write per sibling so the flip cannot
     * reach them through the legacy fallback.
     *
     * The sibling write carries the sibling's *current* visibility ([ModelRow.visible],
     * the effective value [modelRows] already resolved), so materialising it never
     * changes what the sibling shows; it only pins that state to the sibling's own
     * key. Without it, hiding `gpt-5.6` would write `openai:gpt-5.6 = hide`, which
     * every `-fast`/`-pro` entry would inherit — the group switch this change
     * removes. [rows] must be the **unfiltered** row list, so a search cannot leave
     * a sibling unmaterialised.
     */
    fun toggleWrites(rows: List<ModelRow>, row: ModelRow, visible: Boolean): List<EntryWrite> {
        val own = EntryWrite(row.providerID, row.entryID, visible)
        if (row.entryID != row.id) return listOf(own)
        return buildList {
            add(own)
            for (sibling in rows) {
                if (sibling.providerID != row.providerID) continue
                if (sibling.id != row.id || sibling.entryID == row.entryID) continue
                add(EntryWrite(sibling.providerID, sibling.entryID, sibling.visible))
            }
        }
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
