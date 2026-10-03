package com.xiufeigo.opencodeshell.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SessionAsks
import com.xiufeigo.opencodeshell.SessionUnread
import com.xiufeigo.opencodeshell.notify.AppNotification
import com.xiufeigo.opencodeshell.notify.AppNotificationKind
import com.xiufeigo.opencodeshell.ui.components.SessionProgressIndicator
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The two states a session tab's leading slot can carry, upstream
 * `SessionTabAvatarView`'s props (`shell/layout/session-tab-avatar.tsx:26-49`):
 * `unread` → the 6 dp accent dot, `loading` → the 25-dot marquee instead of the
 * avatar. [NONE] is the default every caller starts from, so a host that has no
 * per-session state to offer renders the strip it always did.
 */
data class SessionTabBadge(
    val unread: Boolean = false,
    val loading: Boolean = false,
) {
    companion object {
        val NONE = SessionTabBadge()
    }
}

/**
 * A-2: the per-tab "needs attention" / "working" derivation, transcribed from
 * upstream `useSessionTabAvatarState` (`shell/layout/project-avatar-state.ts:26-49`)
 * so the two decisions are pure and pinned by a JVM test.
 *
 * Upstream, in order:
 *
 * ```ts
 * hasPermissions = !settings.permissions.autoApprove() && !!sessionPermissionRequest(sessions, …, sessionId)
 * hasForms       = !!sessionFormRequest(sessions, …, sessionId)
 * needsAttention = hasPermissions || hasForms
 * unread         = needsAttention || unseenCount(sessionId) > 0
 * loading        = !needsAttention && sessionStatus(sessionId) === "running"
 * ```
 *
 * Two deliberate readings of that for this shell:
 * - `autoApprove` gates the *permission* half only (a form still counts) —
 *   exactly upstream's nesting;
 * - a session that needs attention is never "loading", so the dot wins over the
 *   marquee and the tab does not look busy while it is actually waiting on the
 *   user.
 *
 * `unread` has **two** sources *beyond* `needsAttention`, because this shell's
 * two clients disagree about where "unseen" lives and the phone needs both
 * halves:
 * - [serverUnread] — the server's own row watermark ([SessionUnread], the TUI's
 *   rule). Persistent and stream-free, so a turn that settled while the app was
 *   off the session route still lights up on the next list read;
 * - [unseenCount] — this shell's in-app feed (`AppNotifier`), the app client's
 *   ledger. It is the only source that can report a *foreign* session's pending
 *   permission/form while a stream is open, so it stays.
 *
 * That second source carries [AppNotificationKind.PERMISSION] rows upstream's
 * ledger has no equivalent of, so [unseenCount] takes the same `autoApprove`
 * gate the permission half of `needsAttention` takes — see there.
 */
object SessionTabIndicator {

    /** `needsAttention` (`project-avatar-state.ts:39`). */
    fun needsAttention(
        hasPermission: Boolean,
        hasForm: Boolean,
        autoApprove: Boolean,
    ): Boolean = (!autoApprove && hasPermission) || hasForm

    /**
     * `SessionTabBadge.unread` / `SessionTabBadge.loading` for one session.
     *
     * [unseenNotifications] is upstream's `notification.session.unseenCount(id)`
     * — this shell counts it off the in-app feed ([unseenCount]), which is the
     * same per-session unseen ledger (`AppNotifier`'s `viewed` flag is set by
     * the same "the user opened that session" event upstream acks). Callers pass
     * the count that already carries the `autoApprove` gate ([unseenCount]), so
     * this argument and `needsAttention` agree about what a permission ask
     * contributes.
     *
     * [serverUnread] is the *other* upstream client's rule for the same question
     * — the TUI's `time.idle > time.viewed` on the session row
     * (`tui/src/context/session-tabs.tsx:120-123`). Defaulted to "the host has no
     * server watermark to offer", so a caller that passes nothing renders the
     * strip it always did.
     */
    fun badge(
        hasPermission: Boolean,
        hasForm: Boolean,
        autoApprove: Boolean,
        unseenNotifications: Int,
        running: Boolean,
        serverUnread: Boolean = false,
    ): SessionTabBadge {
        val attention = needsAttention(hasPermission, hasForm, autoApprove)
        return SessionTabBadge(
            unread = unread(hasPermission, hasForm, autoApprove, unseenNotifications, serverUnread),
            loading = if (attention) false else running,
        )
    }

    /**
     * `unread` (`project-avatar-state.ts:38-40`) — "this session is waiting on
     * the user", the same three sources [badge] ORs into its dot. Split out of
     * [badge] because the drawer row draws the marquee with its own precedence
     * (dot first, `SessionDrawer`), so it cannot take a [SessionTabBadge] — it
     * still has to answer the *same* question, and a second transcription of
     * that OR is how the two surfaces drift apart.
     *
     * The run status is deliberately not an input: upstream's `unread` memo does
     * not read it either, a run in progress never making a session unread.
     */
    fun unread(
        hasPermission: Boolean,
        hasForm: Boolean,
        autoApprove: Boolean,
        unseenNotifications: Int,
        serverUnread: Boolean = false,
    ): Boolean = needsAttention(hasPermission, hasForm, autoApprove) ||
        unseenNotifications > 0 ||
        serverUnread

    /**
     * Upstream `notification.session.unseenCount(id)`: raised and still unseen.
     *
     * [autoApprove] is `settings.permissions.autoApprove`, upstream's gate on the
     * *permission* half of "needs attention" (`project-avatar-state.ts:27`
     * `hasPermissions = !settings.permissions.autoApprove() && …`). This shell's
     * feed carries permission entries ([AppNotificationKind.PERMISSION]) that
     * upstream's ledger never holds at all — its `Notification` union is
     * `turn-complete` | `error` (`shell/notifications/notification.tsx:19-31`),
     * so upstream's `unseenCount` can never report a permission ask. Counting
     * ours without this gate is what let an auto-approved ask light the tab dot
     * and keep it lit (the v17 `switch_perm` report): the entry is raised
     * `viewed = false` whenever the ask lands while the user is not on that
     * session's route, and the auto-approver answering the server never touched
     * the ledger.
     *
     * Only `PERMISSION` is gated. `QUESTION` is upstream's `hasForms`, which is
     * deliberately *not* gated by the setting (a form still waits for the user),
     * and `AGENT` / `ERROR` are the two kinds upstream's ledger does hold.
     * Defaults to off, so a host with no setting to offer keeps the derivation it
     * always had.
     */
    fun unseenCount(
        notifications: List<AppNotification>,
        sessionID: String,
        autoApprove: Boolean = false,
    ): Int = notifications.count {
        it.sessionID == sessionID &&
            !it.viewed &&
            !(autoApprove && it.kind == AppNotificationKind.PERMISSION)
    }
}

/**
 * Every per-session dot input a surface has, in one place
 * (`ChatViewModel`'s five flows + the setting), so the tab strip, the session
 * drawer and Home's session rows answer with **one** derivation instead of three
 * transcriptions of upstream's `useSessionTabAvatarState`
 * (`shell/layout/project-avatar-state.ts:7-50`).
 *
 * The reason it is a type and not three lambdas: the *ask* halves are keyed by
 * session, not by "the session on screen". Upstream reads
 * `ctx.data.session.permission.list` / `session.form.list` — location-wide
 * lists — and `ChatStore.pendingPermissions` is the **tracked** session's dock
 * list, so a foreign session's pending permission / form only reaches a row
 * through [asks] (the [SessionAskLedger] `ChatViewModel` publishes) or the
 * in-app feed. Getting that wrong per surface is exactly how the v28 device run
 * found two surfaces dark while the dock was up.
 *
 * @param trackedSessionID `ChatViewModel.sessionID` — the one session whose dock
 *   list this client observes.
 * @param trackedPermission / [trackedForm] the tracked session's own dock list
 *   (`pendingPermission` / `pendingForm` non-null). Kept *beside* [asks] because
 *   it is the same fact from the other source: the store is fed by the dock's
 *   own snapshot reads, the ledger by the global frame path, and a dot that is
 *   up in one and not the other is the bug this class exists to prevent.
 * @param asks `ChatViewModel.sessionAsks` — pending asks by session, any session.
 * @param unread `ChatViewModel.sessionUnread` — the server row's watermark by
 *   session (`SessionUnread`).
 * @param trackedRunning the tracked session's run status is `RUNNING`.
 */
data class SessionDotSources(
    val trackedSessionID: String? = null,
    val trackedPermission: Boolean = false,
    val trackedForm: Boolean = false,
    val asks: Map<String, SessionAsks> = emptyMap(),
    val autoApprove: Boolean = false,
    val notifications: List<AppNotification> = emptyList(),
    val unread: Map<String, SessionUnread> = emptyMap(),
    val trackedRunning: Boolean = false,
) {

    /** `hasPermissions` — the ask on [sessionID], whatever session is on screen. */
    fun hasPermission(sessionID: String): Boolean =
        asks[sessionID]?.permission == true || (sessionID == trackedSessionID && trackedPermission)

    /** `hasForms` — deliberately not gated by the setting (upstream's nesting). */
    fun hasForm(sessionID: String): Boolean =
        asks[sessionID]?.form == true || (sessionID == trackedSessionID && trackedForm)

    /** One session's dot / marquee ([SessionTabIndicator.badge]). */
    fun badge(sessionID: String): SessionTabBadge = SessionTabIndicator.badge(
        hasPermission = hasPermission(sessionID),
        hasForm = hasForm(sessionID),
        autoApprove = autoApprove,
        unseenNotifications = SessionTabIndicator.unseenCount(notifications, sessionID, autoApprove),
        running = sessionID == trackedSessionID && trackedRunning,
        serverUnread = unread[sessionID]?.unread == true,
    )

    /**
     * The row's dot. Equal to `badge(sessionID).unread` by construction — the
     * drawer draws dot-else-marquee with its own precedence, and both surfaces
     * have to answer the *same* question.
     */
    fun unread(sessionID: String): Boolean = badge(sessionID).unread
}

/**
 * The 16 dp leading slot of a tab (`[data-slot="project-avatar-slot"]`,
 * `tab-nav.tsx:241-266`): the marquee while the session is working, else the
 * unread dot, else nothing.
 *
 * Upstream puts the dot on the *project avatar's* top-right corner (with a mask
 * notch carved out of the avatar). This shell's tab carries no project avatar —
 * `SessionTabRef` holds an id and a title, and inventing an initial from the
 * session title would be a decoration upstream never shows — so the dot is the
 * whole indicator, sized and coloured exactly as upstream's
 * (`project-avatar.css`: 6 px, `--v2-background-bg-accent`).
 *
 * **B-14, the project icon on the tab strip — still not attached.** Home's
 * session rows now draw the project's `icon.override` / `icon.color`
 * (`HomeSessionLeading` via `ProjectIconView`), because a row carries the
 * session's `directory` and Home holds the project catalogue
 * (`HomeProjects.forSession`). A tab carries neither: `SessionTabRef` is
 * `(id, title)`, the tab is added by `App.kt`'s nav listener — which knows only
 * the route's session id — and the three strips ([BottomTabStrip],
 * [DesktopTabStrip], `MobileTabsDrawer`) plus the closed-tab stack all
 * round-trip that same ref. Attaching the avatar therefore needs a *new*
 * per-tab project attribution (a session-directory read on tab open, then
 * `HomeProjects.forSession`), not a second call site of an existing lookup, and
 * it would make this slot unconditional: upstream's avatar shows on every tab,
 * while [SessionTabBadgeSlot] returns early so an idle tab keeps exactly the
 * title/✕ geometry it has today. Left as the documented deviation rather than
 * half-wired; the badge half stays the one thing a tab draws.
 */
@Composable
fun SessionTabBadgeSlot(badge: SessionTabBadge, modifier: Modifier = Modifier) {
    if (!badge.unread && !badge.loading) return
    val working = stringResource(R.string.oc_tab_working)
    val unread = stringResource(R.string.oc_tab_unread)
    Box(
        modifier = modifier.size(BADGE_SLOT_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        if (badge.loading) {
            SessionProgressIndicator(
                modifier = Modifier.semantics { contentDescription = working },
                size = BADGE_SLOT_SIZE,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(UNREAD_DOT_SIZE)
                    .clip(CircleShape)
                    .background(OcTheme.colors.accent)
                    .semantics { contentDescription = unread },
            )
        }
    }
}

/** `size-4` — the avatar slot's 16 dp box. */
private val BADGE_SLOT_SIZE = 16.dp

/** `[data-slot="project-avatar-unread-dot"]` — 6 px, `right/top: -3px` off the avatar. */
private val UNREAD_DOT_SIZE = 6.dp

/** The dot's `-3px` overhang past the avatar's top-right corner (`project-avatar.css`). */
private val UNREAD_DOT_OVERHANG = 3.dp

/**
 * The unread dot **on a project avatar's top-right corner** — upstream's
 * `ProjectAvatar` `unread` prop (`project-avatar.css`: the dot is 6 px at
 * `right: -3px; top: -3px`, and the avatar grows a mask notch so the dot reads
 * as separate from it).
 *
 * The surfaces that *have* an avatar use this (Home's session rows, upstream
 * `HomeSessionRowUI` → `SessionTabAvatarView` → `ProjectAvatar`);
 * [SessionTabBadgeSlot] stays the form for the surfaces that do not (this
 * shell's tab carries no avatar, and the session drawer's rows never had one).
 * Both read the same size / colour / semantics constants, so there is still one
 * dot in the shell, only two placements.
 *
 * The mask notch is the one upstream detail not reproduced: it needs an
 * off-screen compositing layer (`BlendMode.DstOut`) for a 6 dp decoration, and
 * without it the dot simply overlaps the avatar's corner instead of carving it.
 *
 * Must be called from a `Box` whose avatar sits at `Alignment.TopStart` (both
 * call sites lay the avatar out first, top-start).
 *
 * @param avatarSize the avatar's own box (16 dp on the strips' slot, 18 dp on
 *   Home's rows), which the overhang is measured from.
 */
@Composable
fun BoxScope.SessionAvatarUnreadDot(avatarSize: Dp = BADGE_SLOT_SIZE) {
    val unread = stringResource(R.string.oc_tab_unread)
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(
                x = avatarSize - UNREAD_DOT_SIZE + UNREAD_DOT_OVERHANG,
                y = -UNREAD_DOT_OVERHANG,
            )
            .size(UNREAD_DOT_SIZE)
            .clip(CircleShape)
            .background(OcTheme.colors.accent)
            .semantics { contentDescription = unread },
    )
}
