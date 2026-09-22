package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * WAVE 2.4b (P0-7): the queue row's attachment-count text as pure logic.
 *
 * The row shows `N attachment(s)` next to (or instead of) the display text
 * (`queue-panel.tsx:140-147` `language.plural("session.queue.attachments")`).
 * The composable resolves the generated `session_queue_attachments_*` strings;
 * [fallback] pins the English shape for JVM tests.
 */
internal object QueueAttachmentsText {

    fun fallback(count: Int): String =
        if (count == 1) "$count attachment" else "$count attachments"
}
