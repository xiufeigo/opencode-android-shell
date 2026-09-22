package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ComposerDelivery
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerQueue
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSubmission
import com.xiufeigo.opencodeshell.ui.session.composer.DeliveryChipText
import com.xiufeigo.opencodeshell.ui.session.composer.MentionText
import com.xiufeigo.opencodeshell.ui.session.composer.MentionTokens
import com.xiufeigo.opencodeshell.ui.session.composer.ComposerSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAVE 2.4b (P0-8 + P0-7), pure side:
 * - structured `@` mentions ([MentionTokens]): accept records typed tokens,
 *   edits drop dead ones, [ComposerSubmission.build] maps them to the
 *   v1-parts / v2-`{files,agents,skills}` payload fields;
 * - delivery toggle ([ComposerDelivery]) + queue model ([ComposerQueue]):
 *   the `queue.ts:216-223` truth table and the add/remove/reorder/promote ops.
 *
 * Upstream anchors under test: `composer/editor/editor.tsx:351-471` (mention
 * spans), `composer/schema.ts` (part shapes), `composer/request.ts`
 * (buildPromptRequest), `composer/submit.ts:351-402` (delivery + selection),
 * `session/composer/queue.ts:100-243` (rewrite/steer/remove/reorder/edit),
 * `session/composer/queue-panel.tsx` (rows, parked Send, count header).
 */
class Wave24bComposerQueueTest {

    private fun agentItem(label: String) = ComposerSuggestions.Item(
        category = ComposerSuggestions.Category.AGENT,
        label = label,
    )

    private fun skillItem(label: String) = ComposerSuggestions.Item(
        category = ComposerSuggestions.Category.SKILL,
        label = label,
    )

    private fun fileItem(label: String) = ComposerSuggestions.Item(
        category = ComposerSuggestions.Category.FILE,
        label = label,
    )

    private fun commandItem(label: String) = ComposerSuggestions.Item(
        category = ComposerSuggestions.Category.COMMAND,
        label = label,
    )

    // ------------------------------------------------------- mention tokens

    @Test
    fun acceptingAnAgentMentionRecordsItsName() {
        // The text half stays `MentionText.insert` (M1.5 contract); the token
        // half is `tokenFor`. `accept` pairs both for JVM tests.
        val insertion = MentionText.insert("hi @", 3, 4, "build")
            ?: error("expected an insertion")
        assertEquals("hi @build ", insertion.text)
        assertEquals(
            MentionTokens.Token(ComposerSuggestions.Category.AGENT, "build", agent = "build"),
            MentionTokens.tokenFor(agentItem("build")),
        )
        val paired = MentionTokens.accept(agentItem("build"), "hi @", 3, 4, emptyList())
            ?: error("expected an insertion")
        assertEquals(insertion, paired.first)
        assertEquals(listOf(MentionTokens.tokenFor(agentItem("build"))!!), paired.second)
    }

    @Test
    fun acceptingASkillMentionRecordsItWithoutAnId() {
        val result = MentionTokens.accept(skillItem("kb"), "@", 0, 1, emptyList())
            ?: error("expected an insertion")
        assertEquals("@kb ", result.first.text)
        assertEquals(ComposerSuggestions.Category.SKILL, result.second.single().kind)
        assertEquals("kb", result.second.single().label)
    }

    @Test
    fun acceptingAFileMentionRecordsItsPath() {
        val result = MentionTokens.accept(fileItem("src/Main.kt"), "see @", 4, 5, emptyList())
            ?: error("expected an insertion")
        assertEquals("see @src/Main.kt ", result.first.text)
        assertEquals("src/Main.kt", result.second.single().path)
    }

    @Test
    fun commandsRecordNoToken() {
        val result = MentionTokens.accept(commandItem("init"), "@", 0, 1, emptyList())
            ?: error("expected an insertion")
        assertEquals("@init ", result.first.text)
        assertTrue("a /name line is a command invocation, never a mention", result.second.isEmpty())
    }

    @Test
    fun editingTheTextDropsDeadTokens() {
        val tokens = listOf(
            MentionTokens.Token(ComposerSuggestions.Category.AGENT, "build", agent = "build"),
            MentionTokens.Token(ComposerSuggestions.Category.FILE, "src/Main.kt", path = "src/Main.kt"),
        )
        // The user deleted the file chip; only the agent survives.
        assertEquals(
            listOf(tokens[0]),
            MentionTokens.retokenize("hi @build ", tokens),
        )
        assertTrue(MentionTokens.retokenize("plain text", tokens).isEmpty())
    }

    @Test
    fun duplicateLabelsKeepOneTokenPerOccurrence() {
        val token = MentionTokens.Token(ComposerSuggestions.Category.AGENT, "build", agent = "build")
        assertEquals(listOf(token, token), MentionTokens.retokenize("@build @build ", listOf(token, token)))
        assertEquals(listOf(token), MentionTokens.retokenize("@build ", listOf(token, token)))
    }

    // ------------------------------------------------- structured submission

    @Test
    fun buildMapsTokensToPayloadFieldsInOrder() {
        val model = ComposerSubmission.build(
            text = "fix @build and @src/Main.kt",
            tokens = listOf(
                MentionTokens.Token(ComposerSuggestions.Category.AGENT, "build", agent = "build"),
                MentionTokens.Token(ComposerSuggestions.Category.FILE, "src/Main.kt", path = "src/Main.kt"),
            ),
        )
        assertEquals(listOf("build"), model.agents)
        assertTrue(model.skills.isEmpty())
        assertEquals(1, model.files.size)
        assertTrue("mentions resolve to file:// URIs, never data: URLs", model.files[0].uri.startsWith("file://"))
        assertTrue(model.files[0].uri.endsWith("src/Main.kt"))
        assertEquals(ComposerSubmission.Delivery.STEER, model.delivery)
    }

    @Test
    fun attachmentsFollowMentionsAsDataUrls() {
        val attachment = ChatViewModel.Attachment(
            mime = "image/png",
            filename = "shot.png",
            dataUrl = "data:image/png;base64,AAA",
        )
        val model = ComposerSubmission.build(
            text = "see @src/Main.kt",
            attachments = listOf(attachment),
            tokens = listOf(
                MentionTokens.Token(ComposerSuggestions.Category.FILE, "src/Main.kt", path = "src/Main.kt"),
            ),
        )
        assertEquals(2, model.files.size)
        assertTrue(model.files[0].uri.startsWith("file://"))
        assertEquals("data:image/png;base64,AAA", model.files[1].uri)
        assertEquals("shot.png", model.files[1].name)
    }

    @Test
    fun skillsCarryIdAndName() {
        val model = ComposerSubmission.build(
            text = "use @kb",
            tokens = listOf(MentionTokens.Token(ComposerSuggestions.Category.SKILL, "kb")),
        )
        assertEquals(listOf(ComposerSubmission.ComposerSkill("kb", "kb")), model.skills)
    }

    @Test
    fun deliveryParsesBothWireValuesAndFallsBackToSteer() {
        assertEquals(ComposerSubmission.Delivery.QUEUE, ComposerSubmission.Delivery.of("queue"))
        assertEquals(ComposerSubmission.Delivery.STEER, ComposerSubmission.Delivery.of("steer"))
        assertEquals(ComposerSubmission.Delivery.STEER, ComposerSubmission.Delivery.of(null))
        assertEquals(ComposerSubmission.Delivery.STEER, ComposerSubmission.Delivery.of("nope"))
    }

    @Test
    fun fileUrisFollowTheUpstreamAbsoluteAndEncodingRules() {
        // Relative paths resolve against the session directory…
        assertEquals(
            "file:///home/user/proj/src/Main.kt",
            MentionTokens.fileUri("src/Main.kt", "/home/user/proj"),
        )
        // …absolute paths pass through, spaces encode, slashes survive…
        assertEquals(
            "file:///home/user/my%20proj/a.kt",
            MentionTokens.fileUri("/home/user/my proj/a.kt", "/home/user/proj"),
        )
        // …and backslashes fold (Windows session directories).
        assertEquals(
            "file:///C:/proj/src/a.kt",
            MentionTokens.fileUri("src\\a.kt", "C:\\proj"),
        )
    }

    // ---------------------------------------------------------- delivery

    @Test
    fun plainSubmitUsesTheBehaviorWhileRunningAndSteersWhenIdle() {
        assertEquals("queue", ComposerDelivery.delivery(running = true, behavior = "queue"))
        assertEquals("steer", ComposerDelivery.delivery(running = true, behavior = "steer"))
        assertEquals("steer", ComposerDelivery.delivery(running = true, behavior = null))
        assertEquals("steer", ComposerDelivery.delivery(running = false, behavior = "queue"))
    }

    @Test
    fun theHintOffersTheOppositeWhileRunningAndHidesWhenIdle() {
        assertEquals("steer", ComposerDelivery.alternate(true, "queue", editingQueued = false, hasContent = true))
        assertEquals("queue", ComposerDelivery.alternate(true, "steer", editingQueued = false, hasContent = true))
        assertEquals("steer", ComposerDelivery.alternate(true, "queue", editingQueued = true, hasContent = true))
        assertNull("idle hides the hint", ComposerDelivery.alternate(false, "queue", false, true))
        assertNull("empty hides the hint", ComposerDelivery.alternate(true, "queue", false, false))
    }

    @Test
    fun theChipLabelFollowsTheOfferedDelivery() {
        assertEquals("Steer", DeliveryChipText.label("steer"))
        assertEquals("Queue", DeliveryChipText.label("queue"))
        assertEquals("Steer", DeliveryChipText.label(null))
    }

    // ------------------------------------------------------------- queue

    private fun row(id: String, text: String = "prompt $id") = ComposerQueue.Row(id = id, text = text)

    @Test
    fun queueIsSupportedEverywhereButV1() {
        assertTrue(ComposerQueue.isQueueSupported("V2"))
        assertTrue(ComposerQueue.isQueueSupported("UNKNOWN"))
        assertTrue(ComposerQueue.isQueueSupported(null))
        assertFalse("v1 has no inbox API — the panel stays off", ComposerQueue.isQueueSupported("V1"))
        assertFalse(ComposerQueue.isQueueSupported("v1"))
    }

    @Test
    fun addDedupesAndRemoveDrops() {
        val queue = ComposerQueue.empty()
        queue.add(row("a"))
        queue.add(row("a"))
        queue.add(row("b"))
        assertEquals(listOf("a", "b"), queue.rows.map { it.id })
        assertTrue(queue.remove("a"))
        assertFalse(queue.remove("missing"))
        assertEquals(listOf("b"), queue.rows.map { it.id })
    }

    @Test
    fun reorderFollowsThePanelOrderAndIgnoresUnknownIds() {
        val queue = ComposerQueue.Model(listOf(row("a"), row("b"), row("c")))
        queue.reorder(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), queue.rows.map { it.id })
        queue.reorder(listOf("b", "ghost"))
        assertEquals(listOf("b", "c", "a"), queue.rows.map { it.id })
    }

    @Test
    fun promoteToSteerLeavesTheQueue() {
        val queue = ComposerQueue.Model(listOf(row("a"), row("b")))
        assertEquals("a", queue.promoteToSteer("a")?.id)
        assertEquals(listOf("b"), queue.rows.map { it.id })
        assertNull(queue.promoteToSteer("missing"))
    }

    @Test
    fun editLoanOpensConfirmsAndCancels() {
        val queue = ComposerQueue.Model(listOf(row("a", "first"), row("b", "second")))
        assertEquals("first", queue.edit("a")?.text)
        assertEquals("a", queue.editing?.id)
        assertEquals("a", queue.confirmEdit())
        assertNull(queue.editing)
        assertNull("no open edit", queue.confirmEdit())
        assertNull(queue.edit("missing"))
        queue.edit("b")
        queue.cancelEdit()
        assertNull(queue.editing)
        assertEquals("the row survives a cancel", 2, queue.count)
    }

    @Test
    fun replaceRefreshesRowsAndDropsAStaleEditLoan() {
        val queue = ComposerQueue.Model(listOf(row("a")))
        queue.edit("a")
        queue.replace(listOf(row("b")))
        assertEquals(listOf("b"), queue.rows.map { it.id })
        assertNull("the edited row is gone server-side", queue.editing)
    }

    @Test
    fun panelChromeRules() {
        assertTrue(ComposerQueue.showCount(4))
        assertFalse(ComposerQueue.showCount(3))
        assertTrue("parked first row keeps its actions", ComposerQueue.isParkedFirst(running = false, index = 0))
        assertFalse(ComposerQueue.isParkedFirst(running = true, index = 0))
        assertFalse(ComposerQueue.isParkedFirst(running = false, index = 1))
    }
}
