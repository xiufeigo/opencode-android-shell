package com.xiufeigo.opencodeshell.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.delay

/**
 * The `new-session` route (`Route.NEW_SESSION`, PLAN §4.1 "✎ → `/new-session`").
 *
 * Upstream this page is a draft editor (`DraftTab`, §9b.3) whose first send
 * creates the session. This shell has no draft model, so the route does the one
 * thing the brief asks for: `createSession()` and then hand off to
 * `Route.SESSION` — the draft entry is popped so Back from the new session lands
 * on Home rather than re-creating another session.
 *
 * Failure paths are visible on purpose: a missing server config surfaces the
 * shared setup hint immediately, and a POST that never answers (offline server)
 * grows a Retry after [SLOW_MS] instead of leaving a dead spinner.
 */
@Composable
fun NewSessionScreen(
    chatViewModel: ChatViewModel,
    onCreated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var attempt by remember { mutableStateOf(0) }
    var slow by remember { mutableStateOf(false) }
    val configMissing by chatViewModel.configMissing.collectAsState()

    // `ensureClient` first: a cold start can land directly on this route (route
    // restore / deep link) with no client built yet.
    LaunchedEffect(attempt) {
        slow = false
        if (chatViewModel.ensureClient()) {
            chatViewModel.createSession { id -> onCreated(id) }
        }
    }
    LaunchedEffect(attempt) {
        delay(SLOW_MS)
        slow = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgDeep)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.oc_home_creating),
                color = c.textMuted,
                fontSize = 13.sp,
            )
            if (configMissing) {
                Text(
                    text = stringResource(R.string.oc_setup_hint),
                    color = c.textMuted,
                    fontSize = 12.sp,
                )
            }
            if (configMissing || slow) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeutralButton(
                        label = stringResource(R.string.retry),
                        iconRes = R.drawable.ic_oc_edit,
                        onClick = { attempt += 1 },
                    )
                    Text(
                        text = stringResource(R.string.oc_back),
                        color = c.textMuted,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(OcTheme.shapes.md)
                            .clickable(role = Role.Button, onClick = onBack)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
    // System back always leaves the draft (the nav graph would pop anyway; this
    // makes the intent explicit and keeps Home's BackHandler ordering intact).
    BackHandler { onBack() }
}

/** After this long without a session, the draft offers Retry/Back. */
private const val SLOW_MS = 3_000L
