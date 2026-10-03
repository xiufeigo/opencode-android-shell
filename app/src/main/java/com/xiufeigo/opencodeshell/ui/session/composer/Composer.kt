package com.xiufeigo.opencodeshell.ui.session.composer

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
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
import com.xiufeigo.opencodeshell.ui.components.OcPopoverDismiss
import com.xiufeigo.opencodeshell.ui.components.ocPopoverDismiss
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogActive
import com.xiufeigo.opencodeshell.ui.overlay.SessionComposerBounds
import com.xiufeigo.opencodeshell.ui.palette.PaletteCommand
import com.xiufeigo.opencodeshell.ui.palette.SessionCommands
import com.xiufeigo.opencodeshell.ui.session.LocalTerminalImeFocus
import com.xiufeigo.opencodeshell.ui.session.SessionComposerBandUnion
import com.xiufeigo.opencodeshell.ui.session.SessionPaneLayout
import com.xiufeigo.opencodeshell.ui.session.btw.LocalSessionBtwHost
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
 *   F-1: the menu additionally merges the session slash aliases
 *   (`SessionCommands.SESSION_SLASH_COMMAND_IDS`: `/undo`, `/compact` …)
 *   resolved against [paletteCommands] — picking one (or typing it) invokes
 *   the palette row's `onSelect` and sends nothing
 *   (`composer/model.ts:339-344` `command.trigger(id, "slash")`).
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
 *   the full handoff back (text + attachments + message ref, U-3 — attachments
 *   re-enter screen state through [onRestoreAttachments]).
 * - **B9 `@` categories** — agent / command / skill / file rows with their
 *   category icon ([ComposerSuggestions.mentionItems]); the inserted text stays a
 *   plain `@label ` mention, exactly like the file case.
 *   F-1 adds the two upstream sources that were missing:
 *   **reference** (`GET /api/reference`, `hidden` filtered — `model.ts:146-165`)
 *   and **MCP resource** (`GET /api/mcp/resource` — `model.ts:166-190`), both
 *   with their structured mention semantics ([MentionSources], [MentionTokens]).
 * - **B-12 popover columns** — every `/` command row draws its keybind
 *   ([SuggestionKeybind], `editor.tsx:813-815`) and a query with no matches keeps
 *   the popover open with the empty state instead of closing it
 *   (`editor.tsx:790-792`). The `@` rows carry no keybind — upstream's context
 *   items have none.
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
 * F-1 adds `references`/`resources` to that list: both are fed from
 * `ChatViewModel.availableReferences` / `availableResources` (the
 * `GET /api/reference` + `GET /api/mcp/resource` catalogs the view model already
 * refreshes), and the `@` menu lists nothing for them until the screen passes
 * them — the two categories are additive, so an unwired screen renders exactly
 * the pre-F-1 menu.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    /**
     * v99 P0 「队列条把时间线压到 16px 且 100% 覆盖」: the queued-prompts bar's
     * ceiling, derived by the pane ([SessionPaneLayout.queueBarCeiling]) instead
     * of hard-coded at the upstream 148 dp.
     *
     * The bar is the **first** child of the strip `Column` below, so on a
     * height-constrained host it is measured *before* the card — it took its full
     * intrinsic height, the pane charged the whole host to the timeline
     * (`SessionPaneLayout.viewportHeightPx`), and the bar's height came out of
     * the message band: 188 px → **16 px** on emulator-5562 at 1366x768, all of
     * it under the bar's opaque rectangle
     * (`research/v97_verify/5562_v98/REPORT_SWEEPD3.md` §2).
     *
     * The pane now pays the timeline's floor and this composer's tool row
     * **first** and hands the bar the remainder. Past the ceiling the bar
     * scrolls rather than grows, so the rows keep their `Steer` / **×**
     * actions; the *timing* of the bar appearing while a turn streams is
     * untouched, and the default keeps every other caller on 148 dp.
     */
    queueBarMaxHeight: Dp = SessionPaneLayout.QUEUE_BAR_MAX_HEIGHT,
    /**
     * v55 P0 「短窗零渲染」: a pane too short to carry the full editor, the tool
     * row, the chrome band and a message row (`SessionPaneLayout.composerCompact`)
     * asks for the compact form — the editor's floor drops from
     * [EDITOR_MIN_HEIGHT] to [EDITOR_MIN_HEIGHT_COMPACT], which is 16 dp back
     * for the messages on the measured 1440x810 @420dpi window
     * (`research/v54_verify/tE/v54_38_short_clean.png`).
     *
     * Only the editor moves: the tool row keeps [TOOL_ROW_HEIGHT], because a
     * composer that lost its ⊞ / 模型 / Send row is the v51 bug this is a
     * follow-up to, not the answer to it.
     */
    compact: Boolean = false,
    /**
     * v57 P0 「短窗 composer 塌成麦克风条」: a pane too short even for the
     * compact composer, the chrome and a message band
     * ([SessionPaneLayout.composerMinimal]) lays the editor and the tool row
     * out as **one** row instead of two — [TOOL_ROW_HEIGHT] rather than
     * `EDITOR_MIN_HEIGHT_COMPACT + TOOL_ROW_HEIGHT`, which is the 40 dp the
     * measured 1440x810 @420dpi window needs for messages
     * (`research/v57_verify/tE/v57_20_short_msgs.png`).
     *
     * The tool row keeps its full 44 dp in every form: the editor is the half
     * that gives way, never ⊞ / 模型 / Send.
     */
    minimal: Boolean = false,
    /** W5-C: the session this composer drafts for (draft + history key). */
    sessionID: String? = null,
    /** W5-C B4: `GET /agent`; the chip renders nothing while this is empty. */
    agents: List<OpenCodeClient.Agent> = emptyList(),
    selectedAgent: String? = null,
    agentOverrideActive: Boolean = false,
    onSelectAgent: (String) -> Unit = {},
    onUseDefaultAgent: () -> Unit = {},
    /**
     * B-9: upstream `local.agent.visible()`
     * (`providers/models/selection.tsx:94` → `composer/model.ts:367-376`), where
     * the agent control is `undefined` — not merely disabled — while it is off.
     * Defaults to `true` so pre-existing call sites keep the chip.
     */
    agentPickerVisible: Boolean = true,
    /** W5-C B1: `GET /command` — the `/` menu's server half. */
    commands: List<OpenCodeClient.CommandInfo> = emptyList(),
    /**
     * F-1: the palette's registered rows — the `/` menu's session-alias half.
     * The menu merges the `SessionCommands.SESSION_SLASH_COMMAND_IDS` aliases
     * resolved against these (the same `PaletteCommand` objects the palette
     * lists, so picking `/undo` or typing it invokes the identical `onSelect`
     * instead of sending a prompt). Empty (the default) keeps the pre-F-1
     * behavior: no alias rows, typed aliases sent as prompts. The session
     * screen passes its palette `commands` list once it wires them.
     */
    paletteCommands: List<PaletteCommand> = emptyList(),
    /** W5-C B9: `GET /skill` — the `@` menu's skill category. */
    skills: List<OpenCodeClient.SkillInfo> = emptyList(),
    /**
     * F-1: `GET /reference` (hidden rows filtered) — the `@` menu's reference
     * category, upstream `composer/model.ts:146-165`. Empty (the default) keeps
     * the pre-F-1 menu. The session screen feeds it from
     * `ChatViewModel.loadMentionReferences`.
     */
    references: List<MentionSources.Reference> = emptyList(),
    /**
     * F-1: `GET /mcp/resource`'s `resources[]` — the `@` menu's MCP-resource
     * category, upstream `composer/model.ts:166-190`. The session screen feeds
     * it from `ChatViewModel.loadMentionResources`.
     */
    resources: List<MentionSources.Resource> = emptyList(),
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
     * U-3: a restored draft carries the full handoff (text + attachments +
     * message ref), but attachments live in screen state — they re-enter
     * through here. Empty by default: unwired screens keep text-only restore.
     */
    onRestoreAttachments: (List<ChatViewModel.Attachment>) -> Unit = {},
    /**
     * U-3: a restored draft's referenced message id re-enters through here
     * (e.g. to scroll the timeline back to it). Empty by default.
     */
    onRestoreMessageRef: (String) -> Unit = {},
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
    /**
     * B-12: the recorded keybind override table (`Settings.keybinds`) — the
     * popover's keybind column shows the **effective** chord, exactly like
     * upstream's `command.keybindParts(item.id)` (`composer/model.ts:256`). The
     * screen passes `settings.keybinds`; the default (empty) leaves every row on
     * its [SHORTCUT_ROWS] default.
     */
    keybindOverrides: Map<String, String> = emptyMap(),
    /**
     * B-13: the model picker's「管理模型」row (`providers/models/select-dialog.tsx:544-556`
     * — upstream's `dialog.model.manage`, a full-width button under the model
     * list). Non-null opens Settings→Models, which owns the visibility toggles
     * and the connection entry; null hides the row, so a screen without the
     * settings route keeps the pre-B-13 picker.
     */
    onManageModels: (() -> Unit)? = null,
    /**
     * B-5: the `model.choose` chord (`composer/commands.tsx:52-60`). A value
     * change opens the model picker — the chip keeps owning its own open state,
     * exactly like the composer's own `/model` alias (see [ModelChip.openRequest]).
     *
     * v75 P2: 只有**新值**才开（[ModelChip.openRequest] 的消费语义）。宿主那条
     * 计数器是单向闩、永不回落，v74 的"每次挂载重读"让用户点过一次之后任何重建
     * 都会再弹一次（点 composer 文本区就是一次重建）；v74 真正要保证的那件事现在
     * 由下面那个 movable 槽位保证 —— 形态翻转是移动，chip 和它开着的弹层不会被
     * 卸载。
     */
    externalModelOpen: Int = 0,
    /**
     * v74 P0: the model chip's own tap, reported up so the host can raise the
     * same counter [externalModelOpen] is. The chip's open state is local
     * `remember`, and the composer is rebuilt whenever its form/arrangement
     * flips — which the picker's own focus change (the keyboard going away)
     * causes — so a tap that only wrote the local flag lost the picker, while
     * `/model` survived on the host-held counter. A host without one keeps the
     * pre-v74 behaviour (the default no-op).
     */
    onModelPickerRequest: () -> Unit = {},
    /**
     * B-5: the `input.focus` chord (`use-session-commands.tsx:385-391`) — a value
     * change re-requests focus on the editor.
     */
    externalFocus: Int = 0,
    /**
     * B-19: the `file.attach` command (`composer/model.ts:411-418`) — a value
     * change opens the attachment picker. Upstream's `controller.attach()` is
     * the same entry the 「+」 menu's 附件 row uses, so this calls [onAttachClick]
     * instead of a second picker path.
     */
    externalAttachRequest: Int = 0,
    /**
     * B-19: the `prompt.mode.shell` command (`composer/model.ts:419-426`) — a
     * value change enters shell mode. Upstream's `dispatch({type:"mode.shell"})`
     * (`suggestions/machine.ts:75`) sets the mode and closes the popover; the
     * editor text stays (only the `!` trigger clears it, `machine.ts:93-97`).
     */
    externalShellMode: Int = 0,
    /**
     * B-19: the `prompt.mode.normal` command (`composer/model.ts:427-434`) — a
     * value change leaves shell mode (`machine.ts:76`).
     */
    externalNormalMode: Int = 0,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    val context = LocalContext.current
    val running = runStatus == ChatStore.RunStatus.RUNNING
    // `/btw` side questions: the host is published by `ui/App.kt` around the
    // session screen (the same pattern `SessionBtwEntry` reads), so the composer
    // routes its own `/btw` without a new parameter — and a screen without a
    // host (previews) simply keeps the pre-routing behavior.
    val btwHost = LocalSessionBtwHost.current

    val draftKey = remember(sessionID) { ComposerDrafts.key(sessionID) }
    val history = remember(draftKey) { ComposerHistory.of(draftKey) }

    var value by rememberSaveable(
        draftKey,
        stateSaver = TextFieldValue.Saver,
        // v16: the draft key as an input alone cannot keep two sessions apart —
        // see [ComposerDrafts.saveableSlot]. Naming the saveable slot after the
        // draft key is what makes a freshly created session start from *its own*
        // draft instead of the outgoing session's restored editor value.
        key = ComposerDrafts.saveableSlot(draftKey, ComposerDrafts.SLOT_EDITOR_VALUE),
    ) {
        // v66 BUG-2: a restored draft arrives as text only, and
        // `TextFieldValue(text)` seeds its selection with `TextRange.Zero` — a
        // **non-empty line with the caret at offset 0**, the exact state the
        // device run measured on 5556 / 5558: every later keystroke was applied
        // in front of the line, backspace and select-all+delete were no-ops, and
        // a caret at 0 makes [MentionText.anchorAt] answer null, which is why the
        // `@` panel was dead at the same time
        // (`research/v65_verify/5558/17_at_clean.png`, `18_at_wait.png` — `v@`
        // with the caret in front of the `v`). The draft's caret is the end of
        // the line, the same place [insertText] and the failed-send restore put
        // it, so the initializer says so.
        val restored = HalfWidthText.normalize(ComposerDrafts.text(draftKey))
        mutableStateOf(TextFieldValue(restored, TextRange(restored.length)))
    }
    // Shell mode (`composer/suggestions/machine.ts:4,75,93-97`); per draft key so
    // switching sessions does not carry the mode over — the saveable slot is
    // scoped the same way as the editor value (v16).
    var shellMode by rememberSaveable(
        draftKey,
        key = ComposerDrafts.saveableSlot(draftKey, ComposerDrafts.SLOT_SHELL_MODE),
    ) { mutableStateOf(false) }
    // Popover rows and selection. The open shape itself is never mirrored —
    // it is re-read off the editor's own text and caret on every composition
    // ([live], v64/v66), so there is no state here that can drift from what
    // the field holds. The two surfaces share one popover slot because the
    // editor text can only be in one shape at a time (`machine.ts:86-119`).
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var addMenuOpen by remember { mutableStateOf(false) }
    // `/model` and `/agent` open the chips' menus: a bumped counter each.
    var modelOpenRequest by remember { mutableStateOf(0) }
    var agentOpenRequest by remember { mutableStateOf(0) }

    // v64: the popover is a function of the editor's **own** text and caret
    // ([ComposerSuggestions.triggerAt]), not of the two `remember` mirrors
    // above. Those are plain state while the editor's value is
    // `rememberSaveable`, so a composer that is rebuilt while its draft survives
    // returns with the text and neither trigger — which is exactly what
    // `research/v64_verify/5556/02_24_slash_only.png` (a bare `/` drawing the
    // *context* empty state) and `5558/07b_mention_panel.png` /
    // `08b_context_panel.png` (`@`, and + → Context, drawing nothing) show.
    // v63 had already taken the `/` trigger off the text for visibility; the row
    // source, the empty-state copy and the `@` trigger now read the same place.
    val live = ComposerSuggestions.triggerAt(value.text, value.selection.end)
    // v69: the two halves of the `@` menu, because only one of them answers
    // synchronously. The catalogs are already here, so their rows are a function
    // of the keystroke in front of the user; the `/find/file` rows land 200 ms
    // plus a round trip later ([searchDelayMs]) and must flow in whenever they
    // arrive. Holding the joined list (v68) froze *that* wave too, so the panel
    // kept the previous query's paths until the next keystroke released the
    // hold — `@ex` showed the bare `@` list, `@exp` was the first state that
    // filtered (`research/v68_verify/5558/19_at_ex.png` → `24_at_exp.png`).
    val freshMentionRows = remember(live.mention, files, agents, commands, skills, references, resources) {
        live.mention?.let { anchor ->
            ComposerSuggestions.mentionRowSets(
                query = anchor.query,
                agents = agents,
                commands = commands,
                skills = skills,
                // `MentionText.candidates` still owns the file rows' trim / dedupe
                // / cap, so the two surfaces cannot drift apart.
                files = MentionText.candidates(files).map { it.label },
                // F-1: the two sources the audit found missing
                // (`composer/model.ts:146-190`).
                references = references,
                resources = resources,
            )
        } ?: EMPTY_MENTION_ROWS
    }
    // v68: the open panel holds its painted **fixed** row generation while one
    // query stays open ([PopoverPick.steadyRows]) — the catalogs stream in
    // sequentially, and a late wave inserting at the top or middle must not
    // shove the rows a committed tap is aimed at
    // (`research/v68_verify/5560/21-35`). The file rows are outside the hold on
    // purpose: they are appended under the fixed block, so a new answer moves
    // nothing a committed tap was aimed at, and holding them is exactly the
    // "one keystroke late" the device reported. Scoped to the draft key with the
    // other session satellites, so a switch never paints the old session's rows.
    var heldMentionRows by remember(draftKey) { mutableStateOf(freshMentionRows.fixed) }
    var heldMentionAnchor by remember(draftKey) { mutableStateOf(live.mention) }
    val heldFixed =
        PopoverPick.steadyRows(heldMentionRows, heldMentionAnchor, freshMentionRows.fixed, live.mention)
    if (heldFixed !== heldMentionRows || live.mention != heldMentionAnchor) {
        heldMentionRows = heldFixed
        heldMentionAnchor = live.mention
    }
    val mentionItems = remember(heldFixed, freshMentionRows.files) {
        ComposerSuggestions.joinMentionRows(heldFixed, freshMentionRows.files)
    }
    // F-1: the session-slash alias rows, resolved against the palette registry
    // the screen passes in. Titles come from resources here because
    // ComposerSuggestions is pure Kotlin; the filtering itself stays inside
    // commandItems (pure, JVM-tested). Uncomputed when the menu is closed is
    // unnecessary — nine string lookups per composition are trivial, and this
    // keeps locale switches correct (no stale remember).
    val slashAliasRows: List<ComposerSuggestions.SessionSlashRow> =
        SessionCommands.SESSION_SLASH_COMMAND_IDS.mapNotNull { (trigger, commandId) ->
            val registered = paletteCommands.firstOrNull { it.id == commandId } ?: return@mapNotNull null
            if (registered.hidden) return@mapNotNull null
            ComposerSuggestions.SessionSlashRow(
                trigger = trigger,
                commandId = commandId,
                title = stringResource(registered.titleResId),
                description = registered.descriptionResId?.let { stringResource(it) },
            )
        }
    val slashItems = remember(live.slashQuery, commands, slashAliasRows) {
        live.slashQuery?.let { ComposerSuggestions.commandItems(it, commands, slashAliasRows) }
            ?: emptyList()
    }
    val items = ComposerSuggestions.rowsFor(live, slashItems, mentionItems)
    val popoverOpen = ComposerMention.popoverVisible(
        items.isNotEmpty(),
        live.slashQuery,
        live.mention,
    )
    // B-12: upstream renders the popover on the **trigger**, not on a non-empty
    // list — with zero matches it draws the empty state instead of disappearing
    // (`editor.tsx:790-792`). So the card is visible while either menu is open,
    // and only its keyboard handling still needs rows.
    //
    // v63 F-1 / v64: the `/` trigger is read from the editor's own text
    // ([live], via [ComposerSuggestions.triggerAt]) rather than from a
    // mirrored state alone. The placeholder promises 「/ for commands」, and
    // on device the first `/` opened nothing — the value reached the panel
    // through an IME commit whose mirrored state no longer described it, so the
    // command menu was unreachable and ⋮ → Command palette was the only way in
    // (`research/v63_verify/5556/25_palette3.png` → `26_palette4.png`). Reading
    // the trigger off the text makes every shape of `/` — ASCII, a Chinese
    // IME's `／`, a restored draft — open the command menu, and it is what the
    // whole card now reads ([popoverOpen] already carries it).
    val popoverVisible = popoverOpen
    // v67 BUG-3: the session the popover was opened in. A pick that lands after
    // the session moved is dropped instead of being applied to the new one.
    var popoverSession by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(popoverVisible, draftKey) {
        popoverSession = if (popoverVisible) draftKey else null
    }
    // B-12: the row chords, with the recorded overrides applied — the same table
    // `ui/App.kt` matches events against.
    val keybindRows = remember(keybindOverrides) { SuggestionKeybind.effectiveRows(keybindOverrides) }
    // ESC with the popover open hides the IME first: while the keyboard is up
    // ESC only dismisses it (the event is consumed, the popover stays); with
    // the keyboard already down ESC closes the popover (upstream v2 behaviour).
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
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
    // v67 B4: the search answer's staleness guard reads the anchor live, the
    // same single source the rows render from — never a mirrored copy.
    val liveMention by rememberUpdatedState(live.mention)
    val pasteAttachments by rememberUpdatedState(onPasteAttachments)
    val submitPayload by rememberUpdatedState(onSubmit)
    val submitStructured by rememberUpdatedState(onStructuredSubmit)
    // WAVE 2.4b (P0-7): the Steer/Queue hint offer. Same visibility rule as
    // upstream editor.tsx ComposerEditorAlternateDelivery: while the turn runs
    // and the composer holds a value, null hides the chip. Generation-gated.
    //
    // v131: the queue-edit loan is ONE state and the chip flag is **derived**
    // from it. They used to be two — the row (`queueEdit`) plus a `queueEditOpen`
    // latch that only `editQueued` / `cancelQueueEdit` moved — and the confirm
    // path closed the row while leaving the latch on, so
    // `ComposerDelivery.alternate`'s first line (`if (editingQueued) return
    // STEER`) answered `Steer` for the rest of the composer's life and the
    // `Queue` entry never came back; Esc could not save it either, because the
    // IME swallows the key while the keyboard is up (v130, emulator-5554).
    // One state cannot diverge, so the loan is declared here, above its first
    // read, and `queueEditOpen` is a `val` over it. Upstream has the same single
    // source (`state.editing` in `queue.ts`, read by `editing()`/`alternate()`).
    var queueEdit by remember { mutableStateOf<ComposerQueue.Row?>(null) }
    val queueEditOpen = queueEdit != null
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
    //
    // v74: …unless the keyboard is already the terminal's. The docked terminal
    // asks for the focus when the pane opens (its own `TerminalFocusRequest`), and
    // this effect used to be what took it back: the composer is rebuilt whenever
    // the pane's arrangement flips — which opening the terminal does — so a mount
    // that no user gesture asked for re-targeted the IME at a composer that could
    // be squeezed to nothing, while the terminal's prompt and block cursor sat
    // there receiving nothing (`research/v74_verify/5556/08_term_type_notap.png`:
    // the IME's Enter key still wore the composer's Send action with the pane up).
    // A mount is not the user turning to the editor, so it yields to whoever holds
    // the view focus ([LocalTerminalImeFocus]); every path that *is* the user
    // turning to the editor — a tap, the `input.focus` chord, a popover handing
    // the caret back — keeps requesting unconditionally below.
    //
    // v81 P1 「重开会话首击丢字」: the entry handoff is a **window**, not one ask.
    // This effect was a single ask on the first settled frame, and on a device it
    // is spent before the entry is over: reopening an existing session moves the
    // pane through a state a new session never reaches — `observeSession` clears
    // the store and the snapshot/UI rebuild follows on a later frame — so the ask
    // can land on a field whose input session the platform is about to re-create
    // (`TextInputServiceAndroid` stops and restarts the connection around the
    // rebuild). The field then draws a caret (Compose focus) with no live
    // `InputConnection`: the injected batch commits its first character and drops
    // the rest, the caret goes away, and a *second* tap — a fresh ask against the
    // settled tree — is what makes typing work again
    // (`research/v80_verify/5554/26_480_typed.png` → `27_480_recheck.png` →
    // `28_480_refocus.png`; the same "only the first character landed" shape at
    // 420 dpi is `research/v79_verify/5554/35_taps.png` → `36_10chars2.png`).
    //
    // The repo has already measured both halves of the answer on the Home rename
    // field, which is the only other editor a mount focuses:
    //
    // - `requestFocus()` **alone** leaves a field with no live `InputConnection`
    //   ("the first keystrokes went nowhere and Enter / the IME's ✓ had nowhere to
    //   land", v43) — so the ask raises the keyboard with it, which is the
    //   `keyboard.show()` half below;
    // - a **single** ask lands too early on a tree that is still settling ("the
    //   first open left `focused=false` and the first characters went to the
    //   search field, while every later open worked", v45) — so the window is
    //   retried a bounded number of times ([ComposerEntryFocus]).
    //
    // What the retries must *not* do is ask again once the field holds the caret:
    // a repeat `requestFocus()` on an already-focused node is a no-op, but a
    // repeat `keyboard.show()` is a fresh `restartInput()` and an IME
    // re-initialised mid-word drops the character it is holding (v66, measured as
    // `E2ECHK5` + `558-` + `ent` → `E2ECHK5558-nt`). So [editorFocused] — the
    // field's own focus state, reported by `onFocusChanged` on the field — ends
    // the window the moment the caret lands, and `show()` rides the first ask
    // only.
    //
    // The terminal yield stays per-ask (v74): every attempt here is a mount-time
    // ask, and a mount is not the user turning to the editor.
    val focusRequester = remember { FocusRequester() }
    val terminalImeFocus = LocalTerminalImeFocus.current
    var editorFocused by remember { mutableStateOf(false) }
    LaunchedEffect(draftKey) {
        var attempt = 0
        while (true) {
            // One frame so the field exists in the layout tree before it takes
            // focus — and one frame per retry, so each ask is made against a tree
            // the previous ask has been applied to.
            withFrameNanos { }
            // The handoff is over: the field holds the caret, or the keyboard
            // belongs to another surface (the docked terminal).
            if (editorFocused) return@LaunchedEffect
            if (terminalImeFocus?.holdsKeyboard == true) return@LaunchedEffect
            if (runCatching { focusRequester.requestFocus() }.isSuccess &&
                ComposerEntryFocus.raisesKeyboard(attempt)
            ) {
                runCatching { keyboardController?.show() }
            }
            attempt += 1
            if (!ComposerEntryFocus.retries(attempt)) return@LaunchedEffect
            delay(ComposerEntryFocus.RETRY_DELAY_MS)
        }
    }
    // B-5: the `input.focus` chord. Keyed on the request counter, not on the
    // composition, so pressing it twice re-focuses twice (after a dialog took
    // focus away, say).
    LaunchedEffect(externalFocus) {
        if (externalFocus <= 0) return@LaunchedEffect
        runCatching { focusRequester.requestFocus() }
    }
    // B-5: the `model.choose` chord rides the same internal counter `/model`
    // uses, so one code path opens the picker.
    LaunchedEffect(externalModelOpen) {
        if (externalModelOpen > 0) modelOpenRequest += 1
    }

    // v80 P0 「720x1280 @420dpi 首击被吞」: the form flip no longer rebuilds the
    // editor, so there is nothing to hand back here.
    //
    // v79 answered the flip with a `LaunchedEffect(minimal)` that re-requested the
    // caret one frame later, and on device it lost the race: the flip's own
    // disposal had already asked the platform to hide the keyboard, the re-focus
    // landed on a field whose input connection was on its way down, the body grew
    // back, the form flipped *back* — and the effect, gated on the one-row form,
    // had nothing to say about that half of the flip. The caret is now kept by
    // construction instead: the editor has one call site and one parent across
    // both forms (see the card's own `Row` below), so a flip is a modifier value
    // change and never a dispose — the focus, the input session and the IME
    // connection survive it, and a later hand-back would only be a second party
    // asking for a caret that never left. The effect is gone rather than gated
    // further: while it existed it could also pull the caret out of a surface the
    // user had turned to (any IME that shortened the body flipped the form, and
    // the gate only knew about the terminal).

    // v69 C2: a dialog layer that took the focus hands it back when it closes.
    // The `/btw` card is a [com.xiufeigo.opencodeshell.ui.overlay.DialogHost]
    // layer, so it owns the window focus for as long as it is up and the tap
    // that closes it is the scrim's — nothing is left to focus the editor. The
    // caret stayed in the card and the composer could not be reached again
    // without a tab round-trip, which is the only thing that re-ran the
    // `draftKey` effect above (`research/v68_verify/5558/30-39`).
    //
    // This is the same hand-back the popovers already do on unmount
    // ([ModelChip]'s `onRestoreFocus` and its
    // `com.xiufeigo.opencodeshell.ui.components.OcPopoverPolicy`), lifted to the
    // one signal every modal shares: the layer stack going empty. The keyboard
    // is deliberately not raised — a tab round-trip does not raise it either,
    // and the field takes the caret without it.
    val dialogActive = LocalDialogActive.current
    var layerWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(dialogActive) {
        if (dialogActive) {
            layerWasOpen = true
            return@LaunchedEffect
        }
        if (!layerWasOpen) return@LaunchedEffect
        layerWasOpen = false
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    // B8: a failed send put a draft back — adopt it.
    //
    // P1-C: every mount additionally adopts the stashed switch handoff (top-tab
    // leave/return, drawer session switch, home round-trip, session re-entry):
    // the text half arrives through the initializer above, the attachments and
    // the message ref re-enter screen state through the bridges. The merge is
    // idempotent (`AttachmentPolicy.merge` dedupes), and the ref is consumed
    // (`takeMessageRef`) so a tab return does not re-scroll the timeline.
    val drafts by ComposerDrafts.state.collectAsState()
    val restoredPending = drafts[draftKey]?.pendingRestore == true
    val restoreAttachmentsBridge by rememberUpdatedState(onRestoreAttachments)
    val restoreMessageRefBridge by rememberUpdatedState(onRestoreMessageRef)

    // v81 BUG-01 「错误卡驻留时输入被锁死」: the handback is the *other* moment the
    // editor can lose the caret — and the one nothing re-asked for.
    //
    // A failed send hands the payload back through `ComposerDrafts.restoreHandoff`
    // and this effect writes it into a value the IME owns, which is exactly the
    // platform's `restartInput()` window (v63/v66 measured the IME
    // re-initialising at offset 0 there). On the device the session then reads
    // the draft back in the field with **no caret** and every injected key
    // landing nowhere (`research/v81_verify/5558/t2_s05_send_offline_err.png`,
    // `t2_s08_send_after_recover.png`: the keyboard up, the draft intact, no
    // caret, and the error card on screen), and only a session round-trip —
    // which re-runs the entry handoff above — gave typing back. The caret window
    // is the same bounded, `show()`-free ask the entry handoff uses, so the
    // handback gets the same treatment.
    //
    // Bounded and conditional by construction, so it can never become the focus
    // thief v80 removed: it fires only on a handback ([handbackTick]), it ends on
    // the first frame the field already holds the caret (the healthy case — a
    // failed send that never lost focus does nothing at all), it yields to the
    // terminal (v74), and it stops after [ComposerEntryFocus.MAX_ATTEMPTS].
    // Deliberately no `keyboard.show()`: the field's IME session is already up
    // here, and a repeat `show()` is a fresh `restartInput()` that drops the
    // character the IME is holding (v66).
    var handbackTick by remember(draftKey) { mutableStateOf(0) }
    LaunchedEffect(handbackTick) {
        if (handbackTick == 0) return@LaunchedEffect
        var attempt = 0
        while (true) {
            withFrameNanos { }
            if (editorFocused) return@LaunchedEffect
            if (terminalImeFocus?.holdsKeyboard == true) return@LaunchedEffect
            runCatching { focusRequester.requestFocus() }
            attempt += 1
            if (!ComposerEntryFocus.retries(attempt)) return@LaunchedEffect
            delay(ComposerEntryFocus.RETRY_DELAY_MS)
        }
    }

    LaunchedEffect(draftKey, restoredPending) {
        if (restoredPending) {
            // v84 P2: the handback is answered by a coroutine, so it can land
            // long after the composer is back on screen — and it used to be
            // written over whatever the field held by then, gluing a stale
            // payload onto the user's next message. `takeRestore` is handed the
            // editor's own text and answers null when the user got there first
            // (see [ComposerDrafts.adoptRestore]).
            val restored = ComposerDrafts.takeRestore(draftKey, value.text)
            if (restored != null) {
                // W5-F: a draft written before the fold (or by an older build)
                // may still hold `／`/`＠`; the editor only ever shows half-width
                // triggers.
                val text = HalfWidthText.normalize(restored)
                value = ComposerImeCommit.adopt(
                    TextFieldValue(text, TextRange(text.length)),
                    previous = value,
                )
                // The rewrite above is this composer's own write into the IME's
                // value: re-ask the caret (see [handbackTick]).
                handbackTick += 1
            }
        }
        // U-3: the failure handed back the full handoff, not just text — and a
        // switch stashed it. Attachments live in screen state, so they re-enter
        // through the callback; the message ref follows for whoever observes
        // the timeline.
        val handoffAttachments = ComposerDrafts.attachments(draftKey)
        if (handoffAttachments.isNotEmpty()) {
            restoreAttachmentsBridge(handoffAttachments.map { it.toAttachment() })
        }
        ComposerDrafts.takeMessageRef(draftKey)?.let { restoreMessageRefBridge(it.messageID) }
    }

    // P1-C: flush the live editor text when the composer leaves the composition
    // (a top-tab switch destroys it via `key(mobileTab)`). Keystrokes already
    // save through `setText`, but a switch racing the last commit must not drop
    // a character. Blank flushes are skipped: a send clears the draft, and an
    // empty entry would only resurrect map junk.
    //
    // v74 P0: the text is read off the editor's own state **at dispose time**,
    // not off a `rememberUpdatedState` mirror. That mirror is only refreshed by
    // a composition, so a composer rebuilt in the same frame as the write it
    // followed — which is what a send does, because clearing the editor moves
    // the body and flips the composer's form — flushed the text the user had
    // *just sent* back into the draft, and the rebuilt editor then re-seeded
    // itself from that draft: 「发送成功后文本留在输入框」. The delegate read here
    // is the same state object the send wrote `""` into, so the flush sees the
    // clear and skips (blank writes are refused).
    val boundKey by rememberUpdatedState(draftKey)
    DisposableEffect(draftKey) {
        onDispose {
            // v58: only while this editor is still bound to the key the effect
            // was created for — a switch disposes it after the incoming session
            // is bound, and an unconditional write filed the outgoing editor's
            // text under a session that never held it.
            ComposerDrafts.flush(draftKey, boundKey, value.text)
        }
    }

    // Debounced `/find/file`: the effect key is the anchor *and* the query, so
    // moving the caret inside the same query does not re-issue a request, and a
    // new keystroke cancels the pending one. v64: the key is the anchor read off
    // the editor text ([live]), so a rebuilt composer still asks for the paths
    // of an `@` its draft restored.
    //
    // v67 B2/B4: the key also carries the draft key, so a session switch
    // re-asks in the *new* session's directory scope instead of serving the
    // outgoing session's paths, and the staleness guard reads [live] (through
    // [liveMention]) rather than a mirrored copy — the mirror answered for a
    // caret the editor no longer owned, so a response that belonged to the
    // field was dropped while rows the trigger no longer covers stayed up
    // (the full list on an emptied editor, `research/v67_verify/5558/s48`).
    LaunchedEffect(live.mention, draftKey) {
        val active = live.mention
        if (active == null) {
            files = emptyList()
            return@LaunchedEffect
        }
        delay(ComposerMention.searchDelayMs(active.query))
        val latch = CompletableDeferred<List<String>>()
        searchFiles(active.query) { latch.complete(it) }
        // `searchFiles` answers on the view-model scope; a missing client never
        // answers at all, so do not leak the coroutine forever on that path.
        val paths = withTimeoutOrNull(MENTION_SEARCH_TIMEOUT_MS) { latch.await() }
            ?: return@LaunchedEffect
        if (liveMention != active) return@LaunchedEffect
        files = paths
        selected = 0
    }

    // WAVE 2.4b (P0-8): the accepted `@` mention tokens. The editor text
    // stays plain (`@label ` chips via MentionColorTransformation); this list
    // is the structured knowledge the submit payload carries.
    var tokens by remember { mutableStateOf<List<MentionTokens.Token>>(emptyList()) }

    // WAVE 2.4b (P0-7): the one-shot queue-edit loan — the queued prompt
    // loaded in the composer (`queue.ts` edit/cancelEdit) — is declared with the
    // delivery hint above, because the chip flag is derived from it (v131). It
    // stays composer-local: the VM never sees it, only the confirm (re-admit
    // plus cancel-original) via onQueueConfirm.
    // Queue-row callbacks, latest-bridge style like the other bridges above.
    val queueConfirm by rememberUpdatedState(onQueueConfirm)
    val queueSteerBy by rememberUpdatedState(onQueueSteer)
    val queueRemoveBy by rememberUpdatedState(onQueueRemove)

    fun closePopovers() {
        files = emptyList()
        selected = 0
    }

    fun setText(next: TextFieldValue) {
        // v63 BUG-1: every write into the editor goes through the caret guard,
        // so a value can never reach the IME with an offset it does not own.
        // v81: the guard is given the value the app was holding, so an IME
        // re-attach batch (an empty composing region opened at 0, see [adopt])
        // cannot park the caret in front of the line.
        val adopted = ComposerImeCommit.adopt(next, previous = value)
        // v68: a soft-keyboard backspace never reaches `onPreviewKeyEvent` — it
        // arrives here with the one char already removed by the IME. When that
        // char fell inside a mention chip the deletion widens to the whole chip
        // ([MentionTokens.snapImeDeletion]), so the soft path deletes atomically
        // exactly like the hardware branch above, and the intermediate illegal
        // `@gener` states — each re-arming the popover — never exist.
        val snapped = MentionTokens.snapImeDeletion(
            oldText = value.text,
            newText = adopted.text,
            newCaret = adopted.selection.end,
            tokens = tokens,
        )?.let { deletion ->
            val text = value.text.removeRange(deletion.start, deletion.endExclusive)
            ComposerImeCommit.adopt(TextFieldValue(text, TextRange(deletion.start)), previous = value)
        } ?: adopted
        value = snapped
        ComposerDrafts.set(draftKey, snapped.text)
        // WAVE 2.4b: a text edit can delete a chip — drop tokens whose `@label`
        // no longer occurs so the payload never carries a phantom mention.
        tokens = MentionTokens.retokenize(snapped.text, tokens)
    }

    /** Loads a queued row into the editor for editing; Esc cancels. */
    fun editQueued(row: ComposerQueue.Row) {
        // Upstream opens at most one loan and supersedes any previous one
        // (`queue.ts:152` `if (state.editing) cancelEdit()`), so assigning the
        // new row *is* the close of the old one.
        queueEdit = row
        val text = row.text
        setText(TextFieldValue(text, TextRange(text.length)))
        tokens = emptyList()
        closePopovers()
    }

    fun cancelQueueEdit() {
        queueEdit = ComposerQueue.endEditLoan(queueEdit, ComposerQueue.EditLoanEvent.CANCEL)
    }

    // v131: upstream converges the loan against the queue instead of trusting a
    // run of discrete exits (`queue.ts:93-97`): any read that finds the edited
    // row gone from `queued()` drops the loan. This is the exit the native port
    // never had, and it is the one that keeps the surface honest when the *VM*
    // ends the edit rather than the composer — a row consumed by the running
    // turn, removed, steered, or drained when the turn settles all shrink
    // `queuedRows` and take the loan (hence the `Steer` chip, hence the missing
    // `Queue` entry) with them. `queueBusy` is upstream's `mutation.isPending`:
    // it delays the close while a mutation is in flight, never blocks it.
    LaunchedEffect(queuedRows, queueBusy) {
        val current = queueEdit ?: return@LaunchedEffect
        val kept = ComposerQueue.convergeEditLoan(current, queuedRows.map { it.id }, queueBusy)
        if (kept == null) {
            queueEdit = ComposerQueue.endEditLoan(current, ComposerQueue.EditLoanEvent.CANCEL)
        }
    }

    // v67 B2: the editor's satellites — accepted mention tokens, an open queue
    // edit — are plain `remember` state, so on a session switch the composer
    // itself survives (the tab strip rebinds it to the incoming session's
    // draft key) they stayed bound to the outgoing session: session B's card
    // and its `/` were still standing in session A
    // (`research/v67_verify/5558/s31`). The popover's open shape needs no
    // converging — it is re-derived from the restored draft on every
    // composition ([live]) — but the satellites are reset here so nothing
    // session-scoped outlives the switch.
    LaunchedEffect(draftKey) {
        closePopovers()
        tokens = emptyList()
        cancelQueueEdit()
        // OBS-1 (v78, emulator-5558): a session with no draft of its own opens
        // an empty composer. The editor seeds from its own draft and its
        // saveable slot is scoped to the draft key (v16), so text on screen
        // with nothing in the store is another session's — drop it instead of
        // adopting it. A pending restore, a returning session's own draft and
        // a cold-start slot restore all read back through the store and are
        // left alone ([ComposerDrafts.dropForeignSeed]).
        if (ComposerDrafts.dropForeignSeed(draftKey, value.text)) {
            value = TextFieldValue("")
        }
    }

    // B-19: the three `composer-editor` commands (`composer/model.ts:411-434`).
    // They are placed after the local helpers so each one reuses the same code
    // path its UI entry point does — the 「+」 menu's 附件 row for `file.attach`,
    // and the mode flips the `!` trigger / Esc already perform. Latest-bridge
    // for the picker lambda, like every other callback above.
    val attachClick by rememberUpdatedState(onAttachClick)
    LaunchedEffect(externalAttachRequest) {
        if (externalAttachRequest <= 0) return@LaunchedEffect
        attachClick()
    }
    LaunchedEffect(externalShellMode) {
        if (externalShellMode <= 0) return@LaunchedEffect
        shellMode = true
        closePopovers()
    }
    LaunchedEffect(externalNormalMode) {
        if (externalNormalMode <= 0) return@LaunchedEffect
        shellMode = false
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
        selected = 0
    }

    fun accept(picked: ComposerSuggestions.Item?) {
        // v67 BUG-3/5: the row is resolved by identity against the list the
        // popover draws now, and only while this is still the session the popover
        // opened in. The rows used to carry their index into the handler, which
        // then looked the item up in whatever list was current when the tap
        // arrived — a tap on `explore` inserted `build`, the next list's first
        // row, and a `/model` tapped in one session was applied to the session
        // that became active while the tap was in flight
        // (`research/v67_verify/5558/s28-s47`, `s31`).
        val item = PopoverPick.resolve(picked, items, popoverSession, draftKey) ?: return
        // v66 BUG-3: the row list comes from [live] (the editor's own text and
        // caret) but the branch below used to read the two `remember` mirrors.
        // Those are plain state, so a composer rebuilt with its draft — a top-tab
        // switch disposes it behind `key(mobileTab)`, the phone layout the
        // testers were on — came back with the popover drawn from [live] and
        // both mirrors null. Every pick then fell through to
        // `val active = trigger ?: return` and did **nothing at all**: no insert,
        // no command, no close (`research/v65_verify/5556/25_cmd_model.png`,
        // `26_cmd_undo.png`). One source of truth for the shape and the rows.
        val openSlash = live.slashQuery
        if (openSlash != null) {
            if (item.builtIn) {
                // v67 BUG-3: the open request is raised **before** the editor is
                // rewritten. `/model` closed the panel and never opened the
                // picker, because the picker's `LaunchedEffect(openRequest)` was
                // keyed on a counter the surrounding text writes used to race.
                when (item.label.removePrefix("/")) {
                    ComposerSuggestions.BUILT_IN_MODEL -> modelOpenRequest += 1
                    ComposerSuggestions.BUILT_IN_AGENT -> agentOpenRequest += 1
                    // `/btw` picked with no question: upstream `run()` opens its
                    // question dialog (`btw.tsx:64-66`) — here the btw panel is
                    // that dialog, so nothing is sent and no prompt is queued.
                    ComposerSuggestions.BTW_TRIGGER -> btwHost?.openPanel()
                }
                // Built-ins run locally and clear the draft (`interaction.ts:169-177`).
                setText(TextFieldValue(""))
                closePopovers()
                return
            }
            val aliasId = item.commandId
            if (aliasId != null) {
                // F-1: a session alias runs the palette row (upstream
                // `command.trigger(id, "slash")`, `model.ts:339-344`) — the
                // draft clears like the built-ins and nothing is sent.
                setText(TextFieldValue(""))
                closePopovers()
                paletteCommands.firstOrNull { it.id == aliasId }?.onSelect?.invoke()
                return
            }
            val next = TextFieldValue(item.label + " ", TextRange(item.label.length + 1))
            setText(next)
            closePopovers()
            return
        }
        val active = live.mention ?: return
        // v66 BUG-3: the caret only decides the span when it really sits inside
        // the anchor. An anchor read off the text's tail (a composer whose caret
        // the app lost — see [MentionText.anchorAtEndOfLine]) is followed to its
        // own end, so the pick replaces the `@query` it filtered on instead of
        // refusing to insert at all.
        val caret = value.selection.end
            .takeIf { it >= active.start && it <= value.text.length }
            ?: (active.start + active.query.length)
        // WAVE 2.4b (P0-8): the popover pick records a structured token (file
        // path / agent name / skill name) alongside the plain-text chip the
        // editor shows — the submit payload carries both. `MentionText.insert`
        // stays the text half of the accept (M1.5 contract).
        //
        // v30 BUG-2: the anchor may come from a full-width `＠` the IME is still
        // composing (`ComposerImeCommit` keeps the IME's characters in the
        // editor until the composition ends). The fold is 1:1, so folding here
        // is index-exact and the accept inserts its chip instead of silently
        // no-op'ing on `MentionText.insert`'s ASCII-trigger check.
        val insertion = MentionText.insert(
            HalfWidthText.normalize(value.text),
            active.start,
            caret,
            item.label,
        ) ?: return
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
     * v75: what leaves the composer is the editor's text **verbatim** — the fold
     * is 1:1, and `trim()` is applied to [trimmed] only, i.e. to the blank gate
     * and to the `/name` / alias / `/btw` parsers, which read a *shape*. The
     * committed text itself is never edited: a batch the IME finished with
     * trailing whitespace (a space, a tab, a `\n` from Shift+Enter) is the
     * user's text, and dropping it here was the one place the shell silently
     * rewrote it — `submit()` used to hand `…normalize(value.text).trim()` to
     * the payload, while upstream hands `input.text` itself and reaches for
     * `trim()` only as the gate (`request.ts:128`; `submit.ts:200-203`).
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
     * `/btw` is routed **before** any payload is built: it is a side question
     * (`ComposerSuggestions.btwQuestion` → `ui/session/btw`), so it never becomes
     * a prompt, a server command or a queued follow-up.
     *
     * The gate in front of all of this is **content only** ([ComposerSubmit.canSubmit]
     * / [ComposerSubmit.stopping]): the send button is `stopping || hasContent`
     * and this function's first line re-checks the same rule, so the shape of the
     * line never decides whether a submit is possible. `ComposerSuggestions.commandQuery`
     * (`^/(\S*)$`) governs the *completion menu* alone — typing the space in
     * `/btw what is 2+2` closes that menu, it does not block the submit — and the
     * slash-argument commands (`/btw`, upstream's `slash: {arguments: true}` set,
     * `ComposerSuggestions.argumentCommand`) are resolved from the submitted text.
     *
     * The Steer/Queue toggle arms the next submit via `alternateNext = true`;
     * every other caller (Enter, IME send, submit button) leaves it `false`.
     */
    fun submit() {
        val outgoing = HalfWidthText.normalize(value.text)
        val trimmed = outgoing.trim()
        if (!ComposerSubmit.canSubmit(trimmed, attachments.size, shellMode)) return
        // v64: the clear is a write-back like any other, so it goes through the
        // caret guard rather than around it (see [setText]); v81 passes the value
        // it is clearing, so the guard sees this write for what it is (an empty
        // line — an empty text has no head to park at).
        value = ComposerImeCommit.adopt(TextFieldValue(""), previous = value)
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
        // the handover (file rows, selection, tokens) follows.
        files = emptyList()
        selected = 0
        val submittedTokens = tokens
        tokens = emptyList()
        val known = commands.mapTo(HashSet()) { it.name }
        val invocation = if (shellMode) null else ComposerSuggestions.invocation(trimmed, known)
        // F-1: a typed session alias (`/undo`, `/compact` …) runs the palette
        // row instead of being sent as a prompt or a server command. Server
        // customs win (an [invocation] hit), shell mode never routes, and a
        // queue-edit confirm keeps its re-admit semantics — upstream slash
        // aliases only ever fire through the suggestion menu
        // (`model.ts:339-344`), never through `findCommand`.
        val aliasTarget = if (shellMode || invocation != null || editing != null) {
            null
        } else {
            ComposerSuggestions.aliasCommandId(trimmed, SessionCommands.SESSION_SLASH_COMMAND_IDS)
                ?.let { id -> paletteCommands.firstOrNull { it.id == id } }
        }
        // `/btw` (upstream `session.aside`, `slash: {name:"btw", arguments:true}`)
        // is a **side question**, not a prompt: it goes to the btw panel, whose
        // ask is a transient `POST …/generate` — the answer never enters the
        // timeline and no turn is queued (the v12 finding: `/btw …` landed in the
        // queue panel as an ordinary follow-up). Same precedence as the menu row
        // order: a server custom named `btw` ([invocation]) wins, shell mode never
        // routes, and a queue-edit confirm keeps its re-admit semantics.
        val btw = if (shellMode || invocation != null || editing != null) {
            null
        } else {
            ComposerSuggestions.btwQuestion(trimmed)
        }
        // A queue-edit confirm is not a history entry (upstream re-admits the
        // replacement; the original entry is cancelled, never re-recorded).
        if (editing == null) history.add(outgoing, shellMode)
        if (btw != null) {
            // The bare form opens the panel (its field is upstream's question
            // dialog); with a question the ask runs right away.
            if (btw.isEmpty()) btwHost?.openPanel() else btwHost?.ask(btw)
            ComposerDrafts.clear(draftKey)
            return
        }
        if (aliasTarget != null) {
            // F-1: palette-routed alias — recorded above like a server command,
            // executed through the palette row, never sent anywhere.
            aliasTarget.onSelect()
            ComposerDrafts.clear(draftKey)
            return
        }
        val model = ComposerSubmission.build(
            text = outgoing,
            attachments = attachments,
            tokens = submittedTokens,
            delivery = delivery,
            shell = shellMode,
            command = invocation?.name,
            arguments = invocation?.arguments.orEmpty(),
        )
        // The draft is handed over **before** the send is dispatched: a failure
        // that is answered synchronously — `ChatViewModel.submit` with no
        // transport at all (v76) — writes the payload back through
        // `ComposerDrafts.restoreHandoff` *during* the call below, and a clear
        // that ran after it would wipe the very text that failure just saved.
        // A late (coroutine) failure is unaffected: it restores after this line
        // either way, and a send that goes through leaves the slot empty, which
        // is what the hand-over means.
        ComposerDrafts.clear(draftKey)
        if (editing != null) {
            // v131: a confirm is an **exit** — the replacement is re-admitted
            // and the original cancelled (`queue.ts:65-74`), so the loan ends
            // here. This is the exit the native port never had: the row was
            // cleared on this line before v131 while the chip's own latch stayed
            // on, which is what pinned the hint to `Steer` for good.
            queueEdit = ComposerQueue.endEditLoan(editing, ComposerQueue.EditLoanEvent.CONFIRM)
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

    /**
     * Drop onto the composer (`composer/dropzone.tsx`; `attachments.ts:204-214`
     * `handleDrop`). Dropped URIs take the same [AttachmentUploads] read as SAF
     * picks and land through [AttachmentPolicy.merge] — append + dedupe + cap 6,
     * never a replace — then [onPasteAttachments] hands the screen the full
     * post-merge list (its own merge is idempotent on it).
     */
    fun dropUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        AttachmentUploads.start(context, uris) { result ->
            for (name in result.tooLarge) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_attach_too_large, name),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            val merged = AttachmentPolicy.merge(attachments, result.attachments)
            if (merged.dropped > 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.chat_attach_limit, AttachmentPolicy.MAX_ATTACHMENTS),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            if (merged.attachments != attachments) pasteAttachments(merged.attachments)
        }
    }

    // v73 — this Column *is* the composer's strip on screen, and its box is what
    // a modal layer's scrim has to read as backdrop (`SessionComposerBounds` →
    // `DialogScrimState.backdropBounds`): on a short window the centered `/btw`
    // card is laid out over exactly this box, and a tap the user aims here has to
    // close the card instead of dying on the card's own padding
    // (`research/v73_repro2`, 5556). Cleared on unmount so a layer over another
    // screen cannot read a stale box.
    DisposableEffect(Unit) { onDispose { SessionComposerBounds.bounds = null } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { SessionComposerBounds.bounds = it.boundsInRoot() },
    ) {
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
                // v131: upstream closes the loan *first* when the edited row
                // itself is promoted or cancelled (`queue.ts:133`, `queue.ts:139`),
                // so the hint recovers without waiting for the VM round-trip.
                // Aimed at another row it is not an exit for this loan.
                onSteer = { id ->
                    queueEdit = ComposerQueue.endEditLoan(queueEdit, ComposerQueue.EditLoanEvent.STEER, id)
                    queueSteerBy(id)
                },
                onRemove = { id ->
                    queueEdit = ComposerQueue.endEditLoan(queueEdit, ComposerQueue.EditLoanEvent.REMOVE, id)
                    queueRemoveBy(id)
                },
                onEdit = { id ->
                    queuedRows.find { it.id == id }?.let(::editQueued)
                },
                // v99 P0: the bar's ceiling comes from the pane's budget, so a
                // short pane (keyboard up + streamed turn + queued prompts)
                // cannot have the bar measured out of the timeline's band. It
                // scrolls instead of growing, and the rows keep their actions.
                maxHeight = queueBarMaxHeight,
            )
        }
        // M6: `popover-open` / `popover-close` — `.15s ease-out` in, `.1s ease-in`
        // out, `scale(.96) → 1` + opacity (`research/03` §2.1/§3.4), growing out
        // of the editor's top edge.
        //
        // v67 BUG-1/2: the popover must draw **above** the stack, upstream's
        // `absolute inset-x-0 -top-2 -translate-y-full` (`editor.tsx:765-772`),
        // not inside it. As a stacked child it counted toward the measured
        // composer height, and `SessionPaneLayout.composerSharesViewport`
        // (SessionScreen's cover/shared decision) reads exactly that
        // measurement: with the keyboard up on `emulator-5556` the pane sat at
        // the threshold, so opening the popover flipped the pane into the
        // shared form, whose `heightIn` cap starved the card's weighted editor
        // to ~0 dp — and the capped measurement fell back under the threshold,
        // flipping the pane again. Every tap or keystroke re-parented the whole
        // composer, tearing the row click and the list's drag down mid-gesture
        // (`research/v67_verify/5556/30-42`: dead clicks, a list that would not
        // scroll, a composer that "collapsed" until the next keystroke).
        // Reporting zero stack height and placing the popover above keeps the
        // measured composer a function of the card, which is what the pane
        // decision has always meant.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val popover = measurable.measure(constraints)
                    layout(popover.width, ComposerPopoverOverlay.STACK_HEIGHT) {
                        popover.place(0, ComposerPopoverOverlay.contentTop(popover.height))
                    }
                },
        ) {
            OcEnterExitVisibility(
                visible = popoverVisible,
                overlay = OcMotion.Overlay.POPOVER,
            ) {
                MentionPopover(
                    items = items,
                    selected = selected,
                    onPick = ::accept,
                    // B-12: the two empty-state strings the View shell never had.
                    emptyLabelRes = SuggestionKeybind.emptyLabelRes(slash = live.slashQuery != null),
                    keybindRows = keybindRows,
                )
            }
        }

        // v63 BUG-2 「附件预览整屏覆盖 composer」: the pending-attachment strip is
        // no longer a child of the composer card. It rides in a band of its own,
        // directly above the card and clipped to [AttachmentPreviewLayout.MAX_HEIGHT],
        // so the card below — the editor and the tool row, i.e. the only part
        // carrying the send button — is measured against its own budget and stays
        // visible and tappable. As a card child the strip shared the card's
        // measurement, and on `emulator-5558` a single picked file spent the whole
        // pane: the card filled the content area, painted no editor or controls,
        // and refused taps until the chip was removed
        // (`research/v63_verify/5558/27-29`, reproduced live).
        if (attachments.isNotEmpty() || upload.running) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shapes.xl)
                    .background(c.layer01)
                    .border(0.5.dp, c.borderBase, shapes.xl)
                    .heightIn(max = AttachmentPreviewLayout.MAX_HEIGHT),
            ) {
                if (attachments.isNotEmpty()) {
                    AttachmentPreviewRow(
                        attachments = attachments,
                        onRemove = onRemoveAttachment,
                    )
                }
                // B6: the SAF read's progress + cancel (only while a round runs).
                if (upload.running) {
                    UploadProgressRow(state = upload, onCancel = { AttachmentUploads.cancel() })
                }
            }
        }

        AttachmentDropZone(
            onUris = ::dropUris,
            modifier = Modifier.fillMaxWidth(),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shapes.xl)
                .background(c.layer01)
                .border(0.5.dp, c.borderBase, shapes.xl),
        ) {
            // v57 P0 「短窗 composer 塌成麦克风条」: on a pane that cannot carry
            // the editor and the tool row as two rows, they are laid out as one
            // (`SessionPaneLayout.composerMinimal`) — 44 dp instead of 96, and
            // ⊞ / 模型 / Send can never be squeezed off the end. Same field
            // composable both ways; only its modifier differs.
            val editorField: @Composable (Modifier) -> Unit = { editorModifier ->
            BasicTextField(
                value = value,
                onValueChange = { input ->
                    // W5-F: a Chinese IME commits `／` (U+FF0F) for the slash key
                    // and `＠` (U+FF20) for the at key — measured on device, see
                    // `research/wave5_composer.md` §8. The trigger logic must see
                    // the half-width characters, or `commandQuery` (`^/(\S*)$`)
                    // never matches and the B1 `/` menu silently stays closed.
                    //
                    // v64 BUG-1/2: the editor must NOT fold that back into the
                    // value. Compose answers any app-side text rewrite of a value
                    // the IME owns with `InputMethodManager.restartInput()`
                    // ([ComposerImeCommit]), and a restart re-initialises the IME
                    // at offset 0 — measured on both emulators: the caret jumped
                    // in front of the line, the keystroke right after the `/` was
                    // swallowed, and everything after it edited at 0, so backspace
                    // and select-all+delete did nothing (`@de` survived five
                    // `KEYCODE_DEL`) and `/`+`t`+`r` read `rm/`
                    // (`research/v64_verify/5556/02_24…05_27`, `5558/01b*`, `21`).
                    //
                    // The IME's own characters are therefore stored as they
                    // arrive, and the fold is a *read* view
                    // ([ComposerImeCommit.readable]) — 1:1, so the caret, the
                    // anchor index and `commandQuery` are all unaffected.
                    val readable = ComposerImeCommit.readable(input.text)
                    // B3: a lone `!` switches to shell mode and empties the editor
                    // (`machine.ts:93-97`) — the IME's `！` counts as the trigger.
                    if (!shellMode && HalfWidthText.isShellTrigger(readable)) {
                        shellMode = true
                        setText(TextFieldValue(""))
                        closePopovers()
                    } else {
                        setText(input)
                        selected = 0
                    }
                },
                modifier = editorModifier
                    .focusRequester(focusRequester)
                    // v81: the field's own focus state, the signal the entry
                    // handoff's retry window ends on (see the `LaunchedEffect`
                    // above). It observes the field, not a parent: the caret
                    // landing *here* is what the window is waiting for.
                    .onFocusChanged { editorFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        // v68: a backspace on a mention chip deletes the whole
                        // chip, upstream's atomic-span behaviour. This runs
                        // ahead of the popover keys below: the panel has no
                        // Backspace binding, and deleting the chip kills its
                        // anchor, so the panel follows the text and closes
                        // instead of reopening mid-delete.
                        if (event.key == Key.Backspace && value.selection.collapsed) {
                            MentionTokens.backspace(value.text, value.selection.end, tokens)?.let { deletion ->
                                val next = value.text.removeRange(deletion.start, deletion.endExclusive)
                                setText(TextFieldValue(next, TextRange(deletion.start)))
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (popoverVisible) {
                            // The popover owns the arrows / Enter / Esc while open.
                            // B-12: with no rows (the empty state) the list keys fall
                            // through to their normal meaning; ESC still belongs to
                            // the popover, which is what dismisses the empty card.
                            return@onPreviewKeyEvent when (event.key) {
                                Key.DirectionDown -> if (items.isEmpty()) {
                                    false
                                } else {
                                    selected = (selected + 1) % items.size
                                    true
                                }

                                Key.DirectionUp -> if (items.isEmpty()) {
                                    false
                                } else {
                                    selected = (selected - 1 + items.size) % items.size
                                    true
                                }

                                Key.Enter, Key.NumPadEnter, Key.Tab -> if (items.isEmpty()) {
                                    false
                                } else {
                                    accept(items.getOrNull(selected))
                                    true
                                }

                                Key.Escape -> {
                                    val decision = ComposerPopoverEsc.decide(
                                        popoverOpen = true,
                                        imeVisible = imeVisible,
                                    )
                                    if (decision.hideKeyboard) keyboardController?.hide()
                                    if (decision.closePopover) closePopovers()
                                    decision.consumed
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
                                return@onPreviewKeyEvent true
                            }
                            return@onPreviewKeyEvent false
                        }
                        if (event.key != Key.Enter && event.key != Key.NumPadEnter) {
                            return@onPreviewKeyEvent false
                        }
                        // v57 P0: the whole Enter key is owned here. Falling
                        // through is not "let the text field newline": with
                        // `imeAction = Send` Compose performs the IME action for
                        // any Enter key-down that reaches the field, so a
                        // `Shift+Enter` sent instead of newlining (upstream
                        // `editor.tsx:211` newlines it). Newline is therefore
                        // inserted here and every branch is consumed.
                        when (ComposerSubmit.enterAction(
                            isShiftPressed = event.isShiftPressed,
                            isRepeat = event.nativeKeyEvent.repeatCount > 0,
                        )) {
                            ComposerSubmit.EnterAction.SUBMIT -> submit()
                            ComposerSubmit.EnterAction.NEWLINE -> insertText("\n")
                            // A held Enter is swallowed: falling through would
                            // re-run the IME action on every repeat.
                            ComposerSubmit.EnterAction.IGNORE -> Unit
                        }
                        true
                    },
                // v57 P0: the minimal form is a single 44 dp row, so its editor
                // is one line — an unbounded editor wrapped onto a second line
                // and painted over the tool controls, outside the composer card
                // (`research/v57_verify/5554/42_short_nokb.png`).
                maxLines = if (minimal) 1 else Int.MAX_VALUE,
                textStyle = TextStyle(
                    color = c.textBase,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    // B3: `font-mono!` in shell mode (`editor.tsx:192`).
                    fontFamily = if (shellMode) FontFamily.Monospace else null,
                ),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = remember(c, tokens) {
                    // F-1: the accepted tokens' kinds win over the text
                    // heuristic, so `@docs` (a reference) colours as one.
                    MentionColorTransformation(c, MentionTokens.colors(tokens))
                },
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
                                // Same one-line ceiling as the field (see
                                // `maxLines`): the placeholder is a free-floating
                                // child of the decoration box and would otherwise
                                // wrap out of the minimal row.
                                maxLines = if (minimal) 1 else Int.MAX_VALUE,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            }

            // v57 P0: the controls viewport's measured width, so the model chip
            // can shrink to the room it actually has. The chip's own ceiling is a
            // *window* share, which is no bound at all inside the horizontal
            // scroller (its children are measured with an unbounded width) — a
            // short pane ran the label under the submit button instead of
            // ellipsizing (`research/v57_verify/5554/42_short_nokb.png`).
            var controlsWidth by remember { mutableStateOf(0) }

            val toolControls: @Composable () -> Unit = {
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
                        // v63 BUG-1: the menu's Popup is focusable; hand the
                        // editor its window focus and its IME back on close.
                        onRestoreFocus = { runCatching { focusRequester.requestFocus() } },
                    )
                }

                Spacer(Modifier.width(4.dp))

                // The controls viewport (`[data-slot=composer-controls]`) scrolls
                // horizontally upstream; on a phone that keeps chips from pushing
                // the submit button off screen.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .onSizeChanged { controlsWidth = it.width }
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
                            visible = agentPickerVisible,
                            // v97（v57 移交项）：agent 弹层同款焦点归还 —— 弹层根
                            // 节点的初始聚焦把编辑器的焦点与 IME 抢走，真关过一次
                            // 的卸载把两者还回来（`ModelChip` 的 onRestoreFocus
                            // 同款；外点关闭不还，那一击被补发给底下的控件）。
                            onRestoreFocus = { runCatching { focusRequester.requestFocus() } },
                        )
                        if (agentPickerVisible) Spacer(Modifier.width(4.dp))
                        ModelChip(
                            current = modelLabel,
                            providers = providers,
                            selectedModel = selectedModel,
                            overrideActive = modelOverrideActive,
                            variantLabel = selectedVariant,
                            onSelect = onSelectModel,
                            onSelectVariant = onSelectVariant,
                            onUseServerDefault = onUseServerDefaultModel,
                            onManageModels = onManageModels,
                            openRequest = modelOpenRequest,
                            // v74 P0: the chip's own tap raises the host's
                            // request counter, so the picker survives a composer
                            // rebuilt under it (see [onModelPickerRequest]).
                            onRequestOpen = onModelPickerRequest,
                            // v57: the picker autofocuses its search box and takes
                            // the editor's focus and IME with it; hand both back when
                            // it closes, or the composer stays parked in the
                            // no-keyboard layout and every later anchor is measured
                            // a keyboard-height off (`ModelChip`).
                            onRestoreFocus = { runCatching { focusRequester.requestFocus() } },
                            maxWidth = if (controlsWidth > 0) {
                                with(LocalDensity.current) { controlsWidth.toDp() }
                            } else {
                                Dp.Unspecified
                            },
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
                // Hoisted: `semantics {}` is not a composable scope. The label
                // rides the clickable Box, not the `Icon` inside it — only the
                // node carrying ACTION_CLICK is announced as a button.
                val submitLabel = stringResource(
                    if (stopping) R.string.chat_abort else R.string.chat_send,
                )
                val longPressLabel = stringResource(R.string.oc_composer_send_newline)
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
                        // v57 P0: with `imeAction = Send` a soft keyboard has no
                        // newline key at all, so the IME half of upstream's
                        // `Shift+Enter` (editor.tsx:211) needs a reachable path:
                        // a long press on the send button types a line break. The
                        // stop button is a different affordance and inert here.
                        .combinedClickable(
                            enabled = enabled,
                            onClick = { if (stopping) onStop() else submit() },
                            onLongClick = { if (!stopping) insertText("\n") },
                            onLongClickLabel = longPressLabel,
                        )
                        .semantics { contentDescription = submitLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(
                            if (stopping) R.drawable.ic_oc_stop else R.drawable.ic_oc_send,
                        ),
                        contentDescription = null,
                        tint = SubmitButton.icon(dark),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // v75 P1: the flip used to dispose the whole tool row — the model chip
            // with it, and the picker it had open. That is the short window's
            // 「chip 点不开」: the picker's own focus change hides the keyboard, the
            // body grows, the form flips, the chip (and its popup) is rebuilt, the
            // teardown handed the focus and the keyboard back, the body shrank, the
            // form flipped back — an open/destroy loop the picker never survived,
            // and the reason its 150 ms enter fade was always caught half-done
            // (`v74_21/27`, the see-through panel).
            //
            // v81 P0 「720x1280 @420dpi 打开会话必崩 4/4」: the answer is no longer a
            // `movableContentOf` slot but **one node tree**. The slot mounted the
            // same controls under two different parents — the one-row form hung them
            // inside the editor row, the stacked form in the 44 dp tool row below it
            // — so a flip was a **move**: the runtime removed the subtree from the
            // old parent and inserted it at the new anchor in the same frame. On
            // device that threw `IllegalStateException: Cannot insert LayoutNode@…
            // because it already has a parent` from `UiApplier.insertBottomUp` under
            // `Operation$CopyNodesToNewAnchorLocation.execute`
            // (`research/v80_verify/5554/app_fatals.txt`: 4/4 on the 420 window,
            // opening a session), with the neighbouring `CoreTextField` /
            // `SimpleLayout` nodes dragged into the double mount (the crashed
            // frame's "Other tree"). The runtime hazard is the one v76 diagnosed for
            // the pane's own slot: a movable content that is invalidated and moved
            // before it can recompose keeps its invalidation pointed at the *old*
            // slot-table location (`issuetracker.google.com/issues/229001114`, fixed
            // after 1.7.6; the subcomposition variant is 436858107) — and this
            // composer sits inside a `BoxWithConstraints` (a `SubcomposeLayout`),
            // one of the documented amplifiers.
            //
            // So the controls are not moved at all any more: the card is one
            // `Layout` ([ComposerArrangement]) whose second child is the tool row in
            // **both** forms — the one-row form places that row beside the editor,
            // the stacked form below it — and a flip only changes measurement,
            // placement and two modifier values. The subtree keeps its nodes and its
            // `remember`ed state (the chip's `expanded`, the popup window) by
            // construction, with no movable machinery in this path at all. (The
            // pane's own `composerSlot` in `SessionScreen.kt` is a different path —
            // the mobile tab switch disposes the whole pane — and keeps its slot;
            // see `MovableContentMoveHarnessTest`.)

            // v80 P0 「720x1280 @420dpi composer 首击被吞」: the editor keeps
            // **one** call site and **one** parent across the form flip — v81 keeps
            // that by construction, since the field is the arrangement's first child
            // in both forms and the form reaches it only as the numbers the
            // arrangement measures it with.
            //
            // `minimal` is a function of the *body*
            // ([SessionPaneLayout.composerOneRow]), and the body shrinks by the
            // keyboard's own inset — so on every window whose form is not already
            // minimal at rest, the tap that focuses the editor is what raises the
            // keyboard and what flips the form. Until v80 the two forms were two
            // `if (minimal)` branches with the field under a different parent in
            // each (a `Row` in the one-row form, the card's `Column` in the
            // stacked one) — two call sites, so the flip disposed the focused
            // field and composed a fresh one, and a disposed field does not take
            // the caret back: the platform stops the input connection, the
            // keyboard hides, the body grows, the form flips *back*, and the tap
            // that started it all is left with nothing. Measured on
            // emulator-5554 at 720x1280 @420dpi (`research/v79_verify/5554/32-36`):
            // the first four taps all leave `mInputShown=false` with no caret and
            // injected text landing nowhere, a fifth tap finally sticks, and one
            // tap in three shows a stray character when a re-create catches an
            // in-flight IME commit.
            //
            // Not a `movableContentOf` slot for the field either: a move is a
            // remove plus an insert of the same node — `Applier.move` cannot cross
            // parents, so `CopyNodesToNewAnchorLocation` inserts at the new anchor
            // after the old one is removed — and the removal detaches the node,
            // whose `FocusTargetNode.onDetach` clears focus
            // (`clearFocus(force = true)`). A moved text field is an unfocused one.
            // Not a frozen form either: that would hand v74's 「细条」 back. The flip
            // still follows the keyboard; what it no longer does is rebuild the
            // editor.
            //
            // The two forms stay the same arithmetic, and the field's chain keeps
            // the same *structure* — only values follow the form (`heightIn`'s
            // floor, the padding's insets, the placeholder's `maxLines`). That is
            // not a detail: `NodeChain`'s diff updates a modifier node when the new
            // element is of the same class and replaces it otherwise
            // (`actionForModifiers`), so a chain that changed shape would hand the
            // band tap's `pointerInput` node a fresh instance — and a reset
            // pointer-input node cancels the gesture that is mid-flight, one more
            // way for a first tap to be swallowed.
            //
            // The width half is no longer a `weight` on the field (v81): the
            // arrangement hands the editor the one-row band's editor share as its
            // own bound, and `fillMaxWidth` makes the field *take* it, so the text
            // band is the whole share in both forms. The share itself is still the
            // tool row's own arithmetic — the `weight`ed ghost spacer in
            // [ComposerArrangement] carries `COMPOSER_ONE_ROW_EDITOR_WEIGHT`
            // against the controls' `1f`, the split the row used to compute with the
            // field as its first weighted child. Measured at 480 dpi
            // (`research/v79_verify/5554/13_480_kb.png`): the editor takes its whole
            // share around a 55 dp line of text — the ⊞ starts exactly at the
            // share's edge and Send ends flush with the card's right edge. The
            // one-row field used to take its content's intrinsic width instead
            // (`minWidth` left at 0 through the weight), so a short draft left the ⊞
            // hugging the text and the band short of the share; the fill is v80's one
            // visible change there, and it only moves the ⊞ when the draft is
            // shorter than the share. In the stacked form the arrangement measures
            // the editor against `card − TOOL_ROW_HEIGHT`, exactly as the column
            // child it used to be.
            //
            // v86 P0: that ghost's weight is now a function of the band's own width
            // (`SessionPaneLayout.composerOneRowEditorWeight`), so the band the
            // keyboard leaves on 720x1280 gives the editor a floor instead of 39 /
            // 25 dp of text (see that function); on every band where the v81 ratio
            // already pays the floor the weight is the same `Float`, and the share
            // is still measured from the row, never assumed here.
            ComposerArrangement(
                minimal = minimal,
                editor = {
                    editorField(
                        Modifier
                            // The width half of the editor's share: the arrangement
                            // hands the field the band's editor share as its own
                            // bound and `fillMaxWidth` makes the field *take* it —
                            // `TextFieldDelegate.layoutText` fixes the width to the
                            // bound when `minWidth == maxWidth` ("if minWidth ==
                            // maxWidth the width is fixed", foundation 1.7.6), while
                            // with `minWidth` left at 0 the field would be only as
                            // wide as its content, and the text band would shrink to
                            // the length of the draft. The height half is the
                            // `heightIn` below; in the one-row form the band's own
                            // 44 dp bounds it, so the floor drops to 0 there — the
                            // field is one line (`maxLines = if (minimal) 1 …`) and
                            // the arrangement centres it.
                            .fillMaxWidth()
                            .heightIn(
                                min = if (minimal) {
                                    0.dp
                                } else {
                                    if (compact) EDITOR_MIN_HEIGHT_COMPACT else EDITOR_MIN_HEIGHT
                                },
                                max = EDITOR_MAX_HEIGHT,
                            )
                            // v75 P2: the text band owns its own taps, so the chip's
                            // minimum-touch-target band can never win a tap that was
                            // aimed at the text area (see [editorBandTap]).
                            .editorBandTap(focusRequester)
                            .padding(
                                start = if (minimal) 12.dp else 16.dp,
                                top = if (minimal) 0.dp else 16.dp,
                                end = if (minimal) 4.dp else 16.dp,
                                bottom = if (minimal) 0.dp else 8.dp,
                            ),
                    )
                },
                controls = { toolControls() },
                modifier = Modifier.fillMaxWidth(),
            )

        }
        }
    }
}

/**
 * v81 P0: the composer card's two forms as **one node tree**.
 *
 * The card's content is this `Layout` plus exactly two children — the editor and
 * the tool row — in *both* forms (the card's own `Column` above it is only its
 * clipped/bordered surface, and this `Layout` is its single child in both forms).
 * What the form decides is only
 *
 *  * the tool row's own modifier (its 44 dp band, and the stacked form's 8 dp of
 *    horizontal padding),
 *  * how the two children are measured: side by side in the one-row band (the
 *    editor gets the band's editor share, the tool row the whole card) or
 *    stacked, with the editor against `card − TOOL_ROW_HEIGHT` and the tool row
 *    below it, and
 *  * where they are put (the band centres the editor; the stacked form stacks).
 *
 * Neither child is ever re-parented, so a flip cannot move a node — which is
 * what the `movableContentOf` slot this replaced did, and what crashed on device
 * (`IllegalStateException: Cannot insert LayoutNode@… because it already has a
 * parent`, `Operation$CopyNodesToNewAnchorLocation`; see the call site in
 * `Composer`). The editor keeps its focus, its input session and its IME
 * connection across a flip, and the tool row keeps its nodes and `remember`ed
 * state (the model chip's `expanded`, the picker's popup window) for the same
 * reason — nothing is disposed or moved, only measured again.
 *
 * The one-row form's editor share is still the row's own **weight** arithmetic,
 * not a number copied into this file: the tool row's first child is a zero-width
 * spacer carrying [SessionPaneLayout.composerOneRowEditorWeight] of the row's own
 * available width — v81's [SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_WEIGHT]
 * wherever that ratio already pays the editor's floor, and the weight that lands
 * the editor on [SessionPaneLayout.COMPOSER_ONE_ROW_EDITOR_FLOOR] when it does not
 * (v86; see that function for the 720x1280 keyboard-up arithmetic) — so the same
 * `Row` that sizes the controls' viewport (the `1f` scroller inside
 * [toolControls]) hands the editor its share of what is left after ⊞ / gaps /
 * Send, and reports it through `onSizeChanged`. The measure pass reads that value
 * back — the tool row is measured *first*, so the report is this pass's, not a
 * frame-old one — and gives the editor exactly the bound the weight would have.
 * In the stacked form the spacer carries no weight: it measures 0 × 0, shifts
 * nothing, and the scroller is the row's only weighted child, i.e. the stacked
 * form's whole-card editor is the arrangement's `card − TOOL_ROW_HEIGHT`.
 *
 * The tool row is measured first on purpose in both forms: it is what tells the
 * one-row band how much of it is the editor's, and in the stacked form its height
 * is what the editor has to leave.
 *
 * v86 P0 adds two things to the arithmetic above, both of them *values*:
 *
 *  * the weight is a function of the band the row is measured with, read from the
 *    `BoxWithConstraints` the tool row now sits in. That wrapper is the same node
 *    in both forms (its own constraints are the card's either way), so a flip
 *    still re-composes nothing; and because a `SubcomposeLayout` composes its
 *    content inside its own measure pass, the weight a *narrow* band picks is the
 *    same frame's, never a write from layout back into composition;
 *  * the band is clamped to the card (`min(TOOL_ROW_HEIGHT, constraints.maxHeight)`),
 *    so a card the pane caps below 44 dp — 31.33 dp measured at 720x1280 @480dpi
 *    with the keyboard up — shrinks the row instead of overflowing it into the
 *    card's `clip`, which used to cut the bottom 4.7 dp off Send.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ComposerArrangement(
    minimal: Boolean,
    editor: @Composable () -> Unit,
    controls: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The one-row band's editor share, in px, as the tool row's own weight
    // arithmetic reported it. Read inside the measure pass (see above), never in
    // composition — it is a layout fact about this frame's band.
    var editorShare by remember { mutableStateOf(0) }
    Layout(
        modifier = modifier,
        content = {
            editor()
            // v86 P0: the one-row band's split is a function of the band's own
            // width, so the row asks for that width the only way composition can:
            // the card's constraint is the tool row's available width, and this
            // `SubcomposeLayout` composes its content *inside* the measure pass it
            // is measured in — the weight below is therefore this frame's, applied
            // in the same pass, with no state written from layout into composition
            // (the v57 oscillation) and no second frame. Both forms are inside it,
            // so the wrapper's own constraints never change and a form flip neither
            // re-composes nor moves anything (v81's invariant).
            //
            // The weight is the editor's share of what the row's fixed children
            // leave ([SessionPaneLayout.composerOneRowEditorWeight]): v81's 0.45
            // wherever that already pays the editor's floor, and the weight that
            // lands the editor on the floor when the band is too narrow for the
            // ratio — the 720x1280 keyboard-up windows, where the ratio left 39 /
            // 25 dp of text. The share itself is still *measured*: the ghost spacer
            // reports its own width in this pass (see the doc above), and the
            // editor is handed exactly that (`maxWidth = editorShare…` below).
            BoxWithConstraints {
                // `maxWidth` is this scope's own reading of the band the row is
                // about to be measured with — captured here because the nested `Row`
                // scope would hide the outer implicit receiver.
                val bandWidth = maxWidth
                // v104: the band's own height, read **outside** the `Row` below —
                // `RowScope` has a `maxHeight` of its own that would shadow it.
                val bandMaxHeight = maxHeight
                Row(
                    modifier = if (minimal) {
                        // v57 P0: one row — the editor's share and the tool controls
                        // are the same band, so the composer's floor is
                        // TOOL_ROW_HEIGHT and never a clipped sliver.
                        // v106 P0（方案 A）: the band is [BAND_HEIGHT] (48dp) so the
                        // 48dp touch target material3 forces on every control
                        // **fits inside the band** instead of overflowing it — the
                        // children no longer need to be squeezed, so nothing
                        // depends on a child honouring a composition local.
                        Modifier.fillMaxWidth().height(BAND_HEIGHT)
                    } else {
                        // v51 P0: the stacked form's own tool row, the composer's
                        // fixed half: the editor above it is what gives
                        // (`research/v51_verify/tE/v51_07_typed`).
                        Modifier.fillMaxWidth().height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)
                    },
                    // v136 P0: 一行形态**顶沿对齐**，不居中。
                    //
                    // 真机那 1px 的并集错位就出在这里（`k600_MEASURE_BARON.xml`：
                    // `EditText [24,214][405,262]` vs 按钮组 `[399,215][441,263]` ——
                    // 同样 48 高，顶沿差 1，并集高 49 vs 基线 48）。机制：带高 `band`
                    // 是 48dp，而行内控件**实测高可以是奇数**；`Alignment.CenterVertically`
                    // 把偏移算成 `(band - child) / 2f` 再 `roundToInt()`，**半像素进位**
                    // ⇒ 一个 47 高的控件被放在 **+1** 而不是 0，它那 48dp 触摸靶再绕着
                    // 自己居中 ⇒ a11y 读出 `215..263`。
                    //
                    // 一行形态里控件本来就**该**贴带的顶沿（带 = 48 = 触摸靶本身，没有
                    // 「居中」可谈），故取 `Top`：`band == child` 时 `Top` 与
                    // `CenterVertically` 逐位相同（偏移都是 0，**一个像素都不动**），
                    // 只有奇数高那种本来就在错的情况被拉回 0。堆叠形态一行不动。
                    verticalAlignment = if (minimal) Alignment.Top else Alignment.CenterVertically,
                ) {
                    Spacer(
                        (if (minimal) {
                            Modifier.weight(SessionPaneLayout.composerOneRowEditorWeight(bandWidth))
                        } else {
                            Modifier
                        }).onSizeChanged { editorShare = it.width },
                    )
                    // v104 P0: `clickable` carries Compose's **48dp minimum touch
                    // target** (`minimumInteractiveComponentSize`), 4px taller than
                    // the 44dp band these controls are centred in. Where the band
                    // can pay it, that expansion is free and every already-passed
                    // reading keeps it (`1366x768`'s `Add/Send h=48` among them).
                    //
                    // Where it cannot, those 4px are exactly the overflow: the host
                    // box is the band, but 真机 measures the child union
                    // `union(EditText, Add, Send)`, so
                    // `union.bottom − pane.bottom = union.height − hostBox.height`
                    // — `1366x600`+IME reads `49 − 44 = +5`
                    // (`REPORT_COMPOSER_OVERFLOW.md` §4.1; the editor shows `4` and
                    // the buttons `5`, the 1px being the centring's sub-pixel
                    // rounding — `REPORT_U1E.md` §5.3). v103's `top + host ≡ pane`
                    // is an identity about the **host box** and says nothing about a
                    // union taller than it.
                    //
                    // v105 P0: **48dp 是 material3 强制的**（`Modifier.clickable` 挂
                    // `minimumInteractiveComponentSize`，读 `LocalMinimumInteractiveComponentSize`
                    // 的 `48dp` 默认值 —— 本仓没有任何 `provides 48.dp`）。它比 44dp 的
                    // 带高 4px，控件在带内 `CenterVertically` 居中 ⇒ **子节点溢出自己的行**：
                    // 上 2px + 下 2px，再加居中的 1px 亚像素 ⇒ 底沿 +5px
                    // （`REPORT_OVERFLOW_DIAG.md` §5.1，两档逐位对上）。
                    //
                    // ⇒ v105 **受控覆盖**：把靶**封顶到带本身**（`SessionPaneLayout.
                    // composerBandTouchTargetDp`，唯一求值处，测试读同一个数），而不是
                    // v104 那样整条撤掉地板（`Dp.Unspecified` ⇒ 控件回落 28dp）：
                    //  * 带 `>= 48` ⇒ **原值 48，一像素不动**（`1366x768` 的 `h=48` 逐字不变）；
                    //  * 一行形态带 `[28,48)` ⇒ 靶 = **带高本身**（44dp 带 ⇒ 44dp 靶）
                    //    ⇒ 并集 `= band`（原来 `48 > 44`）⇒ `unionPx <= band` 与
                    //    `unionBottom <= hostBottom` **两条同时成立**；
                    //  * 一行形态带 `< 28`（连一个整控件都放不下，v86 地板之下）
                    //    ⇒ 才交还 `Dp.Unspecified`（v104 的那一支原样保留）。
                    //
                    // **可点性未退化**（`ComposerBandTouchTargetV105Test` pin）：
                    // 封顶值 44dp 仍**大于**控件固有的 28dp（`SUBMIT_SIZE`）⇒ a11y 节点
                    // 仍是 44×44 的 `clickable`，比 v104 的 28×28 **多 16dp 可点面积**；
                    // 且原来那 4px 底沿落在 **IME 顶沿之下**（`218 > imeTop 213`）本就被
                    // 键盘压住、点不到 ⇒ 把它收回带内**净增**真正可点的面积。
                    // v104: the controls stay mounted **once** — the three branches below
                    // differ only in the composition local they read, so this stays a
                    // single call site (v80/v81's "mounted once and never move").
                    val bandControls: @Composable () -> Unit = { controls() }
                    val bandTouchTarget = SessionPaneLayout.composerBandTouchTargetDp(
                        oneRow = minimal,
                        band = bandMaxHeight,
                    )
                    if (bandMaxHeight >= MIN_TOUCH_TARGET) {
                        bandControls()
                    } else if (bandTouchTarget != Dp.Unspecified) {
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides bandTouchTarget,
                        ) { bandControls() }
                    } else {
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                        ) { bandControls() }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // v86 P0: the band never exceeds the card it is drawn in. It used to be a
        // flat 44 dp, and on a card the pane caps under that — the measured frame
        // is 31.33 dp at 720x1280 @480dpi with the keyboard up — the tool row was
        // still laid out at 44 dp and the card's own `clip` cut its bottom 12.7 dp:
        // the ⊞ / chip / Send are centred in the 44 dp row, so their centres landed
        // 6.3 dp below the card's and the Send lost its last 4.7 dp
        // (`research/v85_verify/5554/40_480_ime_up.png`). Clamping the band to the
        // card keeps every control whole at every step of the v84 ladder (28 dp of
        // controls inside the ≥ 28 dp the ceiling leaves, `SessionPaneImeBandV84Test`)
        // and changes nothing wherever the card can pay the band — 44 dp at 420 dpi,
        // every unwrapped window.
        // v104 P0: the **child union** — `union(EditText, Add, Send)`, the rectangle
        // 真机 actually measures — may never be taller than the host box it is
        // placed in. Its admissible height is resolved in **one** place,
        // `SessionPaneLayout.composerChildUnionHeightPx` (the same
        // `coerceAtMost` containment as `composerHostHeightPx`), so it cannot drift
        // away from the box the way v103's host-box identity did.
        //
        // **v103 恒等式为什么没兜住**（`research/v97_verify/5556_v98/
        // REPORT_COMPOSER_OVERFLOW.md` §4.1，两档逐位对上）：
        // ```
        // 1366x600 + IME: union 169..218 (h=49) − host 44 = +5   ← 越界
        // 1366x768 + IME: union 208..296 (h=88) − host 94 = -6   ← 未越界
        // ```
        // `top + host ≡ pane` 是**宿主盒**的恒等式；而宿主盒 = `TOOL_ROW_HEIGHT`
        // （44dp），并集里每个子节点却带 `clickable` 的 48dp 最小触摸靶 ⇒
        // **子节点比盒子高 4px，在带内居中后底沿从盒子底沿下方穿出**，
        // 加上编辑器 1px 亚像素取整 = **那 5px**。
        // v106 P0（方案 A）: the band is [BAND_HEIGHT] = `max(TOOL_ROW_HEIGHT, 48dp)`,
        // so the enforced touch target **fits in its own band** and the child union
        // equals the band by construction:
        // `toolRow = min(48, band) <= band`, `editor = min(48, band) <= band`,
        // `unionPx = max(editorTop + editor, toolRow) <= band = hostPx`.
        // `minOf(..., constraints.maxHeight)` is the **只在需要时增高** guard: a pane
        // that cannot pay 48dp still falls back to the clamped band, unchanged.
        // v105's gate below is left exactly as it was — with a 48dp band
        // `bandMaxHeight >= MIN_TOUCH_TARGET` now holds, so the enforced 48dp value
        // is used **verbatim** and no child is ever shrunk.
        val band = minOf(BAND_HEIGHT.roundToPx(), constraints.maxHeight)
        // Both children are measured with a **zero** minimum height: the band and
        // the editor's own floor (`heightIn`) are what bound them, and the card's
        // incoming minimum (the pane's) is not a fact about either.
        val toolRow = measurables[1].measure(
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = band),
        )
        if (minimal) {
            val editor = measurables[0].measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = editorShare.coerceIn(0, constraints.maxWidth),
                    minHeight = 0,
                    maxHeight = band,
                ),
            )
            // v104 P0: `clickable` 的 **48dp 最小触摸靶**（Compose
            // `minimumInteractiveComponentSize`）让 ⊞/Send 各自比 44dp 的带高 4px，
            // 在 `Alignment.CenterVertically` 下**上下各溢出 2px**
            // ⇒ 子节点并集比宿主盒高，底沿从盒子下沿穿出去。
            //
            // 这就是真机那 **+5px = 4（48dp 触摸靶 − 44dp 带）+ 1（居中亚像素取整）**：
            // `REPORT_COMPOSER_OVERFLOW.md` §4.1 两档逐位对上
            // （`union 169..218`(49) − `host 44` = **+5**；`union 208..296`(88)
            // − `host 94` = **−6**）。
            //
            // 修法：**并集的可用高与它的摆位在同一个数上收口** ——
            // ① 可用高 `composerChildUnionHeightPx`（唯一求值处）；
            // ② 编辑器按**并集**上界摆位，`editorTop + editor.height <= band` 恒成立
            // ⇒ 子节点并集底沿 `<= 宿主盒底沿 <= pane 底沿`（v103 的恒等式）
            // 在**被测量的那一维**（并集）上兑现，而不再只是宿主盒的自说自话。
            // v136 P0: 两个孩子**用同一条摆位规则**（v104 只对齐了尺寸，没对齐摆位）。
            // 原来这里是 `editor.place(0, editorTop)` + `toolRow.place(0, 0)` ——
            // 编辑器居中、工具行钉顶沿，两套规则。带高 48、两个子节点都 48 高时两者
            // 同为 0，所以真机那 1px **不是**这一行造成的（本报告 §1 有逐位算式）；
            // 但只要两者高度不等（工具行里坐着被 `minimumInteractiveComponentSize`
            // 撑起来的控件，编辑器是 `heightIn` 的内容高），「一个居中一个钉顶」就会
            // 把并集底沿推出带外。现在两者都走 [SessionPaneLayout.composerBandChildTopPx]，
            // 顶沿相同当且仅当同高，且 `top + 自己的高 <= band` 对**每一个**子节点恒成立。
            //
            // **48dp 触摸靶一个像素不动**：`band` 仍是 `BAND_HEIGHT`(48dp)、子节点仍以
            // `maxHeight = band` 测量、`composerBandTouchTargetDp` 未被触碰；本行只改
            // 「放在带内的哪个 y」。
            val editorTop = SessionPaneLayout.composerBandChildTopPx(
                bandPx = band,
                childPx = editor.height,
            )
            val toolRowTop = SessionPaneLayout.composerBandChildTopPx(
                bandPx = band,
                childPx = toolRow.height,
            )
            val unionBottom = SessionPaneLayout.composerBandUnionBottomPx(
                bandPx = band,
                editorPx = editor.height,
                toolRowPx = toolRow.height,
            )
            layout(constraints.maxWidth, constraints.constrainHeight(band)) {
                // The band centred the field when the field was its child
                // (`verticalAlignment = CenterVertically`); the arrangement does
                // the centring now, and the tool row fills the band.
                editor.place(0, editorTop)
                toolRow.place(0, toolRowTop)
                // v136: the union's bottom edge is **computed** above rather than
                // assumed, and it is deliberately *not* clamped and *not* asserted:
                // a `unionBottom > band` is the defect itself, and hiding it behind a
                // `coerceAtMost` (v135 §1.6 的假绿陷阱) or a `check()` (which kills the
                // process inside `layout` — `CRASH_SHRINK_FIX.md`) would make it
                // invisible. The same production function is what the JVM pin asserts,
                // so a placement regression is visible to the suite, not only to a
                // device frame.
                SessionComposerBandUnion.record(unionBottom = unionBottom, bandPx = band)
                // v104: the union (`unionPx`, the rectangle 真机 measures) is bounded
                // by the band **by construction** — `maxHeight = band` on both
                // children above is the Compose contract, and `editorTop` is a
                // non-negative centring offset, so
                // `union.bottom = max(editorTop + editor.height, toolRow.height)
                //                 <= band = host box bottom <= pane bottom`.
                // **No runtime throw here on purpose:** a failing `check()` inside
                // `layout` would kill the process, which is the exact P0 this repo
                // just fixed (`CRASH_SHRINK_FIX.md`); the invariant is pinned in
                // `ComposerChildUnionV104Test` instead.
            }
        } else {
            val editor = measurables[0].measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxHeight = (constraints.maxHeight - toolRow.height).coerceAtLeast(0),
                ),
            )
            layout(
                constraints.maxWidth,
                constraints.constrainHeight(editor.height + toolRow.height),
            ) {
                editor.place(0, 0)
                toolRow.place(0, editor.height)
            }
        }
    }
}

/**
 * v75 P2: the composer's **text band** owns its taps.
 *
 * The model chip is a 28 dp `clickable`, so Compose 1.7 gives it a centred
 * 48 dp minimum touch target ([modelChipTouchHeightDp]) — 10 dp above and below
 * the chip — and that band reaches 2 dp past the 44 dp tool row
 * ([modelChipTouchOverflowDp]). `NodeCoordinator.hitTestChild` descends into
 * children without a bounds check, so the sliver sits inside the composer's text
 * band, where nothing else is directly hit: a tap aimed at the text area's
 * bottom edge opened the model picker instead of focusing the editor
 * (`v74_28_taptext`, the reported「chip 的点击区覆盖 composer 上半部」).
 *
 * The band cannot be shrunk — the platform minimum is not opt-out-able in 1.7 and
 * a clickable inside a 44 dp row is clamped to that row — so the text band claims
 * the tap itself: a **direct** hit always beats a minimum-touch-target hit
 * (`NodeCoordinator.outOfBoundsHit`'s own priority rule, "the node with direct
 * hit will get the event"). Taps that land inside the field are consumed by the
 * field before this node ever sees them, so caret placement, selection drags and
 * the IME are untouched; only the slot's own padding (16/8 dp around the field)
 * is new.
 */
private fun Modifier.editorBandTap(focusRequester: FocusRequester): Modifier =
    pointerInput(focusRequester) {
        detectTapGestures { runCatching { focusRequester.requestFocus() } }
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
    onRestoreFocus: () -> Unit,
) {
    val c = OcTheme.colors
    // v49: ESC 关闭 + 外点穿透，宿主窗口在 Popup 之外取（见 `OcPopoverDismiss`）。
    val hostView = LocalView.current
    val popoverDismiss = remember { OcPopoverDismiss(hostView) }
    LaunchedEffect(open) { if (!open) popoverDismiss.flushPendingTap() }
    // v63 BUG-1: this menu is a `focusable` Popup, so opening it takes the
    // editor's window focus and its IME with it — and a Popup never gives them
    // back. The composer was then left unfocused after the menu closed: no
    // caret, no keyboard, and the send button inert until the field was tapped
    // again. Same hand-back [ModelPicker] already does through
    // `onRestoreFocus`; fired only on a close that follows a real open.
    var openedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            openedOnce = true
        } else if (openedOnce) {
            openedOnce = false
            onRestoreFocus()
        }
    }
    var anchor by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(LocalDensity.current) { ADD_MENU_GAP.roundToPx() }
    // Hoisted: `semantics {}` is not a composable scope (see the submit Box).
    val addLabel = stringResource(R.string.oc_composer_add)

    Box(modifier = Modifier.onSizeChanged { anchor = it }) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(OcTheme.shapes.md)
                .clickable(role = Role.Button) { onOpenChange(true) }
                .semantics { contentDescription = addLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_plus),
                contentDescription = null,
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
                // v49: explicit so a later edit cannot silently drop them back to
                // relying on the defaults — the flags are the dismiss contract
                // this menu has always advertised.
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                OcEnterOnMount(
                    overlay = OcMotion.Overlay.DROPDOWN,
                    // ESC 关闭 + 外点穿透（含 Popup 自己不建立的焦点归属）。
                    modifier = Modifier.ocPopoverDismiss(popoverDismiss) { onOpenChange(false) },
                ) {
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

/**
 * The editor's floor while the pane is short (v55 P0 「短窗零渲染」): the row
 * still takes two lines of a placeholder plus the caret, and the 20 dp it gives
 * up goes to the message timeline, which on that window had nothing visible at
 * all. `SessionPaneLayout.COMPOSER_COMPACT_HEIGHT` is the composer this adds up
 * to.
 */
private val EDITOR_MIN_HEIGHT_COMPACT = SessionPaneLayout.COMPOSER_EDITOR_COMPACT_HEIGHT

/** `max-h-[180px]` — past this the editor scrolls internally (no auto-grow). */
private val EDITOR_MAX_HEIGHT = 180.dp

/** `h-11` tool row. */
private val TOOL_ROW_HEIGHT = 44.dp

/**
 * v106 P0（**方案 A**）：一行形态的带高 = [SessionPaneLayout.composerBandHeightDp]
 * = `max(44dp, 48dp)` = **48 dp** —— **让带装下子节点被强制的 48dp 触摸靶**。
 *
 * **为什么不改 [TOOL_ROW_HEIGHT]**：44dp 是 `h-11` 的**视觉/内容**行高（堆叠形态那一支
 * 仍用它，`.height(TOOL_ROW_HEIGHT).padding(horizontal = 8.dp)` 逐字不动）；
 * 方案 A 改的是**装东西的那条带**，两者是不同量，所以
 * `ComposerEditorFormV80Test` / `ComposerModelChipV75Test` / `ComposerOneRowEditorFloorV86Test`
 * 对 `TOOL_ROW_HEIGHT = 44.dp` 的字面量 pin **一条都不用改、也不许改**。
 *
 * **代价（显式记账）**：一行 composer 的卡高 44 → 48（**+4dp**）。这 +4dp **落在
 * band 上**；`SessionPaneLayout.COMPOSER_MINIMAL_HEIGHT` **保持 56dp 不动** —— 它是
 * v57 起按「44dp 行 + 12dp padding」立的账，被 v74/v79/v84/v86/v99/v102 的**真机实测
 * 阶梯**逐个钉死，本轮无真机可重测，故一个都不动。由此产生的 4dp 地板缺口
 * （真实 60 vs 地板 56）由 `composerHostHeightPx` 的 `coerceAtMost(pane)` 与下面的
 * `minOf(BAND_HEIGHT, constraints.maxHeight)` 两处 clamp 吸收，**不会溢出**；
 * 列为未闭环，交 Lead 裁定。
 *
 * **两条算式在这里成为构造性恒等式**（不再依赖任何 local 是否被理会）：
 * ```
 * band = min(48, maxHeight)          <- 父给的约束
 * hostPx = constrainHeight(band) = band
 * toolRow = min(48, band) = 48 <= band
 * editor  = min(48, band) = 48 <= band ; editorTop = (band - editor)/2 = 0
 * unionPx = max(editorTop + editor, toolRow) = 48 = band   ✅ unionPx <= band
 * unionBottom = hostTop + unionPx <= hostTop + hostPx = hostBottom   ✅
 * ```
 * 真机 600 档逐位：修前 `union 48 > band 44`（+4）、`unionBottom 218 > hostBottom 213`（+5）
 * ⇒ 修后 `union 48 <= band 48`、`unionBottom <= hostBottom`，**两条同时成立**。
 */
private val BAND_HEIGHT: Dp = SessionPaneLayout.composerBandHeightDp(TOOL_ROW_HEIGHT)

/**
 * v104 P0: Compose 的**最小触摸靶**（`Modifier.clickable` 挂的
 * `minimumInteractiveComponentSize`，material 的 48dp）——比 [TOOL_ROW_HEIGHT]
 * 高 4px。
 *
 * 工具行**装得下**它时，那 4px 是白赚的触摸面积（本仓每一处已 PASS 的读数都带着它，
 * 包括真机 `1366x768` 的 `Add/Send h=48`）。工具行**装不下**时，这 4px 就是那
 * **+5px** 越界的全部来源：`REPORT_COMPOSER_OVERFLOW.md` §4.1 两档逐位对上 ——
 * `1366x600`+IME `union(49) − hostBox(44) = +5`、`1366x768`+IME
 * `union(88) − hostBox(94) = −6`。故只在**装不下**时关掉它。
 */
private val MIN_TOUCH_TARGET = 48.dp

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

/** v69: the idle editor's `@` menu — one stable instance, so `remember` keeps it. */
private val EMPTY_MENTION_ROWS =
    ComposerSuggestions.MentionRows(fixed = emptyList(), files = emptyList())

/**
 * ESC while the inline `@`/`/` popover is open. Soft-keyboard-first: with the
 * IME up ESC only dismisses it (consumed, popover stays); with the IME down
 * ESC closes the popover. Pure so the matrix is JVM-testable.
 */
object ComposerPopoverEsc {
    data class Decision(val consumed: Boolean, val closePopover: Boolean, val hideKeyboard: Boolean)

    fun decide(popoverOpen: Boolean, imeVisible: Boolean): Decision {
        if (!popoverOpen) return Decision(consumed = false, closePopover = false, hideKeyboard = false)
        return if (imeVisible) {
            Decision(consumed = true, closePopover = false, hideKeyboard = true)
        } else {
            Decision(consumed = true, closePopover = true, hideKeyboard = false)
        }
    }
}

/**
 * v30 BUG-2 — the composer's IME commit boundary.
 *
 * The W5-F fold ([HalfWidthText.normalize]) is a **rewrite of text the IME
 * produced**, and Compose answers exactly that with an input restart. Verified
 * against the library sources this build compiles against
 * (`foundation-android:1.7.6`, `ui-android:1.7.6`):
 *
 * - `EditProcessor.reset(value, session)` commits the editing buffer's composing
 *   region whenever the value's text changed (`mBuffer.commitComposition()`,
 *   `value.copy(composition = null)`) and then calls
 *   `TextInputSession.updateState(oldValue, newValue)`;
 * - `TextInputServiceAndroid.updateState` runs `inputMethodManager.restartInput()`
 *   as soon as `oldValue.text != newValue.text` — the same code path whose own
 *   comment says "if restartInput is going to be called the composition has to
 *   be cleared otherwise it results in keyboards behaving strangely".
 *
 * So folding while the IME still owns a composing region restarts the IME's
 * session *and* leaves the app's buffer holding characters the IME believes are
 * still its composing region. The IME's next edit — "replace the word I am
 * composing" — is then applied to text the app owns, and a character the app
 * already committed can be deleted by it. That is the v30 `tB_12b` shape:
 * `/` first, then `zzzzz` as one bulk commit, and only `zzzzz` left behind
 * (the same bulk injection is also measured mangling ASCII on this device's
 * pinyin Gboard — `research/adbui.ps1:58-62` — so the IME's own rewrite is part
 * of that repro; what this object removes is the app's half of it: the restart).
 *
 * The rule is therefore: **the editor never rewrites text the IME produced** —
 * not while it composes, and not after it commits either. The W5-F fold is a
 * *view* ([readable]) the popover logic reads, not a write-back.
 *
 * v64 (real device, both emulators): the committed case was the one that was
 * left, and it is the one that locks the composer. On `emulator-5556` a typed
 * `/` opened the command card, threw the caret to the front of the line and ate
 * the next keystroke — `t` vanished, `r` landed at offset 0 and the line read
 * `rm/` (`research/v64_verify/5556/02_24` → `03_25` → `05_27`). On
 * `emulator-5558` the same fold behind `@` parked the caret at 0 for good:
 * `@de` survived five `KEYCODE_DEL` and a `Ctrl+A`+`DEL` untouched
 * (`research/v64_verify/5558/01b*`, `01c`, `07b*`, `21`) — a caret at 0 makes
 * backspace a no-op and select-all+delete a no-op, which is exactly the
 * "删不掉" a tester reports. A bare ASCII line typed the same way is fine,
 * because no rewrite happens and no restart is requested.
 *
 * The fold is 1:1 ([HalfWidthText.toAscii] maps one char to one char), so
 * nothing that reads the folded view is affected: [ComposerSuggestions.commandQuery]
 * folds through the same rule, the `@` anchor's indices stay valid in the
 * stored (unfolded) text, the draft is folded again on restore and `submit()`
 * folds the outgoing text.
 *
 * Pure so the transition is JVM-testable (`ComposerImeCommitTest`).
 */
object ComposerImeCommit {

    /**
     * The text the popover logic reads: [text] with the full-width syntax folded
     * to ASCII, 1:1, so every index it yields also addresses [text].
     *
     * The composer's `onValueChange` stores the IME's own value and derives
     * both triggers from this view — which is why a Chinese IME's `／` / `＠`
     * opens the same panels an ASCII `/` / `@` does, with no app-side rewrite
     * and therefore no `restartInput()`.
     */
    fun readable(text: String): String = HalfWidthText.toAscii(text)

    /**
     * v63 BUG-1: an editor value whose `selection` / `composition` reaches past
     * its own text is not a legal state, and holding one is what parks the caret
     * at offset 0.
     *
     * Measured on `emulator-5558` (v63, `research/v63_verify/5558/25-29`): once
     * the composer's value carried a stale offset, every later IME edit was
     * applied at 0 — tapping mid-text inserted at the start, and backspace /
     * delete / `KEYCODE_MOVE_END` all became no-ops, which is the reported
     * "文本锁死". logcat names the mechanism: `ImeTracker … onRequestShow at
     * ORIGIN_SERVER reason ATTACH_NEW_INPUT` (the app-side `restartInput()`
     * this file's doc above describes) followed by
     * `GoogleInputMethodService.onStartInput … initialSelStart=0`, i.e. the IME
     * re-initialised its editor at offset 0 and every subsequent `setSelection`
     * was anchored there.
     *
     * The guard is the smallest rule that makes the value legal again: fold both
     * ranges back into `[0, text.length]` (keeping them ordered), and drop a
     * composition that no longer covers any text. A value that is already legal
     * comes back as the very same instance, so the common keystroke schedules no
     * extra recomposition — the same reference-preserving contract
     * [HalfWidthText.normalize] keeps.
     *
     * v81 P1 「翻转后草稿头部游离字符」: legal is not enough for one shape — the
     * IME's **re-attach** batch. When a connection is re-created (a rotation, a
     * focus ask landing on a connection on its way down, a `restartInput()` the
     * app itself provoked) the platform re-initialises the IME at offset 0 — the
     * `onStartInput … initialSelStart=0` line quoted above — and Gboard's first
     * batch back arrives as an **opened-but-empty composing region at 0**: the
     * phantom [ComposerCaretAdoptV63Test] already drops. With [previous] the
     * batch is read for what it is, because the caret it carries is not a user
     * event: it is the restart's idea of where the caret is. Accepting it parks a
     * non-empty line's caret in front of the text — the state that makes the
     * IME's next edit *insert there*, i.e. a character the user never put at the
     * head of their draft. Measured on `emulator-5554` at 720x1280 @480dpi
     * (`research/v80_verify/5554/29_480_pre_rot.png` → `30_480_landscape.png`):
     * the draft reads `STRAYCHK`, the device is rotated, and the head of the line
     * comes back as `RSTRAYCHK`. The app's own caret is kept instead (folded into
     * the text the batch carried), and only when that caret was **not** already at
     * the head — a caret the user put there is never moved, and a value whose
     * caret is not parked at the head is untouched (the rule can only *prevent* a
     * park, never move a live one).
     *
     * Pure, so `ComposerImeCommitTest` pins it on the JVM.
     */
    fun adopt(input: TextFieldValue, previous: TextFieldValue? = null): TextFieldValue {
        val length = input.text.length
        val selection = clamp(input.selection, length)
        val composition = input.composition
            ?.let { clamp(it, length) }
            ?.takeUnless { it.collapsed && it.start == 0 }
        // The re-attach batch: an empty composing region opened at 0 that would
        // park the caret in front of a non-empty line (v81, see above).
        val restartPark = composition == null &&
            isRestartPhantom(input.composition) &&
            selection == TextRange.Zero &&
            length > 0 &&
            previous != null
        val caret = if (restartPark) {
            clamp(previous.selection, length).end.takeIf { it > 0 }?.let { TextRange(it) } ?: selection
        } else {
            selection
        }
        return if (caret == input.selection && composition == input.composition) {
            input
        } else {
            TextFieldValue(input.text, caret, composition)
        }
    }

    /**
     * The IME's re-attach fingerprint: a composing region it *opened* without
     * putting any text in it, at the head of the line. A live composing region
     * covers at least one character (the IME's own pinyin/word text), so a
     * collapsed one at 0 is the platform re-initialising its editor
     * (`initialSelStart=0`) — the shape v63 measured and [adopt] drops.
     */
    private fun isRestartPhantom(composition: TextRange?): Boolean =
        composition != null && composition.collapsed && composition.start == 0

    /** [range] folded into `[0, length]` with its two ends back in order. */
    private fun clamp(range: TextRange, length: Int): TextRange {
        val start = range.start.coerceIn(0, length)
        val end = range.end.coerceIn(0, length)
        return if (start <= end) TextRange(start, end) else TextRange(end, start)
    }
}

/**
 * v81 P1 「重开会话后编辑器失焦需二次点击」: the composer's session-entry focus
 * handoff as pure policy.
 *
 * The handoff is the one ask the composer makes for a caret it was not given by
 * a user gesture (B7: entry into, or a switch to, a session). On device that ask
 * has to survive a *window*: the session's own restore path rebuilds the pane
 * after the composer mounts — `observeSession` clears the store and the
 * snapshot/UI rebuild follows on a later frame — and an ask made before that
 * rebuild is spent on a field whose input session the platform then re-creates.
 * The field draws a caret with no live `InputConnection`, the first injected
 * batch commits one character and drops the rest, the caret goes away, and only
 * a *second* tap makes typing work
 * (`research/v80_verify/5554/26_480_typed.png` → `27_480_recheck.png` →
 * `28_480_refocus.png`; the 420dpi "only the first character landed" shape is
 * `research/v79_verify/5554/35_taps.png` → `36_10chars2.png`).
 *
 * [MAX_ATTEMPTS] × one settled frame each plus [RETRY_DELAY_MS] between them is
 * the window's budget, the same cadence the Home rename field settled on
 * (`HomeSessionsList`'s `FOCUS_LATE_RETRIES` × 120 ms) — a *bounded* window, so
 * the effect can never become the focus thief v80 removed: it ends on the first
 * frame the field holds the caret, it ends when another surface owns the
 * keyboard (the v74 terminal yield), and it stops at [MAX_ATTEMPTS] regardless.
 *
 * [raisesKeyboard] is the other half of the v43 measurement ("`requestFocus()`
 * alone left the field with no live `InputConnection`"): the keyboard is raised
 * with the **first** ask, which is the entry handoff itself. A later attempt
 * never calls `show()` again — a repeat `keyboard.show()` is a fresh
 * `restartInput()` and an IME re-initialised mid-word drops the character it is
 * holding (v66), while a repeat `requestFocus()` on an already-focused node is a
 * no-op.
 *
 * Pure, so the window's arithmetic is JVM-testable (`ComposerEntryFocusV81Test`).
 */
internal object ComposerEntryFocus {

    /** One settled-frame ask plus two retries — ~2 frames + 2 × the delay. */
    const val MAX_ATTEMPTS: Int = 3

    /** Delay between attempts; the same 120 ms step the rename field uses. */
    const val RETRY_DELAY_MS: Long = 120L

    /** Whether another ask is left after [attempt] asks have been issued. */
    fun retries(attempt: Int): Boolean = attempt < MAX_ATTEMPTS

    /** Only the first ask raises the keyboard (see the object note). */
    fun raisesKeyboard(attempt: Int): Boolean = attempt == 0
}

/**
 * The popover slot's placement contract (v67): the wrapper around
 * [OcEnterExitVisibility] measures the popover but reports zero stack height
 * and places the content entirely above itself, so the popover draws over the
 * timeline like upstream's absolutely-positioned card without ever entering
 * the measured composer height (see the call site for the pane-flip bug this
 * prevents). Pure so the contract is JVM-testable.
 */
internal object ComposerPopoverOverlay {

    /** The height the wrapper reports to the composer column — always nothing. */
    const val STACK_HEIGHT: Int = 0

    /**
     * The y offset the measured popover is placed at: its bottom edge (the
     * [MentionPopover]'s `POPOVER_GAP` included) sits on the composer card's
     * top, exactly where the stacked layout put it.
     */
    fun contentTop(contentHeight: Int): Int = -contentHeight
}

/**
 * The `@` popover's two open-state rules as pure Kotlin, so the bare-`@` shape —
 * the one the device run measured as "nothing came up"
 * (`research/v62_verify/5560/17_at.png`: a `@` alone in a fresh session, no card
 * above the editor) — is locked on the JVM instead of only on a device.
 *
 * - [popoverVisible] renders on the **trigger**, never on a non-empty list
 *   (`editor.tsx:790-792`): upstream draws the popover whenever the editor is in
 *   a trigger shape and renders the empty state (`prompt.popover.emptyResults`)
 *   where the rows would be. A live `@` with no match is therefore *open*, and
 *   the only thing a caller may key the row list on is [hasRows].
 * - [searchDelayMs] is the debounce the `/find/file` ask waits out. A **blank**
 *   query is not a keystroke in progress — upstream lists its `recent` context
 *   source for it (`composer/model.ts:195-…`) — so it asks at once; every
 *   further character waits the [MentionText.DEBOUNCE_MS] pause. That is what
 *   makes the rows reachable on the keystroke that opens the menu, instead of
 *   after another [MentionText.DEBOUNCE_MS] plus the round trip.
 */
internal object ComposerMention {

    /**
     * The popover's visibility: a live `/` query or a live `@` anchor opens it
     * whatever the row list holds; with neither trigger it shows only when rows
     * somehow exist.
     */
    fun popoverVisible(
        hasRows: Boolean,
        slashQuery: String?,
        trigger: MentionText.Anchor?,
    ): Boolean = hasRows || slashQuery != null || trigger != null

    /** 0 for a bare `@`, the debounce for every query typed after it. */
    fun searchDelayMs(query: String): Long =
        if (query.isEmpty()) 0L else MentionText.DEBOUNCE_MS
}
