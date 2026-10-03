package com.xiufeigo.opencodeshell.ui.session.timeline

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatStore
import com.xiufeigo.opencodeshell.ContextCompactionPolicy
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.session.actions.LocalSessionActionsHost
import com.xiufeigo.opencodeshell.ui.theme.OcMotion
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Session error card (`research/02` §2.2 `.error-card` + §3.3
 * `tool-error-card.css`), rendered as the trailing timeline row when
 * `runStatus == ERROR` or the session carries an error.
 *
 * Numbers taken from §3.3 / §2.2:
 * - card surface `layer-01`, `md` radius, `0.5 dp` `border-base`;
 * - vertical padding **8 dp**, line padding **12 dp**;
 * - error body indented **24 dp** on the left, `margin-bottom 8 dp`,
 *   `max-height: 240px` with its own scroll, `user-select: text`;
 * - `[tool-error-card-icon]` is coloured with the accent — here
 *   `stateDangerFg`, which is the danger accent for a session failure.
 *
 * Two upstream details are intentionally **not** copied: the hover-only copy
 * button (no hover on touch) and the "left colour bar" some earlier plans
 * imagined — §3.1 ruled that no left bar exists in this design system, so the
 * danger accent rides on the **icon** (documented again in the M2 status note).
 *
 * v32 BUG-1 residual: the summary line and the expanded body are **display**
 * text, so both pass through the server phrase table ([SERVER_ERROR_PHRASES])
 * and read in the UI locale. The card never rewrites what the store holds
 * (`ChatState.error` / `errorDetail` keep the server's own sentence), and the
 * table only ever swaps phrases the shell knows by name.
 */
@Composable
fun SessionErrorCard(
    message: String,
    /** Raw `session.error` JSON (`ChatState.errorDetail`): carries the `type` / `status` the human line drops. */
    detail: String? = null,
    modifier: Modifier = Modifier,
    /**
     * Stable identity of this failure, when the caller has one
     * ([TimelineRows.Row.Error.key] — the `error:<messageID>` `LazyColumn`
     * key). Null falls back to the card's own content ([errorCardStateKey]).
     */
    stateKey: String? = null,
) {
    val c = OcTheme.colors
    // Keyed by the failure's identity, not the Lazy slot: consecutive failures
    // reuse the same `ERROR_CARD_KEY` item, so an unkeyed toggle would leak
    // one error's expand state into the next (a fresh failure must start
    // collapsed, and tapping it must always open *its own* detail).
    //
    // The key is also what makes the state survive leaving and reopening the
    // session (device v11): the timeline — and this card's `rememberSaveable`
    // scope with it — is torn down with the screen, and the failure rows are
    // rebuilt from the same server messages, so a fresh composition lands on
    // the *same* key. [ErrorCardExpansion] holds those keys for the life of the
    // process, which is what the re-entry reads back.
    val expansionKey = remember(stateKey, message, detail) {
        errorCardStateKey(stateKey, message, detail)
    }
    var expanded by rememberSaveable(expansionKey) {
        mutableStateOf(ErrorCardExpansion.isExpanded(expansionKey))
    }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "error-chevron")
    // Failure text can be a wall of JSON: unwrap it first (`unwrapErrorMessage`,
    // `timeline/projection.ts:726-759`), so the header shows `{type: message}`
    // instead of the raw payload. The full raw body stays behind the chevron.
    val unwrapped = remember(message) { ToolText.unwrapErrorMessage(message) }
    val summary = remember(unwrapped) {
        unwrapped.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: unwrapped.trim()
    }
    // Provider-failure category: quota exhaustion and region blocks get their
    // own title + actionable advice instead of the generic card. Classification
    // never swallows — anything else keeps the generic title with the original
    // code/type in the summary and the full payload behind the chevron.
    val classified = remember(message, detail) { ChatStore.classifyStoredError(message, detail) }
    // Over-limit recovery (device v19 P2, `research/v19_verify/b/`): a full
    // context window reaches this card as a bare `provider.invalid-request`
    // (`to-session-error.ts:26-27` keeps only that type and drops the
    // `classification: "context-overflow"` `provider-error.ts:143-150` had
    // attached), so the ratio the ring paints is what identifies it — see
    // [contextOverflowActions] for the rule and why it needs both halves.
    //
    // The actions are taken from `LocalSessionActionsHost`, the same seam the
    // session ⋮ menu uses: `SessionScreen` calls this timeline with a fixed
    // argument list (owned elsewhere), so a new parameter could never reach it,
    // while the host is published by `ui/App.kt` around the whole session route.
    // A null host (preview, legacy chat tab) simply means "no recovery rows".
    val host = LocalSessionActionsHost.current
    val viewModel = host?.viewModel
    // Explicit `Flow` types: the nullable-`StateFlow` elvis needs an expected
    // type, or `emptyFlow()` would be inferred against `StateFlow<…>` itself.
    val contextTokensFlow: Flow<Long?> = remember(viewModel) {
        viewModel?.activeContextTokens ?: emptyFlow()
    }
    val contextLimitFlow: Flow<Int?> = remember(viewModel) {
        viewModel?.contextLimit ?: emptyFlow()
    }
    val contextTokens by contextTokensFlow.collectAsState(initial = null)
    val contextLimit by contextLimitFlow.collectAsState(initial = null)
    val overflowActions = remember(message, detail, contextTokens, contextLimit, host) {
        if (host == null) {
            emptyList()
        } else {
            contextOverflowActions(message, detail, contextTokens, contextLimit)
        }
    }
    val copy = errorCopy(classified, overflowActions.isNotEmpty())
    val titleRes = copy.titleRes
    val adviceRes = copy.adviceRes
    val fullBody = remember(message, detail) { errorCardBody(message, detail) }
    val hasBody = fullBody.isNotEmpty()
    // v32 BUG-1 residual: the server composes its failure sentences in English
    // and never sends a key, so the generic (`OTHER`) branch used to print the
    // raw English line under a localized title. The *displayed* half therefore
    // goes through the same phrase table the tool error card uses
    // ([SERVER_ERROR_PHRASES]); the store keeps the untouched payload
    // (`ChatState.error` / `errorDetail`) and the body stays selectable, so a bug
    // report still carries the server's own words. An English locale maps every
    // phrase onto itself, which [ToolText.localizeServerPhrases] skips — nothing
    // is rewritten for a reader of the source language.
    val phrases = serverErrorPhrases()
    val shownSummary = remember(summary, phrases) {
        ToolText.localizeServerPhrases(summary, phrases)
    }
    val shownBody = remember(fullBody, phrases) {
        ToolText.localizeServerPhrases(fullBody, phrases)
    }
    // One in-flight write at a time: the compact request owns its own flag (the
    // route answers as soon as the compaction is *admitted*, so the VM's revert
    // flag cannot describe it), while the undo half reads the VM's own
    // `reverting` — the same flag the per-message 撤销到此 buttons use.
    var compacting by remember(expansionKey) { mutableStateOf(false) }
    val revertingFlow: Flow<Boolean> = remember(viewModel) {
        viewModel?.reverting ?: emptyFlow()
    }
    val reverting by revertingFlow.collectAsState(initial = false)
    val recoveryBusy = compacting || reverting
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Row, not the chevron `Icon` inside it — only the node carrying
    // ACTION_CLICK is announced as a button.
    val detailsLabel = stringResource(R.string.oc_error_details)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OcTheme.shapes.md)
            .background(c.layer01)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = hasBody, role = Role.Button) {
                    expanded = !expanded
                    ErrorCardExpansion.setExpanded(expansionKey, expanded)
                }
                .semantics { contentDescription = detailsLabel }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_alert),
                contentDescription = null,
                tint = c.stateDangerFg,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(titleRes),
                color = c.textBase,
                fontSize = 14.sp,
                fontWeight = FontWeight.W500,
                maxLines = 1,
            )
            if (shownSummary.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = shownSummary,
                    color = c.textMuted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_oc_chevron_down),
                contentDescription = null,
                tint = c.textFaint,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }

        if (adviceRes != null) {
            Text(
                text = stringResource(adviceRes),
                color = c.textBase,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 8.dp),
            )
        }

        // The two recovery rows of an over-limit failure. Both are existing
        // session writes, not new wire surface: 「压缩会话」 is
        // `ChatViewModel.compactSession` (`POST /api/session/{id}/compact`, the
        // palette's own `session.compact`) and 「撤销本轮」 is
        // `ChatViewModel.undoSession` — the `session/revert` chain that rewinds
        // the boundary one user message, which is the escape that still works
        // when the window is already full: a compaction's *own* summary request
        // carries the same over-long history and can fail the same way (measured
        // v19), while dropping the turn removes it from the context outright.
        if (overflowActions.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 36.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                overflowActions.forEachIndexed { index, action ->
                    if (index > 0) Spacer(Modifier.width(8.dp))
                    OverflowRecoveryButton(
                        action = action,
                        enabled = !recoveryBusy,
                        onClick = {
                            when (action) {
                                ContextOverflowAction.COMPACT -> {
                                    compacting = true
                                    viewModel?.compactSession(onResult = { compacting = false })
                                }

                                ContextOverflowAction.REVERT -> viewModel?.undoSession()
                            }
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && hasBody,
            enter = expandVertically(animationSpec = OcMotion.expand) + fadeIn(OcMotion.fade),
            exit = shrinkVertically(animationSpec = OcMotion.expand) + fadeOut(OcMotion.fade),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp, bottom = 8.dp)
                    .heightIn(max = ERROR_BODY_MAX_HEIGHT)
                    .clip(OcTheme.shapes.md)
                    .background(c.layer02)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = shownBody,
                        color = c.stateDangerFg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/** `.error-card { max-height: 240px }` (`research/02` §2.2). */
private val ERROR_BODY_MAX_HEIGHT = 240.dp

/**
 * The expandable detail of [SessionErrorCard]: the human line plus the raw
 * `session.error` JSON (which carries the `type` / `status` the line drops),
 * de-duplicated when the line already embeds the payload. Blank halves are
 * dropped, so an expandable card (`isNotEmpty`) always has something to show
 * and a card with neither line nor detail is never expandable.
 */
internal fun errorCardBody(message: String, detail: String?): String =
    listOf(message.trim(), detail?.trim().orEmpty())
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n\n")

/**
 * The identity [SessionErrorCard] remembers its expand state under.
 *
 * A caller that has a stable row key hands it in: the timeline's failure rows
 * are rebuilt from the same server message on every snapshot
 * (`ChatStore.materializeMessageErrors`), so `error:<messageID>` names the same
 * failure before and after a session re-entry — and two failures, even two
 * identical ones, stay independent. The trailing status mirror has no row key
 * (it renders `ChatState.error` itself), so it falls back to its content: the
 * same failure text keeps its state across a re-entry, while a *new* failure
 * text starts collapsed, which is the behaviour the card had before this key
 * existed.
 *
 * Pure, so it is covered by a JVM test.
 */
internal fun errorCardStateKey(stateKey: String?, message: String, detail: String?): String =
    stateKey ?: "content:${message.length}:${message}\u0000${detail.orEmpty()}"

/**
 * Process-lifetime memory of the error cards the user expanded, keyed by
 * [errorCardStateKey].
 *
 * Why it is not just `rememberSaveable`: the state has to outlive the
 * timeline's own composition. Opening another session pops the session
 * destination, which disposes the screen — its `SaveableStateHolder` and every
 * `rememberSaveable` inside it go with it — so after a re-entry both failure
 * cards came back collapsed (device v11). The keys themselves are stable, so
 * the card reads its previous state back from here.
 *
 * Only the *expanded* cards are held ([MAX_ENTRIES], oldest first out), so a
 * long-lived process cannot accumulate one entry per failure it has ever seen,
 * and collapsing a card drops its entry. Not thread-safe on purpose: only the
 * composition thread reads and writes it.
 */
internal object ErrorCardExpansion {

    /** How many expanded cards are remembered; the oldest falls out past this. */
    const val MAX_ENTRIES = 16

    private val expanded = LinkedHashSet<String>()

    fun isExpanded(key: String): Boolean = key in expanded

    fun setExpanded(key: String, value: Boolean) {
        if (!value) {
            expanded.remove(key)
            return
        }
        // Re-inserting moves the key to the recent end, so eviction takes the
        // least recently expanded card.
        expanded.remove(key)
        expanded.add(key)
        while (expanded.size > MAX_ENTRIES) expanded.remove(expanded.first())
    }

    /** Test seam: the holder is process-wide, so a test must not leak into the next. */
    fun clear() {
        expanded.clear()
    }
}

// ------------------------------------------------- over-limit recovery (v19 P2)

/**
 * One recovery row of an over-limit error card. Both are *existing* session
 * writes — see the call site — so the card introduces no new wire surface.
 */
internal enum class ContextOverflowAction {
    /** `ChatViewModel.compactSession` → `POST /api/session/{id}/compact`. */
    COMPACT,

    /** `ChatViewModel.undoSession` → the `session/revert` chain, one user message back. */
    REVERT,
}

/**
 * Upstream's context-overflow phrases
 * (`_upstream/packages/ai/src/provider-error.ts:16-44`), ported as the subset a
 * provider's own text is likely to carry to a client. Upstream matches these
 * against the failure message *and* the raw body, which is why
 * [contextOverflowActions] scans the card's detail payload too — a provider that
 * nests the phrase inside its JSON error body must still classify.
 *
 * A phrase is affirmative evidence on its own: it needs no ratio, because the
 * provider has already named the problem.
 */
private val OVERFLOW_PATTERNS = listOf(
    Regex("prompt is too long", RegexOption.IGNORE_CASE),
    Regex("input is too long for requested model", RegexOption.IGNORE_CASE),
    Regex("exceeds the context window", RegexOption.IGNORE_CASE),
    Regex("exceeds (?:the )?(?:model'?s )?maximum context length", RegexOption.IGNORE_CASE),
    Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE),
    Regex("maximum prompt length is \\d+", RegexOption.IGNORE_CASE),
    Regex("maximum context length is \\d+ tokens", RegexOption.IGNORE_CASE),
    Regex("reduce the length of the messages", RegexOption.IGNORE_CASE),
    Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE),
    Regex("context window exceeds limit", RegexOption.IGNORE_CASE),
    Regex("exceeded model token limit", RegexOption.IGNORE_CASE),
    Regex("greater than the context length", RegexOption.IGNORE_CASE),
    Regex("too large for model with \\d+ maximum context length", RegexOption.IGNORE_CASE),
    Regex("too many tokens", RegexOption.IGNORE_CASE),
    Regex("token limit exceeded", RegexOption.IGNORE_CASE),
    Regex("request_too_large", RegexOption.IGNORE_CASE),
)

/**
 * Upstream's exclusions for the phrase scan (`provider-error.ts:48`): a throttle
 * that happens to mention tokens is not an overflow, and its own advice (quota /
 * retry) is the right one.
 */
private val OVERFLOW_EXCLUSIONS = listOf(
    Regex("rate limit", RegexOption.IGNORE_CASE),
    Regex("too many requests", RegexOption.IGNORE_CASE),
    Regex("^(?:throttling error|service unavailable):", RegexOption.IGNORE_CASE),
)

/**
 * The server discriminators that mean "this request was rejected deterministically
 * and is not retryable". `provider.invalid-request` is what a context overflow
 * arrives as (`to-session-error.ts:26-27`); the other two are the same rejection
 * seen through the prompt-POST path's `HTTP 400 [InvalidRequestError]` line
 * (`V2Api.errorText`) and the provider's own code.
 */
private val INVALID_REQUEST_TYPES = setOf(
    "provider.invalid-request",
    "invalidrequesterror",
    "invalid_request_error",
)

/** True when [text] carries one of upstream's overflow phrases and no exclusion. */
internal fun isContextOverflowText(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    if (OVERFLOW_EXCLUSIONS.any { it.containsMatchIn(text) }) return false
    return OVERFLOW_PATTERNS.any { it.containsMatchIn(text) }
}

/**
 * The recovery rows an over-limit failure offers — the pure core of the card's
 * actionable state, so the decision is JVM-testable without a composition.
 *
 * The rule, and why it takes four inputs:
 *
 * 1. quota / region failures keep their own advice — a 429 that happens to fire
 *    while the window is full is still a 429;
 * 2. an overflow *phrase* in [message] or [detail] is enough on its own
 *    ([isContextOverflowText]);
 * 3. otherwise the failure must be a deterministic invalid-request **and** the
 *    session must actually be over the threshold
 *    ([ContextCompactionPolicy.overThreshold], the same ratio the automatic
 *    trigger uses). `provider.invalid-request` alone is too weak a signal — a
 *    malformed tool schema produces it too — and the ratio is what makes the
 *    v19 case (a generic "The request contains invalid parameters" at
 *    `input=643K` + `cache_read=1.3M` against a 1M window) identifiable at all,
 *    since the server strips the `context-overflow` classification before the
 *    error is stored.
 *
 * Either ratio half unknown ⇒ the third branch cannot fire, exactly as it cannot
 * fire the automatic trigger: an unknown usage never fabricates a diagnosis.
 */
internal fun contextOverflowActions(
    message: String?,
    detail: String?,
    tokens: Long?,
    limitContext: Int?,
    ratio: Double = ContextCompactionPolicy.DEFAULT_RATIO,
): List<ContextOverflowAction> {
    val classified = ChatStore.classifyStoredError(message, detail)
    if (classified.kind != ChatStore.ProviderErrorKind.OTHER) return emptyList()
    val text = listOfNotNull(message, detail)
        .filter { it.isNotBlank() }
        .joinToString("\n")
    val phrase = isContextOverflowText(text)
    val invalidRequest = classified.type?.lowercase() in INVALID_REQUEST_TYPES
    val overLimit = ContextCompactionPolicy.overThreshold(tokens, limitContext, ratio)
    return if (phrase || (invalidRequest && overLimit)) {
        listOf(ContextOverflowAction.COMPACT, ContextOverflowAction.REVERT)
    } else {
        emptyList()
    }
}

/**
 * The title/advice pair one failure renders with — the **copy** half of the
 * diagnosis, in one place so the Compose `SessionErrorCard` and the legacy
 * `ChatView` banner cannot call the same failure by two different names
 * (v21 P3: the legacy banner said 「出错了」 + the raw provider line for a full
 * context window while the card said 「上下文超限」 and told the user what to do
 * about it).
 *
 * [overflow] is the caller's own [contextOverflowActions] verdict rather than a
 * re-derivation: the card needs the action list anyway, and the host gate
 * (`LocalSessionActionsHost == null`, i.e. a preview) belongs to that caller —
 * the same gate must move the copy with it. [classified] is the
 * [ChatStore.classifyStoredError] result the caller already holds.
 *
 * quota / region keep their own advice: a 429 that fires while the window is
 * full is still a 429 ([contextOverflowActions] returns nothing for either
 * kind), so the overflow branch can only describe an `OTHER` failure. The
 * generic branch has no advice — the raw line is all there is to say.
 *
 * v31 BUG-3: a **deleted project directory** gets its own title and advice
 * (`error.locationMissing.*`), because 「HTTP 500」 says nothing about the
 * directory being gone. Upstream names the same failure with
 * `session.location.unavailable` / `session.location.description`
 * (`tui/src/routes/session/location-missing.tsx`, shown when `currentLocation`
 * errors out — the very `realPath` failure classified here); the Android copy is
 * the actionable version of it, and like every other string in this ladder it
 * comes from the v2 dictionaries, so all three locales carry it.
 *
 * Pure (no `stringResource`), so the split stays unit-testable.
 */
internal data class ErrorCopy(@StringRes val titleRes: Int, @StringRes val adviceRes: Int?)

internal fun errorCopy(classified: ChatStore.ProviderError, overflow: Boolean): ErrorCopy = when {
    overflow -> ErrorCopy(R.string.oc_error_overflow_title, R.string.oc_error_overflow_advice)
    classified.kind == ChatStore.ProviderErrorKind.QUOTA_EXHAUSTED ->
        ErrorCopy(R.string.oc_error_quota_title, R.string.oc_error_quota_advice)
    classified.kind == ChatStore.ProviderErrorKind.REGION_RESTRICTED ->
        ErrorCopy(R.string.oc_error_region_title, R.string.oc_error_region_advice)
    classified.kind == ChatStore.ProviderErrorKind.LOCATION_MISSING ->
        ErrorCopy(R.string.error_locationMissing_title, R.string.error_locationMissing_advice)
    // v32 BUG-1: the connection dropped. Upstream names this state
    // (`session.error.serverConnection`, 「无法连接到此服务器」) and the advice is
    // the retry the write path deliberately does not perform itself.
    classified.kind == ChatStore.ProviderErrorKind.TRANSPORT ->
        ErrorCopy(R.string.session_error_serverConnection, R.string.session_error_transient_advice)
    else -> ErrorCopy(R.string.oc_error_title, null)
}

/**
 * One recovery row: the same flat button the review panel's retry uses
 * (`layer03`, `sm` radius, 13 sp / W500), so a card-level action reads as a
 * button and not as another line of the error text. Disabled while a write is in
 * flight — a second tap must not queue a second compaction or a second revert.
 */
@Composable
private fun OverflowRecoveryButton(
    action: ContextOverflowAction,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val label = when (action) {
        ContextOverflowAction.COMPACT -> R.string.oc_error_overflow_compact
        ContextOverflowAction.REVERT -> R.string.oc_error_overflow_revert
    }
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(OcTheme.shapes.sm)
            .background(c.layer03)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = stringResource(label),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
        )
    }
}

/** A disabled recovery row stays readable (Material's own disabled opacity). */
private const val DISABLED_ALPHA = 0.38f
