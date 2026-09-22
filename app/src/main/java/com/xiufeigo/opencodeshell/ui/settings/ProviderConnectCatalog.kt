package com.xiufeigo.opencodeshell.ui.settings

/**
 * P0 Providers — v2-shaped provider-connection model on top of the EXISTING
 * `OpenCodeClient`/`SettingsServerApi` surface.
 *
 * Upstream (`settings/providers/providers.tsx:40-121,135-213`):
 *
 * - Connected section (`providers.connected()`, filtered so `opencode` only
 *   counts when it has a priced model) sorted `opencode-go` first, then the
 *   Popular section (`popularProviders` order in
 *   `providers/catalog/order.ts`) minus the connected ids, with per-row source
 *   badges (env/api/config/custom/other), per-provider notes, a recommended
 *   badge for `opencode`/`opencode-go`, and a "view all" entry opening the
 *   full connect dialog (`DialogConnectProvider`).
 * - Connection state comes from the integration list (`useIntegrations`):
 *   a `credential` connection ⇒ api-key/OAuth grant, `env` ⇒ environment
 *   detection, a connectionless integration ⇒ config-provided. Disconnect
 *   removes the credential connections
 *   (`GET /api/integration/{id}` → `DELETE /api/credential/{id}`,
 *   `providers.tsx:100-121`).
 *
 * The v1 half of this app's servers (`GET /provider`, `GET /mcp`,
 * `GET /skill` …) exposes none of those endpoints, so the full read/write
 * flow cannot run today. This file is the pure layer for what CAN ship now:
 *
 * - [providerSourceRows] pins connected providers first (`opencode-go`
 *   first, upstream's `toSorted`), then the popular order;
 * - [providerSourceBadge]/[providerDisconnectable] encode the env/api/config
 *   rule when an integration snapshot is available, and degrade honestly
 *   (`other`) when it is not;
 * - [ProviderConnectForm.validateApiKey] validates the API-key connect form
 *   (`providers/connect/controller.ts` + `connect/key` openapi body
 *   `{key, answer?, label?}`) so a later connect dialog cannot send a blank
 *   key.
 *
 * The actual HTTP calls (`GET /api/integration[/{id}]`,
 * `POST /api/integration/{id}/connect/key`, `DELETE /api/credential/{id}`,
 * `POST /api/experimental/mcp/{server}/{connect,disconnect}`,
 * `GET /api/plugin`) are a handoff to the protocol agent — see
 * `research/v2_audit/wave2.3a-notes.md` §1.
 */
object ProviderConnectCatalog {

    /** `providers/catalog/order.ts` — the exact upstream popular order. */
    val POPULAR_ORDER: List<String> = listOf(
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
     * Provider notes (`PROVIDER_NOTES` in `providers.tsx:18-27`): the
     * `dialog.provider.*` i18n key rendered under the popular row's name.
     * Null = the row has no note (upstream renders nothing).
     */
    fun providerNoteKey(providerID: String): String? = when {
        providerID == "opencode" -> "dialog.provider.opencode.note"
        providerID == "opencode-go" -> "dialog.provider.opencodeGo.tagline"
        providerID == "anthropic" -> "dialog.provider.anthropic.note"
        providerID.startsWith("github-copilot") -> "dialog.provider.copilot.note"
        providerID == "openai" -> "dialog.provider.openai.note"
        providerID == "google" -> "dialog.provider.google.note"
        providerID == "openrouter" -> "dialog.provider.openrouter.note"
        providerID == "vercel" -> "dialog.provider.vercel.note"
        else -> null
    }

    /** `dialog.provider.tag.recommended` — `opencode` + `opencode-go` only. */
    fun isRecommended(providerID: String): Boolean =
        providerID == "opencode" || providerID == "opencode-go"

    /**
     * Connection source (`ProviderSource` in `providers.tsx:15`):
     * `env` = environment variables detected, `api` = API key/OAuth grant,
     * `config` = config-provided, `custom` = custom provider, `other` = the
     * panel could not determine one (upstream's fallback label).
     */
    enum class ProviderSource { ENV, API, CONFIG, CUSTOM, OTHER }

    /**
     * The badge text's i18n key for [source]
     * (`settings.providers.tag.environment`, `provider.connect.method.apiKey`,
     * `settings.providers.tag.config`, `settings.providers.tag.custom`,
     * `settings.providers.tag.other` — `providers.tsx:82-89`).
     */
    fun sourceBadgeKey(source: ProviderSource): String = when (source) {
        ProviderSource.ENV -> "settings.providers.tag.environment"
        ProviderSource.API -> "provider.connect.method.apiKey"
        ProviderSource.CONFIG -> "settings.providers.tag.config"
        ProviderSource.CUSTOM -> "settings.providers.tag.custom"
        ProviderSource.OTHER -> "settings.providers.tag.other"
    }

    /**
     * Source resolution (`source()` in `providers.tsx:71-80`) from the
     * integration snapshot. [connectionTypes] are the integration's
     * connection `type` values (`credential`/`env`/…); [hasIntegration] is
     * whether an integration entry exists at all; [catalogSource] is the
     * provider catalog's own `source` field when integrations are absent.
     */
    fun resolveSource(
        connectionTypes: List<String>,
        hasIntegration: Boolean,
        catalogSource: String?,
    ): ProviderSource {
        if (connectionTypes.any { it == "credential" }) return ProviderSource.API
        if (connectionTypes.any { it == "env" }) return ProviderSource.ENV
        if (hasIntegration) return ProviderSource.CONFIG
        return when (catalogSource) {
            "env" -> ProviderSource.ENV
            "api" -> ProviderSource.API
            "config" -> ProviderSource.CONFIG
            "custom" -> ProviderSource.CUSTOM
            else -> ProviderSource.OTHER
        }
    }

    /**
     * Disconnect availability (`canDisconnect()` in `providers.tsx:91-96`):
     * a credential connection can be removed; without an integration entry a
     * provider is removable unless its source is env/config.
     */
    fun canDisconnect(
        connectionTypes: List<String>,
        hasIntegration: Boolean,
        catalogSource: String?,
    ): Boolean {
        if (hasIntegration) return connectionTypes.any { it == "credential" }
        val source = resolveSource(connectionTypes, hasIntegration = false, catalogSource = catalogSource)
        return source != ProviderSource.ENV && source != ProviderSource.CONFIG
    }

    /**
     * Connected rows in render order (`connected()` in `providers.tsx:48-56`,
     * drained of the priced-model `opencode` filter which needs model cost
     * data the v1 catalog does carry — see [providerSourceRows]).
     */
    fun connectedOrder(ids: List<String>): List<String> =
        ids.sortedWith(compareBy({ if (it == "opencode-go") 0 else 1 }, { it }))

    /**
     * Popular rows in render order (`popular()` in `providers.tsx:58-66`):
     * [POPULAR_ORDER] order, minus connected ids.
     */
    fun popularOrder(connectedIDs: Set<String>): List<String> =
        POPULAR_ORDER.filter { it !in connectedIDs }

    /**
     * Full render order for the providers tab: connected first
     * (`opencode-go` pinned, `providers.tsx:55`), then popular ids that are
     * not already connected and not already present in the catalog
     * (`providers.tsx:58-66`).
     */
    fun providerSourceRows(rows: List<ProviderRow>, pricedOpencode: Boolean = true): List<ProviderRow> {
        val connected = rows.filter { it.connected }
            .filter { it.id != "opencode" || pricedOpencode }
            .sortedWith(compareBy({ if (it.id == "opencode-go") 0 else 1 }, { it.name.lowercase() }, { it.id }))
        val connectedIDs = connected.map { it.id }.toSet()
        val popularSlots = POPULAR_ORDER.filter { id -> id !in connectedIDs && rows.none { it.id == id } }
        val popular = popularSlots.map { id ->
            ProviderRow(id = id, name = id, connected = false, modelCount = 0)
        }
        val rest = rows.filter { it.id !in connectedIDs && it.id !in popularSlots.toSet() }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        return connected + popular + rest
    }
}

/**
 * API-key connect form validation (`providers/connect/controller.ts` key
 * method + `POST /api/integration/{id}/connect/key` body `{key}` — required).
 * Returns the trimmed key, or null when the form must not submit.
 */
object ProviderConnectForm {
    fun validateApiKey(raw: String?): String? {
        val key = raw?.trim().orEmpty()
        return key.takeIf { it.isNotEmpty() }
    }
}
