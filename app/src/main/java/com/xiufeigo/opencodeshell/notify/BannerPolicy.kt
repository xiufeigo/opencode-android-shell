package com.xiufeigo.opencodeshell.notify

import com.xiufeigo.opencodeshell.ChatStore

/**
 * Which feed entry the in-app banner shows, and which ones it has already
 * retired from the banner band (`ui/App.kt` `NotificationBanner`).
 *
 * Pure data rules, deliberately free of Compose so they stay JVM-testable —
 * the same split as [AppNotifier] (data here, presentation in `ui/App.kt`):
 *
 * - The banner surfaces the newest entry that is still in the band. Entries
 *   the user already retired from the band — an auto-hide that fired, or a tap
 *   whose handler ran — stay out of the banner while remaining **unviewed**
 *   in the feed, so the tab dots and the feed keep the truth and only the
 *   transient band goes quiet (v48: taps on Dismiss looked dead because a
 *   re-raised twin was already on screen, and one stuck entry pushed the
 *   whole layout down for a minute — the band must be transient even when
 *   the feed is not).
 * - `viewed` no longer suppresses the band (v49). The old `!it.viewed` filter is
 *   what made the foreground 「回复已完成」 banner invisible: a turn that settles
 *   while the user is *looking at that very session* is raised `viewed=true`, so
 *   the entry the user most wanted was the one entry the band would never show
 *   — 2 of 7 rounds popped a banner at all, and the rest only produced one after
 *   a force-stop restart (`research/v49_verify/tE`). What keeps a stale band off
 *   the screen instead is [allIds]: the banner pre-retires the feed it was
 *   handed at composition time (the restored/cold-start feed), so a `viewed`
 *   entry that arrives *now* surfaces while a page of restored history does not.
 * - Because `viewed` no longer steers the band, a tap has to retire what the
 *   old filter hid for it — the whole session, not just the shown entry
 *   ([hiddenAfterDismiss]). Retiring one id at a time only handed the frame to
 *   the identical twin behind it, so the tap measured as no tap at all (v50 tE).
 * - A genuinely new entry (an id never retired) still surfaces and re-arms
 *   the auto-hide timer: the snooze never swallows news, it only retires
 *   what was already shown.
 * - The retired-id set is pruned against the live feed on every write, so a
 *   long run cannot grow it without bound.
 */
object BannerPolicy {
    /** How long a shown entry keeps the banner band before it hides itself. */
    const val AUTO_DISMISS_MS = 8_000L

    /**
     * The entry the banner shows: the newest feed entry that has not been
     * retired from the band. `null` when the band should render nothing.
     *
     * The caller's `hiddenIds` is what the band already retired — seeded from
     * [allIds] when it mounted, then grown by the auto-hide and the taps. The
     * entry's own `viewed` flag plays no part here (see the v49 note above).
     */
    fun select(
        feed: List<AppNotification>,
        hiddenIds: Set<String>,
    ): AppNotification? = feed.lastOrNull { it.id !in hiddenIds }

    /**
     * Retires [ids] from the band, dropping ids that already left the feed
     * (acknowledged / cleared / pruned) so the set tracks the live feed.
     */
    fun hiddenAfter(
        feed: List<AppNotification>,
        hiddenIds: Set<String>,
        ids: Collection<String>,
    ): Set<String> {
        if (ids.isEmpty()) return hiddenIds
        val live = feed.map { it.id }.toSet()
        return (hiddenIds + ids).filter { it in live }.toSet()
    }

    /** Retires every currently unviewed entry — the dismiss-all shape. */
    fun hideUnviewed(
        feed: List<AppNotification>,
        hiddenIds: Set<String>,
    ): Set<String> = hiddenAfter(feed, hiddenIds, feed.filter { !it.viewed }.map { it.id })

    /**
     * The 「知道了」 shape: retires [entry] **and every entry of its session**.
     *
     * Retiring only the shown id is what made the tap look dead in v50: a busy
     * feed holds several entries for one session (one per settled turn, plus a
     * session-update raise that should never have been raised at all), the band
     * shows one at a time, and the twin behind it — the same title, the same
     * second line — stepped into the frame the instant the tap retired the
     * first. Measured on the device: 8.3 s without a tap, 8.3 s with one
     * (`research/v50_verify/tE`), against v49, where the tap killed the banner
     * immediately. The old `!viewed` selection hid the twin for free, because
     * `AppNotifier.acknowledge` marks a session's remaining entries viewed; once
     * the band stopped reading `viewed`, the retirement had to do that work
     * itself.
     */
    fun hiddenAfterDismiss(
        feed: List<AppNotification>,
        hiddenIds: Set<String>,
        entry: AppNotification,
    ): Set<String> = hiddenAfter(
        feed,
        hiddenIds,
        feed.filter {
            it.id == entry.id ||
                (entry.sessionID != null && it.sessionID == entry.sessionID)
        }.map { it.id },
    )

    /**
     * The 「全部清除」 shape: retires **every** entry the feed holds, not only
     * the unviewed half. The tap clears the feed right after, so the extra
     * retirements cost nothing — and they make the band die in the same frame
     * whatever the feed round-trip does, instead of showing a `viewed` entry
     * (the common case since v49) until the clear lands.
     */
    fun hiddenAfterDismissAll(
        feed: List<AppNotification>,
        hiddenIds: Set<String>,
    ): Set<String> = hiddenAfter(feed, hiddenIds, allIds(feed))

    /**
     * The auto-hide shape: retires [shownId] (the entry that just timed out)
     * together with every entry still waiting unviewed behind it, so the band
     * goes quiet for good instead of the next entry stepping into the frame
     * [AUTO_DISMISS_MS] later. [shownId] is listed even when it is `viewed`,
     * which is the common case now (a turn that finished while the user was
     * watching it).
     */
    fun hiddenAfterShow(
        feed: List<AppNotification>,
        hiddenIds: Set<String>,
        shownId: String,
    ): Set<String> = hiddenAfter(
        feed,
        hiddenIds,
        feed.filter { !it.viewed }.map { it.id } + shownId,
    )

    /**
     * Every id in [feed] — the band's opening retirement set.
     *
     * Seeded once, when the banner first composes, it retires whatever the
     * feed already held (the persisted cold-start feed, whose entries the user
     * has seen on a previous run or in a previous foreground round) while
     * leaving everything raised *from here on* free to surface. This is what
     * replaces the `!viewed` filter after v49: freshness, not seen-ness, is
     * what decides whether an entry may put the band on screen.
     */
    fun allIds(feed: List<AppNotification>): Set<String> = feed.map { it.id }.toSet()

    /**
     * One failure card, as [staleTransportIds] reads it.
     *
     * A plain projection of the timeline's failure rows so this rule stays a
     * pure function of (id, text, raw payload) — the caller maps its own row
     * type in and keeps the store's append/drop bookkeeping where it lives.
     */
    data class FailureRow(val id: String, val message: String, val detail: String?)

    /**
     * The failure cards that stop being the truth once the **connection** is
     * back, i.e. the ones whose whole claim was 「I cannot reach the server」
     * (`session.error.serverConnection`, 「无法连接到此服务器」) while the link was
     * down.
     *
     * v57 (emulator-5556 `E2ECHK5556-28…30`): `adb reverse` was pulled, a send
     * raised a transport card, the reverse was restored — and the card stayed on
     * screen indefinitely, because a failure row is a *transcript* row: nothing
     * ever retires it except a retry or a same-turn duplicate. The connection
     * had come back and nothing on screen said so. The caller retires these the
     * moment a turn actually reaches the server again (a `running` status can
     * only come from a server that answered).
     *
     * Deliberately narrow: only rows that classify as a transport failure
     * ([ChatStore.ProviderErrorKind.TRANSPORT], the same matcher the retry
     * policy uses). A turn that failed for a *real* reason — a quota, a region
     * block, a provider error, a missing directory — keeps its card, because
     * upstream projects those as `TimelineRow.Error` for good
     * (`timeline/projection.ts:357-359`) and the failure history is the
     * transcript, not a transient banner.
     */
    fun staleTransportIds(rows: List<FailureRow>): Set<String> = rows
        .filter {
            ChatStore.classifyStoredError(it.message, it.detail).kind ==
                ChatStore.ProviderErrorKind.TRANSPORT
        }
        .map { it.id }
        .toSet()
}
