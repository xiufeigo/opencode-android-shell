package com.xiufeigo.opencodeshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v94 — a passphrase typed into the SSH challenge dialog used to be thrown
 * away (`research/v94_verify/5558/`).
 *
 * The failure: `SshConnection.SshUserInfo.promptPassphrase` published the
 * challenge and then returned `awaitAnswer(prompt).accepted` **straight to
 * jsch**, keeping nothing, while `getPassphrase()` only ever read
 * `config.passphrase`. jsch's answer to `promptPassphrase → true` is to call
 * `getPassphrase()` and decrypt the identity with whatever comes back, so on
 * the one config that actually needs the dialog — an encrypted private key
 * with the form's passphrase left blank, i.e. every re-dialed resting row,
 * since secrets are re-collected rather than persisted — the decrypt ran with
 * a null passphrase and could only fail. The dialog worked and the secret
 * vanished.
 *
 * `promptPassword` already had the right shape (`suppliedPassword` retention +
 * `getPassword() = suppliedPassword ?: config.password`), so the fix is a
 * mirror, not a redesign: four properties are pinned here in the structural
 * style of [SshTunnelDeleteV91Test] / [SshSeedForgetV92Test].
 *
 * The two prompt *entry points* F12b's host/panel work calls are pinned too —
 * `respondTrust` / `respondPrompt` / `cancelPrompt` must keep their exact
 * signatures, because they are that seam.
 */
class SshPassphraseAnswerV94Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun connection() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshConnection.kt")

    private fun fnBody(text: String, signature: String): String =
        text.substringAfter(signature).substringBefore("\n        }\n")

    private fun passphrasePrompt() =
        fnBody(connection(), "override fun promptPassphrase(message: String): Boolean {")

    private fun passwordPrompt() =
        fnBody(connection(), "override fun promptPassword(message: String): Boolean {")

    /** Everything from the published challenge down — the part that must mirror. */
    private fun promptTail(body: String) = body.substringAfter("val prompt = publishPrompt(")

    /** Collapses the password/passphrase naming difference so the two tails compare. */
    private fun normalized(text: String): String =
        text
            .replace("suppliedPassword", "supplied")
            .replace("suppliedPassphrase", "supplied")
            .replace("config.password", "config.secret")
            .replace("config.passphrase", "config.secret")
            .trim()

    // ---------------------------------------- 1. the answer is kept at all

    @Test
    fun thePromptBranchRetainsTheAnswerItCollected() {
        val body = passphrasePrompt()
        // The answer is awaited into a named binding (not returned inline) …
        //
        // v97: `awaitAnswer` now takes the dial attempt (`SshAttemptGuard`), so
        // the literal gained a parameter. The pin is the *binding*, not the
        // argument list — it is re-expressed, not relaxed: a returned-inline
        // await is still rejected below, and the attempt argument is now
        // required alongside it.
        assertTrue(
            "the answer must be bound before it is used",
            body.contains("val answer = awaitAnswer(attempt, prompt)"),
        )
        // … and that binding's value is what gets retained, so jsch's
        // follow-up getPassphrase() can hand the same secret to the decrypt.
        assertTrue(
            "promptPassphrase must retain the typed passphrase",
            body.contains("suppliedPassphrase = answer.value"),
        )
        // The v94 bug shape, pinned as a regression: returning the await
        // inline is exactly what discarded the secret.
        assertFalse(
            "promptPassphrase must not return the await answer inline (the v94 discard)",
            body.contains("return awaitAnswer(prompt).accepted") ||
                body.contains("return awaitAnswer(attempt, prompt).accepted"),
        )
    }

    @Test
    fun theRetainedValueLivesInAVolatileFieldBesideThePasswordOne() {
        val text = connection()
        // Same retention primitive promptPassword uses — jsch reads it from
        // the connect thread while the answer was published from another.
        assertTrue(
            text.contains("@Volatile\n        private var suppliedPassphrase: String? = null") ||
                text.contains("@Volatile private var suppliedPassphrase: String? = null"),
        )
        assertTrue(text.contains("@Volatile\n        private var suppliedPassword: String? = null"))
        // Exactly one passphrase retention slot: a second writer would let the
        // dialog answer and the decrypt read different fields.
        assertEqualsOnce(text, "private var suppliedPassphrase")
    }

    // --------------------------------- 2. getPassphrase prefers what was typed

    @Test
    fun getPassphrasePrefersTheRetainedAnswerOverTheFormValue() {
        val text = connection()
        assertTrue(
            "getPassphrase must read the retained answer first",
            text.contains("override fun getPassphrase(): String? = suppliedPassphrase ?: config.passphrase"),
        )
        // The v94 shape, pinned as a regression: reading only the form value
        // is what made the decrypt unconditional-fail with a blank form.
        assertFalse(
            "getPassphrase must not be a bare config read",
            text.contains("override fun getPassphrase(): String? = config.passphrase"),
        )
        // Same precedence shape as the password side, so both secrets are
        // collected identically.
        assertTrue(text.contains("override fun getPassword(): String? = suppliedPassword ?: config.password"))
    }

    @Test
    fun aBlankFormPassphraseStillDecryptsThroughTheFallback() {
        // The fallback leg is what makes the fast path honest: with
        // config.passphrase set, promptPassphrase returns without publishing,
        // and getPassphrase's right-hand side is the only source left.
        val text = connection()
        assertTrue(text.contains("override fun getPassphrase(): String? = suppliedPassphrase ?: config.passphrase"))
        assertTrue(
            "the retained value must be nullable so a blank form falls through",
            text.contains("private var suppliedPassphrase: String? = null"),
        )
    }

    // ------------------------------- 3. same return semantics as promptPassword

    @Test
    fun promptPassphraseAnswersWithTheSameSemanticsAsPromptPassword() {
        // Both must reject an accepted-but-empty answer: jsch treats a true
        // return as "a passphrase follows" and decrypts with "" otherwise.
        val expected = "return answer.accepted && !answer.value.isNullOrEmpty()"
        assertTrue("promptPassword's contract", passwordPrompt().contains(expected))
        assertTrue("promptPassphrase must match it", passphrasePrompt().contains(expected))
        // …and the two challenge branches are the same code once the secret's
        // name is factored out.
        assertTrue(
            "the passphrase challenge must mirror the password challenge",
            normalized(promptTail(passphrasePrompt())) == normalized(promptTail(passwordPrompt())),
        )
        // An accepted-but-empty answer is not acceptance, on either side.
        assertFalse(passphrasePrompt().contains("return answer.accepted\n"))
    }

    // ------------------------------------- 4. the form fast path still skips it

    @Test
    fun aFormPassphraseAnswersWithoutEverPublishingAChallenge() {
        val body = passphrasePrompt()
        assertTrue("the fast path", body.contains("config.passphrase?.let { return true }"))
        // Before any publish: a configured passphrase never reaches the dialog.
        assertTrue(
            "the fast path must come first",
            body.indexOf("config.passphrase?.let") < body.indexOf("publishPrompt("),
        )
        // A dialog is only ever published as a non-confirm challenge with no
        // host key — the same shape as the password challenge.
        //
        // v97: `publishPrompt` now takes the dial attempt, so the literal gained
        // a leading argument. The pin is the *challenge shape* (non-confirm, no
        // host key) plus — new, and strictly more than before — that a muted
        // attempt is refused instead of publishing a card at all. That refusal
        // is what stops a cancelled private-key dial from walking to
        // keyboard-interactive (D1/F12b).
        assertTrue(
            "the challenge shape: non-confirm, no host key",
            body.contains("publishPrompt(attempt, message, confirm = false, hostKey = null)"),
        )
        assertTrue(
            "v97 D1/F12b: a muted attempt must be refused, not published as a card",
            body.contains("?: return false"),
        )
    }

    // --------------------------------------- the secret never enters the UI state

    @Test
    fun theRetainedSecretStaysOutOfThePublishedPromptState() {
        val text = connection()
        // The published challenge carries only the jsch message + the confirm
        // flag — the typed secret stays in the private volatile field and
        // never becomes renderable state.
        val publish = text
            .substringAfter("private fun publishPrompt(")
            .substringBefore("\n    }\n")
        assertTrue(publish.contains("SshPrompt(UUID.randomUUID().toString(), text, confirm)"))
        assertFalse("no secret slot on the published prompt", publish.contains("suppliedPassphrase"))
        assertFalse(
            "the answer is never handed to the prompt/state emission",
            passphrasePrompt().contains("publishPrompt(answer") ||
                text.contains("Prompted(prompt, hostKey) { answer") ||
                text.contains("state.update { answer"),
        )
    }

    // ------------------------- the concurrent host/panel seam stays exactly as is

    @Test
    fun thePromptEntryPointsKeepTheirExactSignatures() {
        val text = connection()
        // F12b's host/panel work calls these; the fix above must not have
        // moved, renamed or re-shaped any of them.
        assertTrue(text.contains("fun respondTrust(accepted: Boolean) {"))
        assertTrue(text.contains("fun respondPrompt(id: String, value: String) {"))
        assertTrue(text.contains("fun cancelPrompt() {"))
        // The trust prompt stays a confirm (no value slot) and the challenge
        // stays non-confirm — that split is what routes an answer at all.
        assertTrue(text.contains("answers.offer(PromptAnswer(prompt.id, accepted, null))"))
        assertTrue(text.contains("answers.offer(PromptAnswer(id, true, value))"))
    }

    private fun assertEqualsOnce(haystack: String, needle: String) {
        val first = haystack.indexOf(needle)
        assertTrue("missing '$needle'", first >= 0)
        assertTrue(
            "'$needle' is declared more than once",
            haystack.indexOf(needle, first + 1) < 0,
        )
    }
}
