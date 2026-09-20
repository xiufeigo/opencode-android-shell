@file:OptIn(ExperimentalFoundationApi::class)

package com.xiufeigo.opencodeshell.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.ui.theme.ocFloatingShadow
import com.xiufeigo.opencodeshell.ui.theme.ocShadow

/**
 * Home's sessions column (`home-sessions-view.tsx`, `research/02` §1.3):
 * sticky search field, a floating "new session" ✎, the Today / Yesterday /
 * Older group list (sticky headers with the scroll fade), the skeleton and the
 * empty state.
 *
 * Verified geometry: search field `h-9` = 36 dp / radius 6 / `layer-02` at 60 %
 * opacity; session row `h-10` = 40 dp / radius 6 / `overlay-hover` on press;
 * group header `h-7` = 28 dp; the ✎ is absolutely positioned at
 * `right-0; top-[84px]` (`lg: top-[108px]`), i.e. on the first group header's
 * line — which is exactly where it lands here: the list starts `pt-3` (12 dp)
 * below the search block and the button is pinned to the list's top-right.
 */
@Composable
internal fun HomeSessionsColumn(
    rows: List<HomeSessions.Row>,
    loading: Boolean,
    configMissing: Boolean,
    openSessionIds: Set<String>,
    onOpenSession: (String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
    /** `pt-6` (24 dp) on mobile, `lg:pt-12` (48 dp) on desktop. */
    topInset: Dp = MOBILE_TOP_INSET,
) {
    val c = OcTheme.colors
    var query by remember { mutableStateOf("") }
    val sections = remember(rows) { HomeSessions.sections(rows, System.currentTimeMillis()) }
    val results = remember(rows, query) { HomeSessions.filter(rows, query) }
    val listState = rememberLazyListState()

    // The search overlay is Home's top-most layer, so system back dismisses it
    // before the nav graph gets a chance to pop (PLAN §3: 先关弹层，再返回).
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    Column(modifier) {
        // Sticky header block: `pt-6 pb-3` around the search field
        // (`lg:pt-12 pb-3`). It never scrolls away, which is why the list below
        // needs no pinned copy of the field.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset, bottom = 12.dp),
        ) {
            HomeSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
            )
            if (configMissing) {
                Text(
                    text = stringResource(R.string.oc_setup_hint),
                    color = c.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // `contentPadding pt-3` — one 12 dp step between the search block and
            // the first sticky group header.
            Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                when {
                    loading -> HomeSessionSkeleton(Modifier.fillMaxSize())
                    rows.isEmpty() -> HomeSessionsEmpty(
                        onNewSession = onNewSession,
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> HomeSessionList(
                        sections = sections,
                        listState = listState,
                        openSessionIds = openSessionIds,
                        onOpenSession = onOpenSession,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // `data-action="home-new-session" variant="ghost-muted"` ✎
            FloatingNewSessionButton(
                onClick = onNewSession,
                modifier = Modifier.align(Alignment.TopEnd),
            )

            if (query.isNotEmpty()) {
                HomeSessionSearchPanel(
                    query = query,
                    results = results,
                    onPick = { row ->
                        query = ""
                        onOpenSession(row.id)
                    },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // Inline style of the panel: `top:-6px; left:-6px`.
                        .offset(x = (-6).dp, y = (-6).dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

/** `HomeSessionSearch` closed state: `h-9 rounded-[6px] bg-layer-02/60`. */
@Composable
private fun HomeSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .height(SEARCH_HEIGHT)
            .clip(OcTheme.shapes.md)
            .background(c.layer02.copy(alpha = 0.6f))
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_search),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = c.textBase, fontSize = 13.sp),
            cursorBrush = SolidColor(c.textBase),
            modifier = Modifier.weight(1f),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.oc_home_search),
                            color = c.textFaint,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(ICON_BUTTON_SMALL)
                    .clip(OcTheme.shapes.sm)
                    .clickable(role = Role.Button, onClick = { onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = stringResource(R.string.oc_home_clear_search),
                    tint = c.iconMuted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

/**
 * The open-state search panel (`home-session-search-panel`): radius 12,
 * `bg-base`, `elevation-floating`, results capped at `max-h-80` = 320 dp,每行
 * `h-10` with `pl-[18px] pr-6`. Upstream ranks with fuzzysort and offsets the
 * panel by -6 px on all four sides; M3 filters by substring
 * ([HomeSessions.filter]) and only offsets the top-left (see the class doc).
 */
@Composable
private fun HomeSessionSearchPanel(
    query: String,
    results: List<HomeSessions.Row>,
    onPick: (HomeSessions.Row) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val elevation = OcTheme.elevation.floating
    Column(
        modifier = modifier
            .ocShadow(elevation, shape)
            .clip(shape)
            .background(c.bgBase),
    ) {
        Text(
            text = stringResource(R.string.oc_home_search_group),
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 18.dp, end = 24.dp, top = 6.dp, bottom = 6.dp),
        )
        if (results.isEmpty()) {
            Text(
                text = stringResource(R.string.oc_home_search_no_results, query.trim()),
                color = c.textMuted,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = PANEL_MAX_HEIGHT)) {
                items(items = results, key = { "q:" + it.id }) { row ->
                    SearchResultRow(row = row, onClick = { onPick(row) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(row: HomeSessions.Row, onClick: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SEARCH_RESULT_HEIGHT)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 18.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.project != null) {
            ProjectLabel(text = row.project, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/**
 * The grouped session list. Header order is fixed
 * (Today → Yesterday → Older) and each group carries a trailing spacer:
 * `mb-6` = 24 dp between groups, `pb-16` = 64 dp under the last one.
 *
 * **Sticky + scroll fade**: upstream drives `titleOpacity` from
 * `home-scroll-controller`; here each header reads how far its own row has been
 * scrolled past (`firstVisibleItemIndex`/`firstVisibleItemScrollOffset`) and
 * fades over one header height. Compose's sticky header stays pinned, so the
 * title dims out just before the next group's header pushes it away.
 */
@Composable
private fun HomeSessionList(
    sections: List<HomeSessions.Section>,
    listState: LazyListState,
    openSessionIds: Set<String>,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Item index of each group header in the LazyColumn below
    // (header + pt-4 spacer + rows + trailing spacer).
    val headerIndexes = remember(sections) {
        val map = mutableMapOf<HomeSessions.Group, Int>()
        var index = 0
        sections.forEach { section ->
            map[section.group] = index
            index += section.rows.size + 3
        }
        map
    }
    val fadeDistancePx = with(LocalDensity.current) { HEADER_HEIGHT.toPx() }

    LazyColumn(modifier = modifier, state = listState) {
        sections.forEachIndexed { position, section ->
            val headerIndex = headerIndexes[section.group] ?: 0
            stickyHeader(key = "group:${section.group.name}") {
                val scrolled = if (listState.firstVisibleItemIndex == headerIndex) {
                    listState.firstVisibleItemScrollOffset
                } else {
                    0
                }
                val opacity = (1f - scrolled / fadeDistancePx).coerceIn(MIN_HEADER_OPACITY, 1f)
                HomeGroupHeader(group = section.group, opacity = opacity)
            }
            item(key = "group-pad:${section.group.name}") { Spacer(Modifier.height(16.dp)) }
            items(items = section.rows, key = { "session:" + it.id }) { row ->
                HomeSessionRow(
                    row = row,
                    open = row.id in openSessionIds,
                    onClick = { onOpenSession(row.id) },
                    modifier = Modifier.padding(bottom = ROW_GAP),
                )
            }
            item(key = "group-end:${section.group.name}") {
                Spacer(Modifier.height(if (position == sections.lastIndex) LIST_BOTTOM_GAP else 24.dp))
            }
        }
    }
}

/** `HomeSessionGroupHeader`: `h-7`, 13 px `text-muted`, fades while scrolling. */
@Composable
private fun HomeGroupHeader(group: HomeSessions.Group, opacity: Float) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.bgBase)
            .height(HEADER_HEIGHT)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (group) {
                HomeSessions.Group.TODAY -> stringResource(R.string.oc_home_group_today)
                HomeSessions.Group.YESTERDAY -> stringResource(R.string.oc_home_group_yesterday)
                HomeSessions.Group.OLDER -> stringResource(R.string.oc_home_group_older)
            },
            color = c.textMuted.copy(alpha = c.textMuted.alpha * opacity),
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/** `HomeSessionRow`: `h-10`, radius 6, avatar + title + project name — no time. */
@Composable
private fun HomeSessionRow(
    row: HomeSessions.Row,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            // `py-3 pl-3 pr-10`: the 40 dp trailing gap is the archive slot
            // (`SHOW_HOME_SESSION_ARCHIVE === false`), kept for fidelity so the
            // title's ellipsis lands where upstream's does.
            .padding(start = 12.dp, end = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeSessionLeading(row = row, open = open)
        Spacer(Modifier.width(8.dp))
        Text(
            text = row.title,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // `max-w-[min(70%,480px)] flex-[0_1_auto]` with a project name,
            // `flex-[1_1_auto]` without one.
            modifier = Modifier.weight(if (row.project == null) 1f else TITLE_WEIGHT, fill = false),
        )
        if (row.project != null) {
            ProjectLabel(text = row.project, modifier = Modifier.weight(1f - TITLE_WEIGHT, fill = false))
        }
    }
}

/**
 * `HomeSessionLeading` — `SessionTabAvatarView` + the "open as tab" marker
 * (`h-3 w-0.5 rounded-[2px] bg-layer-04` at `calc(100% + 4px)`).
 *
 * The avatar is a stand-in: `ProjectAvatar` renders server/GPU-provided images
 * and an emoji/initial fallback, and the M3 client has neither an image URL nor
 * a project endpoint (see [HomeProjects]).
 */
@Composable
private fun HomeSessionLeading(row: HomeSessions.Row, open: Boolean) {
    val c = OcTheme.colors
    Box(modifier = Modifier.width(LEADING_WIDTH).height(AVATAR_SIZE)) {
        Box(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .clip(OcTheme.shapes.sm)
                .background(c.layer02),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = row.project?.firstOrNull()?.uppercaseChar()?.toString()
                    ?: row.title.firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
                color = c.textMuted,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        if (open) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(2.dp)
                    .height(12.dp)
                    .clip(OcTheme.shapes.xs)
                    .background(c.layer04),
            )
        }
    }
}

@Composable
private fun ProjectLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = OcTheme.colors.textMuted,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * `HomeSessionSkeleton`: a title row + 4 `h-10 rounded-[6px] bg-deep opacity-70`
 * bars. Static by design — the shimmer pass belongs to the M6 motion milestone.
 */
@Composable
private fun HomeSessionSkeleton(modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    val bar = c.bgDeep.copy(alpha = 0.7f)
    Column(
        modifier = modifier.padding(start = 12.dp, end = 40.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(
            Modifier
                .padding(top = 4.dp, bottom = 12.dp)
                .width(72.dp)
                .height(12.dp)
                .clip(OcTheme.shapes.xs)
                .background(bar),
        )
        repeat(HomeSessions.SKELETON_ROWS) {
            Box(Modifier.fillMaxWidth().height(ROW_HEIGHT).clip(OcTheme.shapes.md).background(bar))
        }
    }
}

/**
 * `HomeSessionsEmpty`: `pt-[52px]`, centred "Nothing here yet" + description +
 * a `ButtonV2 variant="neutral"` "New session".
 */
@Composable
private fun HomeSessionsEmpty(onNewSession: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Column(
        modifier = modifier.padding(top = 52.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.oc_home_empty),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.oc_home_empty_description),
            color = c.textMuted,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(20.dp))
        NeutralButton(
            label = stringResource(R.string.oc_new_session),
            iconRes = R.drawable.ic_oc_edit,
            onClick = onNewSession,
        )
    }
}

/**
 * `ButtonV2 variant="neutral" size="normal"`: `h-7` = 28 dp, padding 11 dp,
 * radius 6, 13 px/530, `gap: 6px`, surface
 * `--v2-background-bg-button-neutral` (+ a 0.5 dp `borderMuted` hairline, since
 * Compose cannot port `--v2-elevation-button-neutral`'s two-layer box-shadow).
 */
@Composable
internal fun NeutralButton(
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    val shape = OcTheme.shapes.md
    Row(
        modifier = Modifier
            .height(BUTTON_HEIGHT)
            .clip(shape)
            .background(c.buttonNeutral)
            .border(0.5.dp, c.borderMuted, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = c.iconBase,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * `IconButtonV2 variant="ghost-muted" size="large" icon="edit"` — the floating
 * new-session button (`h-7 px-2`, absolutely positioned by the caller).
 */
@Composable
private fun FloatingNewSessionButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    Box(
        modifier = modifier
            .size(ICON_BUTTON_LARGE)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_edit),
            contentDescription = stringResource(R.string.oc_new_session),
            tint = c.iconMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** `h-9` = 36 dp search field. */
private val SEARCH_HEIGHT = 36.dp

/** `HomeSessionRow` `h-10` = 40 dp. */
private val ROW_HEIGHT = 40.dp

/** Search panel result row `h-10` = 40 dp. */
private val SEARCH_RESULT_HEIGHT = 40.dp

/** Group header `h-7` = 28 dp. */
private val HEADER_HEIGHT = 28.dp

/** `max-h-80` = 320 dp result list. */
private val PANEL_MAX_HEIGHT = 320.dp

/** `icon-button-v2[size=large]` = 28 × 28 dp. */
private val ICON_BUTTON_LARGE = 28.dp

/** `icon-button-v2[size=small]` = 20 × 20 dp. */
private val ICON_BUTTON_SMALL = 20.dp

/** `button-v2[size=normal]` = 28 dp. */
private val BUTTON_HEIGHT = 28.dp

/** Avatar edge (`SessionTabAvatarView`), a 18 dp rounded square here. */
private val AVATAR_SIZE = 18.dp

/** Avatar + the 6 dp slot the "open" marker lives in (never reflows the title). */
private val LEADING_WIDTH = 24.dp

/** `pt-6` = 24 dp on mobile … */
private val MOBILE_TOP_INSET = 24.dp

/** `gap-px` between rows. */
private val ROW_GAP = 1.dp

/** `pb-16` under the last group. */
private val LIST_BOTTOM_GAP = 64.dp

/** Title share of a row when a project name follows (`max-w-[min(70%,480px)]`). */
private const val TITLE_WEIGHT = 0.7f

/** Floor of the sticky-header scroll fade. */
private const val MIN_HEADER_OPACITY = 0.25f
