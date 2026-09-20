package com.xiufeigo.opencodeshell.ui.session.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcEnterExitVisibility
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Composer (prompt-input v2). M1 stage 1 laid out the shell; M1.5 wires the
 * three data-bearing controls:
 *
 * - 「+」 → SAF multi-document pick (`onAttachClick`, registered by the screen)
 *   and the pending-attachment strip [AttachmentPreviewRow] (58×46 thumbs /
 *   `layer02` file chips, 16 dp remove badge)
 * - 「@」 → [MentionPopover] fed by `/find/file` (`onSearchFiles`), 200 ms
 *   debounce (`MentionText.DEBOUNCE_MS`), Enter/↑/↓/Esc keyboard, and mention
 *   colouring inside the editor ([MentionColorTransformation])
 * - the model chip → [ModelChip] menu (M2 fills the catalog)
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
 */
@Composable
fun Composer(
    runStatus: ChatStore.RunStatus,
    modelLabel: String?,
    attachments: List<ChatViewModel.Attachment>,
    modelOptions: List<ModelOption>,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (ChatViewModel.Attachment) -> Unit,
    onSelectModel: (ModelOption) -> Unit,
    onSearchFiles: (query: String, onResult: (List<String>) -> Unit) -> Unit,
    onSend: (String, List<ChatViewModel.Attachment>) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shapes = OcTheme.shapes
    val running = runStatus == ChatStore.RunStatus.RUNNING

    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    // Open mention (the `@` the caret sits in) + its fetched candidates.
    var trigger by remember { mutableStateOf<MentionText.Anchor?>(null) }
    var candidates by remember { mutableStateOf<List<MentionText.Candidate>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    val mentionOpen = trigger != null && candidates.isNotEmpty()
    val hasContent = ComposerSubmit.hasContent(value.text, attachments.size)
    // §4.6: stop only while the input is blank — otherwise the button sends
    // (a typed follow-up must not abort the running turn).
    val stopping = ComposerSubmit.stopping(running, value.text, attachments.size)
    // The search effect can outlive the composition that started it: always call
    // the latest bridge, never a captured lambda.
    val searchFiles by rememberUpdatedState(onSearchFiles)

    // Debounced `/find/file`: the effect key is the anchor *and* the query, so
    // moving the caret inside the same query does not re-issue a request, and a
    // new keystroke cancels the pending one.
    LaunchedEffect(trigger) {
        val active = trigger
        if (active == null) {
            candidates = emptyList()
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
        candidates = MentionText.candidates(paths)
        selected = 0
    }

    fun acceptMention(index: Int) {
        val active = trigger ?: return
        val candidate = candidates.getOrNull(index) ?: return
        val caret = value.selection.end
        val insertion = MentionText.insert(value.text, active.start, caret, candidate.label) ?: return
        value = TextFieldValue(insertion.text, TextRange(insertion.caret))
        trigger = null
        candidates = emptyList()
        selected = 0
    }

    /**
     * Submit (`research/02` §4.7): never send a blank prompt, and clear the
     * editor + the open mention popover once the prompt has been handed over.
     * Attachments are cleared by the screen, which owns them.
     */
    fun submit() {
        val outgoing = value.text.trim()
        if (!ComposerSubmit.hasContent(outgoing, attachments.size)) return
        onSend(outgoing, attachments)
        value = TextFieldValue("")
        trigger = null
        candidates = emptyList()
        selected = 0
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // M6: `popover-open` / `popover-close` — `.15s ease-out` in, `.1s ease-in`
        // out, `scale(.96) → 1` + opacity (`research/03` §2.1/§3.4), growing out
        // of the editor's top edge.
        OcEnterExitVisibility(
            visible = mentionOpen,
            overlay = OcMotion.Overlay.POPOVER,
        ) {
            MentionPopover(
                candidates = candidates,
                selected = selected,
                onPick = ::acceptMention,
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

            BasicTextField(
                value = value,
                onValueChange = { updated ->
                    value = updated
                    trigger = MentionText.anchorAt(updated.text, updated.selection.end)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = EDITOR_MIN_HEIGHT, max = EDITOR_MAX_HEIGHT)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        if (mentionOpen) {
                            // The popover owns the arrows / Enter / Esc while open.
                            return@onPreviewKeyEvent when (event.key) {
                                Key.DirectionDown -> {
                                    selected = (selected + 1) % candidates.size
                                    true
                                }

                                Key.DirectionUp -> {
                                    selected = (selected - 1 + candidates.size) % candidates.size
                                    true
                                }

                                Key.Enter, Key.NumPadEnter -> {
                                    acceptMention(selected)
                                    true
                                }

                                Key.Escape -> {
                                    trigger = null
                                    candidates = emptyList()
                                    true
                                }

                                else -> false
                            }
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
                textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, lineHeight = 20.sp),
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
                                text = stringResource(R.string.oc_composer_placeholder),
                                color = c.textFaint,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
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
                // Attach: SAF OpenMultipleDocuments("*/*") via the screen's
                // launcher (images become data URLs, see `AttachmentReader`).
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(shapes.md)
                        .clickable(onClick = onAttachClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_oc_plus),
                        contentDescription = stringResource(R.string.chat_attach),
                        tint = c.iconMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Spacer(Modifier.width(4.dp))
                ModelChip(
                    current = modelLabel,
                    options = modelOptions,
                    onSelect = onSelectModel,
                )

                Spacer(Modifier.weight(1f))

                // §4.6: stop needs `running && blank`; a non-blank input keeps the
                // send icon and queues the follow-up instead of aborting.
                val enabled = stopping || hasContent
                Box(
                    modifier = Modifier
                        .size(SUBMIT_SIZE)
                        .alpha(if (enabled) 1f else DISABLED_ALPHA)
                        .clip(shapes.md)
                        .background(c.accent)
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
                        tint = c.inverse,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
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

private const val DISABLED_ALPHA = 0.5f

/** `/find/file` round-trip guard: never leave a debounce coroutine hanging. */
private const val MENTION_SEARCH_TIMEOUT_MS = 4_000L
