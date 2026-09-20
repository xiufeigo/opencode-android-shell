package com.xiufeigo.opencodeshell.ui.session.timeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * Streaming coalescing window for the Markwon body (`ChatView.RENDER_COALESCE_MS`
 * in the View shell, M1 review "Markwon 每帧全量重解析").
 *
 * A streaming answer produces a delta every frame; re-running Markwon
 * `parse()+render()+Prism4j` per delta is O(n) per frame (quadratic over the
 * answer) and allocates a fresh span set each time. The window keeps the visible
 * text ≤ 80 ms behind the stream instead.
 */
internal const val COL_CHUNK_MS = 80L

/**
 * Leading-edge throttle with a trailing newest-wins slot: the first value is
 * emitted immediately, then **at most one value per [windowMs]** — always the
 * newest one seen inside the window.
 *
 * Two properties matter for streaming markdown:
 * - intermediates are *dropped*, never rendered (one re-parse per window);
 * - the last value of a burst is never lost, because `conflate()` keeps the
 *   newest pending value while the collector waits out the window.
 *
 * Pure Kotlin (no Android/Compose imports), so the contract is JVM-tested
 * (`MarkdownStreamTest`).
 */
internal fun <T> Flow<T>.coalesceLatest(windowMs: Long = COL_CHUNK_MS): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(windowMs)
    }

/**
 * `Configuration.uiMode` → "use the night resource set".
 *
 * This is the key the Markwon instance is rebuilt on: its palette is baked in at
 * build time and `values-night/colors_oc.xml` differs for inline code / code
 * blocks, so a single frozen instance renders the wrong colours after a
 * dark⇄light switch (`OcActivity` declares `configChanges=uiMode`, i.e. the
 * Activity is *not* recreated).
 *
 * The mask/flag values are repeated as local constants instead of reading
 * `android.content.res.Configuration`, which keeps the helper assertable from a
 * plain JVM test (the unit-test android.jar stubs return defaults).
 */
internal fun isNightUiMode(uiMode: Int): Boolean = (uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES

/** `Configuration.UI_MODE_NIGHT_MASK`. */
private const val UI_MODE_NIGHT_MASK = 0x30

/** `Configuration.UI_MODE_NIGHT_YES`. */
private const val UI_MODE_NIGHT_YES = 0x20
