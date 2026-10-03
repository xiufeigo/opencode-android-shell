package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshAuthMethod
import com.xiufeigo.opencodeshell.ui.servers.SshConnection
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionConfig
import com.xiufeigo.opencodeshell.ui.servers.SshConnectionStatus
import com.xiufeigo.opencodeshell.ui.servers.SshErrorCode
import com.xiufeigo.opencodeshell.ui.servers.SshPrompt
import com.xiufeigo.opencodeshell.ui.servers.SshPromptCandidate
import com.xiufeigo.opencodeshell.ui.servers.SshPromptOffer
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.SshTunnel
import com.xiufeigo.opencodeshell.ui.servers.sshPromptCandidate
import com.xiufeigo.opencodeshell.ui.servers.sshPromptFor
import com.xiufeigo.opencodeshell.ui.servers.sshPromptOffer
import com.xiufeigo.opencodeshell.ui.servers.sshPromptOffered
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v94 — the SSH row's **prompt exit** (`research/v94_verify/5558/
 * E2ECHK5558_39_reprompts.png`, `E2ECHK5558_40_prompt3.png`).
 *
 * The device round: Reconnect on a stored SSH entry advances the line to
 * `Connecting over SSH…`, the server refuses the stored password, jsch falls back
 * to keyboard-interactive and publishes a challenge on
 * `SshConnection.state.prompt` — and the app draws nothing for it. The only
 * [com.xiufeigo.opencodeshell.ui.servers.SshAuthenticate] in the codebase was the
 * config dialog's own prompt branch (`SshDialog.kt:228-245`), which a row dial
 * never goes through, so the user waits out
 * `PROMPT_TIMEOUT_SECONDS` (600 s) with no way to answer.
 *
 * This pins upstream's three pieces as a pure rule plus the panel's wiring:
 *
 *  1. [sshPromptOffer] — `controller.ts:88-99` `dialog.next()` behind
 *     `context.tsx:64`'s `dialog.active` gate: the **first** row in list order
 *     that is waiting on a challenge nobody answered, suppressed per row by
 *     `!attempt.error` and `!attempt.prompted` (§1–§6);
 *  2. [sshPromptOffered] — `controller.ts:99` `dialog.opened(id)` /
 *     `authentication-state.ts:4-5`'s "offered once": keyed by **promptId**, so a
 *     cancelled prompt is not re-offered while a genuinely new challenge of the
 *     same dial is (§2, §3);
 *  3. the panel seam — the rows are the effect's input, `addingSsh` is
 *     `dialog.active`, the mark is written before the card is drawn, and the
 *     card is the existing [com.xiufeigo.opencodeshell.ui.servers.SshAuthenticate]
 *     fed the same band inputs `SshDialog` feeds its own (§7–§9).
 *
 * No new user-visible string is involved: the card is reused as-is, so the
 * three-language parity stays [StringsLocaleParityTest]'s.
 */
class SshPromptOfferV94Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun panel() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt")

    /** The offer effect, from its `LaunchedEffect` to the effect's own closing brace. */
    private fun offerEffect(): String =
        panel().substringAfter("LaunchedEffect(sshTunnels, addingSsh) {").substringBefore("\n    }\n")

    /** The offered card, from its guard to the end of the panel function. */
    private fun promptCard(): String =
        panel().substringAfter("if (sshPrompt != null && sshOfferedTunnel != null) {")
            .substringBefore("\n    }\n}\n")

    /** One callback of the card, cut at its sibling so indentation cannot matter. */
    private fun callback(name: String, next: String): String =
        promptCard().substringAfter("$name = ").substringBefore("$next = ")

    private fun candidate(
        id: String,
        promptId: String? = null,
        error: Boolean = false,
    ) = SshPromptCandidate(configId = id, promptId = promptId, error = error)

    private fun tunnel(id: String, prompt: SshPrompt? = null, error: SshErrorCode? = null): SshTunnel {
        val config = SshConnectionConfig(
            id = id,
            target = "ssh deploy@$id.example",
            name = "box $id",
            auth = SshAuthMethod.PASSWORD,
        )
        return SshTunnel(
            config,
            SshConnection(config),
            SshConnectionStatus(
                stage = if (prompt != null) SshStage.AUTHENTICATION else SshStage.DISCONNECTED,
                error = error,
                prompt = prompt,
            ),
        )
    }

    private fun challenge(id: String, confirm: Boolean = false) =
        SshPrompt(id = id, text = "Password: ", confirm = confirm)

    // ------------------------------- 1. the offer (controller.ts:88-99)

    @Test
    fun aRowWaitingOnAnUnofferedChallengeIsSelected() {
        val offer = sshPromptOffer(
            candidates = listOf(candidate("a"), candidate("box", promptId = "p1")),
            offered = emptyMap(),
            dialogActive = false,
        )
        assertEquals(SshPromptOffer("box", "p1"), offer)
    }

    @Test
    fun theSamePromptIdIsNeverOfferedTwice() {
        val offered = sshPromptOffered(emptyMap(), SshPromptOffer("box", "p1"))
        assertEquals(mapOf("box" to "p1"), offered)
        // The answered (or cancelled) prompt is settled: re-running the rule on
        // the same row state — what an unrelated status emission does — offers
        // nothing, and the mark does not grow either.
        assertNull(sshPromptOffer(listOf(candidate("box", "p1")), offered, false))
        assertSame(offered, sshPromptOffered(offered, SshPromptOffer("box", "p1")))
    }

    @Test
    fun aNewPromptIdOfTheSameRowIsOfferedAgain() {
        // The v94 device path: the stored password is refused, jsch publishes a
        // second challenge with a fresh id on the *same* row. Keying the mark on
        // the promptId (not on the attempt) is what keeps that answerable while
        // the first prompt stays settled.
        val offered = sshPromptOffered(emptyMap(), SshPromptOffer("box", "p1"))
        assertEquals(
            SshPromptOffer("box", "p2"),
            sshPromptOffer(listOf(candidate("box", "p2")), offered, false),
        )
        assertEquals(
            mapOf("box" to "p2"),
            sshPromptOffered(offered, SshPromptOffer("box", "p2")),
        )
    }

    @Test
    fun nothingIsOfferedWhileADialogIsActive() {
        // `context.tsx:64` — `if (!item || dialog.active) return`: the config
        // dialog answers its own prompt, so a row never pushes a card behind it.
        assertNull(
            sshPromptOffer(
                candidates = listOf(candidate("box", "p1")),
                offered = emptyMap(),
                dialogActive = true,
            ),
        )
        // … and the mark is untouched by the suppressed attempt, so the row is
        // offered the moment the dialog closes.
        assertEquals(
            SshPromptOffer("box", "p1"),
            sshPromptOffer(listOf(candidate("box", "p1")), emptyMap(), false),
        )
    }

    @Test
    fun aRowWithoutAChallengeAndAnEmptyListOfferNothing() {
        assertNull(sshPromptOffer(listOf(candidate("a"), candidate("b")), emptyMap(), false))
        assertNull(sshPromptOffer(emptyList(), emptyMap(), false))
        // A blank id could not be routed to `respondPrompt` anyway, so it is no
        // challenge at all.
        assertNull(sshPromptOffer(listOf(candidate("a", "")), emptyMap(), false))
    }

    @Test
    fun competingRowsResolveToTheFirstInListOrder() {
        // `input.items().find(...)` — list order, not id order, and not the order
        // the challenges happened to arrive in.
        assertEquals(
            SshPromptOffer("b", "p9"),
            sshPromptOffer(listOf(candidate("b", "p9"), candidate("a", "p1")), emptyMap(), false),
        )
        // A row already settled is skipped, so the first *eligible* row wins.
        assertEquals(
            SshPromptOffer("a", "p1"),
            sshPromptOffer(
                listOf(
                    candidate("b", "p9"),
                    candidate("c", "p3", error = true),
                    candidate("a", "p1"),
                ),
                offered = mapOf("b" to "p9"),
                dialogActive = false,
            ),
        )
    }

    @Test
    fun aSettledAttemptIsNotOfferedEvenWithAChallenge() {
        // `controller.ts:95` `!attempt.error`: a failed attempt is not offered a
        // dialog, even if a stale challenge were still on the row.
        assertNull(
            sshPromptOffer(
                candidates = listOf(candidate("box", "p1", error = true)),
                offered = emptyMap(),
                dialogActive = false,
            ),
        )
    }

    // ------------------------------ 2. the projection and the live card

    @Test
    fun theProjectionCarriesTheConfigsIdThePendingIdAndTheErrorFlag() {
        assertEquals(
            SshPromptCandidate("box", "p1", false),
            tunnel("box", challenge("p1")).sshPromptCandidate(),
        )
        assertEquals(
            SshPromptCandidate("box", null, true),
            tunnel("box", null, SshErrorCode.CONNECTION).sshPromptCandidate(),
        )
    }

    @Test
    fun theCardFollowsTheLiveRowPrompt() {
        val row = tunnel("box", challenge("p1", confirm = true))
        // While the row still carries the offered id, the card has its prompt …
        assertEquals(challenge("p1", confirm = true), row.sshPromptFor(SshPromptOffer("box", "p1")))
        // … answering it clears `status.prompt`, which takes the card down with
        // no close handler, and a *different* row's offer is never this card's.
        val answered = tunnel("box", null)
        assertNull(answered.sshPromptFor(SshPromptOffer("box", "p1")))
        assertNull(answered.sshPromptFor(SshPromptOffer("box", "p2")))
        assertNull(row.sshPromptFor(SshPromptOffer("other", "p1")))
    }

    // ------------------------------------------------- 3. the panel's wiring

    @Test
    fun thePanelFeedsTheRowsIntoTheOfferAndMarksBeforeItDraws() {
        val effect = offerEffect()
        // The same row list the cards render is the effect's input …
        assertTrue(effect.contains("candidates = sshTunnels.map { it.sshPromptCandidate() }"))
        // … `addingSsh` is `dialog.active` …
        assertTrue(effect.contains("dialogActive = addingSsh"))
        // … the mark goes in first (`dialog.opened(id)` runs before the push) …
        val mark = effect.indexOf("sshOffered = sshPromptOffered(sshOffered, next)")
        val draw = effect.indexOf("sshOffer = next")
        assertTrue("the offered mark is written before the card is opened", mark in 0 until draw)
        // … and a suppressed offer leaves the open card alone.
        assertTrue(effect.contains("?: return@LaunchedEffect"))
    }

    @Test
    fun anOfferedPromptIsRenderedAsTheExistingPromptCard() {
        val card = promptCard()
        assertTrue(card.contains("SshAuthenticate("))
        // The guard: no offer (or no live prompt) draws nothing.
        assertTrue(panel().contains("if (sshPrompt != null && sshOfferedTunnel != null) {"))
        assertTrue(panel().contains("val sshPrompt = sshOffer?.let { offer -> sshOfferedTunnel?.sshPromptFor(offer) }"))
        // The card is reused whole — the trust question keeps its fingerprints,
        // the challenge keeps its secret field (no new copy, no new string).
        assertTrue(card.contains("prompt = sshPrompt"))
        assertTrue(card.contains("hostKey = sshOfferedTunnel.status.hostKey"))
        // The band inputs are the ones `SshDialog.kt:218-243` feeds its own card:
        // the window's IME bottom, the panel slot box, and the window height.
        assertTrue(panel().contains("val sshPromptImeBottomDp = windowImeBottomDp()"))
        assertTrue(panel().contains("val sshPromptPanel = LocalOcSelectContainerBounds.current"))
        assertTrue(panel().contains("val sshPromptWindowHeightDp = sshPromptConfig.screenHeightDp.toFloat()"))
        for (input in listOf(
            "windowHeightDp = sshPromptWindowHeightDp",
            "imeBottomDp = sshPromptImeBottomDp",
            "panelTopDp = sshPromptPanelTopDp",
            "panelHeightDp = sshPromptPanelHeightDp",
        )) {
            assertTrue("the card is fed $input", card.contains(input))
        }
    }

    @Test
    fun trustAndContinueAnswerTheTunnelsOwnChallenge() {
        assertTrue(callback("onTrust", "onContinue").contains("sshOfferedTunnel.connection.respondTrust(true)"))
        val answer = callback("onContinue", "onCancel")
        assertTrue(answer.contains("sshOfferedTunnel.connection.respondPrompt(sshPrompt.id, value)"))
        // The id travels with the answer, so a stale card can never answer the
        // next challenge of the same row.
        assertTrue(answer.contains("SshPromptOffer(sshOfferedTunnel.config.id, sshPrompt.id)"))
    }

    @Test
    fun cancelReleasesTheChallengeAndNothingElse() {
        val cancel = callback("onCancel", "windowHeightDp =")
        assertTrue(cancel.contains("sshOfferedTunnel.connection.cancelPrompt()"))
        // Cancel is the prompt's, not the row's: the entry is never dropped and
        // the forward is never released here (the row keeps whatever state the
        // dial left it in, for the user to act on).
        assertFalse("cancel never forgets the row", cancel.contains("forget"))
        assertFalse("cancel never drops the persisted recipe", cancel.contains("removeSshEntry"))
        assertFalse("cancel never releases the tunnel", cancel.contains("disconnect"))
    }

    @Test
    fun everyCallbackRecordsTheOfferedPromptBeforeItAnswers() {
        val callbacks = listOf(
            "onTrust" to "respondTrust(true)",
            "onContinue" to "respondPrompt(sshPrompt.id, value)",
            "onCancel" to "cancelPrompt()",
        )
        for ((name, answer) in callbacks) {
            val body = when (name) {
                "onTrust" -> callback("onTrust", "onContinue")
                "onContinue" -> callback("onContinue", "onCancel")
                else -> callback("onCancel", "windowHeightDp =")
            }
            val mark = body.indexOf("sshPromptOffered(")
            val respond = body.indexOf(answer)
            assertTrue("$name records the offered promptId", mark >= 0)
            assertTrue("$name answers the challenge", respond >= 0)
            assertTrue("$name marks the prompt offered before answering it", mark < respond)
        }
    }
}
