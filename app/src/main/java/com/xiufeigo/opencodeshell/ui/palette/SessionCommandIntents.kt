package com.xiufeigo.opencodeshell.ui.palette

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One `file.open` request: [path] is the palette row's file and [id] makes two
 * identical requests distinguishable (a `StateFlow<String?>` would swallow the
 * second one).
 */
data class OpenFileRequest(val id: Long, val path: String)

/**
 * The one-way channel from the command layer to the **screen-local** panel state
 * of `SessionScreen`.
 *
 * Upstream keeps the review panel and the opened-file tab in app-level state
 * (`session/session-layout.ts` `view().reviewPanel`, `session/review/model.ts`
 * `openFile`), so `review.toggle` / `file.open` reach it from anywhere
 * (`use-session-commands.tsx:369-374`, `palette.ts:66-80`). The Android shell
 * keeps that state inside `SessionScreen` (`reviewOpen`, the panel's tab), which
 * no command can touch; this holder is the channel instead of a hard dependency:
 *
 * - [App] creates one instance and provides it through [LocalSessionCommandIntents];
 * - the palette rows / the `Ctrl+`` chords call [toggleReview] / [openFile];
 * - `SessionScreen` collects both flows and applies them (it owns the panel).
 *
 * Requests are *state*, not events: a request that arrives before the session
 * screen is composed is still delivered when it lands, which is what makes
 * `file.open` work even though the palette closes first.
 */
class SessionCommandIntents {

    private val _reviewRevision = MutableStateFlow(0L)

    /**
     * Bumped once per `review.toggle`. The session screen flips `reviewOpen`
     * for every new value (`LaunchedEffect(revision)` keyed on it), which keeps
     * the toggle semantics a plain boolean flip instead of an "open" command.
     */
    val reviewRevision: StateFlow<Long> = _reviewRevision.asStateFlow()

    private val _fileRequest = MutableStateFlow<OpenFileRequest?>(null)

    /** The pending `file.open` request, consumed by the session screen. */
    val fileRequest: StateFlow<OpenFileRequest?> = _fileRequest.asStateFlow()

    private val _fileTreeRevision = MutableStateFlow(0L)

    /**
     * Bumped once per `fileTree.toggle`. Unlike [reviewRevision] this always
     * *opens* the file view (there is no "file tree closed" state a command
     * could observe from here).
     */
    val fileTreeRevision: StateFlow<Long> = _fileTreeRevision.asStateFlow()

    private var sequence = 0L

    /** `review.toggle` (`use-session-commands.tsx:369-374`). */
    fun toggleReview() {
        _reviewRevision.value += 1
    }

    /**
     * `createCommandPaletteFileOpener` (`palette.ts:66-80`): open the file **and**
     * open the review panel on its file tab. The panel open is part of the
     * request, so this deliberately does not bump [reviewRevision] — a toggle
     * would *close* an already-open panel.
     */
    fun openFile(path: String) {
        if (path.isBlank()) return
        sequence += 1
        _fileRequest.value = OpenFileRequest(sequence, path)
    }

    /** `fileTree.toggle` (`use-session-commands.tsx:375-384`). */
    fun openFileTree() {
        sequence += 1
        _fileTreeRevision.value += 1
    }

    /** Clears [fileRequest] once the session screen has shown the file. */
    fun consume(request: OpenFileRequest) {
        if (_fileRequest.value?.id == request.id) _fileRequest.value = null
    }
}

/**
 * Ambient intent channel. Null outside [App] (previews, isolated panel tests),
 * which is why every consumer reads it as nullable.
 */
val LocalSessionCommandIntents = compositionLocalOf<SessionCommandIntents?> { null }
