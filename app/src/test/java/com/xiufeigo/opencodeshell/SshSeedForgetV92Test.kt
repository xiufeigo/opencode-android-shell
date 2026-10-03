package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshForgetState
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.SshTunnel
import com.xiufeigo.opencodeshell.ui.servers.SshTunnelHost
import com.xiufeigo.opencodeshell.ui.servers.confirmSshForget
import com.xiufeigo.opencodeshell.ui.servers.mergeSeededSshTunnels
import com.xiufeigo.opencodeshell.ui.servers.toRestingTunnel
import com.xiufeigo.opencodeshell.ui.servers.toSshEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v92 — the SSH list is a **persisted list** with an in-memory connection
 * layer (`research/v91_verify/`, P1): `ServerConfigStore.sshEntries()`
 * (`ssh_json`) is the durable list, `SshTunnelHost.tunnels` only carries live
 * connection state.
 *
 * Device evidence this answers (`research/v91_verify/5554+5556/`): leaving /
 * re-entering Settings dropped every SSH row, and a language switch (which
 * recreates the panel composition) dropped them permanently — while the
 * `E2ECHK5556-ssh` record sat orphaned in the encrypted prefs, unreachable
 * from any UI. The panel rendered SSH rows only from the in-memory host and
 * `sshEntries()` had no production reader at the time.
 *
 * Pinned here, mirroring the structural-assertion style of
 * [SshTunnelDeleteV91Test]:
 *
 *  1. the **seed merge** ([mergeSeededSshTunnels]): an id present in both
 *     keeps the live tunnel (it carries connection state the store never
 *     sees); an id only in prefs is adopted as a resting DISCONNECTED row
 *     ([toRestingTunnel]); an id only in memory (just submitted) stays;
 *  2. the **delete sticks** ([confirmSshForget]): the confirm path drops
 *     the persisted `ssh_json` record in the same call path as `host.forget`,
 *     so a seeded row cannot resurrect on the next seed;
 *  3. **reconnect/disconnect never touch persistence** — they only
 *     release/redial the live tunnel.
 *
 * NOTE — wiring landed (parent agent, same day): the panel seeds the
 * composition-scoped host from `sshEntries()` on every fresh composition,
 * the card's confirm runs [confirmSshForget] with the panel's persisted
 * remover (which also cascades to the loopback server row a READY tunnel
 * wrote, via `SshEntry.lastLocalUrl`), and submit persists the recipe
 * (secret-free, [toSshEntry]) plus the READY `lastLocalUrl`. The card stays
 * store-free: `removePersisted` is a panel-injected callback, the same
 * shape as the `ServerHealthSource` capability cast. §5 below pins the
 * landed wiring; §1–§4 pin the helpers and the memory-only paths.
 *
 * Design choice (panel-scoped seed, not the app-scoped host): the app-scoped
 * `SshTunnelHost` in `ui/App.kt` is published only on the session route
 * (`LocalSshTunnelHost` → `SshTunnelPanel`), which never renders the Servers
 * list — sharing it with Settings would couple the settings screen to the
 * session lifecycle and let either side's `forget` drop the other's live
 * dial state, for zero gain: the store, not the host, is the shared seam.
 * Seeding the panel-scoped host touches one consumer (the panel) instead of
 * every session-route reader.
 */
class SshSeedForgetV92Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt")

    private fun bridge() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsServerBridge.kt")

    private fun store() =
        source("src/main/java/com/xiufeigo/opencodeshell/ServerConfigStore.kt")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt")

    private fun forgetUi() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelForget.kt")

    private fun app() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/App.kt")

    private fun fnBody(text: String, signature: String): String =
        text.substringAfter(signature).substringBefore("\n    }\n")

    private fun tunnel(
        id: String,
        stage: SshStage = SshStage.FAILED,
        localUrl: String? = null,
    ): SshTunnel {
        val config = SshConnectionConfig(
            id = id,
            target = "ssh deploy@$id.example",
            name = "box $id",
            auth = SshAuthMethod.PASSWORD,
            password = "secret",
        )
        return SshTunnel(config, SshConnection(config), SshConnectionStatus(stage = stage, localUrl = localUrl))
    }

    private fun entry(
        id: String,
        authKind: ServerConfigStore.SshAuthKind = ServerConfigStore.SshAuthKind.PASSWORD,
        privateKeyPath: String? = null,
    ) = ServerConfigStore.SshEntry(
        id = id,
        target = "ssh deploy@$id.example",
        name = "box $id",
        authKind = authKind,
        privateKeyPath = privateKeyPath,
    )

    private fun liveHost(vararg tunnels: SshTunnel): SshTunnelHost {
        val host = SshTunnelHost(CoroutineScope(Dispatchers.Unconfined))
        for (tunnel in tunnels) host.submit(tunnel.config, tunnel.connection) { _, _ -> }
        return host
    }

    // ------------------------------------------------- 1. the resting adoption

    @Test
    fun restingAdoptionCarriesTheRedialRecipeButNoSecret() {
        val resting = entry("E2ECHK5556-ssh").toRestingTunnel()
        assertEquals("E2ECHK5556-ssh", resting.config.id)
        assertEquals("ssh deploy@E2ECHK5556-ssh.example", resting.config.target)
        assertEquals("box E2ECHK5556-ssh", resting.config.name)
        assertEquals(SshAuthMethod.PASSWORD, resting.config.auth)
        // A resting row renders exactly like a disconnected tunnel; reconnect
        // is a fresh dial over the carried config.
        assertEquals(SshStage.DISCONNECTED, resting.status.stage)
        assertNull(resting.status.localUrl)
        // Secrets are never persisted and never rehydrated: the password / key
        // passphrase is re-collected interactively on the next dial.
        assertNull(resting.config.password)
        assertNull(resting.config.passphrase)
    }

    @Test
    fun restingKeyEntryKeepsItsKeyPathAndAuthMethod() {
        val resting = entry("cloud", ServerConfigStore.SshAuthKind.KEY, "~/.ssh/id_ed25519").toRestingTunnel()
        assertEquals(SshAuthMethod.KEY, resting.config.auth)
        assertEquals("~/.ssh/id_ed25519", resting.config.privateKeyPath)
        assertEquals(SshStage.DISCONNECTED, resting.status.stage)
    }

    // ------------------------------------------------------- 2. the seed merge

    @Test
    fun mergeKeepsTheLiveTunnelAdoptsThePersistedAndKeepsTheUnsubmitted() {
        val liveReady = tunnel("a", SshStage.READY, "http://127.0.0.1:4101")
        val liveFailed = tunnel("b")
        val liveUnsubmitted = tunnel("d")
        val persisted = listOf(entry("b"), entry("c"), entry("E2ECHK5556-ssh"))
        val merged = mergeSeededSshTunnels(
            live = listOf(liveReady, liveFailed, liveUnsubmitted),
            persisted = persisted,
        )
        // Live order wins; persisted-only ids append in store order. The live
        // "d" (just submitted, not yet persisted) keeps its own slot — only
        // the two persisted-only ids append after it.
        assertEquals(listOf("a", "b", "d", "c", "E2ECHK5556-ssh"), merged.map { it.config.id })
        // An id present in both keeps the LIVE object — the persisted recipe
        // must not clobber connection state the store never sees.
        assertTrue(merged[0] === liveReady)
        assertTrue(merged[1] === liveFailed)
        assertTrue(merged[2] === liveUnsubmitted)
        assertEquals(SshStage.READY, merged[0].status.stage)
        assertEquals("http://127.0.0.1:4101", merged[0].status.localUrl)
        // An id only in prefs is adopted as a resting row carrying the recipe.
        assertEquals(SshStage.DISCONNECTED, merged[3].status.stage)
        assertEquals("ssh deploy@c.example", merged[3].config.target)
        assertNull(merged[3].config.password)
        // An id only in memory (just submitted this session) stays untouched.
        assertEquals(SshStage.FAILED, merged[2].status.stage)
    }

    @Test
    fun mergeWithEmptySidesIsIdentity() {
        val live = listOf(tunnel("a"), tunnel("b"))
        // Nothing persisted: the live list passes through unchanged.
        assertEquals(live, mergeSeededSshTunnels(live, emptyList()))
        // Nothing live: every persisted entry is adopted, in store order.
        val adopted = mergeSeededSshTunnels(emptyList(), listOf(entry("x"), entry("y")))
        assertEquals(listOf("x", "y"), adopted.map { it.config.id })
        assertTrue(adopted.all { it.status.stage == SshStage.DISCONNECTED })
        assertTrue(mergeSeededSshTunnels(emptyList(), emptyList()).isEmpty())
    }

    // ------------------------------------------- 3. the delete sticks (both halves)

    @Test
    fun confirmForgetsTheLiveRowAndThePersistedRecordTogether() {
        val host = liveHost(tunnel("a"), tunnel("E2ECHK5556-ssh"), tunnel("c"))
        val removed = mutableListOf<String>()
        val forgotten = confirmSshForget(SshForgetState().ask("E2ECHK5556-ssh"), "E2ECHK5556-ssh", host) {
            removed.add(it)
            true
        }
        assertEquals("E2ECHK5556-ssh", forgotten)
        // The in-memory entry is gone …
        assertEquals(listOf("a", "c"), host.tunnels.value.map { it.config.id })
        // … and the persisted record dropped in the SAME call path, so the
        // next seed cannot resurrect the row.
        assertEquals(listOf("E2ECHK5556-ssh"), removed)
        // The removal ran before the drop: a re-seed between the two still
        // sees the live row and keeps it (merge rule §2).
        val body = forgetUi().substringAfter("fun confirmSshForget(").substringBefore("\n}\n")
        assertTrue(body.indexOf("removePersisted(target)") < body.indexOf("host.forget(target)"))
    }

    @Test
    fun anUnarmedConfirmTouchesNeitherMemoryNorPersistence() {
        val host = liveHost(tunnel("a"))
        val removed = mutableListOf<String>()
        assertNull(confirmSshForget(SshForgetState(), "a", host) { removed.add(it); true })
        assertNull(confirmSshForget(SshForgetState().ask("a"), "b", host) { removed.add(it); true })
        assertEquals(listOf("a"), host.tunnels.value.map { it.config.id })
        assertTrue(removed.isEmpty())
    }

    // --------------------------------- 4. reconnect/disconnect stay memory-only

    @Test
    fun reconnectAndDisconnectNeverTouchPersistence() {
        val body = host()
        for (signature in listOf("fun reconnect(id: String) {", "fun disconnect(id: String) {")) {
            val fn = fnBody(body, signature)
            assertFalse("$signature must not read the store", fn.contains("sshEntries"))
            assertFalse("$signature must not write the store", fn.contains("putSshEntry"))
            assertFalse("$signature must not drop a record", fn.contains("removeSshEntry"))
            assertFalse("$signature must not forget", fn.contains("forget("))
        }
        // The card's own buttons stay on the same memory-only path: the
        // Reconnect / Disconnect taps never reach the persisted remover.
        val card = body.substringAfter("fun SshTunnelCard(")
        assertTrue(card.contains("onClick = { host.reconnect(tunnel.config.id) }"))
        assertTrue(card.contains("onClick = { host.disconnect(tunnel.config.id) }"))
        // The card itself stays store-free: persistence reaches it only as
        // the panel-injected `removePersisted` callback on the confirm path
        // (§5 pins the wiring); the store API names never appear in the card.
        assertFalse("the card never touches the persisted record", card.contains("removeSshEntry"))
        assertFalse("the card never writes the store", card.contains("putSshEntry"))
    }

    // ------------------------------------------------- 5. the diagnosis anchors
    //
    // The panel renders SSH rows only from `sshHost.tunnels` — the wiring the
    // helpers above are written for — so the anchors stay visible here: the
    // host stays panel-scoped (the app-scoped host serves the session route
    // only), and the persisted `sshEntries()` recipes are the durable list
    // the seed/forget helpers above operate on.

    @Test
    fun thePanelKeepsItsOwnHostAndTheSessionSeamIsUntouched() {
        val body = panel()
        // Still panel-scoped (the app-scoped host stays on the session route).
        assertTrue(body.contains("remember(sshScope) { SshTunnelHost(sshScope) }"))
        // The session seam is untouched: App.kt still holds the app-scoped
        // host and publishes it on the session route only.
        assertTrue(app().contains("LocalSshTunnelHost provides sshHost"))
    }

    @Test
    fun theBridgeExposesThePersistedSshListThroughThePreExistingStoreApi() {
        // The seam the seed/forget wiring reads: the bridge is built over the
        // store, and the store owns `ssh_json` through its pre-existing
        // `sshEntries()` / `removeSshEntry()` API — no new store surface.
        val body = store()
        assertTrue(body.contains("fun sshEntries()"))
        assertTrue(body.contains("fun removeSshEntry(id: String)"))
        assertTrue(body.contains("fun putSshEntry("))
    }

    @Test
    fun submitPersistsTheRecipeAndKeepsTheReadyRowPath() {
        val body = panel()
        val submit = body.substringAfter("onSubmit = { config, connection ->")
        // The recipe is persisted on submit (secret-free) …
        assertTrue("submit persists the recipe", submit.contains("sshStore?.putSshEntry(config.toSshEntry())"))
        // … and the READY → loopback-row path is byte-for-byte what it was:
        // observe the tunnel, upsert the loopback row + default.
        assertTrue(submit.contains("sshHost.submit(config, connection)"))
        assertTrue(submit.contains("bridge?.upsert(item, password = null)"))
        assertTrue(submit.contains("bridge?.setDefault(item.url)"))
        // READY also records which loopback row the tunnel fed, so the
        // confirmed delete can cascade to it.
        assertTrue(
            "READY records the loopback url on the recipe",
            submit.contains("sshStore?.putSshEntry(submitted.toSshEntry(lastLocalUrl = localUrl))"),
        )
        assertFalse("submit never drops a persisted record", submit.contains("removeSshEntry"))
    }

    // ------------------------------------------------- 6. the landed wiring

    @Test
    fun thePanelSeedsItsHostFromThePersistedListOnEveryComposition() {
        val body = panel()
        // The capability cast (the ServerHealthSource pattern) …
        assertTrue(body.contains("bridge as? SshEntryStore"))
        // … feeding a per-composition seed of the composition-scoped host —
        // the fix for "leaving Settings / switching language dropped every
        // SSH row" (research/v91_verify/5554+5556/).
        assertTrue(body.contains("sshStore?.sshEntries()"))
        assertTrue(body.contains("sshHost.seed(persisted)"))
        // The host seeds through the pinned pure merge (never a hand-rolled
        // union that could clobber live connection state).
        val seedBody = fnBody(host(), "fun seed(persisted: List<ServerConfigStore.SshEntry>) {")
        assertTrue(seedBody.contains("mergeSeededSshTunnels(live, persisted)"))
    }

    @Test
    fun theConfirmedDeleteDropsTheRecordAndCascadesToTheLoopbackRow() {
        val body = panel()
        // The panel hands the card its persisted remover …
        val remover = body.substringAfter("removePersisted = { id ->").substringBefore("},")
        // … which drops the ssh_json record …
        assertTrue(remover.contains("sshStore?.removeSshEntry(id)"))
        // … and cascades to the loopback server row a READY tunnel wrote
        // (the F8 follow-up): read `lastLocalUrl` BEFORE the removal, then
        // delete that row.
        assertTrue(remover.contains("sshStore?.sshEntry(id)?.lastLocalUrl"))
        assertTrue(remover.contains("bridge?.delete(localUrl)"))
        assertTrue(
            "the recipe is read before it is dropped",
            remover.indexOf("sshEntry(id)") < remover.indexOf("removeSshEntry(id)"),
        )
        // The card runs the remover on the confirm path only, through the
        // pinned helper (removal-before-forget — §3).
        val card = host().substringAfter("fun SshTunnelCard(")
        assertTrue(card.contains("removePersisted: (String) -> Boolean = { false }"))
        assertTrue(card.contains("confirmSshForget(forget, tunnel.config.id, host, removePersisted)"))
    }

    @Test
    fun theBridgePublishesThePersistedSshListThroughThePreExistingStoreApi() {
        val body = bridge()
        // The capability interface the panel casts to …
        assertTrue(body.contains("interface SshEntryStore"))
        // … implemented over the store's pre-existing API (no new store
        // surface), null-store degrading to empty/false.
        assertTrue(body.contains(") : ServerBridge, ServerHealthSource, SettingsServerData, SshEntryStore {"))
        assertTrue(body.contains("override fun sshEntries(): List<ServerConfigStore.SshEntry> = store?.sshEntries().orEmpty()"))
        assertTrue(body.contains("override fun removeSshEntry(id: String): Boolean = store?.removeSshEntry(id) ?: false"))
        assertTrue(body.contains("override fun putSshEntry(entry: ServerConfigStore.SshEntry): Boolean ="))
    }

    @Test
    fun toSshEntryCarriesTheRedialRecipeButNoSecret() {
        val config = SshConnectionConfig(
            id = "E2ECHK5556-ssh",
            target = "ssh deploy@box.example",
            name = "box",
            auth = SshAuthMethod.KEY,
            password = "secret",
            privateKeyPath = "~/.ssh/id_ed25519",
            passphrase = "key-secret",
        )
        val recipe = config.toSshEntry()
        assertEquals("E2ECHK5556-ssh", recipe.id)
        assertEquals("ssh deploy@box.example", recipe.target)
        assertEquals("box", recipe.name)
        assertEquals(ServerConfigStore.SshAuthKind.KEY, recipe.authKind)
        assertEquals("~/.ssh/id_ed25519", recipe.privateKeyPath)
        assertNull(recipe.lastLocalUrl)
        // No secret can ever ride along: the entry type has no field for one
        // (compile-time), and the READY half carries the loopback only.
        val ready = config.toSshEntry(lastLocalUrl = "http://127.0.0.1:4101")
        assertEquals("http://127.0.0.1:4101", ready.lastLocalUrl)
        assertEquals(recipe.copy(lastLocalUrl = null), ready.copy(lastLocalUrl = null))
        // … and the round trip back to a resting tunnel re-collects secrets.
        val resting = ready.toRestingTunnel()
        assertNull(resting.config.password)
        assertNull(resting.config.passphrase)
    }
}
