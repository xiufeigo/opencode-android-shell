package com.xiufeigo.opencodeshell.ui.session.composer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * W5-C B8: one draft per composer key (the session id), plus the "the send
 * failed, give the text back" slot.
 *
 * Why a process-wide store instead of `rememberSaveable` alone: the composer is
 * re-created whenever the screen switches sessions (`SessionScreen` rebuilds the
 * whole region on a session switch) and the app has several screens that can own
 * a composer. Upstream keeps the same state in `composer/persistence.tsx`
 * (draft + cursor per session key), and the `Screen`-switch survival requirement
 * is exactly why the value cannot live in the composition.
 *
 * The store is deliberately dumb and synchronous so it is JVM-testable:
 * [text] / [set] / [restore] / [takeRestore]. The composer is the only writer of
 * [set]; [restore] is called by the view model when a prompt POST fails
 * (`composer/submit.ts:407-418` `failSubmission` → `restore()`), so the text the
 * user typed is never lost.
 */
object ComposerDrafts {

    /** Key used when the caller has no session id (pre-session draft / previews). */
    const val DEFAULT_KEY = "oc-draft-default"

    /**
     * [pendingRestore] is the one-shot flag that makes the composer adopt [text]
     * even though the user is not typing: it is set by [restore] and cleared by
     * [takeRestore].
     */
    data class Draft(val text: String = "", val pendingRestore: Boolean = false)

    private val _state = MutableStateFlow<Map<String, Draft>>(emptyMap())

    /** All live drafts, observable so the composer can adopt a restored one. */
    val state: StateFlow<Map<String, Draft>> = _state.asStateFlow()

    /** Session id (or [DEFAULT_KEY]) → store key. Blank ids fall back too. */
    fun key(sessionID: String?): String = sessionID?.takeIf { it.isNotBlank() } ?: DEFAULT_KEY

    fun text(key: String): String = _state.value[key]?.text.orEmpty()

    fun pendingRestore(key: String): Boolean = _state.value[key]?.pendingRestore == true

    /**
     * Records what the editor currently holds. No-op when nothing changed, so the
     * caller may call it on every keystroke without growing the map.
     */
    fun set(key: String, text: String) {
        _state.update { current ->
            val existing = current[key]
            if (existing != null && existing.text == text && !existing.pendingRestore) {
                current
            } else {
                current + (key to Draft(text, pendingRestore = false))
            }
        }
    }

    /** A send failed: hand [text] back to the editor for that key. */
    fun restore(key: String, text: String) {
        if (text.isEmpty()) return
        _state.update { it + (key to Draft(text, pendingRestore = true)) }
    }

    /**
     * Consumes the restore request and answers the text to put back, or null when
     * there is nothing pending. Callers must not touch the editor otherwise.
     */
    fun takeRestore(key: String): String? {
        val pending = _state.value[key] ?: return null
        if (!pending.pendingRestore) return null
        _state.update { it + (key to Draft(pending.text, pendingRestore = false)) }
        return pending.text
    }

    /** Drops one key's draft (a sent prompt, a closed session). */
    fun clear(key: String) {
        _state.update { it - key }
    }

    /** Test seam: forget every draft. */
    fun reset() {
        _state.value = emptyMap()
    }
}
