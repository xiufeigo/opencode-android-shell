package com.xiufeigo.opencodeshell.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * Add-project directory browser (upstream `dialog-select-directory`, opened by
 * `PromptProjectController.add` / `home.model`'s add-project action).
 *
 * Hosted in the global dialog stack ([com.xiufeigo.opencodeshell.ui.overlay.DialogHost]).
 * The initial path is the server cwd (`GET /path`); rows are the subdirectories
 * of the current path (`GET /file?path=`, directories only — files and
 * git-ignored entries are hidden), a row enters it, the chevron goes to the
 * parent ([HomeProjects.parentOf]). The footer always shows the current
 * absolute path; 「选择此目录」 hands it to [onSelect] without requiring the
 * server to know it as a project — `POST /session?directory=` discovers it.
 */
@Composable
fun DirectoryPickerDialog(
    chatViewModel: ChatViewModel,
    /** Starting path; null resolves to the server cwd (`GET /path`). */
    initialPath: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var currentPath by remember { mutableStateOf(initialPath?.trim().orEmpty()) }
    var nodes by remember { mutableStateOf<List<OpenCodeClient.FileNode>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    // No starting directory from the caller: fall back to the server cwd.
    LaunchedEffect(Unit) {
        if (currentPath.isEmpty()) {
            chatViewModel.serverDirectory { currentPath = it.orEmpty() }
        }
    }
    LaunchedEffect(currentPath, attempt) {
        if (currentPath.isEmpty()) return@LaunchedEffect
        loading = true
        failed = false
        chatViewModel.listFiles(currentPath) { result ->
            loading = false
            failed = result == null
            nodes = result
                ?.filter { it.directory && !it.ignored }
                ?.sortedBy { it.name.lowercase() }
        }
    }

    Column(
        modifier = modifier
            .widthIn(max = DIALOG_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(OcTheme.shapes.xl)
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.xl)
            .padding(16.dp),
    ) {
        // Header: title + close.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.oc_home_projects_choose_title),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = stringResource(R.string.oc_back),
                    tint = c.iconMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Current path + parent.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val parent = HomeProjects.parentOf(currentPath)
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(OcTheme.shapes.md)
                    .clickable(enabled = parent != null, role = Role.Button) {
                        if (parent != null) currentPath = parent
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_chevron_down),
                    contentDescription = stringResource(R.string.oc_home_projects_parent),
                    tint = if (parent != null) c.iconMuted else c.borderStrong,
                    modifier = Modifier.size(14.dp).rotate(180f),
                )
            }
            Text(
                text = currentPath.ifEmpty { stringResource(R.string.oc_home_projects_loading) },
                color = c.textMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        // Subdirectory list.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .heightIn(min = LIST_MIN_HEIGHT, max = LIST_MAX_HEIGHT)
                .clip(OcTheme.shapes.md)
                .background(c.layer01),
        ) {
            val directories = nodes.orEmpty()
            when {
                loading || currentPath.isEmpty() -> DialogHint(
                    text = stringResource(R.string.oc_home_projects_loading),
                    modifier = Modifier.align(Alignment.Center),
                )

                failed -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogHint(text = stringResource(R.string.oc_newsession_create_failed))
                    NeutralButton(
                        label = stringResource(R.string.retry),
                        iconRes = R.drawable.ic_oc_edit,
                        onClick = { attempt += 1 },
                    )
                }

                directories.isEmpty() -> DialogHint(
                    text = stringResource(R.string.oc_home_projects_empty_dir),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    items(directories, key = { it.absolute }) { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT)
                                .clip(OcTheme.shapes.md)
                                .clickable(role = Role.Button) { currentPath = node.absolute }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_oc_folder),
                                contentDescription = null,
                                tint = c.iconMuted,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = node.name.ifEmpty { node.absolute },
                                color = c.textBase,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Footer: current absolute path + the pick action.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = currentPath,
                color = c.textFaint,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(OcTheme.shapes.md)
                    .background(c.accent)
                    .clickable(
                        enabled = currentPath.isNotEmpty(),
                        role = Role.Button,
                        onClick = { onSelect(currentPath) },
                    )
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.oc_home_projects_select_directory),
                    color = c.inverse,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DialogHint(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = OcTheme.colors.textFaint, fontSize = 12.sp, modifier = modifier)
}

private val DIALOG_WIDTH = 480.dp
private val LIST_MIN_HEIGHT = 200.dp
private val LIST_MAX_HEIGHT = 320.dp
private val ROW_HEIGHT = 32.dp
