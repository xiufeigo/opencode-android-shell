package com.xiufeigo.opencodeshell.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「创建工作树」 (`workspaces/create.ts`, `session.new.worktree.create`) as one
 * modal, shared by its three upstream entry points:
 *
 * | call site | upstream |
 * |---|---|
 * | `NewSessionScreen`'s workspace selector | `new-session/workspace/selector.tsx:152-163` |
 * | `SessionHeader`'s ⋮ menu (Move group) | `session/timeline/session-workspace-menu.tsx:103-127` |
 * | the palette's `workspace.new` | `shell/commands/palette.ts:40` |
 *
 * Upstream splits the flow in two — the selector's `create` stop plus its
 * branch list are a nested menu, and the worktree is only created when the
 * draft is submitted (`composer-adapter.ts:200-216`). This shell has no draft
 * model, so the two halves are one dialog: pick the branch to branch **from**
 * (upstream's `session.new.workspace.fromBranch` list, `selector.tsx:257-360`),
 * then create, and the caller receives the created directory.
 *
 * The preparing state is upstream's `session-preparing` view shrunk to its
 * copy: `session.new.worktree.creating` while the create is in flight
 * (`session/route.tsx:84-87`), `workspace.error.stillPreparing` once it has
 * been in flight for [WorktreeCreatePolicy.SLOW_MS], and — on failure —
 * `prompt.toast.worktreeCreateFailed.title` plus the server's own message with
 * Retry / Back (`composer-adapter.ts:210-216`).
 *
 * @param sourceDirectory the checkout the worktree is created from
 *   (upstream's `input.directory`); null ⇒ the most recently updated project.
 * @param serverStore a live handle on the server prefs. Null builds the same
 *   second read-only handle `NewSessionScreen` documents.
 * @param onCreated the created worktree's directory — the caller selects it,
 *   moves the session into it, or drafts there.
 * @param onCreatedFrom the same event with the branch the worktree was branched
 *   **from** (null = the project default). `POST /api/worktree` answers
 *   `Worktree.Info` = `{directory}` alone (`core/src/worktree/git.ts:19-21`),
 *   while the create is `git worktree add --detach <branch>`
 *   (`core/src/git.ts:654-658`) — so this is the only place the name is known.
 *   Defaults to [onCreated] for callers that do not keep a create record.
 */
@Composable
fun WorktreeCreateDialog(
    sourceDirectory: String?,
    onCreated: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    serverStore: ServerConfigStore? = null,
    onCreatedFrom: (String, String?) -> Unit = { directory, _ -> onCreated(directory) },
) {
    val c = OcTheme.colors
    val context = LocalContext.current
    val store = serverStore ?: remember(context) {
        ServerConfigStore(context).also { it.restore() }
    }
    var source by remember { mutableStateOf<WorktreeSource?>(null) }
    var query by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf<String?>(null) }
    var state by remember { mutableStateOf<WorktreeCreateState>(WorktreeCreateState.Idle) }
    var attempt by remember { mutableStateOf(0) }
    var slow by remember { mutableStateOf(false) }

    // The picker's two reads (`controller.ts:150-157`): the project behind the
    // source directory and its canonical checkout's branches.
    LaunchedEffect(store, sourceDirectory) {
        source = withContext(Dispatchers.IO) { loadWorktreeSource(store, sourceDirectory) }
    }

    LaunchedEffect(attempt, state) {
        if (state !is WorktreeCreateState.Preparing) return@LaunchedEffect
        slow = false
        val outcome = withContext(Dispatchers.IO) { createWorktree(store, sourceDirectory, branch) }
        when (outcome) {
            is WorktreeCreateOutcome.Created -> {
                state = WorktreeCreateModel.settle(outcome.directory)
                onCreatedFrom(outcome.directory, branch)
            }

            WorktreeCreateOutcome.NoProject ->
                state = WorktreeCreateModel.fail(WorktreeCreateFailure.NO_PROJECT, null)

            is WorktreeCreateOutcome.Failed ->
                state = WorktreeCreateModel.fail(WorktreeCreateFailure.REQUEST_FAILED, outcome.message)
        }
    }
    LaunchedEffect(attempt, state) {
        if (state !is WorktreeCreateState.Preparing) return@LaunchedEffect
        delay(WorktreeCreatePolicy.SLOW_MS)
        slow = true
    }

    val preparing = state is WorktreeCreateState.Preparing
    val failure = state as? WorktreeCreateState.Failed
    // A create the server has not answered for a while stops holding the user
    // hostage: Back (and the scrim) work again once `workspace.error.stillPreparing`
    // is up. The request itself is not cancelled on the server's side — exactly
    // upstream's hazard, which the phone makes visible instead of silent.
    val escapable = !preparing || slow
    val scrim = rememberDialogScrimState()
    val ready = source as? WorktreeSource.Ready
    val shown = remember(ready, query) {
        ready?.let {
            WorktreeCreateModel.branches(
                loaded = it.branches,
                loadedFor = it.canonical,
                projectRoot = it.canonical,
                current = it.current,
                search = query,
            )
        }.orEmpty()
    }

    Dialog(
        onDismissRequest = { if (escapable) onDismiss() },
        // The card carries its own width; the platform default would clip it on
        // a phone (same reason `DirectoryPickerDialog` is drawn by the shell's
        // own `DialogHost`).
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            // Scrim first, card second: the card is the later sibling, so its
            // own controls see their taps first and the backdrop only sees the
            // ones outside it (`SessionActionsDialogs.ModalCard`).
            DialogScrim(scrim, onDismiss = { if (escapable) onDismiss() })
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val shape = OcTheme.shapes.xl
                Column(
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .fillMaxWidth()
                        .widthIn(max = 360.dp)
                        .clip(shape)
                        .background(c.bgBase)
                        .border(0.5.dp, c.borderBase, shape)
                        .ocShadow(OcTheme.elevation.floating, shape)
                        .dialogCardBounds(scrim)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.workspace_new),
                        color = c.textBase,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 21.sp,
                    )

                    when (val loaded = source) {
                        null -> Text(
                            text = stringResource(R.string.oc_home_projects_loading),
                            color = c.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )

                        WorktreeSource.NoProject -> Text(
                            text = stringResource(R.string.common_requestFailed),
                            color = c.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )

                        is WorktreeSource.Failed -> Text(
                            text = WorktreeCreateModel.failureDetail(
                                loaded.message,
                                stringResource(R.string.common_requestFailed),
                            ),
                            color = c.textMuted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )

                        is WorktreeSource.Ready -> {
                            // Upstream's `session.new.workspace.fromBranch` chip, in
                            // its list form: every row below branches from this
                            // checkout (`create.ts:15` `from: project.canonical`).
                            Text(
                                text = stringResource(
                                    R.string.session_new_workspace_fromBranch,
                                    loaded.canonical,
                                ),
                                color = c.textFaint,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = OcTheme.monoFont,
                            )
                            if (shown.isNotEmpty()) {
                                BranchSearchField(
                                    value = query,
                                    enabled = !preparing,
                                    onValueChange = { query = it },
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = BRANCH_LIST_MAX_HEIGHT)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                if (shown.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.oc_new_session_workspace_search_empty),
                                        color = c.textMuted,
                                        fontSize = 13.sp,
                                        lineHeight = 20.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                                    )
                                }
                                shown.forEach { name ->
                                    BranchRow(
                                        label = name,
                                        checked = branch == name,
                                        enabled = !preparing,
                                        onClick = { branch = if (branch == name) null else name },
                                    )
                                }
                            }
                        }
                    }

                    // -------------------------------------- preparing / failure
                    if (preparing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_oc_plus),
                                contentDescription = null,
                                tint = c.iconMuted,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = stringResource(R.string.session_new_worktree_creating),
                                color = c.textMuted,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                            )
                        }
                        if (slow) {
                            Text(
                                text = stringResource(R.string.workspace_error_stillPreparing),
                                color = c.textFaint,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                    if (failure != null) {
                        Text(
                            text = stringResource(R.string.prompt_toast_worktreeCreateFailed_title),
                            color = c.textAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 20.sp,
                        )
                        Text(
                            text = WorktreeCreateModel.failureDetail(
                                failure.message,
                                stringResource(R.string.common_requestFailed),
                            ),
                            color = c.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }

                    // ---------------------------------------------------- buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogButton(
                            label = stringResource(R.string.oc_back),
                            enabled = escapable,
                            onClick = onDismiss,
                        )
                        Spacer(Modifier.width(8.dp))
                        if (failure != null) {
                            // A failed create keeps its branch selection and
                            // offers the same request again
                            // (`composer-adapter.ts:210-216` only toasts; the
                            // phone adds the retry because the dialog is the
                            // only surface holding the choice).
                            DialogButton(
                                label = stringResource(R.string.retry),
                                onClick = {
                                    state = WorktreeCreateModel.start()
                                    attempt += 1
                                },
                            )
                        } else {
                            DialogButton(
                                label = stringResource(R.string.session_new_worktree_create),
                                enabled = !preparing && ready != null,
                                onClick = { state = WorktreeCreateModel.start() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** `selector.tsx:296-338`'s inline branch search (magnifier + bare input). */
@Composable
private fun BranchSearchField(value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(c.layer02)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_search),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(12.dp),
        )
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.session_new_workspace_branch_search_placeholder),
                    color = c.textFaint,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight(440),
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.SansSerif,
                ),
                cursorBrush = SolidColor(c.textAccent),
                // Branch names are ASCII identifiers the dialog matches against
                // `git branch` output; autocorrect mangled the typed filter.
                keyboardOptions = OcKeyboard.identifier(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One `Menu.RadioItem` of the branch list (`selector.tsx:340-352`). */
@Composable
private fun BranchRow(label: String, checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 28.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_code),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_check),
                contentDescription = null,
                tint = c.iconBase,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** `button-v2` metrics of the shell's dialogs (h 28, radius 6, padding-x 12, 13/530). */
@Composable
private fun DialogButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) c.textBase else c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}

/** Eight branch rows fit a phone without scrolling; the rest scroll. */
private val BRANCH_LIST_MAX_HEIGHT = 224.dp
