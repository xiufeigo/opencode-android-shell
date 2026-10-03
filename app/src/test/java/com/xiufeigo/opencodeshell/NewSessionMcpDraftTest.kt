package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.home.McpApplyOutcome
import com.xiufeigo.opencodeshell.ui.home.NewSessionGitStatus
import com.xiufeigo.opencodeshell.ui.home.NewSessionMcpDraft
import com.xiufeigo.opencodeshell.ui.home.NewSessionTips
import com.xiufeigo.opencodeshell.ui.settings.McpPageRow
import com.xiufeigo.opencodeshell.v2.V2Api
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the new-session draft page's MCP switches (F-2,
 * `new-session/mcp.ts:11-90`), its git status (`PromptGitStatus`,
 * `new-session/workspace/selector.tsx:374-396`) and its onboarding tips
 * (`new-session/view.tsx:167-200`).
 */
class NewSessionMcpDraftTest {

    private fun rows(vararg pairs: Pair<String, String>): List<McpPageRow> =
        pairs.map { McpPageRow(it.first, it.second, null) }

    // ------------------------------------------------------------ switch state

    @Test
    fun theSwitchFallsBackToTheServersOwnState() {
        assertTrue(NewSessionMcpDraft.defaultEnabled("connected"))
        assertTrue(NewSessionMcpDraft.defaultEnabled("failed"))
        assertTrue(NewSessionMcpDraft.defaultEnabled(null))
        // `disabled` is the only status upstream paints as off
        // (`server-panel.tsx:226`).
        assertFalse(NewSessionMcpDraft.defaultEnabled("disabled"))
        assertFalse(NewSessionMcpDraft.defaultEnabled("Disabled"))

        assertFalse(NewSessionMcpDraft.isEnabled(emptyMap(), "a", "disabled"))
        assertTrue(NewSessionMcpDraft.isEnabled(emptyMap(), "a", "connected"))
        // The draft wins over the server until Create applies it.
        assertTrue(NewSessionMcpDraft.isEnabled(mapOf("a" to true), "a", "disabled"))
        assertFalse(NewSessionMcpDraft.isEnabled(mapOf("a" to false), "a", "connected"))
    }

    @Test
    fun withStateKeepsTheOtherOverridesAndTheInsertionOrder() {
        val states = NewSessionMcpDraft.withState(
            NewSessionMcpDraft.withState(emptyMap(), "b", true),
            "a",
            false,
        )
        assertEquals(listOf("b", "a"), states.keys.toList())
        assertEquals(mapOf("b" to true, "a" to false), states)
    }

    // ------------------------------------------------------------- the catalog

    @Test
    fun serversAreSortedByNameNotByConnectionState() {
        // `mcpPageRows` orders connected-first for the chooser dialog; the draft
        // list is upstream's `toSorted(name.localeCompare)`.
        val json = """[{"name":"zeta","status":{"status":"connected"}},
                       {"name":"alpha","status":{"status":"failed"}},
                       {"name":"Beta","status":{"status":"disabled"}}]"""
        assertEquals(listOf("alpha", "Beta", "zeta"), NewSessionMcpDraft.servers(json).map { it.name })
        assertTrue(NewSessionMcpDraft.servers(null).isEmpty())
        assertTrue(NewSessionMcpDraft.servers("not json").isEmpty())
    }

    // ------------------------------------------------------------ apply(): diff

    @Test
    fun writesOnlyCoverTheServersWhoseStateDiffers() {
        val catalog = rows("a" to "connected", "b" to "disabled", "c" to "failed")
        // a already connected → no write; b already disabled → no write;
        // c is enabled but failed → connect.
        assertEquals(
            listOf("c" to true),
            NewSessionMcpDraft.writes(catalog, mapOf("a" to true, "b" to false, "c" to true))
                .map { it.name to it.enabled },
        )
        // The same draft against a server whose `a` is down now writes it.
        assertEquals(
            listOf("a" to true),
            NewSessionMcpDraft.writes(rows("a" to "failed"), mapOf("a" to true)).map { it.name to it.enabled },
        )
        // An untouched draft writes nothing at all.
        assertTrue(NewSessionMcpDraft.writes(catalog, emptyMap()).isEmpty())
    }

    @Test
    fun aNameTheDirectoryDoesNotOfferIsOnlyAFailureWhenEnabled() {
        val catalog = rows("a" to "connected")
        assertEquals("missing", NewSessionMcpDraft.unavailable(catalog, mapOf("missing" to true)))
        // Turning an absent name **off** is not upstream's `unavailable` case
        // (`mcp.ts:64` only checks `enabled` entries).
        assertNull(NewSessionMcpDraft.unavailable(catalog, mapOf("missing" to false)))
        assertNull(NewSessionMcpDraft.unavailable(catalog, mapOf("a" to true)))
        assertNull(NewSessionMcpDraft.unavailable(catalog, emptyMap()))
    }

    @Test
    fun writesSkipNamesTheDirectoryDoesNotOffer() {
        // `mcp.ts:70-76` filters to servers that exist, so the unavailable check
        // is what reports the missing one — never a connect on a phantom name.
        val catalog = rows("a" to "connected")
        assertTrue(NewSessionMcpDraft.writes(catalog, mapOf("missing" to true)).isEmpty())
    }

    // --------------------------------------------------- apply(): verification

    @Test
    fun verificationReportsTheFirstOverrideThatDidNotHold() {
        // Enabled but still `pending` → not ready.
        val pending = rows("a" to "pending")
        assertEquals(
            "a" to false,
            NewSessionMcpDraft.unresolved(pending, mapOf("a" to true))!!.let { it.name to it.needsAuth },
        )
        // Enabled and needing a sign-in → upstream's own copy picks that.
        val auth = rows("a" to "needs_auth")
        assertTrue(NewSessionMcpDraft.unresolved(auth, mapOf("a" to true))!!.needsAuth)
        // The hyphenated spelling the integration rows use counts too.
        assertTrue(NewSessionMcpDraft.unresolved(rows("a" to "needs-auth"), mapOf("a" to true))!!.needsAuth)
    }

    @Test
    fun verificationAcceptsConnectedAndDisabledAndAMissingRow() {
        assertNull(NewSessionMcpDraft.unresolved(rows("a" to "connected"), mapOf("a" to true)))
        assertNull(NewSessionMcpDraft.unresolved(rows("a" to "disabled"), mapOf("a" to false)))
        // `mcp.ts:82` `status !== undefined`: a server the page no longer lists
        // counts as resolved.
        assertNull(NewSessionMcpDraft.unresolved(rows("other" to "connected"), mapOf("a" to false)))
        // Disabling a server the server still reports as connected is unresolved.
        assertEquals(
            "a",
            NewSessionMcpDraft.unresolved(rows("a" to "connected"), mapOf("a" to false))!!.name,
        )
        assertNull(NewSessionMcpDraft.unresolved(emptyList(), emptyMap()))
    }

    @Test
    fun theOutcomeTypeCarriesUpstreamsFourMessages() {
        assertEquals(McpApplyOutcome.Ready, McpApplyOutcome.Ready)
        assertEquals("x", McpApplyOutcome.Unavailable("x").name)
        assertEquals("y", McpApplyOutcome.NeedsAuth("y").name)
        assertEquals("z", McpApplyOutcome.NotReady("z").name)
        assertEquals("boom", McpApplyOutcome.Failed("boom").detail)
        assertNull(McpApplyOutcome.Failed(null).detail)
    }

    // -------------------------------------------------------------- git status

    @Test
    fun gitStatusSeparatesNoRepositoryFromNoCurrentBranch() {
        // Measured on 2.0.11: a repository answers
        // `{provider:"git",branch:{current,default}}`, anything else
        // `{branch:{}}` (both fields absent).
        val repo = NewSessionGitStatus.Info(current = "main", default = "main", provider = "git")
        assertEquals(NewSessionGitStatus.Kind.BRANCH, NewSessionGitStatus.kind(repo))
        // A directory that is not a repository at all.
        val plain = NewSessionGitStatus.Info(current = null, default = null, provider = null)
        assertEquals(NewSessionGitStatus.Kind.NO_GIT, NewSessionGitStatus.kind(plain))
        // A read that failed says nothing, so nothing is drawn.
        assertEquals(NewSessionGitStatus.Kind.NONE, NewSessionGitStatus.kind(null))
    }

    /**
     * v64 (`research/v64_verify/5556/01_07_wt_label.png` → 「No Git」,
     * `01_73_happytiger_label.png` → an empty chip): every worktree opencode
     * creates is `git worktree add --detach` (`core/src/git.ts:654-658`), so
     * `branch.current` — `symbolic-ref --short HEAD` — is empty for one by
     * construction. A repository with an empty current branch is **detached**,
     * never 「No Git」, and a fresh worktree the server has not read yet
     * (all-empty `Vcs.Info`) is detached too, not a non-repository.
     */
    @Test
    fun aWorktreeIsDetachedRatherThanNoGit() {
        val detached = NewSessionGitStatus.Info(current = null, default = "main", provider = "git")
        assertEquals(NewSessionGitStatus.Kind.DETACHED, NewSessionGitStatus.kind(detached))
        assertNull(NewSessionGitStatus.branch(detached))
        // Both fields empty: the shape a just-created worktree answers.
        val fresh = NewSessionGitStatus.Info(current = null, default = null, provider = "git")
        assertEquals(NewSessionGitStatus.Kind.DETACHED, NewSessionGitStatus.kind(fresh))
        // The same empty shape with no provider: the owning **project**'s `vcs`
        // is what answers for it (`resolveNewSessionGit`, `controller.ts:36-38`),
        // so it needs no second read.
        val unread = NewSessionGitStatus.Info(current = "", default = "", provider = "", projectVcs = "git")
        assertFalse(NewSessionGitStatus.needsRecheck(unread))
        assertEquals(NewSessionGitStatus.Kind.DETACHED, NewSessionGitStatus.kind(unread))
        // `provider` alone is enough: a repository whose default branch is unknown.
        assertEquals(
            NewSessionGitStatus.Kind.DETACHED,
            NewSessionGitStatus.kind(NewSessionGitStatus.Info(null, null, "git")),
        )
        // And so is `default` alone, with nothing else to go on.
        assertEquals(
            NewSessionGitStatus.Kind.DETACHED,
            NewSessionGitStatus.kind(NewSessionGitStatus.Info(null, "main", null)),
        )
        // A read that failed is re-read, a repository that answered is not.
        assertTrue(NewSessionGitStatus.needsRecheck(null))
        assertFalse(NewSessionGitStatus.needsRecheck(detached))
        // The all-empty shape is ambiguous on its own, so it is asked again too.
        assertTrue(NewSessionGitStatus.needsRecheck(plain()))
        assertTrue(NewSessionGitStatus.RECHECK_MS > 0)
    }

    @Test
    fun aDetachedWorktreeIsNamedByTheBranchItWasCreatedFrom() {
        val detached = NewSessionGitStatus.Info(current = null, default = "main", provider = "git")
        assertEquals("main", NewSessionGitStatus.branch(detached, "main"))
        // The server's own branch always wins over the create record.
        val checkedOut = NewSessionGitStatus.Info(current = "dev", default = "main", provider = "git")
        assertEquals(NewSessionGitStatus.Kind.BRANCH, NewSessionGitStatus.kind(checkedOut))
        assertEquals("dev", NewSessionGitStatus.branch(checkedOut, "main"))
        // Blank values are not names.
        assertNull(NewSessionGitStatus.branch(detached, "  "))
        assertEquals("dev", NewSessionGitStatus.branch(checkedOut))
    }

    private fun plain() = NewSessionGitStatus.Info(current = null, default = null, provider = null)

    // ------------------------------------------------------------------- tips

    @Test
    fun aTipShowsUntilItIsDismissedForThirtyDays() {
        val now = 1_000_000_000_000L
        assertTrue(NewSessionTips.visible(true, 0L, now))
        assertFalse(NewSessionTips.visible(false, 0L, now))
        assertFalse(NewSessionTips.visible(true, now, now))
        assertFalse(NewSessionTips.visible(true, now - NewSessionTips.DISMISSAL_MS + 1, now))
        assertTrue(NewSessionTips.visible(true, now - NewSessionTips.DISMISSAL_MS, now))
        assertEquals(30L * 24 * 60 * 60 * 1000, NewSessionTips.DISMISSAL_MS)
    }

    @Test
    fun theProviderTipWinsWhileBothAreEligible() {
        val now = 1_000_000_000_000L
        assertEquals(
            NewSessionTips.Tip.PROVIDER,
            NewSessionTips.which(true, true, 0L, 0L, now),
        )
        assertEquals(
            NewSessionTips.Tip.WORKSPACE,
            NewSessionTips.which(false, true, 0L, 0L, now),
        )
        assertNull(NewSessionTips.which(false, false, 0L, 0L, now))
        // A dismissed provider tip uncovers the workspace one, like upstream's
        // `tip()` memo falling through.
        assertEquals(
            NewSessionTips.Tip.WORKSPACE,
            NewSessionTips.which(true, true, now, 0L, now),
        )
        assertNull(NewSessionTips.which(true, true, now, now, now))
    }

    @Test
    fun theProviderTipCountsConnectedPaidProvidersOnly() {
        val providers = listOf(
            provider("minimax", "minimax"),
            provider("opencode", "opencode"),
            provider("unlinked", null),
        )
        val integrations = listOf(integration("minimax", 1), integration("opencode", 2))
        // Both connected integrations count, the free `opencode` one does not
        // (`providers/catalog/providers.ts:74-83`).
        assertEquals(1, NewSessionTips.paidProviderCount(providers, integrations))
        assertEquals(0, NewSessionTips.paidProviderCount(providers, listOf(integration("minimax", 0))))
        assertEquals(0, NewSessionTips.paidProviderCount(emptyList(), integrations))
        assertEquals("opencode", NewSessionTips.FREE_PROVIDER)
    }

    private fun provider(id: String, integrationID: String?): V2Api.ProviderInfo = V2Api.ProviderInfo(
        id = id,
        name = id,
        activation = "enabled",
        integrationID = integrationID,
        pkg = null,
        apiType = null,
        apiPackage = null,
        apiUrl = null,
        raw = JSONObject().put("id", id),
    )

    private fun integration(id: String, connections: Int): V2Api.IntegrationInfo = V2Api.IntegrationInfo(
        id = id,
        name = id,
        methodTypes = emptyList(),
        connections = connections,
        raw = JSONObject().put("id", id),
    )
}
