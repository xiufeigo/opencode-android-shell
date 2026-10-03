package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAttemptGuard
import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnectEvent
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshFailure
import com.xiufeigo.opencodeshell.ui.servers.SshHostKeyInfo
import com.xiufeigo.opencodeshell.ui.servers.SshPrompt
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.reduceSshConnection
import com.xiufeigo.opencodeshell.ui.servers.sshAttemptSpeaks
import com.xiufeigo.opencodeshell.ui.servers.sshCancelSettles
import com.xiufeigo.opencodeshell.ui.servers.sshPromptIsCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **v99 — the `Verify host identity` card's state machine** (device round 6:
 * `research/v97_verify/5554_v98d3/REPORT_COVERAGE6.md` §2.2 / §3).
 *
 * The device shape: a new SSH entry auto-dials, the host-key trust card comes
 * up, and then **neither** button does anything — `Cancel` leaves the card
 * standing, `Trust and connect` is a silent no-op — and the card keeps the
 * host panel (`Add SSH server`, the row's ⋮) unclickable behind it.
 *
 * Cause, in one line: `cancelPrompt()` muted the attempt (the v97 F12b/D1 fix)
 * and then let the **muted** attempt publish the one event that withdraws the
 * card. `emit` drops everything a muted attempt says, so `PromptResolved` never
 * landed, `prompt` stayed set forever, the row stayed at `CONNECTING`, and the
 * trust answer had no parked dial left to wake.
 *
 * What is pinned here, all of it pure JVM (no jsch, no device):
 *  1. **Cancel leaves verification** — the card's exit condition (`prompt ==
 *     null`, which is literally what `SshDialog` branches on) and the row leave
 *     the connecting stage, so the host is interactive again.
 *  2. **Trust advances** — answering the trust question drives the challenge to
 *     resolved (card gone) while the dial keeps moving toward a tunnel, which
 *     is the only outcome the two buttons are allowed to have.
 *  3. **The security invariant** — no path out of verification (cancel, refuse,
 *     stale answer, timeout) reaches [SshStage.READY] without a real
 *     [SshConnectEvent.Ready]. Untrusted never means connected.
 *  4. The wiring facts that made the card a dead end: the withdraw is a direct
 *     state write, and a trust for a challenge that is not the current one is
 *     refused rather than queued.
 */
class SshHostKeyCardV99Test {

    private fun config() = SshConnectionConfig(
        id = "t1",
        target = "ssh -p 2222 sshtest@127.0.0.1",
        name = "E2ECHK",
        auth = SshAuthMethod.PASSWORD,
        password = "keypass123",
    )

    private val hostKey = SshHostKeyInfo(
        host = "[127.0.0.1]:2222",
        type = "ssh-ed25519",
        keyBase64 = "c3NoLWVkMjU1MTkgQUFBQQ==",
        changed = false,
    )

    /** The trust question as the dial parks it: a `confirm` prompt + fingerprints. */
    private fun verifying(promptId: String = "p-hostkey") = reduceSshConnection(
        reduceSshConnection(SshConnectionStatus(), SshConnectEvent.Start),
        SshConnectEvent.Prompted(SshPrompt(promptId, "Are you sure you want to continue connecting?", true), hostKey),
    )

    // ------------------------------------------------------- 1. cancel leaves

    @Test
    fun cancelLeavesVerificationAndTheHostGoesInteractive() {
        val during = verifying()
        // The preconditions the device showed: the card is up and the row is mid-connect.
        assertEquals("the card is on screen", SshPrompt("p-hostkey", "Are you sure you want to continue connecting?", true), during.prompt)
        assertEquals("verifying parks the dial, it does not advance it", SshStage.CONNECTING, during.stage)
        assertNotNull("the fingerprints are what the card is for", during.hostKey)

        val after = sshCancelSettles(during, "p-hostkey")

        // The card's own exit condition. `SshDialog` draws `SshAuthenticate`
        // while `status.prompt != null`, so this null *is* the card going away.
        assertNull("Cancel must take the verification card off the screen", after.prompt)
        assertNull("and the fingerprints with it", after.hostKey)
        // Nothing is left in a connecting stage, so the host panel is not
        // pinned behind a live dial: this is the `Add SSH server` / ⋮ unlock.
        assertFalse(
            "Cancel must not leave the row in a connecting stage (the panel stays blocked)",
            com.xiufeigo.opencodeshell.ui.servers.isSshConnecting(after.stage),
        )
        assertEquals("a cancelled entry rests disconnected, and is still saved", SshStage.DISCONNECTED, after.stage)
    }

    @Test
    fun aCancelledAttemptNeverSpeaksAgain() {
        // The v97 rule the fix must not weaken: muting a cancelled attempt is
        // what stopped the endless publickey → keyboard-interactive re-pop
        // (F12b/D1). The card is withdrawn *beside* that rule, not by lifting it.
        //
        // Pinned at the layer that owns it — `SshConnection.emit` drops every
        // event a muted attempt publishes, so the card has to leave by a direct
        // write (which is exactly what `sshCancelSettles` + `cancelPrompt` do).
        // The reducer is deliberately attempt-blind and is not where this lives.
        val guard = SshAttemptGuard()
        val attempt = guard.begin()
        assertTrue("a fresh attempt speaks", guard.speaks(attempt))

        guard.cancel(guard.current())

        assertFalse("a cancelled attempt is muted — no challenge, no Fail (F12b/D1)", guard.speaks(attempt))
        // The pure form the suite pins elsewhere, re-read for this leg.
        assertFalse(sshAttemptSpeaks(attempt, guard.current(), cancelled = true))
        // … and a *new* dial is not muted by an old cancel: Reconnect still works.
        val next = guard.begin()
        assertTrue("Reconnect opens a fresh generation that does speak", guard.speaks(next))
    }

    // --------------------------------------------------------- 2. trust moves

    @Test
    fun trustResolvesTheChallengeAndKeepsAdvancingTheDial() {
        val during = verifying()
        // `respondTrust(true)` → jsch's `promptYesNo` takes the answer → the dial
        // adds the key to known_hosts and carries on. What the UI must see is
        // the challenge leave, with the connection still on its way.
        val after = reduceSshConnection(during, SshConnectEvent.PromptResolved("p-hostkey"))

        assertNull("Trust must clear the card too — otherwise Trust looks as dead as Cancel", after.prompt)
        assertNull(after.hostKey)
        assertEquals(
            "the dial is still going: the answer ends the question, not the connection",
            SshStage.CONNECTING,
            after.stage,
        )
        // And it really does reach the tunnel on the real leg.
        val ready = reduceSshConnection(after, SshConnectEvent.Ready("http://127.0.0.1:2222", 42, 1L))
        assertEquals(SshStage.READY, ready.stage)
        assertEquals("http://127.0.0.1:2222", ready.localUrl)
    }

    @Test
    fun aTrustForAChallengeThatIsNotTheCurrentOneIsRefused() {
        // The device's inert `Trust and connect`: the card on screen belonged to
        // an attempt that had already taken its answer, so the offer landed in
        // a one-slot queue with no consumer — a tap nothing could observe. The
        // connection now refuses to queue it, which is also the security half.
        val during = verifying("p-current")
        assertTrue("the on-screen challenge is answerable", sshPromptIsCurrent(during, "p-current"))
        assertFalse("a different challenge id is not answerable", sshPromptIsCurrent(during, "p-stale"))
    }

    @Test
    fun theRealConnectionRefusesAStaleTrustInsteadOfQueueingIt() {
        // The real object, no dial in flight: a cancel with nothing on screen is
        // inert (F13b's pin) and a trust has no challenge to answer, so neither
        // can move a resting connection. This is the F13b shape, re-read after
        // the v99 change: `cancelPrompt` must still be a no-op with no prompt.
        val connection = SshConnection(config())
        val before = connection.state.value

        connection.cancelPrompt()
        connection.respondTrust(true)
        connection.respondPrompt("p1", "secret")

        assertEquals("no challenge on screen: cancel and a trust change nothing", before, connection.state.value)
        assertNull(before.prompt)
        assertNull(before.localUrl)
        assertNull(before.error)
    }

    // ------------------------------------------------ 3. the safety invariant

    @Test
    fun anUntrustedHostNeverReachesReady() {
        // The invariant, walked over every leg the card can take. Nothing short
        // of a real forward being up may park a row at READY.
        val cancel = sshCancelSettles(verifying(), "p-hostkey")
        assertNotEqualsStage(SshStage.READY, cancel.stage, "cancel")

        val refused = reduceSshConnection(
            reduceSshConnection(verifying(), SshConnectEvent.PromptResolved("p-hostkey")),
            SshConnectEvent.Fail(SshFailure(SshStage.FAILED, com.xiufeigo.opencodeshell.ui.servers.SshErrorCode.HOST_KEY, "reject HostKey")),
        )
        assertNotEqualsStage(SshStage.READY, refused.stage, "a refused trust")
        assertNull("a refused trust leaves no tunnel", refused.localUrl)

        // The only way in: the forward actually up.
        val forwardUp = reduceSshConnection(verifying(), SshConnectEvent.Ready("http://127.0.0.1:2222", 42, 1L))
        assertEquals("only a real Ready reaches READY", SshStage.READY, forwardUp.stage)
    }

    @Test
    fun theVerificationCardIsStillMandatoryForAnUnknownKey() {
        // The other half of "we did not loosen anything": an unknown host key
        // still parks the dial on the trust question and the dial is still
        // in-flight while it does. Auto-trust would have made this card
        // unreachable — and the fix does not.
        val during = verifying()
        assertNotNull("an unverified key still raises the question", during.prompt)
        assertTrue("and it is the trust question, not a secret challenge", during.prompt!!.confirm)
        assertEquals("the dial waits for the answer", SshStage.CONNECTING, during.stage)
    }

    private fun assertNotEqualsStage(unexpected: SshStage, actual: SshStage, what: String) {
        assertTrue("$what must not reach $unexpected (it was $actual)", unexpected != actual)
    }
}
