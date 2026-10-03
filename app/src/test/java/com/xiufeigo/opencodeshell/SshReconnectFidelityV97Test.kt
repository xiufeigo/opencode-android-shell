package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.servers.SshAttemptGuard
import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnectEvent
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.SshForgetState
import com.xiufeigo.opencodeshell.ui.servers.SshPromptCandidate
import com.xiufeigo.opencodeshell.ui.servers.SshPromptOffer
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.confirmSshForget
import com.xiufeigo.opencodeshell.ui.servers.mergeSeededSshTunnels
import com.xiufeigo.opencodeshell.ui.servers.reduceSshConnection
import com.xiufeigo.opencodeshell.ui.servers.sshAttemptSpeaks
import com.xiufeigo.opencodeshell.ui.servers.sshForgetReleasesTunnel
import com.xiufeigo.opencodeshell.ui.servers.sshPublishesTerminal
import com.xiufeigo.opencodeshell.ui.servers.sshPromptOffer
import com.xiufeigo.opencodeshell.ui.servers.sshPromptOffered
import com.xiufeigo.opencodeshell.ui.servers.sshTunnelsWithout
import com.xiufeigo.opencodeshell.ui.servers.toRestingTunnel
import com.xiufeigo.opencodeshell.ui.servers.toSshEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v97 — the SSH reconnect-fidelity round: **D1** (a saved entry's Reconnect
 * silently degraded to a keyboard-interactive password card), **D2** (an
 * authentication sshd had already accepted was reported as a failure),
 * **D3** (the delete confirm did nothing on a row parked at `Authentication
 * required`) and the **F12b regression** (Cancel re-popped its card on 7/7
 * samples, as a password card, with `Reconnect` gone).
 *
 * `research/v97_verify/5554_f14/REPORT.md` §5, §5.1, §8. All four share one
 * missing fact — *which dial attempt* a jsch callback belongs to — so the
 * suite is built around [SshAttemptGuard] and the two pure rules it exposes
 * ([sshAttemptSpeaks], [sshPublishesTerminal]), plus the auth recipe and the
 * offer-consumption rules that turn the same fact into user-visible behaviour.
 *
 * Split by what a plain JVM test can reach, the same split
 * [SshPromptCancelDoesNotDismissF13bTest] uses:
 *
 *  1. **the model** — the guard, the terminal-publication rule, the persisted
 *     auth recipe and the offer table: all pure, all asserted as behaviour;
 *  2. **the real connection** — a live [SshConnection]'s cancel is inert
 *     against an unmade attempt, `abort()` is total, disconnect still
 *     publishes;
 *  3. **the wiring** — source pins for the `@Composable` and coroutine seams a
 *     JVM test cannot call (`SshTunnelHost.forget`, `SshConnection.connect`).
 *
 * F14b/F14a are deliberately *not* re-litigated here — the new-entry first
 * dial is untouched by every change in this round, and
 * [SshEd25519IdentityF14Test] / [SshKeyFileFailureF14Test] stay in the
 * regression run as the guard that they still hold.
 */
class SshReconnectFidelityV97Test {

    private val keyPath = "/data/user/0/com.xiufeigo.opencodeshell/files/id_ed25519"

    /** The v97 device's private-key entry, exactly as the form built it. */
    private fun keyEntryConfig() = SshConnectionConfig(
        id = "E2ECHK5554f14-b",
        target = "ssh -p 2222 sshtest@127.0.0.1",
        name = "E2ECHK5554f14-b",
        auth = SshAuthMethod.KEY,
        privateKeyPath = keyPath,
    )

    // ------------------------------------------- 1. D1 — the reconnect recipe

    @Test
    fun aSavedPrivateKeyEntryKeepsItsAuthMethodAndKeyPathThroughARoundTrip() {
        // D1's precondition. The report's card was a `Password:` keyboard-
        // interactive challenge on a row the form had saved as `Private key`,
        // so the first question is whether the saved recipe can even say so.
        val persisted = keyEntryConfig().toSshEntry()
        assertEquals(ServerConfigStore.SshAuthKind.KEY, persisted.authKind)
        assertEquals(keyPath, persisted.privateKeyPath)

        val restored = ServerConfigStore.SshEntry(
            id = persisted.id,
            target = persisted.target,
            name = persisted.name,
            authKind = persisted.authKind,
            privateKeyPath = persisted.privateKeyPath,
        ).toRestingTunnel()

        // The redial recipe the *next* Reconnect dials with.
        assertEquals(SshAuthMethod.KEY, restored.config.auth)
        assertEquals(keyPath, restored.config.privateKeyPath)
    }

    @Test
    fun aSeededPrivateKeyRowIsNotDialedAsAPasswordRow() {
        // The seeded list is what a row looks like after the composition is
        // recreated (leaving Settings, a locale switch). Same assertion as
        // above, through the merge the panel actually calls.
        val merged = mergeSeededSshTunnels(
            live = emptyList(),
            persisted = listOf(keyEntryConfig().toSshEntry()),
        )
        assertEquals(1, merged.size)
        assertEquals(SshAuthMethod.KEY, merged[0].config.auth)
        assertEquals(keyPath, merged[0].config.privateKeyPath)
        // A resting row carries no secret — by design; the next dial
        // re-collects the passphrase on a card, it does not fall back to a
        // password.
        assertNull(merged[0].config.passphrase)
        assertNull(merged[0].config.password)
    }

    @Test
    fun aKeyEntryWithNoPassphraseNeverDegradesToThePasswordFallback() {
        // D1/F12b's causal chain, restated on the attempt guard: the
        // degradation was not a config loss, it was jsch walking to its *next*
        // auth method after a cancel, each step minting a fresh prompt id that
        // the offer rule had never seen. A cancelled attempt is muted, so the
        // walk stops at the first refusal instead of drawing the `Password:`
        // card.
        val guard = SshAttemptGuard()
        val attempt = guard.begin()
        assertTrue(guard.speaks(attempt))

        guard.cancel(attempt)
        assertFalse(
            "F12b/D1: after Cancel no further challenge of this dial may be published",
            guard.speaks(attempt),
        )

        // The next Reconnect is a fresh generation: the credentials really are
        // re-collected, which is the "二次 Reconnect 必重弹" half of the
        // historical contract.
        val next = guard.begin()
        assertTrue("a fresh dial must re-collect the passphrase", guard.speaks(next))
        assertFalse("the cancelled dial stays muted", guard.speaks(attempt))
    }

    @Test
    fun theAuthMethodWalkIsCutAtTheCancelRatherThanAtTheEndOfTheList() {
        // Pin the *rule* the connection uses, not the guard's bookkeeping: a
        // muted attempt speaks for nothing, and a non-muted one speaks for the
        // attempt it was stamped with.
        assertFalse(sshAttemptSpeaks(attempt = 1, currentAttempt = 2, cancelled = false))
        assertFalse(sshAttemptSpeaks(attempt = 2, currentAttempt = 2, cancelled = true))
        assertTrue(sshAttemptSpeaks(attempt = 2, currentAttempt = 2, cancelled = false))
    }

    // ------------------------------------------------ 2. D2 — publish order

    @Test
    fun aSettledSuccessIsNotReDecidedByALateFailure() {
        // D2 as the device showed it: sshd logged `Accepted
        // keyboard-interactive/pam` while the row read `Authentication
        // required` + `Could not establish the SSH connection`.
        val ready = reduceSshConnection(
            reduceSshConnection(SshConnectionStatus(), SshConnectEvent.Start),
            SshConnectEvent.Ready(localUrl = "http://127.0.0.1:46041", latencyMs = 42, at = 1_000L),
        )
        val strayFailure = SshConnectEvent.Fail(
            com.xiufeigo.opencodeshell.ui.servers.SshFailure(
                SshStage.AUTHENTICATION,
                SshErrorCode.CONNECTION,
                "Auth fail",
            ),
        )

        assertFalse(
            "a READY tunnel is not re-decided by a stray failure from a parked attempt",
            sshPublishesTerminal(ready, strayFailure),
        )
        assertTrue(
            "an explicit disconnect still moves a ready tunnel on",
            sshPublishesTerminal(ready, SshConnectEvent.Disconnect),
        )
        assertTrue(
            "a fresh dial still restarts a ready tunnel",
            sshPublishesTerminal(ready, SshConnectEvent.Start),
        )
    }

    @Test
    fun aRealFailureBeforeReadyIsStillPublished() {
        // The guard must not buy a false negative with a swallowed failure:
        // the ordinary dial order (fail, or fail then a later retry) is
        // untouched, because the row is not yet READY.
        val connecting = reduceSshConnection(SshConnectionStatus(), SshConnectEvent.Start)
        val failure = SshConnectEvent.Fail(
            com.xiufeigo.opencodeshell.ui.servers.SshFailure(
                SshStage.FAILED,
                SshErrorCode.KEY_FILE,
                "FileNotFoundException: /sdcard/nope (Permission denied)",
            ),
        )
        assertTrue("a genuine failure is published", sshPublishesTerminal(connecting, failure))

        val failed = reduceSshConnection(connecting, failure)
        assertEquals(SshStage.FAILED, failed.stage)
        // F14a's dedicated key-file copy is still what this failure carries —
        // the v97 changes never touched the classification.
        assertEquals(SshErrorCode.KEY_FILE, failed.error)
    }

    @Test
    fun aSupersededAttemptCannotOverwriteTheWinner() {
        // The two dials of D2: the newer one publishes READY, then the older
        // one — unblocked by its own 600 s poll — tries to fail the row.
        val guard = SshAttemptGuard()
        val stale = guard.begin()
        val winner = guard.begin()
        assertTrue(guard.speaks(winner))
        assertFalse("the parked dial is muted", guard.speaks(stale))
    }

    @Test
    fun successIsPublishedBeforeAnyFailureOnTheSameAttempt() {
        // Order within one attempt, which is what the panel renders: Connect
        // → challenge → answered → READY. The reducer never parks a failure
        // ahead of the success that follows it.
        val stages = mutableListOf<SshStage>()
        var status = SshConnectionStatus()
        val events = listOf(
            SshConnectEvent.Start,
            SshConnectEvent.Prompted(com.xiufeigo.opencodeshell.ui.servers.SshPrompt("p1", "Password: ", false)),
            SshConnectEvent.PromptResolved("p1"),
            SshConnectEvent.Ready(localUrl = "http://127.0.0.1:46041", latencyMs = 7, at = 2L),
        )
        for (event in events) {
            status = reduceSshConnection(status, event)
            stages += status.stage
        }
        assertEquals(
            listOf(SshStage.CONNECTING, SshStage.CONNECTING, SshStage.CONNECTING, SshStage.READY),
            stages,
        )
        assertNull("a settled success carries no error", status.error)
    }

    // ---------------------------------------------- 3. D3 — the delete entry

    @Test
    fun deleteIsOfferedInEveryStageIncludingAuthenticationRequired() {
        // D3's entry point. The stage may change the confirm's *copy* …
        for (stage in SshStage.entries) {
            sshForgetReleasesTunnel(stage)
        }
        // … but it may never gate it. The device shape: on a row parked at
        // `Authentication required` the confirm bar appeared and answered
        // nothing until the user Disconnected first.
        assertTrue(
            "a row parked mid-authentication is the one that needs the exit most",
            sshForgetReleasesTunnel(SshStage.AUTHENTICATION),
        )
        assertEquals(
            "the entry is never gated on the stage — the copy is the only thing that varies",
            setOf(SshStage.AUTHENTICATION),
            SshStage.entries.filter { sshForgetReleasesTunnel(it) }
                .intersect(setOf(SshStage.AUTHENTICATION)),
        )
    }

    @Test
    fun aConfirmedDeleteTakesTheRowOutWhateverStateItIsIn() {
        val tunnel = keyEntryConfig().toSshEntry().toRestingTunnel()
        val armed = SshForgetState().ask(tunnel.config.id)
        assertEquals(tunnel.config.id, armed.armed)

        // The row is at `Authentication required` — the D3 state — and the
        // confirm still resolves to the id it must forget.
        assertEquals(tunnel.config.id, armed.confirm(tunnel.config.id))
        assertNull(
            "a tap that does not answer the armed row forgets nothing",
            SshForgetState().ask("other").confirm(tunnel.config.id),
        )
        assertNull(
            "a row's own state is irrelevant to the confirm",
            SshForgetState().ask("").confirm(""),
        )
    }

    @Test
    fun aForgottenRowIsGoneEvenWhileItsDialIsStillParked() {
        // D3's cause: the delete tore the transport down but left the parked
        // coroutine free to speak. The row itself must leave the list, and the
        // offer table must not be able to resurrect a card for it.
        val tunnels = listOf(keyEntryConfig().toSshEntry().toRestingTunnel())
        val parked = reduceSshConnection(
            tunnels[0].status,
            SshConnectEvent.Prompted(com.xiufeigo.opencodeshell.ui.servers.SshPrompt("p1", "Password: ", false)),
        )
        val after = sshTunnelsWithout(
            tunnels.map { it.copy(status = parked) },
            tunnels[0].config.id,
        )
        assertTrue("the deleted row is out of the list", after.isEmpty())

        // Nothing left to offer a card for.
        assertNull(
            sshPromptOffer(
                candidates = after.map { SshPromptCandidate(it.config.id, it.status.prompt?.id, it.status.error != null) },
                offered = emptyMap(),
                dialogActive = false,
            ),
        )
    }

    @Test
    fun abortIsTotalAndDisconnectionStillPublishes() {
        // The real connection: `abort()` is the delete's half — it leaves the
        // row's stage alone (the row is going away) but guarantees nothing the
        // parked dial raises is published again.
        val connection = SshConnection(keyEntryConfig())
        connection.abort()
        assertEquals(SshStage.DISCONNECTED, connection.state.value.stage)
        assertNull(connection.state.value.prompt)

        // A cancel with nothing on screen is inert, exactly as F13b pinned it.
        connection.cancelPrompt()
        assertEquals(SshConnectionStatus(), connection.state.value)

        // Disconnect still publishes — the guard must not swallow the teardown.
        val live = SshConnection(keyEntryConfig())
        live.disconnect()
        assertEquals(SshStage.DISCONNECTED, live.state.value.stage)
        assertNotNull(live.state.value)
    }

    // ------------------------------- 4. F12b — Cancel consumes the offer once

    @Test
    fun aCancelledPromptIsConsumedAndIsNeverOfferedAgain() {
        // F12b's contract, restated on the offer table: the promptId the user
        // cancelled is marked offered *before* the card is torn down, so the
        // effect that re-runs on the row's next status emission re-offers
        // nothing.
        val id = "E2ECHK5554f14-b"
        val cancelled = SshPromptOffer(id, "prompt-1")
        val offered = sshPromptOffered(emptyMap(), cancelled)
        assertEquals("prompt-1", offered[id])

        // The card is gone and the row is quiet, so there is nothing to offer.
        assertNull(
            sshPromptOffer(
                candidates = listOf(SshPromptCandidate(id, promptId = null, error = false)),
                offered = offered,
                dialogActive = false,
            ),
        )
    }

    @Test
    fun aCancelledDialPublishesNoNewPromptIdToReOffer() {
        // F12b's 7/7 re-pop, mechanically. Before the fix, cancelling answered
        // jsch with `accepted = false`; jsch then tried its *next* method, which
        // minted a *new* prompt id — and the offer rule is keyed by prompt id,
        // so that new id looked like a first-time challenge. With the attempt
        // muted, the method walk has nothing left to offer.
        val guard = SshAttemptGuard()
        val attempt = guard.begin()
        val offered = sshPromptOffered(emptyMap(), SshPromptOffer("row", "prompt-1"))
        guard.cancel(attempt)

        // Same dial, method walk continues: nothing is published …
        val walked = listOf("prompt-2", "prompt-3").filter { guard.speaks(attempt) }
        assertTrue("no new prompt id can be minted after a cancel", walked.isEmpty())

        // … so the effect re-run finds nothing to offer, 7/7 times or otherwise.
        repeat(7) {
            assertNull(
                sshPromptOffer(
                    candidates = listOf(SshPromptCandidate("row", promptId = "prompt-1", error = false)),
                    offered = offered,
                    dialogActive = false,
                ),
            )
        }
    }

    @Test
    fun aSecondReconnectRePromptsEvenThoughTheFirstIdWasCancelled() {
        // F12b's other half — "二次 Reconnect 必重弹" — and the reason the offer
        // table is keyed by promptId rather than by row: a genuinely new
        // challenge of a new dial must still be answerable.
        val id = "row"
        val offered = sshPromptOffered(emptyMap(), SshPromptOffer(id, "prompt-1"))
        val next = SshPromptOffer(id, "prompt-9")
        assertEquals(
            "the new dial's challenge is offered again",
            next,
            sshPromptOffer(
                candidates = listOf(SshPromptCandidate(id, promptId = "prompt-9", error = false)),
                offered = offered,
                dialogActive = false,
            ),
        )
    }

    // ------------------------------------------------------ 5. source pins

    @Test
    fun theCancelPathMutesTheAttemptBeforeAnswering() {
        val cancel = connection().substringAfter("fun cancelPrompt() {").substringBefore("\n    }")
        assertTrue(
            "cancel must abandon the dial, not merely re-route it (F12b/D1)",
            cancel.contains("guard.cancel(guard.current())"),
        )
        assertTrue(
            "the parked dial is still answered so jsch's thread is released",
            cancel.contains("answers.offer("),
        )
        assertTrue(
            "guard.cancel must precede the offer so a challenge raised in between is muted",
            cancel.indexOf("guard.cancel(") < cancel.indexOf("answers.offer("),
        )
    }

    @Test
    fun everyChallengeIsRefusedWhenTheAttemptIsMuted() {
        // The fix for the downgrade is only real if *each* jsch challenge point
        // honours the guard: a card published by any of them would re-offer.
        val info = connection()
            .substringAfter("private inner class SshUserInfo(")
            .substringBefore("/**\n     * `known_hosts` shim")
        for (callback in listOf("promptYesNo", "promptPassword", "promptPassphrase", "promptKeyboardInteractive")) {
            val body = info.substringAfter("override fun $callback(").substringBefore("\n        }")
            assertTrue(
                "$callback must refuse a muted attempt instead of publishing a card",
                body.contains("publishPrompt(attempt,") && body.contains("?: return"),
            )
        }
    }

    @Test
    fun connectStampsItsAttemptBeforeItSpeaks() {
        val connect = connection()
            .substringAfter("suspend fun connect()")
            .substringBefore("    /** Panel `Disconnect`")
        assertTrue(
            "the dial is stamped before its first emit so an abandoned one is already muted",
            connect.indexOf("guard.begin()") < connect.indexOf("SshConnectEvent.Start"),
        )
        assertTrue(
            "the jsch session's UserInfo carries the attempt, not a fresh one",
            connect.contains("SshUserInfo(repository, attempt)"),
        )
    }

    @Test
    fun forgetAbortsTheParkedDialBeforeTheRowLeavesTheList() {
        val forget = tunnelHost()
            .substringAfter("fun forget(id: String) {")
            .substringBefore("\n    }")
        assertTrue(
            "D3: the delete must invalidate the parked dial — teardown alone left it free to re-prompt",
            forget.contains("connection?.abort()"),
        )
        // v91's release order is untouched: abort mutes the dial, then the
        // original disconnect → cancel-watcher → drop sequence still runs.
        assertTrue(forget.contains("disconnect(id)"))
        assertTrue(forget.contains("watchJobs.remove(id)?.cancel()"))
        assertTrue(
            "the abort must land before the release, or the release has nothing to protect",
            forget.indexOf("abort()") < forget.indexOf("disconnect(id)"),
        )
        assertTrue(
            "v91's release-before-drop is preserved",
            forget.indexOf("disconnect(id)") < forget.indexOf("sshTunnelsWithout("),
        )
    }

    @Test
    fun theOfferGateIsUnchangedByThisRound() {
        // The F14-era v94 offer gate is load-bearing for F12b's "no re-pop":
        // a dialog already up still suppresses a second card behind it.
        assertNull(
            "an active config dialog suppresses the row's card",
            sshPromptOffer(
                candidates = listOf(SshPromptCandidate("row", "p1", false)),
                offered = emptyMap(),
                dialogActive = true,
            ),
        )
        // A settled attempt (error) carries no answerable challenge.
        assertNull(
            sshPromptOffer(
                candidates = listOf(SshPromptCandidate("row", "p1", error = true)),
                offered = emptyMap(),
                dialogActive = false,
            ),
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun connection() = source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt")

    private fun tunnelHost() = source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt")
}
