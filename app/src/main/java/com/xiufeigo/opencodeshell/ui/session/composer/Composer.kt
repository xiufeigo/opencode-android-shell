package com.xiufeigo.opencodeshell.ui.session.composer

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcEnterExitVisibility
import com.xiufeigo.opencodeshell.ui.components.OcEnterOnMount
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Composer (prompt-input v2). M1 stage 1 laid out the shell; M1.5 wired the
 * three data-bearing controls:
 *
 * - 「+」 → SAF multi-document pick (`onAttachClick`, registered by the screen)
 *   and the pending-attachment strip [AttachmentPreviewRow] (58×46 thumbs /
 *   `layer02` file chips, 16 dp remove badge)
 * - 「@」 → [MentionPopover] fed by `/find/file` (`onSearchFiles`), 200 ms
 *   debounce (`MentionText.DEBOUNCE_MS`), Enter/↑/↓/Esc keyboard, and mention
 *   colouring inside the editor ([MentionColorTransformation])
 * - the model chip → [ModelChip] + [ModelPickerContent] over the real
 *   `GET /provider` catalog ([providers]); [onSelectModel] pins
 *   `providerID/modelID` on the next prompt, [onUseServerDefaultModel] clears
 *   the override
 *
 * Geometry unchanged from M1 (spec `research/02` §4.2): editor `min 60 / max 180`
 * px-16 pt-16 pb-8 at 13 sp, tool row 44 dp px-8, submit 28 dp radius 6.
 *
 * Submit/stop follows §4.6–§4.7 (M1-review cleanup):
 * - `Enter` (not Shift, not an auto-repeat) submits, `Shift+Enter` newlines —
 *   the soft keyboard sends `ImeAction.Send` (`KeyboardActions.onSend`), a
 *   hardware Enter is caught in `onPreviewKeyEvent`;
 * - the primary button stops the run **only** while the input is blank; with
 *   text/attachments pending it stays a send button (queued follow-up).
 * Both rules live in [ComposerSubmit] so they are JVM-tested.
 *
 * ---------------------------------------------------------------------------
 * W5-C additions (every new parameter is defaulted, so the M1.5 call site keeps
 * compiling and behaving exactly as before until the screen passes the new data):
 *
 * - **B1 `/` commands** — a lone `/word` opens the inline command menu
 *   (`composer/suggestions/machine.ts:107-114`): the two built-ins from
 *   `composer/commands.tsx:49-82` (`/model` opens the model picker, `/agent` opens
 *   the agent menu) plus the server's `GET /command` list, ↑↓/Enter/Esc, tap to
 *   pick. Selecting a server command inserts `/name `; submitting it posts
 *   `POST /session/:id/command` ([onSubmit] → `ChatViewModel.submit`).
 * - **B2 「+」 menu** — `ComposerEditorAddMenu` (`composer/editor/editor.tsx:613-671`,
 *   `min-width:180px`): 附件 (SAF) / 命令 `/` / 上下文 `@` / Shell `!` (+ 粘贴 for
 *   B6, see below), each inserting its trigger or switching the mode.
 * - **B3 shell mode** — `composer/placeholder.ts:6`: text `!` or the menu's Shell
 *   row puts the editor in shell mode (monospace, shell placeholder, the
 *   attach/agent/model controls hidden like upstream's `inert` containers),
 *   submitted through `POST /session/:id/shell` (`composer/submit.ts:296-298`).
 * - **B4 agent chip + model variants** — [AgentChip] over `GET /agent`
 *   (`mode == "primary"`), and the variant group inside [ModelPickerContent]
 *   (`providers/models/variant.ts`); the selection is sent as `agent` / `variant`
 *   by [ChatViewModel.submit].
 * - **B7 input experience** — auto-focus on session entry ([FocusRequester]),
 *   `↑`/`↓` prompt history ([PromptHistory]), Esc stops the running turn
 *   (`composer/editor/interaction.ts:225-233`).
 * - **B8 drafts** — one draft per session ([ComposerDrafts]); a failed send hands
 *   the text back.
 * - **B9 `@` categories** — agent / command / skill / file rows with their
 *   category icon ([ComposerSuggestions.mentionItems]); the inserted text stays a
 *   plain `@label ` mention, exactly like the file case.
 * - **B5/B6 attachments** — 24 dp edge fades and the in-app image preview live in
 *   [AttachmentPreviewRow]; paste (image item → attachment, bulk text → text) and
 *   the SAF read's progress + cancel ([AttachmentUploads]) are wired here. Compose
 *   has no DOM-style `onPaste`, so the paste entry points are the「+」menu's 粘贴 row
 *   and a hardware `Ctrl+V` caught in `onPreviewKeyEvent`.
 *
 * W5-F (full-width input, `research/wave5_composer.md` §8) — a Chinese IME emits
 * `／`/`＠` for the slash/at keys, which is why the `/` menu never opened on the
 * device. Every text entry point ([onValueChange], [insertText], the draft
 * restore, the history recall and [submit]) now folds through [HalfWidthText]
 * *before* the trigger checks, so `commandQuery` / [MentionText.anchorAt] /
 * `invocation` only ever see half-width syntax.
 *
 * Wiring still owed by the session screen (see `research/wave5_composer.md`):
 * `sessionID`, `agents`/`selectedAgent`/`onSelectAgent`, `commands`, `skills`,
 * `selectedVariant`/`onSelectVariant`, `onPasteAttachments` and `onSubmit`.
 */
@Composable
fun Composer(
    runStatus: ChatStore.RunStatus,
    modelLabel: String?,
    attachments: List<ChatViewModel.Attachment>,
    providers: OpenCodeClient.Providers?,
    selectedModel: Pair<String, String>?,
    modelOverrideActive: Boolean,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (ChatViewModel.Attachment) -> Unit,
    onSelectModel: (providerID: String, modelID: String) -> Unit,
    onUseServerDefaultModel: () -> Unit,
    onSearchFiles: (query: String, onResult: (List<String>) -> Unit) -> Unit,
    onSend: (String, List<ChatViewModel.Attachment>) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    /** W5-C: the session this composer drafts for (draft + history key). */
    sessionID: String? = null,
    /** W5-C B4: `GET /agent`; the chip renders nothing while this is empty. */
    agents: List<OpenCodeClient.Agent> = emptyList(),
    selectedAgent: String? = null,
    agentOverrideActive: Boolean = false,
    onSelectAgent: (String) -> Unit = {},
    onUseDefaultAgent: () -> Unit = {},
    /** W5-C B1: `GET /command` — the `/` menu's server half. */
    commands: List<OpenCodeClient.CommandInfo> = emptyList(),
    /** W5-C B9: `GET /skill` — the `@` menu's skill category. */
    skills: List<OpenCodeClient.SkillInfo> = emptyList(),
    /** W5-C B4: the pinned variant label (`null` = default). */
    selectedVariant: String? = null,
    onSelectVariant: (String?) -> Unit = {},
    /**
     * W5-C: the full submit payload (prompt / shell / slash command). When it is
     * null the composer falls back to [onSend] for plain prompts — shell and
     * command submissions then do nothing, which is why the screen is expected to
     * pass `onSubmit = { p -> chatViewModel.submit(p.text, p.attachments, p.shell, p.command, p.arguments) }`.
     */
    onSubmit: ((ComposerSubmit.Payload) -> Unit)? = null,
    /** W5-C B6: pasted images/file items, read by this composer's clipboard hook. */
    onPasteAttachments: (List<ChatViewModel.Attachment>) -> Unit = {},
    /**
     * WAVE 2.4b (P0-7): the `followUpBehavior` setting (`steer` | `queue`,
     * default `steer` — Wave 2.3a `SettingsStore.followUpBehavior`). The
     * composer reads it to default the Steer/Queue toggle while the turn runs;
     * it never persists from here (upstream keeps it in settings). The screen
     * passes `settings.followUpBehavior`; default keeps the M1.5 call site green.
     */
    followUpBehavior: String = ComposerDelivery.STEER,
    /**
     * WAVE 2.4b (P0-7): the server generation name (`V1` | `V2` | `UNKNOWN`,
     * `ChatViewModel.generation`). The queue surface (toggle + panel) is
     * generation-gated: v1 has no inbox API, so v1 keeps send-and-wait
     * ([ComposerQueue.isQueueSupported]).
     */
    generation: String = "UNKNOWN",
    /**
     * WAVE 2.4b (P0-7): the session's queued follow-ups (VM-owned inbox rows
     * mapped to [ComposerQueue.Row]; empty = no panel). Steer/remove go back
     * through the callbacks so the transport round-trips stay VM-side; the
     * edit loan itself is composer-local (tap a row → its text loads into the
     * editor, Enter confirms via [onQueueConfirm], Esc cancels).
     */
    queuedRows: List<ComposerQueue.Row> = emptyList(),
    queueBusy: Boolean = false,
    onQueueSteer: (String) -> Unit = {},
    onQueueRemove: (String) -> Unit = {},
    onQueueConfirm: (originalId: String, replacement: ComposerSubmission.Model) -> Unit = { _, _ -> },
    /**
     * WAVE 2.4b (P0-8): structured submit. When non-null the composer hands
     * over a [ComposerSubmission.Model] (text + mention files/agents/skills +
     * delivery) instead of the legacy [ComposerSubmit.Payload]; when null it
     * falls back to [onSubmit] and then [onSend] exactly as before.
     */
    onStructuredSubmit: ((ComposerSubmission.Model) -> Unit)? = null,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    val context = LocalContext.current
    val running = runStatus == ChatStore.RunStatus.RUNNING

    val draftKey = remember(sessionID) { ComposerDrafts.key(sessionID) }
    val history = remember(draftKey) { ComposerHistory.of(draftKey) }

    var value by rememberSaveable(draftKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(HalfWidthText.normalize(ComposerDrafts.text(draftKey))))
    }
    // Shell mode (`composer/suggestions/machine.ts:4,75,93-97`); per draft key so
    // switching sessions does not carry the mode over.
    var shellMode by rememberSaveable(draftKey) { mutableStateOf(false) }
    // Popovers. `trigger` is the open `@` (and its query); `slashQuery` the open
    // `/word`; they share one popover slot because the editor text can only be in
    // one shape at a time (`machine.ts:86-119`).
    var trigger by remember { mutableStateOf<MentionText.Anchor?>(null) }
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var slashQuery by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(0) }
    var addMenuOpen by remember { mutableStateOf(false) }
    // `/model` and `/agent` open the chips' menus: a bumped counter each.
    var modelOpenRequest by remember { mutableStateOf(0) }
    var agentOpenRequest by remember { mutableStateOf(0) }

    val mentionItems = remember(trigger, files, agents, commands, skills) {
        trigger?.let { anchor ->
            ComposerSuggestions.mentionItems(
                query = anchor.query,
                agents = agents,
                commands = commands,
                skills = skills,
                // `MentionText.candidates` still owns the file rows' trim / dedupe
                // / cap, so the two surfaces cannot drift apart.
                files = MentionText.candidates(files).map { it.label },
            )
        } ?: emptyList()
    }
    val slashItems = remember(slashQuery, commands) {
        slashQuery?.let { ComposerSuggestions.commandItems(it, commands) } ?: emptyList()
    }
    val items = if (slashQuery != null) slashItems else mentionItems
    val popoverOpen = items.isNotEmpty()
    val upload by AttachmentUploads.state.collectAsState()

    // Shell mode ignores attachments (`canSubmit` mirrors `interaction.ts:343-354`),
    // so the button's enabled state uses the same rule as [submit].
    val hasContent = ComposerSubmit.canSubmit(value.text, attachments.size, shell = shellMode)
    // §4.6: stop only while the input is blank — otherwise the button sends
    // (a typed follow-up must not abort the running turn).
    val stopping = ComposerSubmit.stopping(running, value.text, attachments.size)
    // The search effect can outlive the composition that started it: always call
    // the latest bridge, never a captured lambda.
    val searchFiles by rememberUpdatedState(onSearchFiles)
    val pasteAttachments by rememberUpdatedState(onPasteAttachments)
    val submitPayload by rememberUpdatedState(onSubmit)
    val submitStructured by rememberUpdatedState(onStructuredSubmit)
    // WAVE 2.4b (P0-7): the Steer/Queue hint offer. Same visibility rule as
    // upstream editor.tsx ComposerEditorAlternateDelivery: while the turn runs
    // and the composer holds a value, null hides the chip. Generation-gated.
    // `queueEditOpen` is read here (not the state itself) so the declaration
    // order stays valid — the loan state is declared with the other editor
    // state below.
    var queueEditOpen by remember { mutableStateOf(false) }
    // One-shot: the Steer/Queue toggle arms the *next* submit with the offered
    // delivery (upstream `submit({alternate:true})`); plain Enter stays on the
    // default. A flag (not a parameter) keeps the composable submit shape.
    var alternateNext by remember { mutableStateOf(false) }
    val queueSupported = ComposerQueue.isQueueSupported(generation)
    val alternateDelivery = if (!queueSupported) {
        null
    } else {
        ComposerDelivery.alternate(running, followUpBehavior, queueEditOpen, hasContent)
    }

    // B7: first entry into (or switch to) a session focuses the editor.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(draftKey) {
        // One frame so the field exists in the layout tree before it takes focus.
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    // B8: a failed send put a draft back — adopt it.
    val drafts by ComposerDrafts.state.collectAsState()
    val restoredPending = drafts[draftKey]?.pendingRestore == true
    LaunchedEffect(draftKey, restoredPending) {
        if (!restoredPending) return@LaunchedEffect
        val restored = ComposerDrafts.takeRestore(draftKey) ?: return@LaunchedEffect
        // W5-F: a draft written before the fold (or by an older build) may still
        // hold `／`/`＠`; the editor only ever shows half-width triggers.
        val text = HalfWidthText.normalize(restored)
        value = TextFieldValue(text, TextRange(text.length))
    }

    // Debounced `/find/file`: the effect key is the anchor *and* the query, so
    // moving the caret inside the same query does not re-issue a request, and a
    // new keystroke cancels the pending one.
    LaunchedEffect(trigger) {
        val active = trigger
        if (active == null) {
            files = emptyList()
            return@LaunchedEffect
        }
        delay(MentionText.DEBOUNCE_MS)
        val latch = CompletableDeferred<List<String>>()
        searchFiles(active.query) { latch.complete(it) }
        // `searchFiles` answers on the view-model scope; a missing client never
        // answers at all, so do not leak the coroutine forever on that path.
        val paths = withTimeoutOrNull(MENTION_SEARCH_TIMEOUT_MS) { latch.await() }
            ?: return@LaunchedEffect
        if (trigger != active) return@LaunchedEffect
        files = paths
        selected = 0
    }

    // WAVE 2.4b (P0-8): the accepted `@` mention tokens. The editor text
    // stays plain (`@label ` chips via MentionColorTransformation); this list
    // is the structured knowledge the submit payload carries.
    var tokens by remember { mutableStateOf<List<MentionTokens.Token>>(emptyList()) }

    // WAVE 2.4b (P0-7): the one-shot queue-edit loan — the queued prompt
    // loaded in the composer (`queue.ts` edit/cancelEdit). Local to
    // the composer: the VM never sees it, only the confirm (re-admit plus
    // cancel-original) via onQueueConfirm.
    var queueEdit by remember { mutableStateOf<ComposerQueue.Row?>(null) }
    // Queue-row callbacks, latest-bridge style like the other bridges above.
    val queueConfirm by rememberUpdatedState(onQueueConfirm)
    val queueSteerBy by rememberUpdatedState(onQueueSteer)
    val queueRemoveBy by rememberUpdatedState(onQueueRemove)

    fun closePopovers() {
        trigger = null
        files = emptyList()
        slashQuery = null
        selected = 0
    }

    fun setText(next: TextFieldValue) {
        value = next
        ComposerDrafts.set(draftKey, next.text)
        // WAVE 2.4b: a text edit can delete a chip — drop tokens whose `@label`
        // no longer occurs so the payload never carries a phantom mention.
        tokens = MentionTokens.retokenize(next.text, tokens)
    }

    /** Loads a queued row into the editor for editing; Esc cancels. */
    fun editQueued(row: ComposerQueue.Row) {
        queueEdit = row
        queueEditOpen = true
        val text = row.text
        setText(TextFieldValue(text, TextRange(text.length)))
        tokens = emptyList()
        closePopovers()
    }

    fun cancelQueueEdit() {
        queueEdit = null
        queueEditOpen = false
    }

    /**
     * Puts [text] in the editor at the caret and re-derives the open popover.
     * W5-F: the chunk is folded first, so a pasted `／ｉｎｉｔ` or `＠src/main.kt`
     * behaves exactly like text typed in — the `+` menu's triggers included.
     */
    fun insertText(text: String) {
        val chunk = HalfWidthText.normalize(text)
        val caret = value.selection.end.coerceIn(0, value.text.length)
        val inserted = value.text.substring(0, caret) + chunk + value.text.substring(caret)
        val next = TextFieldValue(inserted, TextRange(caret + chunk.length))
        setText(next)
        trigger = MentionText.anchorAt(next.text, next.selection.end)
        slashQuery = ComposerSuggestions.commandQuery(next.text)
        selected = 0
    }

    fun accept(index: Int) {
        val item = items.getOrNull(index) ?: return
        if (slashQuery != null) {
            if (item.builtIn) {
                // Built-ins run locally and clear the draft (`interaction.ts:169-177`).
                setText(TextFieldValue(""))
                closePopovers()
                when (item.label.removePrefix("/")) {
                    ComposerSuggestions.BUILT_IN_MODEL -> modelOpenRequest += 1
                    ComposerSuggestions.BUILT_IN_AGENT -> agentOpenRequest += 1
                }
                return
            }
            val next = TextFieldValue(item.label + " ", TextRange(item.label.length + 1))
            setText(next)
            closePopovers()
            return
        }
        val active = trigger ?: return
        val caret = value.selection.end
        // WAVE 2.4b (P0-8): the popover pick records a structured token (file
        // path / agent name / skill name) alongside the plain-text chip the
        // editor shows — the submit payload carries both. `MentionText.insert`
        // stays the text half of the accept (M1.5 contract).
        val insertion = MentionText.insert(value.text, active.start, caret, item.label) ?: return
        MentionTokens.tokenFor(item)?.let { tokens = tokens + it }
        setText(TextFieldValue(insertion.text, TextRange(insertion.caret)))
        closePopovers()
    }

    /**
     * Submit (`research/02` §4.7): never send a blank prompt, and clear the
     * editor + the open popover once the prompt has been handed over.
     * Attachments are cleared by the screen, which owns them.
     *
     * W5-C: a `/name args` line the server lists is a **command**
     * (`composer/submit.ts:300-306,309-322`), shell mode is a shell command
     * (`:296-298`), everything else is a prompt. The submit is recorded in the
     * session's history and — when the screen wired [onSubmit] — routed through it;
     * otherwise the M1.5 `onSend` path is taken unchanged.
     *
     * W5-F: what leaves the composer is the folded text (`research/wave5_composer.md`
     * §8) — the editor already holds half-width triggers, and the fold here also
     * covers a restored draft, a pasted payload or a history recall.
     *
     * WAVE 2.4b:
     * - P0-8: the payload is upgraded to a [ComposerSubmission.Model] (text +
     *   mention files/agents/skills + delivery) via [onStructuredSubmit] when
     *   the screen wired it; the legacy [onSubmit]/[onSend] fallbacks keep the
     *   exact pre-wave behavior (plain text + attachments).
     * - P0-7: delivery defaults to the `followUpBehavior` setting while the
     *   turn runs (see [ComposerDelivery.delivery]) and steer when idle;
     *   confirming a queue edit re-admits it in place instead of sending a new
     *   prompt. The queue surface is generation-gated: on v1 the toggle/panel
     *   hide and submits steer.
     *
     * The Steer/Queue toggle arms the next submit via `alternateNext = true`;
     * every other caller (Enter, IME send, submit button) leaves it `false`.
     */
    fun submit() {
        val outgoing = HalfWidthText.normalize(value.text).trim()
        if (!ComposerSubmit.canSubmit(outgoing, attachments.size, shellMode)) return
        value = TextFieldValue("")
        trigger = null
        // One-shot: the Steer/Queue toggle arms this submit with the offered
        // delivery (upstream `submit({alternate:true})`), then disarms itself.
        // The clear + popover close above satisfy the M1.5 pin
        // (`ComposerSubmitTest.aSendClearsTheEditor`).
        val alternate = alternateNext
        alternateNext = false
        val supported = ComposerQueue.isQueueSupported(generation)
        // Confirming a queue edit re-admits the prompt instead of sending the
        // composer value as a new one (`model.ts:395-399`): Enter keeps it
        // queued in place, the alternate action steers it.
        val editing = if (supported) queueEdit else null
        val delivery = if (!running || !supported) {
            ComposerSubmission.Delivery.STEER
        } else if (alternate) {
            val offered = ComposerDelivery.alternate(running, followUpBehavior, editing != null, hasContent)
            ComposerSubmission.Delivery.of(offered)
        } else {
            ComposerSubmission.Delivery.of(ComposerDelivery.delivery(running, followUpBehavior))
        }
        // The clear + popover close above satisfy the M1.5 pin; the rest of
        // the handover (file rows, slash menu, selection, tokens) follows.
        files = emptyList()
        slashQuery = null
        selected = 0
        val submittedTokens = tokens
        tokens = emptyList()
        val known = commands.mapTo(HashSet()) { it.name }
        val invocation = if (shellMode) null else ComposerSuggestions.invocation(outgoing, known)
        // A queue-edit confirm is not a history entry (upstream re-admits the
        // replacement; the original entry is cancelled, never re-recorded).
        if (editing == null) history.add(outgoing, shellMode)
        val model = ComposerSubmission.build(
            text = outgoing,
            attachments = attachments,
            tokens = submittedTokens,
            delivery = delivery,
            shell = shellMode,
            command = invocation?.name,
            arguments = invocation?.arguments.orEmpty(),
        )
        if (editing != null) {
            queueEdit = null
            queueConfirm(editing.id, model)
        } else {
            val structured = submitStructured
            if (structured != null) {
                structured(model)
            } else {
                val routed = submitPayload
                if (routed != null) {
                    routed(
                        ComposerSubmit.Payload(
                            text = outgoing,
                            attachments = attachments,
                            shell = shellMode,
                            command = invocation?.name,
                            arguments = invocation?.arguments.orEmpty(),
                        ),
                    )
                } else {
                    onSend(outgoing, attachments)
                }
            }
        }
        // The draft is handed over too: a failed send writes it back through
        // ComposerDrafts.restore, which is the only way it may come back.
        ComposerDrafts.clear(draftKey)
    }

    /**
     * B6 paste. Compose has no paste callback on `BasicTextField`, so this is the
     * shared body behind the「+」menu's 粘贴 row and a hardware `Ctrl+V`:
     * a clipboard **image/file item** is read into an attachment (the same
     * [AttachmentUploads] path SAF picks take), bulk text (≥8000 chars or ≥120
     * lines, [AttachmentPolicy.isLargePaste]) is inserted as one text chunk —
     * i.e. it stays part of the prompt's text part — and ordinary text goes
     * through the same insert.
     */
    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = item?.coerceToText(context)?.toString()
        val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { index ->
            clip?.getItemAt(index)?.uri?.takeIf { it.scheme == "content" }
        }
        when (AttachmentPolicy.pasteKind(hasAttachmentItem = uris.isNotEmpty(), text = text)) {
            AttachmentPolicy.PasteKind.ATTACHMENT -> AttachmentUploads.start(context, uris) { result ->
                if (result.attachments.isNotEmpty()) pasteAttachments(result.attachments)
            }

            AttachmentPolicy.PasteKind.LARGE_TEXT,
            AttachmentPolicy.PasteKind.TEXT,
            -> if (!text.isNullOrEmpty()) insertText(text)

            AttachmentPolicy.PasteKind.NONE -> Unit
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // WAVE 2.4b (P0-7): the queued-follow-ups panel. It slides under the
        // composer card (the panel's own bottom overlap) so the two read as one
        // attached surface (`queue-panel.tsx:18-28`); hidden on v1 and when
        // empty. The VM owns the inbox round-trips — the callbacks below only
        // forward the row id (`wave2.4b-notes.md` §VM).
        if (queueSupported && queuedRows.isNotEmpty()) {
            ComposerQueuePanel(
                rows = queuedRows,
                running = running,
                // While a queue mutation is in flight the rows are inert
                // (`queue-panel.tsx:101,156` `busy()` disables drag + actions).
                busy = queueBusy,
                editingId = queueEdit?.id,
                onSteer = queueSteerBy,
                onRemove = queueRemoveBy,
                onEdit = { id ->
                    queuedRows.find { it.id == id }?.let(::editQueued)
                },
            )
        }
        // M6: `popover-open` / `popover-close` — `.15s ease-out` in, `.1s ease-in`
        // out, `scale(.96) → 1` + opacity (`research/03` §2.1/§3.4), growing out
        // of the editor's top edge.
        OcEnterExitVisibility(
            visible = popoverOpen,
            overlay = OcMotion.Overlay.POPOVER,
        ) {
            MentionPopover(
                items = items,
                selected = selected,
                onPick = ::accept,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.xl)
                .background(c.layer01)
                .border(0.5.dp, c.borderBase, shapes.xl),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentPreviewRow(attachments = attachments, onRemove = onRemoveAttachment)
            }

            // B6: the SAF read's progress + cancel (only while a round runs).
            if (upload.running) {
                UploadProgressRow(state = upload, onCancel = { AttachmentUploads.cancel() })
            }

            BasicTextField(
                value = value,
                onValueChange = { input ->
                    // W5-F: a Chinese IME commits `／` (U+FF0F) for the slash key
                    // and `＠` (U+FF20) for the at key — measured on device, see
                    // `research/wave5_composer.md` §8. Fold them to the half-width
                    // characters *before* anything reads the editor, otherwise
                    // `commandQuery` (`^/(\S*)$`) never matches and the B1 `/`
                    // menu silently stays closed. The fold is 1:1, so the caret
                    // and the IME's composing region are untouched.
                    val updated = HalfWidthText.normalize(input)
                    // B3: a lone `!` switches to shell mode and empties the editor
                    // (`machine.ts:93-97`) — the IME's `！` counts as the trigger.
                    if (!shellMode && HalfWidthText.isShellTrigger(updated.text)) {
                        shellMode = true
                        setText(TextFieldValue(""))
                        closePopovers()
                    } else {
                        setText(updated)
                        trigger = MentionText.anchorAt(updated.text, updated.selection.end)
                        slashQuery = ComposerSuggestions.commandQuery(updated.text)
                        selected = 0
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        if (popoverOpen) {
                            // The popover owns the arrows / Enter / Esc while open.
                            return@onPreviewKeyEvent when (event.key) {
                                Key.DirectionDown -> {
                                    selected = (selected + 1) % items.size
                                    true
                                }

                                Key.DirectionUp -> {
                                    selected = (selected - 1 + items.size) % items.size
                                    true
                                }

                                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                                    accept(selected)
                                    true
                                }

                                Key.Escape -> {
                                    closePopovers()
                                    true
                                }

                                else -> false
                            }
                        }
                        // B3: escape (or backspace on an empty editor) leaves shell
                        // mode — `machine.ts:203-206` checks it *before* the stop
                        // shortcut below.
                        if (shellMode) {
                            val emptyingBackspace = event.key == Key.Backspace && value.text.isEmpty()
                            if (event.key == Key.Escape || emptyingBackspace) {
                                shellMode = false
                                return@onPreviewKeyEvent true
                            }
                        }
                        // B7: Esc / Ctrl+G stop the running turn
                        // (`editor/interaction.ts:225-233`). While a queue edit
                        // is open Esc abandons the edit instead (`queue.ts:172`).
                        if (event.key == Key.Escape && queueEdit != null && !popoverOpen) {
                            cancelQueueEdit()
                            return@onPreviewKeyEvent true
                        }
                        val stopRequested = running &&
                            (event.key == Key.Escape || (event.isCtrlPressed && event.key == Key.G))
                        if (stopRequested) {
                            onStop()
                            return@onPreviewKeyEvent true
                        }
                        // B6: hardware paste (the OS would otherwise consume it).
                        if (event.isCtrlPressed && event.key == Key.V) {
                            pasteFromClipboard()
                            return@onPreviewKeyEvent true
                        }
                        // B7: ↑/↓ walk this session's prompt history
                        // (`interaction.ts:267-298`).
                        if (event.key == Key.DirectionUp || event.key == Key.DirectionDown) {
                            val caret = value.selection.end
                            val recalled = if (event.key == Key.DirectionUp) {
                                history.up(value.text, caret, shellMode)
                            } else {
                                history.down(value.text, caret, shellMode)
                            }
                            if (recalled != null) {
                                val text = HalfWidthText.normalize(recalled)
                                val next = TextFieldValue(text, TextRange(text.length))
                                setText(next)
                                trigger = MentionText.anchorAt(next.text, next.selection.end)
                                slashQuery = ComposerSuggestions.commandQuery(next.text)
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (!isEnter || !ComposerSubmit.enterSubmits(
                                isShiftPressed = event.isShiftPressed,
                                isRepeat = event.nativeKeyEvent.repeatCount > 0,
                            )
                        ) {
                            // Shift+Enter (and every non-Enter key) stays with the
                            // text field, which inserts the newline.
                            return@onPreviewKeyEvent false
                        }
                        submit()
                        true
                    },
                textStyle = TextStyle(
                    color = c.textBase,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    // B3: `font-mono!` in shell mode (`editor.tsx:192`).
                    fontFamily = if (shellMode) FontFamily.Monospace else null,
                ),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = remember(c) { MentionColorTransformation(c) },
                // §4.7: the soft keyboard's Enter is the editor's send action; the
                // IME commits its composing text before performing it.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                text = stringResource(
                                    if (shellMode) {
                                        R.string.oc_composer_placeholder_shell
                                    } else {
                                        R.string.oc_composer_placeholder
                                    },
                                ),
                                color = c.textFaint,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                fontFamily = if (shellMode) FontFamily.Monospace else null,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TOOL_ROW_HEIGHT)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shellMode) {
                    // B3: the attach / agent / model controls are inert in shell
                    // mode upstream (`editor.tsx:253-281`); this badge replaces
                    // them so a phone (no Esc key) can still leave the mode.
                    ShellModeBadge(onExit = { shellMode = false })
                } else {
                    // B2: 「+」 → the four-row add menu (`editor.tsx:613-671`),
                    // whose 附件 row keeps the M1.5 SAF contract.
                    AddMenuButton(
                        open = addMenuOpen,
                        onOpenChange = { addMenuOpen = it },
                        onAttach = onAttachClick,
                        onCommands = { insertText("/") },
                        onContext = { insertText("@") },
                        onShell = {
                            shellMode = true
                            setText(TextFieldValue(""))
                            closePopovers()
                        },
                        onPaste = ::pasteFromClipboard,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // The controls viewport (`[data-slot=composer-controls]`) scrolls
                // horizontally upstream; on a phone that keeps chips from pushing
                // the submit button off screen.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!shellMode) {
                        AgentChip(
                            current = selectedAgent,
                            agents = agents,
                            overrideActive = agentOverrideActive,
                            onSelect = onSelectAgent,
                            onUseDefault = onUseDefaultAgent,
                            openRequest = agentOpenRequest,
                        )
                        Spacer(Modifier.width(4.dp))
                        ModelChip(
                            current = modelLabel,
                            providers = providers,
                            selectedModel = selectedModel,
                            overrideActive = modelOverrideActive,
                            variantLabel = selectedVariant,
                            onSelect = onSelectModel,
                            onSelectVariant = onSelectVariant,
                            onUseServerDefault = onUseServerDefaultModel,
                            openRequest = modelOpenRequest,
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // WAVE 2.4b (P0-7): the Steer/Queue toggle — the delivery
                // opposite to what plain submit does, offered while the turn
                // runs and the composer holds a value
                // (`editor.tsx:822-866` `ComposerEditorAlternateDelivery`).
                // Tapping submits with the offered delivery immediately (same
                // as upstream's alternate keybind → `submit({alternate:true})`,
                // `model.ts:271-273`).
                if (!shellMode && alternateDelivery != null) {
                    DeliveryToggleChip(
                        alternate = alternateDelivery,
                        onToggle = {
                            // Arms the next submit with the offered delivery
                            // (upstream `submit({alternate:true})`).
                            alternateNext = true
                            submit()
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                }

                // §4.6: stop needs `running && blank`; a non-blank input keeps the
                // send icon and queues the follow-up instead of aborting.
                val enabled = stopping || hasContent
                val shape = shapes.md
                val dark = c.bgBase.luminance() < 0.5f
                Box(
                    modifier = Modifier
                        .size(SUBMIT_SIZE)
                        .alpha(if (enabled) 1f else SUBMIT_DISABLED_ALPHA)
                        .shadow(
                            elevation = SUBMIT_SHADOW,
                            shape = shape,
                            ambientColor = SubmitButton.shadow(dark),
                            spotColor = SubmitButton.shadow(dark),
                        )
                        .clip(shape)
                        .background(SubmitButton.surface(dark))
                        // `linear-gradient(180deg, --submit-highlight, transparent)`
                        // stacked over the contrast fill (`submit.css:9-15`).
                        .background(
                            Brush.verticalGradient(
                                listOf(SubmitButton.highlight(dark), Color.Transparent),
                            ),
                        )
                        .border(0.5.dp, SubmitButton.ring(dark), shape)
                        .clickable(enabled = enabled) {
                            if (stopping) onStop() else submit()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(
                            if (stopping) R.drawable.ic_oc_stop else R.drawable.ic_oc_send,
                        ),
                        contentDescription = stringResource(
                            if (stopping) R.string.chat_abort else R.string.chat_send,
                        ),
                        tint = SubmitButton.icon(dark),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * B2: the `[data-action=composer-attach]` trigger plus its menu
 * (`ComposerEditorAddMenu`, `editor.tsx:613-671`): 28 dp ghost button, menu
 * `min-width: 180px`, opened above the button (`placement="top-start"`, 6 px
 * gutter) because the composer sits on the bottom edge.
 *
 * The rows carry upstream's shortcut column (`Mod+U`, `/`, `@`, `!`); 粘贴 is the
 * W5-C B6 addition (see [Composer]'s `pasteFromClipboard`).
 */
@Composable
private fun AddMenuButton(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onAttach: () -> Unit,
    onCommands: () -> Unit,
    onContext: () -> Unit,
    onShell: () -> Unit,
    onPaste: () -> Unit,
) {
    val c = OcTheme.colors
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { ADD_MENU_GAP.roundToPx() }

    Box(modifier = Modifier.onSizeChanged { anchor = it }) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button) { onOpenChange(true) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_plus),
                contentDescription = stringResource(R.string.oc_composer_add),
                tint = c.iconMuted,
                modifier = Modifier.size(16.dp),
            )
        }

        if (open) {
            val provider = remember(anchor, gapPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val above = anchorBounds.top - gapPx - popupContentSize.height
                        val fitsAbove = above >= 0
                        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                        return IntOffset(
                            x = anchorBounds.left.coerceIn(0, maxX),
                            y = if (fitsAbove) above else anchorBounds.bottom + gapPx,
                        )
                    }
                }
            }
            Popup(
                popupPositionProvider = provider,
                onDismissRequest = { onOpenChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                OcEnterOnMount(overlay = OcMotion.Overlay.DROPDOWN) {
                    Column(
                        modifier = Modifier
                            .width(ADD_MENU_MIN_WIDTH)
                            .clip(OcTheme.shapes.md)
                            .background(c.layer01)
                            .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                            .padding(vertical = 4.dp),
                    ) {
                        AddMenuRow(stringResource(R.string.oc_composer_add_attachments), "Mod+U") {
                            onOpenChange(false)
                            onAttach()
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(0.5.dp)
                                .background(c.borderMuted),
                        )
                        AddMenuRow(stringResource(R.string.oc_composer_add_commands), "/") {
                            onOpenChange(false)
                            onCommands()
                        }
                        AddMenuRow(stringResource(R.string.oc_composer_add_context), "@") {
                            onOpenChange(false)
                            onContext()
                        }
                        AddMenuRow(stringResource(R.string.oc_composer_add_shell), "!") {
                            onOpenChange(false)
                            onShell()
                        }
                        AddMenuRow(stringResource(R.string.oc_composer_add_paste), null) {
                            onOpenChange(false)
                            onPaste()
                        }
                    }
                }
            }
        }
    }
}

/** One `[data-slot=menu-v2-item]` row: label + right-aligned shortcut. */
@Composable
private fun AddMenuRow(label: String, shortcut: String?, onClick: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .height(MENU_ROW_HEIGHT)
            .clip(OcTheme.shapes.sm)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        shortcut?.let {
            Text(
                text = it,
                color = c.textFaint,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * B3 shell-mode affordance. Upstream marks the control row `inert` in shell mode
 * and leaves `Escape` / backspace-on-empty as the way out (`machine.ts:203-206`);
 * a phone keyboard has no Escape, so the mode gets one tap-to-leave badge in the
 * same 28 dp slot.
 */
@Composable
private fun ShellModeBadge(onExit: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .height(SHELL_BADGE_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02)
            .clickable(role = Role.Button, onClick = onExit)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_console),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.oc_composer_shell_mode),
            color = c.textMuted,
            fontSize = 12.sp,
        )
    }
}

/**
 * B6: the in-flight SAF read — a `layer02` track with an accent fill, the
 * `done / total` counter and a 取消 action wired to [AttachmentUploads.cancel].
 * Drawn from two Boxes instead of `LinearProgressIndicator` so the bar's height
 * and radius match the 6 dp chip geometry exactly.
 */
@Composable
private fun UploadProgressRow(state: AttachmentUploads.State, onCancel: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(UPLOAD_BAR_HEIGHT)
                .clip(OcTheme.shapes.sm)
                .background(c.layer02),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.fraction)
                    .height(UPLOAD_BAR_HEIGHT)
                    .background(c.accent),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = state.counter ?: stringResource(R.string.oc_attach_uploading),
            color = c.textMuted,
            fontSize = 11.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.oc_attach_cancel),
            color = c.accent,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(OcTheme.shapes.sm)
                .clickable(role = Role.Button, onClick = onCancel)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** `min-h-[60px]` on the editor. */
private val EDITOR_MIN_HEIGHT = 60.dp

/** `max-h-[180px]` — past this the editor scrolls internally (no auto-grow). */
private val EDITOR_MAX_HEIGHT = 180.dp

/** `h-11` tool row. */
private val TOOL_ROW_HEIGHT = 44.dp

/** `size-7` submit button. */
private val SUBMIT_SIZE = 28.dp

/** `submit.css:71-73` — the submit variant's disabled opacity (0.4, not 0.5). */
private const val SUBMIT_DISABLED_ALPHA = 0.4f

/** `box-shadow: var(--v2-elevation-button-contrast)` — 1–2 px, not a raised card. */
private val SUBMIT_SHADOW = 1.5.dp

/** Shell badge height, matching the `+` button it replaces. */
private val SHELL_BADGE_HEIGHT = 28.dp

/** `min-width: 180px` on the add menu (`editor.tsx:651`). */
private val ADD_MENU_MIN_WIDTH = 180.dp

/** The add menu's `gutter={6}` gap. */
private val ADD_MENU_GAP = 6.dp

/** `menu-v2-item` row height — a comfortable touch target. */
private val MENU_ROW_HEIGHT = 36.dp

/** Upload progress bar geometry. */
private val UPLOAD_BAR_HEIGHT = 6.dp

/**
 * `variant="submit"` colours (`_upstream/packages/ui/src/actions/submit.css:6-34`):
 * a **contrast** button, i.e. light = dark-grey fill + white glyph, dark =
 * light-grey fill + dark glyph, with a vertical highlight gradient and a
 * hairline ring from the elevation. The M3-era flat accent blue is gone.
 *
 * Values (`tokens/theme.css:14,24,397` + `theme.css`): fill
 * `--v2-background-bg-icon-button-contrast` = `#2e2e2e` (light) / `#dbdbdb`
 * (dark); glyph `--v2-text-text-contrast` = `#ffffff` (light) /
 * `--v2-icon-icon-inverse` = `#161616` (dark); highlight `--v2-alpha-light-20`
 * (light) / `--v2-alpha-light-60` (dark); the 0.5 px ring is `--v2-grey-800`
 * (light) / `--v2-alpha-light-40` (dark) and the drop shadow is
 * `--v2-alpha-dark-20` (light) / `--v2-alpha-dark-40` (dark).
 *
 * `text-shadow: 0 0.5px 0.5px rgba(0,0,0,.3)` is deliberately not applied: it
 * sits on the upstream button's *text*, and this button renders a vector icon.
 */
internal object SubmitButton {
    private val LightSurface = Color(0xFF2E2E2E)
    private val DarkSurface = Color(0xFFDBDBDB)
    private val LightIcon = Color(0xFFFFFFFF)
    private val DarkIcon = Color(0xFF161616)

    fun surface(dark: Boolean): Color = if (dark) DarkSurface else LightSurface

    fun icon(dark: Boolean): Color = if (dark) DarkIcon else LightIcon

    /** `--submit-highlight`: `--v2-alpha-light-20` light / `--v2-alpha-light-60` dark. */
    fun highlight(dark: Boolean): Color = Color.White.copy(alpha = if (dark) 0.6f else 0.2f)

    /** The 0.5 px elevation ring around the fill. */
    fun ring(dark: Boolean): Color = if (dark) {
        Color.White.copy(alpha = 0.4f)
    } else {
        LightSurface
    }

    fun shadow(dark: Boolean): Color = Color.Black.copy(alpha = if (dark) 0.4f else 0.2f)
}

/** `/find/file` round-trip guard: never leave a debounce coroutine hanging. */
private const val MENTION_SEARCH_TIMEOUT_MS = 4_000L
