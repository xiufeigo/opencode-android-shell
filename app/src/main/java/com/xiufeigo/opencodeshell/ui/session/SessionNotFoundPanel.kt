package com.xiufeigo.opencodeshell.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.OcButton
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.SessionOpenFailure

/**
 * The route-level "session not found" page — the Compose port of upstream
 * `SessionErrorFallback`'s not-found branch
 * (`_upstream/packages/app/src/session/route-error.tsx:31-61`).
 *
 * Upstream shape, top to bottom:
 * - `session.error.notFound` (16 px / 500 / `text`) over
 *   `session.error.notFound.description` (13 px / `text-weak`), both centred and
 *   capped at `max-w-md`;
 * - the display server name (11 px / `text-faint`) above the session id in a
 *   `font-mono` chip whose fill is `color-mix(text-base 8%, transparent)` —
 *   rendered only when the route has an id. Both lines are masked on this shell
 *   (v63 BUG-1): the page is the one screen a user screenshots into a bug
 *   report, and the raw lines carried the internal id and the server address.
 * - one `Button variant="neutral" icon="xmark-small"` labelled
 *   `session.error.notFound.closeTab`.
 *
 * Two Android adaptations, both deliberate:
 * - the close-tab button is the nav **back** action (this shell has no tab bar
 *   in the web sense; leaving the destination is what "close tab" does), and it
 *   carries 「返回」's own copy from [R.string.oc_back];
 * - a second action, 「重试」, re-enters the same session. Upstream offers no
 *   retry here because its route re-reads on every navigation; on this shell the
 *   404 latch (`ChatViewModel.sessionMissing`) also stops every session-scoped
 *   refresh, so without a retry the user has no way back into a session the
 *   server only *temporarily* failed to answer for.
 *
 * v81 P2 「幽灵会话死胡同」 (`research/v81_verify/5558/t4_s02_empty_session.png`):
 * a third action, [R.string.oc_session_not_found_delete], drops the **entry**.
 * Back and Retry were the whole exit set, and both leave the thing the user is
 * stuck with in place: Back returns to Home/another tab with the dead row (and
 * its tab) still listed, and Retry re-runs the same reads.
 *
 * v83 P1 (`research/v83_verify/5558/25_after_delete.png` →
 * `26_list_after_del.png`): the exit **asks the server first**
 * ([SessionDeleteHost.deleteEntry]) and forgets the entry only once the server
 * has agreed (`204`) or said it never had it (`404`). v81 made it local-only on
 * the theory that a `DELETE` for a session the server has forgotten answers
 * `404`; the measured `REFUSED` entry (the missing-project-directory session)
 * answers `204` and really goes away, so the local-only version closed the tab
 * over a row that stayed in Home and a session that stayed on the server. A
 * refusal paints [entryDeleteError] on the page — the one surface that is up —
 * and the same button is the retry ([entryDeleteBusy] locks it meanwhile).
 *
 * The whole screen scrolls: a long server url + a long id must not clip the
 * buttons off a short window.
 *
 * v82 BUG-A (`research/v82_verify/5558/33_ghost_open.png`): the page also serves
 * a session the server **refuses** — its own scoped routes answer `5xx` while
 * the row and the message list answer `200`, so the app used to draw a
 * normal-looking empty session whose every action failed (`ChatViewModel
 * .sessionMissingReason` carries the verdict: [SessionOpenFailure]). Only the
 * copy moves: the same three exits are exactly what that dead end needs.
 */
@Composable
fun SessionNotFoundPanel(
    sessionID: String,
    serverLabel: String?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Which route-level failure this is ([SessionOpenFailure]). `GONE` (the
     * default, and the only shape this page had until v82) is the 404 copy;
     * `REFUSED` is the 5xx copy — same actions, different cause.
     */
    reason: SessionOpenFailure = SessionOpenFailure.GONE,
    /**
     * v83 P1: the entry delete's `DELETE` is out — the exit is locked meanwhile,
     * so a second tap cannot stack a second write.
     */
    entryDeleteBusy: Boolean = false,
    /**
     * v83 P1: the `DELETE` did not land. Shown on the page itself, because this
     * is the only surface up when the user asks — the transient-toast lesson of
     * v82 (`com.xiufeigo.opencodeshell.ui.renameFailureText`) applied to the
     * exit that used to be silent.
     */
    entryDeleteError: String? = null,
) {
    val c = OcTheme.colors
    val facts = remember(sessionID, serverLabel) { sessionNotFoundFacts(sessionID, serverLabel) }
    val titleRes = when (reason) {
        SessionOpenFailure.GONE -> R.string.oc_session_not_found
        SessionOpenFailure.REFUSED -> R.string.oc_session_refused
    }
    val descriptionRes = when (reason) {
        SessionOpenFailure.GONE -> R.string.oc_session_not_found_description
        SessionOpenFailure.REFUSED -> R.string.oc_session_refused_description
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bgBase)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(titleRes),
            color = c.textBase,
            fontSize = 16.sp,
            fontWeight = FontWeight.W500,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Text(
            text = stringResource(descriptionRes),
            color = c.textMuted,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp),
        )
        // `Show when={props.sessionID}` — the id block only exists with an id.
        if (facts.sessionID.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                facts.serverLabel?.let { label ->
                    Text(
                        text = label,
                        color = c.textFaint,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                }
                Text(
                    text = facts.sessionID,
                    color = c.textBase,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W500,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(c.layer02, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // `Button variant="neutral"` — the close-tab half, wired to back.
            OcButton(
                label = stringResource(R.string.oc_back),
                primary = false,
                onClick = onBack,
            )
            OcButton(
                label = stringResource(R.string.oc_session_not_found_retry),
                primary = false,
                onClick = onRetry,
            )
            // v81 P2: the entry-level exit. Not `primary` — it is the way out of
            // a dead end, not the page's main action (upstream's own close-tab
            // button is the neutral one). v83 P1: it writes, so it locks while
            // that write is out.
            OcButton(
                label = stringResource(R.string.oc_session_not_found_delete),
                primary = false,
                enabled = !entryDeleteBusy,
                onClick = onDelete,
            )
        }
        // v83 P1: the refused `DELETE`, said where the user asked for it. No
        // toast: the whole point is that this sentence waits for the reader.
        if (entryDeleteError != null) {
            Text(
                text = entryDeleteError,
                color = c.stateDangerFg,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 320.dp),
            )
        }
    }
}

/**
 * The two data lines of [SessionNotFoundPanel], normalised once so the
 * composable never renders a blank row: a blank/absent server url hides the
 * server line (upstream's `displayServer()` memo returns the key it was given,
 * and this shell has no connection *name* — the url is the only label it has),
 * and a blank id hides the whole id block.
 *
 * Pure, so the mapping is covered by a JVM test.
 */
internal data class SessionNotFoundFacts(val serverLabel: String?, val sessionID: String)

/**
 * v63 BUG-1 (`research/v63_verify/5556/92_final.png`): the page rendered the
 * two lines verbatim, so a screenshot of it handed out the session's internal
 * id (`ses_…`) and the server's own address (`http://127.0.0.1:4095`) — the two
 * things a user copying this page into a bug report most needs *not* to leak.
 * Both are masked now; the page still says which session and which server, just
 * not in a form that identifies either.
 *
 * - a server label that is a URL keeps only its scheme and renders the rest as
 *   a redaction marker — enough to say "https, not ssh" and useless as an
 *   address. A label that is *not* a URL is a display name, not an address, and
 *   passes through untouched (upstream's `displayServer()` renders a name);
 * - the id keeps its first [ID_PREFIX] and last [ID_SUFFIX] characters, so a
 *   user can still match it against their own list of sessions.
 */
internal fun sessionNotFoundFacts(sessionID: String, serverLabel: String?): SessionNotFoundFacts =
    SessionNotFoundFacts(
        serverLabel = serverLabel?.trim()?.takeIf { it.isNotEmpty() }?.let(::maskServerLabel),
        sessionID = maskSessionID(sessionID.trim()),
    )

private const val ID_PREFIX = 6
private const val ID_SUFFIX = 4

internal fun maskSessionID(id: String): String = when {
    id.isEmpty() -> ""
    id.length <= ID_PREFIX + ID_SUFFIX -> "*".repeat(id.length)
    else -> id.take(ID_PREFIX) + "…" + id.takeLast(ID_SUFFIX)
}

internal fun maskServerLabel(label: String): String {
    val scheme = label.takeWhile { it != ':' }.takeIf { scheme ->
        scheme.isNotEmpty() && label.startsWith("$scheme://") && scheme.all { it.isLetter() }
    } ?: return label
    return "$scheme://***"
}
