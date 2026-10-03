package com.xiufeigo.opencodeshell.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocShadow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「Edit project」 — the Android host of upstream `SettingsProjectGeneral`
 * (`settings/workspaces/project.tsx`, opened from the project row menu via
 * `home/projects/controller.tsx:135-138`). Name / icon / worktree startup
 * script, saved as one [HomeProjectEdit.Patch] through
 * [HomeProjectApi.save] (`PATCH /api/project/{id}`) or, when the worktree has
 * no server project, [HomeProjectApi.saveLocal] (the client-local
 * `sync.project.meta` of `project-model.ts:41-46`) — the fork
 * [HomeProjectEdit.writeTargetFor] makes, and the only thing that differs
 * between the two. The panel itself is never disabled because of it: upstream
 * carries no `disabled`/`readOnly` on this page at all, and a worktree without
 * a `project.id` is a normal, fully editable project there.
 *
 * The icon is upstream's two controls: the color swatches, and the custom
 * image — a hidden `file` input whose `accept` is the image mime family + a
 * drop target upstream, here one `ActivityResultContracts.GetContent()` pick
 * filtered to images ([ProjectIconImage] downsamples it into the `data:` URL
 * upstream's `FileReader` produces). Upstream hides the color row while an
 * override is set (`project.tsx:88` `<Show when={!model.store.iconOverride}>`),
 * and so does this dialog: 「清除图片」 is the way back to the color swatches.
 */
@Composable
fun HomeProjectEditDialog(
    api: HomeProjectApi,
    project: HomeProjects.Project,
    onClose: () -> Unit,
    /**
     * Fired on a successful save with the saved name (`null` when the name
     * field was unchanged, `""` when it was cleared back to the folder name):
     * the host renames the row immediately instead of waiting for the next
     * catalogue read.
     */
    onSaved: (String?) -> Unit = {},
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.xl
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var original by remember { mutableStateOf<HomeProjectEdit.Original?>(null) }
    var loadFault by remember { mutableStateOf<ProjectFault?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<HomeProjectEdit.Draft?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Upstream's `iconClick()` → hidden `input type="file" accept="image/*"`:
    // one SAF pick, downscaled and packed on IO. A pick that yields no usable
    // image (a non-image the filter let through, an unreadable document) toasts
    // instead of silently doing nothing; the draft keeps whatever it had.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { ProjectIconImage.read(context, uri) }
            if (dataUrl == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.oc_home_project_edit_icon_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                draft = draft?.copy(iconOverride = dataUrl)
            }
        }
    }

    LaunchedEffect(project.path, loadAttempt) {
        loaded = false
        loadFault = null
        original = null
        draft = null
        when (val result = withContext(Dispatchers.IO) { api.loadOriginal(project.path) }) {
            is ProjectLoad.Ready -> {
                original = result.original
                draft = HomeProjectEdit.draftOf(result.original)
            }
            is ProjectLoad.Failed -> loadFault = result.fault
        }
        loaded = true
    }

    val scrim = rememberDialogScrimState()
    Box(modifier = Modifier.fillMaxSize()) {
        DialogScrim(scrim, onClose)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .clip(shape)
                .background(c.bgBase)
                .border(0.5.dp, c.borderBase, shape)
                .ocShadow(OcTheme.elevation.floating, shape)
                // Measured *before* the inner padding so the whole card counts
                // as "inside": a tap on the card's own 16 dp margin is a tap on
                // the dialog, not on the backdrop. Before the scroll modifier,
                // so the bounds stay the *visible* card while the body scrolls.
                .dialogCardBounds(scrim)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.oc_home_project_edit),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 21.sp,
            )
            // v52 tD: the row menu opens the editor by project **name**, and two
            // projects can share one (`/home/xiufeigo/v36projC` beside
            // `…/opencode-android-shell/v36projC`). Without the worktree the
            // dialog could not say which row it is editing, and the user had no
            // way to tell 「改名」 / 「删除」 would hit which one. The full path is
            // the one thing that always tells them apart.
            val worktree = project.path.trim()
            if (worktree.isNotEmpty()) {
                Text(
                    text = worktree,
                    color = c.textFaint,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val currentDraft = draft
            val currentFault = loadFault
            when {
                !loaded -> Text(
                    text = stringResource(R.string.oc_common_loading),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )
                currentFault != null -> {
                    Text(
                        text = stringResource(R.string.oc_home_project_edit_load_failed),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        text = projectFaultText(context, currentFault),
                        color = c.textFaint,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        EditDialogButton(
                            label = stringResource(R.string.retry),
                            onClick = { loadAttempt += 1 },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
                currentDraft == null -> Text(
                    text = stringResource(R.string.oc_home_project_edit_load_failed),
                    color = c.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                else -> {
                    val state = original
                    // Upstream has **no** `disabled` on this panel at all
                    // (`settings/workspaces/project.tsx` greps clean); the only
                    // gate on the edit path is the write in flight
                    // (`project-row.tsx:103` `disabled={store.editor?.saving}`).
                    // A missing `project.id` is a transport fork upstream, not
                    // an availability one (`project-model.ts:41-46`): the same
                    // three fields stay editable and land in the client-local
                    // store. Gating them on the id is what made this dialog
                    // completely dead for every unregistered worktree.
                    val editable = state != null && !saving
                    EditFields(
                        draft = currentDraft,
                        folderName = state?.folderName.orEmpty(),
                        editable = editable,
                        onPickImage = { pickImage.launch("image/*") },
                        onChange = { draft = it },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                EditDialogButton(
                    label = stringResource(R.string.oc_common_cancel),
                    onClick = onClose,
                )
                Spacer(Modifier.width(8.dp))
                val state = original
                val currentPatch = draft?.let { d -> state?.let { HomeProjectEdit.patchFor(it, d) } }
                EditDialogButton(
                    label = stringResource(R.string.oc_common_save),
                    // Upstream has no disabled-on-no-id rule: the only thing
                    // that can turn Save off is "there is nothing to write" or
                    // "a write is already in flight".
                    enabled = currentPatch?.isEmpty == false && !saving,
                    onClick = {
                        val patch = currentPatch ?: return@EditDialogButton
                        saving = true
                        scope.launch {
                            // The transport fork, once, as a pure function
                            // (`project-model.ts:41-46`): a server project
                            // PATCHes, anything else writes the client-local
                            // meta. Both are a successful save, so both toast
                            // 「已保存」, fire [onSaved] and close.
                            val result = withContext(Dispatchers.IO) {
                                when (val target = HomeProjectEdit.writeTargetFor(state?.projectID)) {
                                    is WriteTarget.Server -> api.save(target.projectID, patch)
                                    WriteTarget.Local -> api.saveLocal(project.path, patch)
                                }
                            }
                            saving = false
                            if (result is ProjectWrite.Ok) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.oc_home_project_edit_saved),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onSaved(patch.name)
                                onClose()
                            } else {
                                val detail = (result as? ProjectWrite.Failed)
                                    ?.let { projectFaultText(context, it.fault) }
                                    ?: context.getString(R.string.common_requestFailed)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.oc_home_project_edit_save_failed, detail),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Name / icon / startup — `SettingsProjectGeneral`'s three rows. */
@Composable
private fun EditFields(
    draft: HomeProjectEdit.Draft,
    folderName: String,
    editable: Boolean,
    onPickImage: () -> Unit,
    onChange: (HomeProjectEdit.Draft) -> Unit,
) {
    val c = OcTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        FieldLabel(R.string.oc_home_project_edit_name)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(c.layer02.copy(alpha = 0.6f), OcTheme.shapes.md)
                .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = draft.name,
                onValueChange = { onChange(draft.copy(name = it)) },
                enabled = editable,
                singleLine = true,
                textStyle = TextStyle(color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight(440)),
                cursorBrush = SolidColor(c.textAccent),
                // The project name is persisted verbatim and shown in the home
                // list; Gboard's autocorrect rewrote it as it was typed.
                keyboardOptions = OcKeyboard.literal(),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (draft.name.isEmpty()) {
                        Text(folderName, color = c.textFaint, fontSize = 13.sp, maxLines = 1)
                    }
                    inner()
                },
            )
        }

        Spacer(Modifier.height(12.dp))
        // Upstream's icon row: the live `ProjectIcon` preview + the image
        // controls, then (only while no image is set) the color swatches.
        FieldLabel(R.string.dialog_project_edit_icon)
        // Upstream's one `iconClick()` button (`project-model.ts:125-132`) is
        // both the picker and the clearer — hover swaps the overlay glyph
        // between `share` and `close` (`project.tsx:90-92`) to say which a tap
        // will do. Touch has no hover, so the fork moves onto the image
        // itself: an uploaded image is tapped to remove it, an empty slot to
        // pick one ([HomeProjectEdit.iconTap]). The 「清除图片」 button below
        // stays — it is the labelled, discoverable form of the same branch,
        // and on a phone an unlabelled ✕ on a 32 dp avatar is not a control
        // anyone can find.
        val clearIconLabel = stringResource(R.string.oc_home_project_edit_icon_clear)
        when (HomeProjectEdit.iconTap(draft.iconOverride, editable)) {
            IconAction.Clear -> Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(OcTheme.shapes.sm)
                    .clickable(role = Role.Button, onClick = { onChange(HomeProjectEdit.draftWithoutIcon(draft)) })
                    .semantics { contentDescription = clearIconLabel },
            ) {
                ProjectIconView(
                    name = draft.name.trim().ifEmpty { folderName },
                    iconColor = draft.color,
                    override = draft.iconOverride,
                    size = 32.dp,
                )
            }
            // Without an image the tap would do the same thing the adjacent
            // 「选择图片」 button does, so the slot stays inert rather than
            // becoming a second, unlabelled picker.
            else -> ProjectIconView(
                name = draft.name.trim().ifEmpty { folderName },
                iconColor = draft.color,
                override = draft.iconOverride,
                size = 32.dp,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditDialogButton(
                label = stringResource(R.string.oc_home_project_edit_icon_pick),
                enabled = editable,
                onClick = onPickImage,
            )
            if (draft.iconOverride != null) {
                EditDialogButton(
                    label = stringResource(R.string.oc_home_project_edit_icon_clear),
                    enabled = editable,
                    onClick = { onChange(HomeProjectEdit.draftWithoutIcon(draft)) },
                )
            }
        }
        Text(
            text = stringResource(R.string.dialog_project_edit_icon_recommended),
            color = c.textFaint,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (draft.iconOverride == null) {
            Spacer(Modifier.height(12.dp))
            FieldLabel(R.string.oc_home_project_edit_color)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tap the selected swatch to clear — `setColor(selected ? undefined : color)`.
                ColorSwatch(
                    color = null,
                    selected = draft.color == null,
                    enabled = editable,
                    onClick = { onChange(draft.copy(color = null)) },
                )
                HomeProjectEdit.COLORS.forEach { name ->
                    ColorSwatch(
                        color = name,
                        selected = draft.color == name,
                        enabled = editable,
                        onClick = { onChange(draft.copy(color = if (draft.color == name) null else name)) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        FieldLabel(R.string.oc_home_project_edit_startup)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .background(c.layer02.copy(alpha = 0.6f), OcTheme.shapes.md)
                .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                .padding(12.dp),
        ) {
            BasicTextField(
                value = draft.startup,
                onValueChange = { onChange(draft.copy(startup = it)) },
                enabled = editable,
                textStyle = TextStyle(
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(c.textAccent),
                // The startup line is a command the app persists and runs
                // verbatim; autocorrect rewrote `:` and flags as it was typed.
                keyboardOptions = OcKeyboard.literal(),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (draft.startup.isEmpty()) {
                        Text(
                            stringResource(R.string.oc_home_project_edit_startup_placeholder),
                            color = c.textFaint,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    inner()
                },
            )
        }
        Text(
            text = stringResource(R.string.oc_home_project_edit_startup_hint),
            color = c.textFaint,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        // `project.tsx:154-157`'s two hint lines, with `inlineVariables`
        // (`project.tsx:165-169`) marking each `$UPPER_SNAKE` run as <code>.
        // Unconditional, like upstream's: they name the two variables the
        // server exports into the command (`core/src/worktree.ts:244-245`),
        // which is the only place a user learns they exist.
        StartupVariableHint(stringResource(R.string.oc_home_project_edit_startup_hint_base))
        StartupVariableHint(stringResource(R.string.oc_home_project_edit_startup_hint_new))
    }
}

/**
 * One `project-settings-startup-hint` span — upstream renders the `$VAR` runs
 * through `inlineVariables` (`project.tsx:165-169`), which splits on
 * `/(\$[A-Z][A-Z0-9_]*)/g` and wraps the captured runs in `<code>`. The
 * monospace face is what makes the token readable inside a sentence, and it
 * is also what the startup field itself uses (`FontFamily.Monospace` above),
 * so the hint and the value it describes look like the same kind of thing.
 */
@Composable
private fun StartupVariableHint(text: String) {
    val c = OcTheme.colors
    Text(
        text = buildAnnotatedString {
            HomeProjectEdit.variableSegments(text).forEach { segment ->
                if (segment.variable) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = c.textMuted)) {
                        append(segment.text)
                    }
                } else {
                    append(segment.text)
                }
            }
        },
        color = c.textFaint,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun FieldLabel(resId: Int) {
    Text(
        text = stringResource(resId),
        color = OcTheme.colors.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * 20 dp rounded square in the swatch's tint; null = the neutral 「None」 chip.
 *
 * While a save is in flight the swatches grey out and swallow the tap, exactly
 * like upstream's swatch row during `saving` — there is nothing to say to the
 * user about a write that is already going out.
 */
@Composable
private fun ColorSwatch(
    color: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.sm
    val fill = HomeProjectEdit.colorHex(color)?.let { Color(it) } ?: c.layer02
    val label = if (color == null) {
        stringResource(R.string.oc_home_project_edit_color_none)
    } else {
        stringResource(R.string.oc_home_project_edit_color_select, color)
    }
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(shape)
            .background(fill)
            .then(if (color == null) Modifier.border(0.5.dp, c.borderMuted, shape) else Modifier)
            .then(
                if (selected) Modifier.border(1.dp, c.textAccent, shape) else Modifier,
            )
            .semantics { contentDescription = label }
            .clickable(
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) {
            Text("–", color = c.textMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** button-v2 metrics: h 28, radius 6, padding-x 12, 13/530 (same as [HomeDeleteDialog]'s). */
@Composable
private fun EditDialogButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(shape)
            .background(if (enabled) c.layer02 else Color.Transparent)
            .clickable(
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) c.textBase else c.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
            lineHeight = 20.sp,
        )
    }
}
