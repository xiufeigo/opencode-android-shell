package com.xiufeigo.opencodeshell.ui.session.composer

/**
 * WAVE 2.4b (P0-7): the composer's delivery choice — steer vs queue.
 *
 * Upstream (`session/composer/queue.ts:216-223` + `controller.ts:27-32`):
 * - `delivery()` — what a plain submit uses right now: the settings
 *   `followUpBehavior` while the turn is running, `"steer"` when idle.
 * - `alternate()` — what the hint button / alternate keybind offers: the
 *   opposite of the behavior while running and the composer holds a value,
 *   `"steer"` while confirming a queue edit, `undefined` (hidden) when idle.
 * - `queue.ts:217` gates the whole surface on `working()`: idle submits always
 *   steer, because there is no running turn to queue behind.
 *
 * The native composer reads `followUpBehavior` from [SettingsStore] (Wave 2.3a
 * owns the key, default `steer`) and the run state from
 * `ChatStore.RunStatus`; it never writes the setting (upstream keeps it in
 * settings — `settings/general/general.tsx`). v1 has no inbox API
 * (`OpenCodeClient` has no inbox endpoint; `V1Transport` drops `delivery`),
 * so the queue surface is generation-gated OFF on v1 — see [ComposerQueue].
 *
 * Pure Kotlin so the toggle truth table is JVM-testable.
 */
object ComposerDelivery {

    /** Wire values (`composer/adapter.ts:42` `ComposerDelivery`). */
    const val STEER = "steer"
    const val QUEUE = "queue"

    /** The two behaviors the settings row offers (`model.tsx:96`, default `steer`). */
    val BEHAVIORS = setOf(STEER, QUEUE)

    /**
     * What a plain submit uses right now (`queue.ts:217`):
     * `delivery() = working ? behavior : "steer"`.
     *
     * @param running true while the session turn is running.
     * @param behavior the `followUpBehavior` setting (`steer` | `queue`).
     */
    fun delivery(running: Boolean, behavior: String?): String =
        if (running && behavior?.trim()?.lowercase() == QUEUE) QUEUE else STEER

    /**
     * What the hint button offers (`queue.ts:218-222` `alternate()`):
     * - queueing an edit → `"steer"` (confirming an edit re-admits it; the
     *   alternate sends it as a steer — `model.ts:395-399`);
     * - idle → null (the hint hides; there is nothing to queue behind);
     * - running with content → the opposite of [delivery].
     *
     * @param hasContent whether the composer currently holds a submittable
     * value (`ComposerSubmit.canSubmit` — the upstream hint shows only while
     * `controller.canSubmit()`, `editor.tsx:833`).
     */
    fun alternate(running: Boolean, behavior: String?, editingQueued: Boolean, hasContent: Boolean): String? {
        if (editingQueued) return STEER
        if (!running || !hasContent) return null
        return if (delivery(running, behavior) == QUEUE) STEER else QUEUE
    }

    /** True when [value] selects the queue behavior (settings or delivery). */
    fun isQueue(value: String?): Boolean = value?.trim()?.lowercase() == QUEUE

    /** Normalizes a stored behavior; unknown values fall back to [STEER]. */
    fun normalize(value: String?): String = if (isQueue(value)) QUEUE else STEER
}
