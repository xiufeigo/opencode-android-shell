package com.xiufeigo.opencodeshell.ui.session.timeline

import com.xiufeigo.opencodeshell.ChatStore
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure (JVM-testable) message meta line — the port of the two meta blocks in
 * `_upstream/packages/session-ui/src/message/message-content.tsx`:
 *
 * - assistant (`:511-521`): `Agent · Model · {duration} · Interrupted`, every
 *   empty segment dropped, joined with `" · "`;
 * - user (`:227-231`): `Agent · Model` (joined with `"\u00A0·\u00A0"`) plus the
 *   locale short **time** stamp.
 *
 * The duration is `time.completed - time.created` rounded to seconds, rendered
 * as `{n}s` below a minute and `{m}m {s}s` above (`:500-509`,
 * `ui.message.duration.*`). A message that never completed has no duration, and
 * a segment without data is simply not rendered — the upstream `Show`/`filter
 * (Boolean)` rules.
 */
object MessageMeta {

    /** The four display segments, each independently optional. */
    data class Meta(
        val agent: String? = null,
        val model: String? = null,
        val durationMs: Long? = null,
        val interrupted: Boolean = false,
        val createdAt: Long = 0L,
    ) {
        /** `Agent · Model · 12s · Interrupted` (assistant), empty when no data. */
        val text: String
            get() = listOfNotNull(
                agent,
                model,
                durationText,
                if (interrupted) "Interrupted" else null,
            ).joinToString(" \u00B7 ")

        /** `Agent · Model` of the user row (`metaHead()`), empty when no data. */
        val head: String
            get() = listOfNotNull(agent, model).joinToString("\u00A0\u00B7\u00A0")

        /** `12s` / `1m 5s`, `null` while the message has not completed. */
        val durationText: String?
            get() = durationOf(durationMs)

        val isEmpty: Boolean get() = text.isEmpty()
    }

    /**
     * Reads the additive display fields `ChatStore` now carries. Single wiring
     * point: if the store renames a field, only this function changes.
     */
    fun of(message: ChatStore.UiMessage): Meta = Meta(
        agent = capitalizeAgent(message.agent),
        // Upstream resolves the catalog display name; the store only carries
        // `providerID/modelID`, so the model id half is the honest fallback.
        model = message.model?.substringAfterLast('/')?.takeIf { it.isNotEmpty() },
        durationMs = durationOf(message.timeCreated, message.timeCompleted),
        interrupted = message.interrupted,
        createdAt = message.timeCreated,
    )

    /** `completed - created`, only for a sane positive span. */
    fun durationOf(createdAt: Long, completedAt: Long?): Long? {
        if (completedAt == null || createdAt <= 0L) return null
        val span = completedAt - createdAt
        return span.takeIf { it > 0L }
    }

    /** `ui.message.duration.seconds` / `minutesSeconds` (`message-content.tsx:500-509`). */
    fun durationOf(ms: Long?): String? {
        val total = durationSeconds(ms) ?: return null
        if (total < 60) return "${total}s"
        return "${total / 60}m ${total % 60}s"
    }

    /**
     * Whole seconds of the span, for the localized templates
     * (`oc_meta_duration_seconds` / `oc_meta_duration_minutes`).
     */
    fun durationSeconds(ms: Long?): Int? {
        if (ms == null || ms < 0L) return null
        return (ms.toDouble() / 1000.0).roundToInt()
    }

    /** `capitalizeAgent()` (`session-timeline-row.tsx:313`): `build` → `Build`. */
    fun capitalizeAgent(agent: String?): String? = agent?.takeIf { it.isNotEmpty() }
        ?.replaceFirstChar { it.uppercase(Locale.US) }
}
