package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentDestination
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentDestination.Input
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentDestination.Route
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentOutcome
import com.xiufeigo.opencodeshell.ui.session.composer.AttachmentPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AttachmentDestination.escalate` + the [AttachmentOutcome] handoff — the
 * second half of upstream `attachments.ts:65-70` `add()`, where a pick the
 * `data:` packer cannot hold falls through to the path branches instead of
 * being rejected. (`route()` itself is pinned in `AttachmentDestinationTest`.)
 */
class AttachmentOutcomeTest {

    private val vision = Input(image = true, pdf = false)

    @Test
    fun escalateKeepsASuccessfulInlinePack() {
        assertEquals(
            Route.INLINE,
            AttachmentDestination.escalate(Route.INLINE, packed = true, local = false, hasSourcePath = false),
        )
    }

    @Test
    fun escalateFallsThroughToThePathBranchesWhenThePackerRefuses() {
        assertEquals(
            "content:// picks have no resolvable path",
            Route.UPLOAD,
            AttachmentDestination.escalate(Route.INLINE, packed = false, local = true, hasSourcePath = false),
        )
        assertEquals(
            Route.UPLOAD,
            AttachmentDestination.escalate(Route.INLINE, packed = false, local = false, hasSourcePath = true),
        )
        assertEquals(
            Route.LOCAL_PATH,
            AttachmentDestination.escalate(Route.INLINE, packed = false, local = true, hasSourcePath = true),
        )
    }

    @Test
    fun escalateNeverReroutesThePathBranches() {
        for (packed in listOf(true, false)) {
            assertEquals(
                Route.LOCAL_PATH,
                AttachmentDestination.escalate(Route.LOCAL_PATH, packed, local = true, hasSourcePath = true),
            )
            assertEquals(
                Route.UPLOAD,
                AttachmentDestination.escalate(Route.UPLOAD, packed, local = false, hasSourcePath = false),
            )
        }
    }

    @Test
    fun nativeMediaBetweenThePackerCeilingAndTheInlineCapEscalates() {
        // Upstream inlines native media up to MAX_INLINE_BYTES, but this app's
        // `data:` packer caps at AttachmentPolicy.MAX_ATTACHMENT_BYTES — the
        // window between them must take the path route, not a TooLarge reject.
        val size = AttachmentPolicy.MAX_ATTACHMENT_BYTES.toLong() + 1
        assertEquals(
            Route.INLINE,
            AttachmentDestination.route("image/png", size, vision, local = false, hasSourcePath = false),
        )
        assertEquals(
            Route.UPLOAD,
            AttachmentDestination.escalate(Route.INLINE, packed = false, local = false, hasSourcePath = false),
        )
    }

    @Test
    fun outcomeVariantsCarryMimeAndFilenameAlongsideThePayload() {
        val inline = AttachmentOutcome.Inline("data:image/png;base64,AA==", "image/png", "a.png")
        val localPath = AttachmentOutcome.LocalPath("/data/a.pdf", "application/pdf", "a.pdf")
        val uploaded = AttachmentOutcome.Uploaded("/tmp/opencode/uploads/u1/notes.md", "text/markdown", "notes.md")

        assertEquals("data:image/png;base64,AA==", inline.uri)
        assertEquals("image/png", inline.mime)
        assertEquals("a.png", inline.filename)

        assertEquals("/data/a.pdf", localPath.path)
        assertEquals("application/pdf", localPath.mime)
        assertEquals("a.pdf", localPath.filename)

        assertEquals("/tmp/opencode/uploads/u1/notes.md", uploaded.path)
        assertEquals("text/markdown", uploaded.mime)
        assertEquals("notes.md", uploaded.filename)

        // Value semantics: the round queue and the view model compare outcomes.
        assertEquals(inline, AttachmentOutcome.Inline("data:image/png;base64,AA==", "image/png", "a.png"))
    }
}
