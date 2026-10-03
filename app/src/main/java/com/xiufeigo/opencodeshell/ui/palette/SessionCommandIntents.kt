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
 * One `session.aside` (`/btw`) request. [question] is what the user typed after
 * the trigger, or `null` for the **bare** form: upstream's palette row calls
 * `run(input)` with no input and falls back to its question dialog
 * (`btw.tsx:64-66`), and on this shell that dialog is the btw panel's own field
 * — so the bare form opens the panel instead of asking. [id] distinguishes two
 * identical requests, like [OpenFileRequest].
 */
data class AsideRequest(val id: Long, val question: String?)

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
 * - the palette rows / the `Ctrl+`` chords call [toggleReview] / [openFile] /
 *   [askAside];
 * - `SessionScreen` collects both flows and applies them (it owns the panel);
 *   [asideRequest] is applied by the btw host that renders the panel
 *   (`ui/session/btw`, see [askAside]).
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

    private val _asideRequest = MutableStateFlow<AsideRequest?>(null)

    /**
     * The pending `session.aside` (`/btw`) request, run by the btw host
     * (`ui/session/btw`: [com.xiufeigo.opencodeshell.ui.session.btw.SessionBtwHost.ask] /
     * `openPanel`).
     *
     * Why this channel and not `LocalSessionBtwHost`: the command palette is a
     * layer of the **global** dialog stack, composed by `DialogHost` *above*
     * `AppScaffold` (`ui/overlay/DialogHost.kt` — `entry.content(controller)`),
     * so the palette never sees the host the session route publishes around
     * `SessionScreen`; a palette row reading that composition local would always
     * get `null`. The screen-local side-question state is therefore reached the
     * same way `review.toggle` / `file.open` reach the panel state.
     */
    val asideRequest: StateFlow<AsideRequest?> = _asideRequest.asStateFlow()

    private val _commandRequest = MutableStateFlow<CommandRequest?>(null)

    /**
     * B-5: the pending **screen-local** command, one at a time — the channel a
     * global chord uses to reach an action whose state lives inside a screen.
     *
     * The App-level chords (`session.undo`, `permissions.autoaccept`, …) call
     * the view model / store directly; the ids below only exist as an action on
     * a route's own state, which no command outside that route can touch:
     *
     * - `session.export`, `message.previous`, `message.next`, `input.focus`,
     *   `model.choose`, `terminal.new` → `SessionScreen`;
     * - `session.location.cycle` → `NewSessionScreen` (the draft's workspace).
     *
     * Each consumer reads the ids it serves and calls [consumeCommand]; an id
     * nobody serves stays pending, which is the honest state (the chord did
     * nothing because no screen on screen implements it).
     */
    val commandRequest: StateFlow<CommandRequest?> = _commandRequest.asStateFlow()

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

    /**
     * `session.aside` — upstream's `/btw` (`btw.tsx` `run`): a one-shot side
     * question answered from the session's context, which must **not** become a
     * turn of the conversation. A blank [question] is the bare form (open the
     * panel and let its field be the question dialog). The request is *state*,
     * so a palette tap that lands before the session view composes is still
     * delivered.
     */
    fun askAside(question: String?) {
        sequence += 1
        _asideRequest.value = AsideRequest(sequence, question?.trim()?.takeIf { it.isNotEmpty() })
    }

    /** Clears [asideRequest] once the btw host has run it. */
    fun consumeAside(request: AsideRequest) {
        if (_asideRequest.value?.id == request.id) _asideRequest.value = null
    }

    /**
     * B-5: ask the screen that serves [commandId] to run it. The request is
     * *state*, so a chord pressed just before the route composes still lands.
     */
    fun requestCommand(commandId: String) {
        if (commandId.isBlank()) return
        sequence += 1
        _commandRequest.value = CommandRequest(sequence, commandId)
    }

    /** Clears [commandRequest] once the screen has run it. */
    fun consumeCommand(request: CommandRequest) {
        if (_commandRequest.value?.id == request.id) _commandRequest.value = null
    }
}

/**
 * B-5: one screen-local command request. [id] distinguishes two presses of the
 * same chord (a `StateFlow<String?>` would swallow the second).
 */
data class CommandRequest(val id: Long, val commandId: String)

/**
 * Ambient intent channel. Null outside [App] (previews, isolated panel tests),
 * which is why every consumer reads it as nullable.
 */
val LocalSessionCommandIntents = compositionLocalOf<SessionCommandIntents?> { null }
