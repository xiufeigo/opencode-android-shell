package com.xiufeigo.opencodeshell.ui.session.btw

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.TextShimmer
import com.xiufeigo.opencodeshell.ui.markdown.OcMarkdown
import com.xiufeigo.opencodeshell.ui.overlay.DialogSize
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogController
import com.xiufeigo.opencodeshell.ui.overlay.LocalDialogScrimState
import com.xiufeigo.opencodeshell.ui.overlay.SessionComposerBounds
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.palette.LocalSessionCommandIntents
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The `/btw` side-question panel — upstream
 * `packages/app/src/session/btw/panel.tsx` (v2 head `8ce629b`) `SessionBtwPanel`,
 * `data-slot="session-btw-panel"`: a question header with a copy button, then
 * exactly one body — the `session.timeline.working` shimmer while pending, the
 * `session.btw.error` + retry pair on failure, or the answer as Markdown.
 *
 * Values in, layout out (like [com.xiufeigo.opencodeshell.ui.session.summary.SessionSummaryPanel]):
 * the composable never reaches into a view model. One deliberate Android
 * addition: a question field at the bottom. Upstream feeds the panel from the
 * hidden `/btw` slash command (`model.ts` `command.register`, `slash: "btw"`,
 * `slashArguments: true`); until `ChatViewModel`'s submit router can hand that
 * over (see the wiring note on [SessionBtwHost]), the field is the ask entry —
 * same interaction the web sidebar has, side-stepping the main thread.
 */
@Composable
fun SessionBtwPanel(
    question: String,
    answer: String,
    pending: Boolean,
    error: Boolean,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Why the ask failed, when the caller knows (device v14): `NO_MODEL` is the
     * local refusal of a session with no model — the panel then says what to do
     * about it instead of only that it failed. Null keeps the upstream copy.
     */
    reason: SessionBtwModel.Reason? = null,
) {
    val c = OcTheme.colors
    val clipboard = LocalClipboardManager.current
    // `panel.tsx` `createEffect`: a new answer resets the copy affordance.
    var copied by remember(answer) { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = BTW_PANEL_MAX_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, RoundedCornerShape(12.dp)),
    ) {
        // -------------------------------------------- question header + copy
        // `panel.tsx:36-53` — `px-5 py-4`, question `text-13-regular text-text-weak`
        // (→ `textMuted`), the copy icon button only once an answer exists.
        if (question.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, c.borderBase, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = question,
                    color = c.textMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (answer.isNotEmpty()) {
                    val copyLabel = if (copied) {
                        stringResource(R.string.common_copied)
                    } else {
                        stringResource(R.string.oc_btw_copy)
                    }
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) {
                                clipboard.setText(AnnotatedString(answer))
                                copied = true
                            }
                            .semantics { contentDescription = copyLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(if (copied) R.drawable.ic_oc_check else R.drawable.ic_oc_copy),
                            contentDescription = null,
                            tint = c.iconMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------- body states
        // `panel.tsx` `Switch`/`Match`: pending → error → answer, one of the three.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BTW_BODY_MIN_HEIGHT),
        ) {
            when {
                pending -> {
                    // `panel.tsx` `data-component="session-working"` — `h-9 px-5 pt-3`,
                    // 13px/530 shimmer of `session.timeline.working`.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .padding(start = 20.dp, top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextShimmer(
                            text = stringResource(R.string.session_timeline_working),
                            active = true,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(530),
                        )
                    }
                }
                error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.oc_btw_error),
                            color = c.textMuted,
                            fontSize = 13.sp,
                        )
                        if (reason == SessionBtwModel.Reason.NO_MODEL) {
                            Text(
                                text = stringResource(R.string.oc_btw_no_model),
                                color = c.textMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        BtwButton(
                            label = stringResource(R.string.oc_btw_retry),
                            onClick = onRetry,
                        )
                    }
                }
                answer.isNotEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = BTW_ANSWER_MAX_HEIGHT)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
                    ) {
                        OcMarkdown(markdown = answer, fontSize = 14.sp)
                    }
                }
                else -> {
                    // Idle empty state (no upstream equivalent — the web panel only
                    // mounts after an ask): the command's own title/description.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.oc_btw_ask),
                            color = c.textBase,
                            fontSize = 13.sp,
                            fontWeight = FontWeight(530),
                        )
                        Text(
                            text = stringResource(R.string.oc_btw_description),
                            color = c.textMuted,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // ----------------------------------------------- question field (Android)
        // The local stand-in for the `/btw` slash arguments: Enter or the send
        // button submits. Blank submits are ignored (the button is disabled) —
        // the `questionRequired` toast stays on the host for the slash entry.
        BtwQuestionField(
            onAsk = onAsk,
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, c.borderBase, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
        )
    }
}

/** One-line question field + send button (`ic_oc_send`), auto-focused on mount. */
@Composable
private fun BtwQuestionField(
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        val question = SessionBtwModel.normalize(value)
        if (question != null) {
            value = ""
            onAsk(question)
        }
    }

    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp),
            cursorBrush = SolidColor(c.textBase),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier
                .weight(1f)
                .clip(OcTheme.shapes.md)
                .background(c.layer02)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.oc_btw_ask),
                        color = c.textMuted,
                        fontSize = 13.sp,
                    )
                }
                inner()
            },
        )
        // Hoisted: `semantics {}` is not a composable scope. The label rides the
        // clickable Box, not the `Icon` inside it — only the node carrying
        // ACTION_CLICK is announced as a button.
        val askLabel = stringResource(R.string.oc_btw_ask)
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (value.isBlank()) c.layer03 else c.accent)
                .clickable(enabled = value.isNotBlank(), role = Role.Button, onClick = { submit() })
                .semantics { contentDescription = askLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_send),
                contentDescription = null,
                tint = if (value.isBlank()) c.iconMuted else c.textInverse,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
}

/** Upstream `Button size="small" variant="outline"` (the error state's Retry). */
@Composable
private fun BtwButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .height(32.dp)
            .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(500),
        )
    }
}

/**
 * The header entry: a trailing icon button that opens the panel
 * ([SessionBtwHost.openPanel]). Upstream has no header button — it reaches the
 * panel through the hidden `/btw` slash command
 * (`_upstream/packages/tui/src/feature-plugins/prompt/btw.tsx`: `id:
 * "session.aside"`, `slash: { name: "btw", arguments: true }`); this shell offers
 * it on every width because the panel is the feature's only entry here. Renders
 * nothing without a host.
 *
 * **The panel is a dialog layer, not a window anchored to this button** (device
 * v32). It used to be a focusable pop-up positioned 4 dp below the entry — and
 * this shell's session header is the **bottom** toolbar (`SessionScreen` stacks
 * timeline → composer → header → tab strip), so that window opened *downwards*
 * off the display: the platform clamped it back against the bottom edge (a card
 * hugging the bottom, over the composer, the header and the navigation bar), the
 * IME covered the whole panel, it carried no safe-area insets at all, and its
 * outside-touch dismissal made the answer vanish the moment the timeline was
 * scrolled. Upstream shows the answer in a **centered large modal dialog**
 * (`btw.tsx:71-75`: `context.ui.dialog.show(...)` and then
 * `context.ui.dialog.set({ size: "large", centered: true })`); a
 * [com.xiufeigo.opencodeshell.ui.overlay.DialogHost] layer is that dialog here —
 * in-window and centered, with the scrim owning the outside taps and dismissal
 * on scrim / BACK / Escape instead of on a stray scroll.
 *
 * It is also the consumer of the palette's `session.aside` row
 * ([com.xiufeigo.opencodeshell.ui.palette.SessionCommandIntents.asideRequest]):
 * the palette layer is composed above `SessionScreen`, so it reaches this host
 * through the intent channel instead of `LocalSessionBtwHost` — the same
 * question dialog, whether the trigger came from the keyboard or the header.
 */
@Composable
fun SessionBtwEntry(modifier: Modifier = Modifier) {
    val host = LocalSessionBtwHost.current ?: return
    // The palette's `session.aside` row, applied here (the channel's KDoc says
    // why it is not a composition local): a bare request (`question == null`)
    // opens the panel — its own field is upstream's `dialog.prompt` — and one
    // carrying words asks straight away, exactly like the composer's `/btw`.
    // Consumed once the host has it, so a tap that lands before this composable
    // is on screen is still delivered (`asideRequest` is state, not an event).
    val intents = LocalSessionCommandIntents.current
    LaunchedEffect(intents, host) {
        val channel = intents ?: return@LaunchedEffect
        channel.asideRequest.collect { request ->
            if (request != null) {
                val question = request.question
                if (question == null) host.openPanel() else host.ask(question)
                channel.consumeAside(request)
            }
        }
    }
    // The layer stack. Without a controller (a preview, or a host that never
    // mounted the layer host) there is nowhere to put the panel: the button stays
    // inert rather than opening a second window that can break the layout again.
    val dialog = LocalDialogController.current
    // One layer per open, pushed from an effect and not from the composition:
    // `show` mutates the layer stack and must not run on every frame, and keyed
    // on the flag alone, so recomposing the header never restarts it.
    LaunchedEffect(host.open) {
        val controller = dialog ?: return@LaunchedEffect
        if (!host.open) return@LaunchedEffect
        controller.show(DialogSize.LARGE) { SessionBtwLayer(host = host) }
    }
    val c = OcTheme.colors
    // Hoisted: `semantics {}` is not a composable scope (see the send Box above).
    val askLabel = stringResource(R.string.oc_btw_ask)

    Box(modifier) {
        Box(
            modifier = Modifier
                .size(BTW_ENTRY_SIZE)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = { host.openPanel() })
                .semantics { contentDescription = askLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_help),
                contentDescription = null,
                tint = if (host.open) c.iconAccent else c.iconBase,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The panel as a dialog layer.
 *
 * `WindowInsets.safeDrawing` is what keeps the card inside the status /
 * navigation / IME bars: a layer is a sibling of the navigation tree
 * (`ui/overlay/DialogHost.kt`), so it inherits none of the per-screen
 * `windowInsetsPadding` the destinations apply themselves — and the window this
 * replaced had no inset at all. The IME is part of that inset, so the field's
 * keyboard lifts the card instead of hiding it (device v32: the keyboard covered
 * the entire panel).
 *
 * The width is viewport-derived like every other dialog in the shell
 * (`CommandPalette` uses `minOf(viewportWidth - 24.dp, 640.dp)`): the 320 dp of
 * the old side-panel stand-in was the review tab's width, not a dialog's.
 *
 * The card also **reports its own bounds** to the layer's scrim
 * ([LocalDialogScrimState] → [dialogCardBounds], v71). The wrapper below carries
 * the safe-area padding, so it is taller than the card and the host's own
 * measurement of the layer's root counted the keyboard strip as part of the
 * card; the report is what makes the tap-outside test read the card alone.
 */
@Composable
private fun SessionBtwLayer(host: SessionBtwHost) {
    // The layer is removed after its exit transition, so this runs exactly once
    // per layer however it was dismissed — scrim tap, BACK, Escape, or a `show()`
    // that replaced it. Clearing the host's flag here is what keeps the entry's
    // active tint and the layer in step.
    DisposableEffect(host) { onDispose { host.dismiss() } }
    val viewportWidth = LocalConfiguration.current.screenWidthDp.dp
    // v71 (`research/v71_verify/5558/55-66`): without this report the whole
    // keyboard strip counts as the card, and the tap on the composer behind the
    // panel — the one the user aims at the screen the card covers — is consumed
    // by the scrim without dismissing anything.
    val scrim = LocalDialogScrimState.current
    // v73 (`research/v73_repro2`): the report alone is not enough on a *short*
    // window. With the keyboard up the centered card is laid out right down over
    // the composer (5556, 1366×768, measured off the screenshots: the card's box
    // y 124…259 × x 443…922 against the composer's y 230…334 × x 12…1353), so a tap
    // on the composer's strip fell inside the card's own (honest) box and the scrim
    // closed nothing — the tester's 「获焦但卡不关」, the desktop half of the v71
    // pair. The composer's box therefore rides the scrim's backdrop override: a tap
    // there belongs to the backdrop wherever the card's box reaches; the card's own
    // field and send button consume their taps first, so the card stays usable.
    val composerBox = SessionComposerBounds.bounds
    SideEffect {
        if (scrim != null) scrim.backdropBounds = composerBox
    }
    Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(
            Modifier
                .width(minOf(viewportWidth - 24.dp, BTW_DIALOG_MAX_WIDTH))
                .then(if (scrim != null) Modifier.dialogCardBounds(scrim) else Modifier),
        ) {
            SessionBtwPanel(
                question = host.state.question,
                answer = host.state.answer,
                pending = host.state.pending,
                error = host.state.error,
                reason = host.state.reason,
                onAsk = { host.ask(it) },
                onRetry = { host.retry() },
            )
        }
    }
}

/** 40 dp circular touch target around the 16 dp icon (the header's `HeaderIconButton`). */
private val BTW_ENTRY_SIZE = 40.dp

/** Widest the dialog gets; a phone viewport is narrower (`CommandPalette`'s rule). */
private val BTW_DIALOG_MAX_WIDTH = 480.dp

private val BTW_PANEL_MAX_HEIGHT = 420.dp

private val BTW_ANSWER_MAX_HEIGHT = 280.dp

private val BTW_BODY_MIN_HEIGHT = 72.dp
