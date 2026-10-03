package com.xiufeigo.opencodeshell.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.ChatViewModel
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcKeyboard
import com.xiufeigo.opencodeshell.ui.components.ocMinTouchTarget
import com.xiufeigo.opencodeshell.ui.overlay.ModalImePolicy
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import com.xiufeigo.opencodeshell.v2.DirEntry
import com.xiufeigo.opencodeshell.v2.TransportFailure
import kotlinx.coroutines.delay

/**
 * Add-project directory browser (upstream `dialog-select-directory`, opened by
 * `PromptProjectController.add` / `home.model`'s add-project action).
 *
 * Hosted in the global dialog stack ([com.xiufeigo.opencodeshell.ui.overlay.DialogHost]).
 * Official layout (`_upstream/packages/app/src/workspaces/selection/dialog.tsx`):
 * path `TextInput` with search suggestions, the `~` / root / parent ghost
 * buttons, the browser body, the resolved-path line, and a cancel + confirm
 * footer. Compose keeps a flat subdirectory list instead of `@pierre/trees`.
 *
 * Paths go through [FsBrowse.resolveInput] (`~` → home, relative → join), and
 * 「选择此目录」 hands [onSelect] the current absolute path without requiring
 * the server to know it as a project — `POST /session?directory=` discovers it.
 * On a v1 server the tree cannot leave the server cwd
 * ([com.xiufeigo.opencodeshell.v2.SessionTransport.browseEscapeAllowed]); the
 * hint below the nav row says so, and a typed path still browses even when
 * listing it fails — but 「选择此目录」 is disabled then ([pickerCanConfirm],
 * upstream `disabled={!policy.result(...)}`).
 *
 * `~` — the ghost button and a `~`-prefixed typed path alike — resolves to the
 * home **derived from the path the browser is on** ([pickerHomeTarget] →
 * [FsBrowse.homeOf]), because v2 has no home field: its location is the server
 * process cwd (v33 BUG-1), which names no home at all when the server runs from
 * a WSL mount. The location stays a second source behind the current path; a
 * path that names no home leaves the button on the remembered home, else on
 * the location itself as a last resort — greyed only while already there
 * ([pickerHomeEnabled], v32 BUG-2: the target used to be the directory the
 * browser was already on, so it looked dead). The last home the session named
 * is remembered, which is what carries `~` from `/home` — a level no path
 * derives a home from — up to `/home/xiufeigo` (v57). Where no path names a
 * home at all (a WSL/Windows server cwd), the probed location itself answers
 * as a last resort, so `~` jumps back to the session's base instead of
 * sitting grey (v68).
 *
 * On a v2 server there is no ceiling: the probed home/root are only a
 * starting hint, never a lock. The 「根目录」 button targets the true
 * filesystem root derived client-side from the current path ([pickerTrueRoot],
 * upstream `pickerRoot(root)`), the parent walk runs to that root, and above
 * a Windows drive root it opens a drive level ([DRIVES_LEVEL]) listing the
 * derived root set ([browseRoots]) instead of dead-ending. The adopted start
 * ([resolvePickerStart]) refuses a Windows system directory — v2 reports the
 * server launch cwd (e.g. `C:\Windows\System32`) as the location, which is
 * not a home — and falls back to the drive root.
 *
 * Two things the card itself guarantees, both measured on the device:
 * - **No entry parks on a Windows system directory.** [pickerEntryStart] guards
 *   `initialPath` before the first frame and [resolvePickerStart] re-checks the
 *   probed answer, so neither Home's remembered directory nor the 移动到…
 *   surface's session directory (handed over verbatim) can leave System32 on
 *   screen.
 * - **The whole card stays on screen, keyboard up.** The footer (待确认路径行 /
 *   取消 / 选择此目录) is the block an overflowing card loses first; the IME
 *   inset rides the card's box, the card itself is capped at [pickerCardMax],
 *   and the browser body carries the column's `weight` — so the body is what
 *   shrinks and scrolls while everything below it stays on screen. The
 *   suggestion dropdown, which is in flow, shares the body's budget
 *   ([pickerSuggestionsMax]) so it cannot grow the card past the window while
 *   the user types. `research/v34_verify/v34_chk_03_path_typed.png` and
 *   `research/v40_verify/tA/23_ime_try.png` / `24_ime_ui.xml` both measured
 *   the footer behind the IME, with no reachable way to confirm. v51 re-audited
 *   the same budget for a window that is short all by itself
 *   (`research/v51_verify/tE/v51_22_movedlg.png`, 308 dp of height): the fixed
 *   rows are now a floor the card is never capped below
 *   ([PICKER_FOOTER_HEIGHT]) and the one optional row under the body gives way
 *   first ([pickerOptionalFits]), so the body shrinks and scrolls while the
 *   footer stays on screen at any window height.
 */
@Composable
fun DirectoryPickerDialog(
    chatViewModel: ChatViewModel,
    /**
     * Starting path; null resolves to `browseHome` then `browseRoot`.
     * Callers pass the remembered last-used directory
     * ([pickerRememberedStart]) so the browser re-opens where the user left
     * it instead of always re-probing from home — or, for 移动到…, the session's
     * own directory verbatim. Either way the value goes through
     * [pickerEntryStart], so a Windows system start is re-rooted to its drive
     * root rather than displayed.
     */
    initialPath: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens the Servers editor (auth-wall escape hatch); null hides the button. */
    onOpenServers: (() -> Unit)? = null,
    /**
     * Every directory the user explicitly navigates to (ghost buttons,
     * subdirectory rows, suggestion picks, typed paths). An optional hook — the
     * probe-driven adoption below never reports here.
     *
     * Home deliberately leaves it null: upstream's picker only ever reads a
     * start directory (`dialog.tsx:82-89`: `props.start || home || location`)
     * and a *browsed* directory is not a project fact. Routing it into the
     * project MRU (`projects.touch`) made browsing re-scope the next draft —
     * the v30 device run's second half: after a picker session that added
     * nothing, the new-session page opened on the last directory *browsed*
     * instead of the project the user had been working in.
     */
    onNavigate: ((String) -> Unit)? = null,
) {
    val c = OcTheme.colors
    // Every entry is guarded, not just the probe's answer: [pickerEntryStart]
    // refuses a Windows system start before the first frame, so the non-empty
    // `initialPath` the session surface hands over verbatim
    // (`SessionActionsDialogs.MoveSurface` passes the session's own directory)
    // cannot park the browser on `C:\Windows\System32` either.
    val entry = remember(initialPath) { pickerEntryStart(initialPath) }
    // v47 tD_30/u33: every piece of browsing state is keyed on the entry
    // start, never on first composition. A (re)supplied `initialPath` — the
    // Move surface's session directory on every reopen — resets the browser
    // instead of inheriting a typed-but-unconfirmed dirty value, an armed
    // confirm or a previous navigation. The probe answers below
    // (`browseRoot`/`browseHome`/`escapeAllowed`) and `attempt` stay unkeyed:
    // they describe the server, not the browsing session.
    var currentPath by remember(entry) { mutableStateOf(entry) }
    var pathInput by remember(entry) { mutableStateOf(entry) }
    // v62: the path field's own selection, which the value it is handed used to
    // leave to the text field — a caret parked at the end, so focusing the field
    // and typing grew the browsed path onto itself instead of replacing it
    // (device v62 on 5560: a hand-typed path came out `/tmp/x/mnt/c/…`).
    // Focus now selects the whole value ([pickerFocusSelection]), so the first
    // character replaces it; a tap still places the caret where the finger lands.
    var pathSelection by remember(entry) { mutableStateOf(TextRange.Zero) }
    var nodes by remember(entry) { mutableStateOf<List<DirEntry>?>(null) }
    var loading by remember(entry) { mutableStateOf(false) }
    var failed by remember(entry) { mutableStateOf(false) }
    // The path whose listing last came back **readable** — the only thing
    // 「选择此目录」 may name. `failed` says "the read in flight for
    // `currentPath` failed"; this says "a directory was verified at all", and
    // the two together gate the confirm on a target the *server* has listed
    // rather than on the path that happened to be on screen (v54 P1, see
    // [pickerCanConfirm]).
    var verifiedPath by remember(entry) { mutableStateOf<String?>(null) }
    var failure by remember(entry) { mutableStateOf<TransportFailure?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var userNavigated by remember(entry) { mutableStateOf(false) }
    // The user has put a hand on the picker: they typed into the path field, or
    // the first 「选择此目录」 tap armed the confirm. Both outrank the probe
    // adoption below (v41 BUG-1/BUG-2) — see [probeMayAdopt].
    var userEdited by remember(entry) { mutableStateOf(false) }
    var pendingConfirm by remember(entry) { mutableStateOf<String?>(null) }
    var browseRoot by remember { mutableStateOf("") }
    var browseHome by remember { mutableStateOf<String?>(null) }
    // `~` resolves here, not to `browseHome`: on v2 the probe's "home" is the
    // location (the server process cwd, not `$HOME`), which the picker often is
    // not even on — see [pickerHomeTarget]. The probe value stays the picker's
    // *start* hint ([resolvePickerStart]) and the drive-root sample
    // ([browseRoots]); it is only the `~` target's fallback.
    // The last home the browser did name, so `~` still works from `/home` — one
    // level above it the path names no user and cannot derive the home again.
    var knownHome by remember(entry) { mutableStateOf(FsBrowse.homeOf(entry)) }
    val homeTarget = remember(currentPath, browseHome, knownHome) {
        pickerHomeTarget(currentPath, browseHome, knownHome, fallback = browseHome)
    }
    LaunchedEffect(homeTarget) { if (homeTarget != null) knownHome = homeTarget }
    var escapeAllowed by remember { mutableStateOf(true) }
    var suggestions by remember(entry) { mutableStateOf<List<DirEntry>>(emptyList()) }
    var inputToken by remember(entry) { mutableStateOf(0) }
    // Self-heal (P0): the picker routinely opens in the same frame as
    // `ensureClient`, while the probe is still in flight. Keying both reads on
    // `generation` re-runs them when the V2 transport lands — no manual retry.
    val generation by chatViewModel.generation.collectAsState()

    fun navigate(raw: String) {
        val base = pickerInputBase(currentPath, browseRoot, homeTarget)
        val resolved = pickerResolveInput(raw, homeTarget, base)
        if (resolved.isEmpty()) return
        userNavigated = true
        pendingConfirm = null
        currentPath = resolved
        pathInput = resolved
        pathSelection = TextRange(resolved.length)
        suggestions = emptyList()
        // In-flight suggestion reads describe an input this navigation has just
        // replaced — dropping them keeps a debounced answer from navigating the
        // browser back to where the field used to read (see [browseResolved]).
        inputToken += 1
        onNavigate?.invoke(resolved)
    }

    /**
     * v63: browse what the debounced suggestion read has just **resolved** — the
     * typed path becomes the browsed path without the IME's Search key, so the
     * body follows the field and 「选择此目录」 verifies instead of staying grey.
     *
     * Deliberately **not** [navigate]: the field keeps the user's own text and
     * caret, so a still-typing path is not rewritten underneath the finger
     * (`navigate` canonicalises it into `pathInput`). A no-op when the target is
     * the directory already on screen, which is what keeps the settled state of
     * a finished path from re-listing on every keystroke.
     */
    fun browseResolved(raw: String) {
        val base = pickerInputBase(currentPath, browseRoot, homeTarget)
        val resolved = pickerResolveInput(raw, homeTarget, base)
        if (resolved.isEmpty() || pickerSamePath(resolved, currentPath)) return
        userNavigated = true
        pendingConfirm = null
        currentPath = resolved
        suggestions = emptyList()
        onNavigate?.invoke(resolved)
    }

    // Browse constraints (blocking `GET /path` / `GET /api/location`); the
    // starting path falls back to home, then the generation's root. Keyed on
    // `attempt` so Retry re-fetches constraints too: a first open during the
    // credential/probe window lands on `/` with no home, and must adopt the
    // home once it answers instead of staying on the failed root.
    //
    // `fresh = true`: opening the picker is a gesture, so it outranks a latched
    // probe verdict exactly like the Retry button does — a reopen while the
    // last verdict was a definitive negative used to answer from that verdict
    // and park the browser on the 「no directory API」 hint until Retry/Root
    // (v21 P3, 334 §bug2). With a live transport the flag costs nothing.
    LaunchedEffect(attempt, generation) {
        chatViewModel.browseConstraints(fresh = true) { root, home, escape ->
            val previousRoot = browseRoot
            browseRoot = root
            browseHome = home
            escapeAllowed = escape
            // A probe that answers after the user has taken hold of the picker
            // adopts nothing (v41 BUG-1/BUG-2 — [probeMayAdopt]): the arm/typed
            // edits below used to be overwritten by the start path here, which
            // is what turned the first 「选择此目录」 tap into a self-contradicting
            // frozen card (body naming the armed path, input and footer naming
            // the start) and moved the base a relative input resolves against.
            val mayAdopt = probeMayAdopt(userNavigated, userEdited, pendingConfirm)
            val adopted = if (!mayAdopt) null else resolvePickerStart(currentPath, previousRoot, userNavigated, home, root)
                // The probe answered a Windows root: a drive-less path inherited
                // from the MRU is re-rooted on it (v36 BUG-2 — see
                // [adoptPickerRoot]), so the picker never displays — nor hands
                // back — a Windows directory with its volume missing.
                ?: adoptPickerRoot(currentPath, root)
            adopted?.let {
                currentPath = it
                pathInput = it
                pathSelection = TextRange(it.length)
            }
        }
    }
    //
    // The callbacks are **token-guarded** by [requested] (v54 P1,
    // `research/v54_verify/tD/17_nodir_err.png`): [ChatViewModel.listFiles]
    // answers from its own `viewModelScope`, not from this effect, so a read
    // launched for a path the user has already left lands *after* the new one
    // and used to overwrite the new state — a directory that exists reported
    // 「不存在」 because the previous read's failure arrived late. Reads off the
    // path on screen are dropped instead.
    LaunchedEffect(currentPath, attempt, generation) {
        val requested = currentPath
        if (requested.isEmpty()) return@LaunchedEffect
        loading = true
        failed = false
        failure = null
        // The previous directory's rows must not sit under the new path while
        // its own listing is in flight (v54 `15_badpath.png`: the body still
        // listed `/` while the field named `/tmp/nope_zzz`).
        nodes = null
        verifiedPath = null
        chatViewModel.listFiles(
            requested,
            fresh = true,
            onFailure = { if (pickerReadIsCurrent(requested, currentPath)) failure = it },
            onResult = { result ->
                if (!pickerReadIsCurrent(requested, currentPath)) return@listFiles
                loading = false
                failed = result == null
                if (result != null) failure = null
                // An empty listing is a verified directory, a `null` one is not.
                verifiedPath = if (result != null) requested else null
                nodes = result?.sortedBy { it.name.lowercase() }
            },
        )
    }
    // Debounced suggestions; [inputToken] drops stale responses (the same
    // trick as `attempt` above). The input is normalised through
    // `scoped()`-style {directory, name} resolution first — a raw
    // `/home/xiufe` would otherwise reach `searchDirectories` as one fuzzy
    // query string instead of a name search scoped to `/home`.
    LaunchedEffect(pathInput, inputToken) {
        val input = pathInput.trim()
        suggestions = emptyList()
        if (input.isEmpty() || input == currentPath) return@LaunchedEffect
        val base = pickerInputBase(currentPath, browseRoot, homeTarget)
        if (base.isEmpty()) return@LaunchedEffect
        val scope = scopedSearch(input, homeTarget, base)
        if (scope.directory.isEmpty()) return@LaunchedEffect
        val token = inputToken
        delay(SEARCH_DEBOUNCE_MS)
        // Empty tail (`~`, a root, trailing `/`): upstream walks to the scoped
        // directory and lists its children instead of name-searching — the body
        // already shows them when the scope is the browsed path.
        if (scope.name.isEmpty() && scope.directory == currentPath) return@LaunchedEffect
        // v56 (`research/v56_verify/tD/23_sugg.png`, `28_namesearch.png`,
        // `30_sugg4.png`): the non-empty tail went through `searchDirectories`,
        // a **tree-wide fuzzy** read the transport scopes to the server's
        // location root and truncates at its own limit — so the eight hits came
        // back from wherever the tree happens to match first, `filterScoped`
        // threw all of them away as out-of-scope, and the dropdown rendered
        // **nothing**: zero suggestions for `opencode` typed while browsing
        // `/home/xiufeigo` (whose listing right below it contains `opencode`),
        // and the same for a hand-typed absolute path. A truncated global search
        // can never be re-scoped client-side, so the match has to happen where
        // the candidates are.
        //
        // It does: the scoped directory is listed and the tail is matched
        // against its children — upstream's segment walk
        // (`domain.ts:415-428`), which is also what the empty-tail branch above
        // already did. One read, one deterministic answer, and the suggestion
        // names a directory whose parent is exactly the scope.
        chatViewModel.listFiles(
            scope.directory,
            onFailure = {},
            onResult = { results ->
                if (token != inputToken) return@listFiles
                val children = results.orEmpty().filter { it.isDirectory && !it.ignored }
                suggestions = children
                    .filter { pickerNameMatches(it.name, scope.name) }
                    .take(MAX_SUGGESTIONS)
                // v63 (`research/v65_verify/5556/57_path_typed.png`): a hand-typed
                // absolute path changed the field and the resolved line while the
                // body kept listing the *old* directory and 「选择此目录」 stayed
                // grey — only the IME's Search key browsed it.
                //
                // Two routes: (1) [pickerAutoBrowseTarget] here — the read above
                // already listed the typed path's parent, so a last segment
                // that names one of its children is resolved with no second
                // round trip. (2) Anything else a settled absolute path names —
                // readable or missing — is the dedicated settled effect's
                // below, which is keyed on the text instead of this run's token
                // and survives IME composing churn (v68).
                val auto = pickerAutoBrowseTarget(input, scope, children, currentPath)
                if (auto != null) {
                    browseResolved(auto)
                }
            },
        )
    }

    // Settled absolute input (v68): the missing-directory answer for a typed
    // path that stays silent above (`research/v68_verify/5556/30_badpath.png`
    // → `31_badpath_wait.png`: `/mnt/c/nopexx5556` sat 8 s with the old
    // listing, a grey confirm, no error and no Retry).
    //
    // Deliberately separate from the suggestion run: that run is keyed on
    // [inputToken] and the v67 settle rode the same token across two
    // sequential reads plus 700 ms, so any bump in between — an IME composing
    // update re-emitting the same text, a slow scope read — cancelled the
    // report and the chain stayed silent. This run is keyed on the *text*
    // ([pathInput]) and re-checks the text after every step, so only real
    // keystrokes debounce it; a half-typed path still stays silent through
    // [pickerMissingBrowse]'s fuzzy guard, exactly like before.
    LaunchedEffect(pathInput) {
        val settledInput = pathInput.trim()
        if (settledInput.isEmpty() || pickerSamePath(settledInput, currentPath)) return@LaunchedEffect
        val settledBase = pickerInputBase(currentPath, browseRoot, homeTarget)
        if (settledBase.isEmpty()) return@LaunchedEffect
        val settledScope = scopedSearch(settledInput, homeTarget, settledBase)
        if (settledScope.directory.isEmpty()) return@LaunchedEffect
        if (pickerDirectBrowseTarget(settledInput, homeTarget, settledBase, currentPath) == null) {
            return@LaunchedEffect
        }
        delay(PICKER_MISSING_SETTLE_MS)
        if (pathInput.trim() != settledInput) return@LaunchedEffect
        // Re-resolve against the latest state: the suggestion run may have
        // auto-browsed an exact child while this run settled.
        val base = pickerInputBase(currentPath, browseRoot, homeTarget)
        val direct = pickerDirectBrowseTarget(settledInput, homeTarget, base, currentPath)
            ?: return@LaunchedEffect
        if (pickerSamePath(direct, currentPath)) return@LaunchedEffect
        val scope = scopedSearch(settledInput, homeTarget, base)
        chatViewModel.listFiles(
            direct,
            onFailure = {},
            onResult = { listed ->
                if (pathInput.trim() != settledInput) return@listFiles
                // Readable: browse it — the same answer the IME's Search key
                // gives, without requiring it.
                if (listed != null) {
                    browseResolved(direct)
                    return@listFiles
                }
                chatViewModel.listFiles(
                    scope.directory,
                    onFailure = {},
                    onResult = { results ->
                        if (pathInput.trim() != settledInput) return@listFiles
                        val children = results.orEmpty().filter { it.isDirectory && !it.ignored }
                        val target = pickerMissingDecision(
                            currentPath = currentPath,
                            direct = direct,
                            scope = scope,
                            entries = children,
                            listed = false,
                        )
                        if (target != null) browseResolved(target)
                    },
                )
            },
        )
    }

    // Upward navigation (upstream `pickerParent`, but without a ceiling): the
    // probed root never gates the walk — on an unlocked transport the parent
    // is always crossable, and above a Windows drive root the walk opens the
    // virtual drive level instead of dead-ending. The v1 cwd lock keeps its
    // `isWithin` gate so the button does not promise what the server 500s.
    val atDrives = currentPath == DRIVES_LEVEL
    val driveSample = if (atDrives) browseRoot else currentPath
    val roots = browseRoots(browseHome, driveSample, browseRoot)
    val up = pickerUp(currentPath, escapeAllowed) { escapeAllowed || FsBrowse.isWithin(browseRoot, it) }
    val parentAllowed = up is PickerUp.Parent
    val showEscapeHint = showBrowseLockHint(escapeAllowed, parentAllowed, failed)

    // Viewport fit: the keyboard is drawn *over* the composition (`OcActivity`
    // is edge-to-edge, so `adjustResize` never shrinks the window — the same
    // read `CommandPalette` makes), and a centered card therefore had its
    // footer — 取消 / 选择此目录 — behind the IME. Measured on the device with
    // the path field focused (`research/v34_verify/v34_chk_03_path_typed.png`):
    // the browser was left with no reachable way to confirm. The IME inset
    // rides the card's own box (below), which lifts the card clear of the
    // keyboard, and the body gives way so the whole card still fits the
    // visible area.
    val density = LocalDensity.current
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // v46 tD_13/tD_24: the system navigation bar covers the same bottom strip
    // the IME does — edge-to-edge, nothing resizes the window for it either —
    // so with the keyboard down the card's 8 dp gutter parked the footer
    // (取消 / 选择此目录) ~25 px above the gesture pill with no inset. The
    // covered strip is both insets; with the keyboard up the navigation bar is
    // gone and `navBottom` reads 0, so the keyboard-up behaviour is unchanged.
    //
    // v47 `research/v47_verify/tE/tE_36_move.png`: shrinking the caps for the
    // nav strip was not enough — the card is *centered*, so a tall card's
    // bottom margin is only (window − card)/2, i.e. less than the bar itself,
    // and the footer (取消 / 选择此目录) was drawn under the black system bar:
    // present in the uiautomator dump, fully untappable. The card therefore
    // also carries the nav inset as padding on its own box (below), which
    // plants its bottom edge — footer included — above the bar with room to
    // spare however tall the card gets. Keyboard-up behaviour is unchanged:
    // the IME still rides [imePadding] and the nav padding reads ~0 under it.
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val coveredBottom = imeBottom + navBottom
    // v68 (`research/v68_verify/5560/75_tilde.png`, uiautomator `u5560.xml`):
    // a tall card is centered into the full window, so its top slid under the
    // status bar — the header ✕ sat at `bounds=[1110,31][1254,175]`, inside
    // the system's own strip, and 3/3 taps never reached the app while footer
    // Cancel (clear of it) worked. The card therefore starts below the status
    // bar ([statusBarsPadding] on its box), and every cap is computed in the
    // viewport that remains — so the v51 short-window invariant (footer always
    // fits) holds in the reduced room instead of overflowing by the inset.
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val viewportHeight = windowHeight - statusTop
    val suggestionsMax = pickerSuggestionsMax(viewportHeight, coveredBottom)
    val bodyMax = pickerBodyMax(viewportHeight, coveredBottom, suggestions.isNotEmpty())
    val cardMax = pickerCardMax(viewportHeight, coveredBottom)
    // v51 `research/v51_verify/tE/v51_22_movedlg.png`: the v1 lock hint is the one
    // optional row below the body, it only shows on a locked transport, and it is
    // what pushed the footer off a short window. It gives way before the footer
    // does ([pickerOptionalFits]).
    val showLockHint = showEscapeHint && pickerOptionalFits(cardMax, bodyMax, PICKER_HINT_HEIGHT)

    // The one path the whole card names while a confirm is armed
    // ([pickerConfirmPath]): the path field, the resolved-path line and the
    // confirm body all read it, so the three can never contradict each other
    // the way the v41 frozen card did (`research/v41_verify/tA/32_sel_tap1.png`).
    //
    // `userEdited` is what makes an **emptied** field stay emptied: falling back
    // to the browsed path there refilled the field the instant it went blank, so
    // select-all + DEL did nothing at all (the text came straight back) and the
    // only way to retype a path was to backspace through the old one
    // (`research/v51_verify/tD/v51_23_typed2.png`, where the retries grew the
    // field a `/mnt` tail each round instead of replacing the text).
    val displayPath = pickerConfirmPath(pendingConfirm, currentPath, pathInput, userEdited)

    // A failed listing greys select (upstream `dialog.tsx`:
    // `disabled={!policy.result(...)}`). v40 measured the button still lit
    // accent-cyan and tappable under 「目录不存在或无法读取」, so a directory the
    // server cannot even list — typically a path the user typed by hand — was
    // one tap away from being added as a project.
    //
    // v54 P1: the gate reads the **target** (the armed path, else the path the
    // button would arm), not the path on screen — so a name typed into the field
    // but not yet browsed can no longer be armed, and an armed path whose own
    // listing failed greys out instead of staying tappable.
    //
    // v56: greyed is a **look**, not a dead button. The tap still arrives and
    // browses the target ([tapConfirm]); only arming and submitting are gated.
    val canConfirm = pickerCanConfirm(
        target = pendingConfirm ?: pickerArmTarget(pathInput, currentPath, browseRoot, homeTarget),
        atDrives = atDrives,
        verifiedPath = verifiedPath,
    )

    // P1（`research/v52_verify/tE/v52_11/12`、`v53_verify/tE/v53_15/16`）：短窗
    // （1440×810@420dpi ≈ 342 dp）上打开「Move to…」软键盘自己升起，整张卡被压成
    // 顶部约 70 px 的残条 —— body 零尺寸、Cancel/Select 不可见，只能 ESC 收一次
    // 键盘才恢复。大窗完全正常，所以这是窗口**空间**的问题。
    //
    // 这里不自动把焦点交给路径输入框（`ModalImePolicy`），改由卡片根节点承担焦点：
    // 用户点输入框时键盘照常升起，[imePadding]/各 cap 系的 footer 保证仍然可达。
    val autofocusPath = ModalImePolicy.autoFocusOnOpen(
        requested = true,
        windowHeightDp = viewportHeight.value.toInt(),
    )
    val cardFocus = remember { FocusRequester() }
    val pathFocus = remember { FocusRequester() }
    LaunchedEffect(autofocusPath) {
        if (autofocusPath) return@LaunchedEffect
        // 首帧可能还没 attach，抢不到焦点就再试几帧（与 `ocModalEscape` 同量级）。
        repeat(PICKER_FOCUS_ATTEMPTS) {
            if (runCatching { cardFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    // The two taps' shared action (v49 tD_59→61): the first tap on the footer
    // button arms the confirm ([DirectoryConfirm.Armed]); the second — a tap
    // on the armed button, or on the confirm body itself — submits it. Both
    // entry points run this one function so the row tap and the button tap
    // can never disagree on what submits.
    //
    // The armed/submitted path is the **resolved typed target**
    // ([pickerArmTarget]), never the browsed `currentPath` (v47 tD_08→09:
    // typing `/home/xiufeigo/v36projB` while browsing `/home/xiufeigo` used
    // to freeze the parent on the confirm card and move the session there on
    // the second tap — zero requests, zero errors). A divergent target also
    // becomes the browsed path first, so the listing, the failed-read gate
    // ([pickerCanConfirm]) and a later relative input all follow what the
    // card shows.
    fun tapConfirm() {
        val target = pendingConfirm
            ?: pickerArmTarget(pathInput, currentPath, browseRoot, homeTarget)
        // v54 P1 (`research/v54_verify/tD/15_badpath.png` → `16_badrESULT.png`):
        // a path typed by hand was armed and submitted without ever being
        // listed — the first tap navigated to `/tmp/nope_zzz` and armed it in
        // the same gesture, and the armed body stayed tappable after that
        // listing came back failed. The confirm is now reached only through a
        // directory the server has listed ([verifiedPath]); an unverified
        // target is browsed first, and arms nothing.
        //
        // v56: this browse branch used to be dead code — the footer button was
        // wired `enabled = canConfirm`, and a disabled node dispatches no click,
        // so the only caller that could reach it never fired and a hand-typed
        // path was a dead end (`research/v56_verify/tD/25_noclick.png`). Both
        // entry points now dispatch unconditionally and this branch is what
        // they run for an unverified target: browse, let the listing verify it
        // (`verifiedPath`), and the next tap arms, the one after that submits.
        if (!pickerCanConfirm(target, atDrives, verifiedPath)) {
            pendingConfirm = null
            if (target.isNotEmpty() && target != currentPath) navigate(target)
            return
        }
        when (val step = confirmStep(pendingConfirm, target)) {
            is DirectoryConfirm.Submit -> {
                pendingConfirm = null
                onSelect(step.path)
            }
            is DirectoryConfirm.Armed -> pendingConfirm = step.path
        }
    }

    Column(
        modifier = modifier
            // The IME inset rides the card's own box, as padding *outside* the
            // chrome below: the card is lifted clear of the keyboard while its
            // clip and background stay on the card itself, never on the
            // keyboard strip.
            .imePadding()
            // The nav-bar inset rides the same box: a centered card's bottom
            // margin is only half the leftover window, so caps alone cannot
            // keep the footer clear of the system bar (v47, see above) — this
            // padding plants the whole card above it, gesture pill included.
            .navigationBarsPadding()
            // The status-bar inset rides the same box: a tall card is centered
            // into the full window, so without this its header (title + ✕)
            // slides under the status bar where taps go to the system, never
            // the app (v68, see the caps above).
            .statusBarsPadding()
            // The card may never grow past the visible window. v40 measured the
            // opposite: focused path field, 300 dp of keyboard, and the card —
            // already lifted by [imePadding] — still overflowed the window, so
            // its lower half (the pending-path line, 取消 and 选择此目录) was
            // drawn *under* the keyboard and gone from the uiautomator dump
            // (`research/v40_verify/tA/23_ime_try.png`, `24_ime_ui.xml`): the
            // directory could be browsed but not submitted. The bound plus the
            // body's `weight` below make the card a viewport-shaped layout
            // instead of a content-shaped one.
            .heightIn(max = cardMax)
            .widthIn(max = DIALOG_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(OcTheme.shapes.xl)
            .background(c.bgBase)
            .border(0.5.dp, c.borderBase, OcTheme.shapes.xl)
            .padding(16.dp)
            // P1: 窗口不够高时焦点落在卡片根节点（不是输入框）—— 键盘不自己升起，
            // 见上面 `autofocusPath` 的说明。
            .focusRequester(cardFocus)
            .focusable(),
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
            // The label rides the clickable Box, not the `Icon` below it: only the
            // node carrying ACTION_CLICK is announced as a button, and Compose
            // reads `contentDescription` from a node's own config.
            val closeLabel = stringResource(R.string.oc_back)
            Box(
                modifier = Modifier
                    // 28 dp of pixels, 48 dp of finger (`ocMinTouchTarget`).
                    .ocMinTouchTarget(visual = 28.dp)
                    .clip(OcTheme.shapes.md)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_oc_close),
                    contentDescription = null,
                    tint = c.iconMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // Path input (combobox): any absolute path, `~`/relative per FsBrowse.
        Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OcTheme.shapes.md)
                    .background(c.layer02.copy(alpha = 0.6f))
                    .border(0.5.dp, c.borderBase, OcTheme.shapes.md)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = remember(displayPath, pathSelection) {
                        pickerFieldValue(displayPath, pathSelection)
                    },
                    onValueChange = {
                        // [inputToken] restarts the debounced suggestion read:
                        // it moves only when the *text* moves. IME
                        // composing/autocorrect updates re-emit the same text
                        // with a new composition, and every such bump used to
                        // cancel the settle that reports a missing directory —
                        // so a finished path never settled while the IME held
                        // it (`research/v68_verify/5556/30_badpath.png` →
                        // `31_badpath_wait.png`: 8 s of silence, no error).
                        val textChanged = it.text != pathInput
                        pathInput = it.text
                        pathSelection = it.selection
                        if (textChanged) inputToken += 1
                        userEdited = true
                        pendingConfirm = null
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = c.textBase,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    cursorBrush = SolidColor(c.textBase),
                    // `OcKeyboard.identifier`: the field is a path the app sends
                    // on the wire verbatim, and Gboard rewrote it — `:` and `/`
                    // came out full-width (`C：/`, `/home／x`, measured on the
                    // tablet run `research/v36_verify/tC/tC_41_picker_path2.png`),
                    // which names no directory the server can read. Autocorrect
                    // off + the ASCII layout; the search key still submits.
                    keyboardOptions = OcKeyboard.identifier(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { navigate(pathInput) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .focusRequester(pathFocus)
                        // v62: focusing the field selects it whole, so the first
                        // character the user types *replaces* the path instead of
                        // being appended to it (a hand-typed `/mnt/c/...` grew onto
                        // the browsed path and named no directory). Nothing else
                        // about the field changes — a tap still drops the caret
                        // where it lands, and the typed text still drives the
                        // suggestions, the resolved line and the confirm.
                        .onFocusChanged { state ->
                            if (state.isFocused) pathSelection = pickerFocusSelection(displayPath)
                        },
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (pathInput.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.oc_home_projects_path_hint),
                                    color = c.textFaint,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }

            // Official `dialog.directory.search.placeholder` suggestions.
            if (suggestions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // The dropdown is in flow, so it shares the body's room
                        // rather than growing the card past the window — see
                        // [pickerSuggestionsMax]. The cap is *outside* the 4 dp
                        // inset below, so the whole dropdown stays within the
                        // room the body reserved for it, and `clip` keeps the
                        // rows inside the cap.
                        .heightIn(max = suggestionsMax)
                        .padding(top = 4.dp)
                        .clip(OcTheme.shapes.md)
                        .background(c.layer01)
                        .border(0.5.dp, c.borderBase, OcTheme.shapes.md),
                ) {
                    suggestions.forEach { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ROW_HEIGHT)
                                .clip(OcTheme.shapes.md)
                                .clickable(role = Role.Button) { navigate(suggestion.absolute) }
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
                                text = suggestion.absolute,
                                color = c.textBase,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // `~` / root / parent ghost buttons (official `.directory-picker-actions`).
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GhostNavButton(
                label = "~",
                contentDescription = stringResource(R.string.oc_home_projects_home),
                // The home derived from the path on screen, then the probed
                // location, then the last home this session named
                // ([pickerHomeTarget]): a disabled `~` means "no home to go
                // to", including "already there" — a button that looks live but
                // cannot move is the dead zone v57 measured on `/home`.
                enabled = pickerHomeEnabled(homeTarget, currentPath),
                onClick = { homeTarget?.let { navigate(it) } },
            )
            GhostNavButton(
                // True filesystem root derived from the current path
                // (upstream `pickerRoot(root)`), never the probed ceiling; on
                // the drive level it returns to the first derived root.
                label = stringResource(R.string.oc_home_projects_root),
                enabled = if (atDrives) roots.isNotEmpty() else pickerTrueRoot(currentPath, browseRoot).isNotEmpty(),
                onClick = {
                    if (atDrives) roots.firstOrNull()?.let { navigate(it) }
                    else navigate(pickerTrueRoot(currentPath, browseRoot))
                },
            )
            GhostNavButton(
                label = stringResource(R.string.oc_home_projects_parent),
                enabled = up is PickerUp.Parent || up is PickerUp.Drives,
                onClick = {
                    when (val target = up) {
                        is PickerUp.Parent -> navigate(target.path)
                        // Above a drive root: the drive level ([DRIVES_LEVEL])
                        // listing every derived root, so no drive dead-ends.
                        is PickerUp.Drives -> {
                            userNavigated = true
                            pendingConfirm = null
                            suggestions = emptyList()
                            currentPath = DRIVES_LEVEL
                            pathInput = ""
                            pathSelection = TextRange.Zero
                        }
                        is PickerUp.None -> Unit
                    }
                },
            )
        }

        // The v1 cwd lock: say so instead of silently failing (P0 UX fix).
        if (showLockHint) {
            Text(
                text = stringResource(R.string.oc_home_projects_escape_hint),
                color = c.textFaint,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        // Subdirectory list. While confirming, the list gives way to the full
        // pending path (last segment emphasised) so a silent drift into a
        // child such as `.agents` is visible before it is submitted.
        //
        // The confirm body is itself the second tap (v49 tD_59→61): the whole
        // row is clickable and submits exactly what the footer button's second
        // tap would ([tapConfirm]). Before, the row only displayed the path —
        // it looked like the confirm target but tapped dead, while the footer
        // button kept its first-stage label, so nothing told the user the card
        // was armed and waiting.
        //
        // Both states take the **one fixed** [LIST_HEIGHT], never a content-
        // derived one: the footer below them is the tap target, and a body that
        // resizes under the finger moves it. The v30 device run measured the
        // cost of the old `heightIn(min, max)`: browsing `/home/xiufeigo` (many
        // entries → 320 dp) and then a 3-entry project directory (200 dp) moved
        // 「选择此目录」 by 146 px, so a tap aimed from the previous frame landed
        // *below* the dialog — on the host's scrim, whose tap-outside handler
        // closes the whole picker. No `onSelect`, no project, no request, no
        // log: the silent no-op this fixes.
        //
        // [bodyMax] is the one cap on top of it, and it is a *viewport* bound,
        // not a content one: with the keyboard up the window has no room for the
        // full body, and the body is what gives way so the footer stays on
        // screen. It reads neither the listing nor the row count — the only
        // things that move it are the keyboard coming up and the suggestion
        // dropdown opening, i.e. the window's room — so the footer still cannot
        // move between two frames of the same window.
        //
        // The body carries the column's `weight`, so it is what absorbs the
        // keyboard: the resolved-path line and the footer sit *after* it and
        // therefore stay on screen in every state (v40 BUG-1, the bug this
        // fixes). `fill = false` keeps the old frame when the window has room —
        // the body is still [LIST_HEIGHT] and the footer still cannot move
        // between two frames of the same window — and it is the `LazyColumn`
        // inside that scrolls once the body is squeezed.
        val confirming = pendingConfirm
        // v63: the body list's scroll offset is *state*, so it survived a
        // navigation and the next directory opened at the tail of the previous
        // one — the short-window card shows two rows at a time, and a list that
        // opens on its last entry reads as a list that will not scroll back
        // (`research/v63_verify/5556/51_prog3.png`, `52_scrolltest.png`: the
        // swipes changed nothing because the rows on screen were the bottom of
        // an offset left over from a longer listing). Every browsed path starts
        // its own listing at the top, exactly like the row tap that navigated.
        val listState = rememberLazyListState()
        LaunchedEffect(currentPath) { if (nodes != null) listState.scrollToItem(0) }
        if (confirming != null) {
            val cut = confirmPathTail(confirming)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .weight(1f)
                    .heightIn(min = LIST_MIN_HEIGHT, max = bodyMax)
                    .clip(OcTheme.shapes.md)
                    .background(c.layer01)
                    .clickable(role = Role.Button, onClick = { tapConfirm() })
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = c.textFaint)) {
                            append(confirming.substring(0, cut))
                        }
                        withStyle(SpanStyle(color = c.textBase, fontWeight = FontWeight.Bold)) {
                            append(confirming.substring(cut))
                        }
                    },
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // v63: 4 dp of inset, not 8. On the short window the whole body
                // is ~60 dp (`research/v63_verify/5556/55_path3.png` measures the
                // rows at two), and a third of that was padding — the browser
                // showed one and a half rows. The chrome it costs is one eighth
                // of a row of dead space in every other window.
                .padding(vertical = 4.dp)
                .weight(1f)
                .heightIn(min = LIST_MIN_HEIGHT, max = bodyMax)
                .clip(OcTheme.shapes.md)
                .background(c.layer01),
        ) {
            val directories = nodes.orEmpty().filter { it.isDirectory && !it.ignored }
            when {
                // Virtual drive level: synthetic rows for the derived root
                // set — no backend listing involved (drives are not listable).
                atDrives -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(driveEntries(roots), key = { it.absolute }) { node ->
                        DirectoryRow(name = node.name) { navigate(node.absolute) }
                    }
                }

                loading || currentPath.isEmpty() -> DialogHint(
                    text = stringResource(R.string.oc_home_projects_loading),
                    modifier = Modifier.align(Alignment.Center),
                )

                failed -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DialogHint(text = failureText(failure))
                    // Status-only failures get no second line: the friendly text
                    // above already says the directory is missing, and a bare
                    // `HTTP 500` under it is the raw body repeating itself
                    // (v40 measured exactly that double rendering). Same
                    // [STATUS_ONLY_ERROR] split [pickerFailureRes] uses.
                    val detail = failureDetail(failure)
                    if (!detail.isNullOrBlank()) {
                        Text(
                            text = detail,
                            color = c.textFaint,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    val fail = failure
                    if (fail is TransportFailure.AuthRequired && onOpenServers != null) {
                        NeutralButton(
                            label = stringResource(R.string.oc_servers_edit),
                            iconRes = R.drawable.ic_oc_edit,
                            onClick = onOpenServers,
                        )
                    }
                    NeutralButton(
                        label = stringResource(R.string.retry),
                        iconRes = R.drawable.ic_oc_edit,
                        onClick = {
                            chatViewModel.retryProbe()
                            attempt += 1
                        },
                    )
                }

                directories.isEmpty() -> DialogHint(
                    text = stringResource(R.string.oc_home_projects_empty_dir),
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(directories, key = { it.absolute }) { node ->
                        DirectoryRow(name = node.name.ifEmpty { node.absolute }) { navigate(node.absolute) }
                    }
                }
            }
        }
        }

        // Resolved path line (official `.directory-picker-selection`); on the
        // drive level nothing is selected yet, so it names the level instead.
        Text(
            text = if (atDrives) stringResource(R.string.oc_home_projects_drives) else displayPath,
            color = c.textFaint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        // Footer: cancel + select (official `DialogFooter`). The first tap on
        // select arms a confirm showing the full path; the second submits it.
        // The armed stage reads differently ([pickerConfirmLabelRes]):「选择此目录」
        // becomes 「确认选择此目录」, so the armed card is recognisable —
        // v49 measured the two stages byte-identical, with the user tapping
        // the dead confirm row instead of the button.
        //
        // A failed listing disables select (upstream `dialog.tsx`:
        // `disabled={!policy.result(...)}`). v40 measured the button still lit
        // accent-cyan and tappable under 「目录不存在或无法读取」, so a directory
        // the server cannot even list — typically a path the user typed by hand
        // — was one tap away from being added as a project.
        //
        // v54 P1: the gate reads the **target** (the armed path, else the path
        // the button would arm), not the path on screen — so a name typed into
        // the field but not yet browsed can no longer be armed, and an armed
        // path whose own listing failed greys out instead of staying tappable.
        //
        // v56: greyed is a look, not a dead button — see the `clickable` below.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            NeutralButton(
                label = stringResource(R.string.oc_common_cancel),
                iconRes = R.drawable.ic_oc_close,
                onClick = { if (confirming != null) pendingConfirm = null else onClose() },
            )
            Box(
                modifier = Modifier
                    .alpha(if (canConfirm) 1f else 0.4f)
                    .height(28.dp)
                    .clip(OcTheme.shapes.md)
                    .background(c.accent)
                    .clickable(
                        // v56 (`research/v56_verify/tD/25_noclick.png`,
                        // `28_namesearch.png`): the greyed button was wired
                        // `enabled = canConfirm`, and a disabled Compose node
                        // **dispatches no click at all** — so the 「未验证就先去
                        // 浏览该路径」 branch in [tapConfirm] was unreachable dead
                        // code and a hand-typed path was a dead end: the only
                        // action the button could ever have was the arm/submit it
                        // was forbidden to take. The *gate* is unchanged and
                        // still decides what the tap may submit ([pickerCanConfirm]
                        // → `verifiedPath`): a greyed tap now browses the typed
                        // path, the listing verifies it, the button lights up and
                        // the same two-stage confirm runs from there. Only the
                        // dispatch is unconditional now; the decision never is.
                        role = Role.Button,
                        onClick = { tapConfirm() },
                    )
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(pickerConfirmLabelRes(confirming != null)),
                    color = c.inverse,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * `ButtonV2 variant="ghost"`: text-only, 28 dp tall (`dialog.tsx` nav row).
 *
 * Both states have to be visible, not just the label colour (v40 BUG-4): with
 * no home to jump to, `~` was a dead tap whose two screenshots were byte
 * identical to the eye, and a *working* `~` that navigates to the directory
 * already on screen looks just as dead — the body does not change, so there is
 * nothing to see. So a disabled button is dimmed outright, and an enabled one
 * paints a press/hover plate the way the upstream ghost button does.
 */
@Composable
private fun GhostNavButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    val c = OcTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .height(28.dp)
            .clip(OcTheme.shapes.md)
            .background(if (enabled && pressed) c.layer02 else Color.Transparent)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            // The description belongs on the clickable node, not on the `Text`
            // below it: Compose announces only the node that carries ACTION_CLICK,
            // and a descendant's `contentDescription` is not merged into it.
            .then(
                if (contentDescription == null) {
                    Modifier
                } else {
                    Modifier.semantics { this.contentDescription = contentDescription }
                },
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (enabled) c.textBase else c.textFaint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DialogHint(text: String, modifier: Modifier = Modifier) {
    Text(text = text, color = OcTheme.colors.textFaint, fontSize = 12.sp, modifier = modifier)
}

/** One subdirectory (or drive) row of the browser body. */
@Composable
private fun DirectoryRow(name: String, onClick: () -> Unit) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(OcTheme.shapes.md)
            .clickable(role = Role.Button, onClick = onClick)
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
            text = name,
            color = c.textBase,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DIALOG_WIDTH = 480.dp

/**
 * Height of the browser body — **fixed**, shared by the directory list and the
 * confirm state (see the body's comment).
 *
 * It is the picker's own stable frame, not a measurement of its content: the
 * list and the pending-path view both take exactly this, so the footer under
 * them never moves between frames. `heightIn(min, max)` (the previous shape)
 * let the body follow the listing — 200 dp for a 3-entry directory, 320 dp for
 * `/home/xiufeigo` — which slid 「选择此目录」 by 146 px between two frames of
 * the same dialog. A tap aimed from the previous frame then fell outside the
 * dialog, onto the host scrim, whose tap-outside handler dismissed the picker
 * silently (v30 device run: the project was never added, nothing was logged).
 */
private val LIST_HEIGHT = 320.dp
private val ROW_HEIGHT = 32.dp
private const val SEARCH_DEBOUNCE_MS = 200L
private const val MAX_SUGGESTIONS = 8

/**
 * How long a typed path must stay untouched before a failed read of it is
 * reported as a missing directory (v67). Long enough that a still-typing
 * segment never flashes the error card, short enough that a finished path that
 * does not exist is answered while the finger is still on the keyboard.
 */
private const val PICKER_MISSING_SETTLE_MS = 700L

/**
 * Everything the card spends outside the browser body: the 16 dp card padding
 * top and bottom, the header (28), the path input (12 + 36), the nav row
 * (8 + 28), the body's own 4 + 4, the resolved-path line (~17) and the footer
 * (12 + 28) — 217 dp, plus the v1 lock hint's ~21 dp so a locked transport does
 * not overflow the window either. Deliberately generous: a few dp of slack
 * costs nothing, a few dp short puts the footer back off-screen.
 */
private val PICKER_CHROME_HEIGHT = 240.dp

/** Floor for [pickerBodyMax]: a two-row browser beats a dialog that does not fit. */
private val LIST_MIN_HEIGHT = 96.dp

/** 焦点请求失败时的重试帧数（首帧可能还没 attach）。 */
private const val PICKER_FOCUS_ATTEMPTS = 4

/**
 * The rows the card must never lose: the 16 dp card padding top and bottom, the
 * header (28), the path input (12 + 36), the nav row (8 + 28) and the footer
 * (12 + 28) — 184 dp. It is the *floor* of [pickerCardMax] and the budget every
 * optional row is taken out of.
 *
 * v51 (`research/v51_verify/tE/v51_22_movedlg.png` … `v51_30_selrow.png`, a
 * 1440×810 @420 dpi window — 308 dp of height): the insets left the card less
 * than [PICKER_CHROME_HEIGHT], so the browser body was squeezed to nothing
 * (its rows measured `[0,0][0,0]`) **and** the footer was the block that fell
 * off the card — the whole window showed a path field, a nav row, the resolved
 * path and a ✕, and nothing else. The 移动到… dialog was a dead end there and
 * fine at the same dialog on a 1344×2992 phone, because the only thing that
 * changed was how much of the fixed rows the window could hold.
 */
private val PICKER_FOOTER_HEIGHT = 184.dp

/** The resolved-path line (11 sp text, one line). Reserved like the body. */
private val PICKER_RESOLVED_LINE_HEIGHT = 17.dp

/** The v1 lock hint (11 sp, one line) — see [pickerOptionalFits]. */
private val PICKER_HINT_HEIGHT = 24.dp

/**
 * The suggestion dropdown at its tallest: the 4 dp inset above the rows plus
 * [MAX_SUGGESTIONS] rows of [ROW_HEIGHT]. Capping there costs nothing in the
 * normal case — the dropdown is *allowed* to be as tall as it naturally is —
 * and gives [pickerSuggestionsMax] a bound. The 4 dp is inside the cap, so the
 * dropdown's own box (inset, background and border included) stays within the
 * room [pickerBodyMax] reserves for it.
 */
private val SUGGESTIONS_MAX_HEIGHT = (MAX_SUGGESTIONS * ROW_HEIGHT.value).dp + 4.dp

/**
 * Height the suggestion dropdown may take inside a [windowHeight]-tall window
 * whose keyboard covers [imeBottom] of it.
 *
 * The dropdown is **in flow** (upstream floats it, `dialog.css:23-36`
 * `position: absolute`), so it is drawn by pushing everything below it down:
 * uncapped it grows the card past the window while the user is typing, and the
 * block that falls off is the path field itself — measured on a 914 dp phone
 * with a 300 dp keyboard, an 8-row dropdown put the card's top 105 dp above the
 * screen. It therefore shares the body's room ([pickerBodyMax]) and never takes
 * more than what is left over a two-row body.
 */
internal fun pickerSuggestionsMax(windowHeight: Dp, imeBottom: Dp): Dp =
    (windowHeight - imeBottom - PICKER_CHROME_HEIGHT - LIST_MIN_HEIGHT)
        .coerceIn(0.dp, SUGGESTIONS_MAX_HEIGHT)

/**
 * Height the browser body may take inside a [windowHeight]-tall window whose
 * keyboard covers [imeBottom] of it (the `CommandPalette` read: the app is
 * edge-to-edge, so the IME is drawn *over* the composition and never shrinks
 * the window). [LIST_HEIGHT] whenever the window has room for the whole card —
 * every phone and tablet with the keyboard down — and the leftover otherwise,
 * so the footer (取消 / 选择此目录) stays on screen instead of behind the IME.
 * [suggestionsOpen] hands the dropdown its share of that leftover first.
 *
 * A **viewport** bound, never a content one: it does not read the listing, so
 * the footer still cannot move between two frames of the same window (the v30
 * regression [LIST_HEIGHT] documents).
 */
internal fun pickerBodyMax(windowHeight: Dp, imeBottom: Dp, suggestionsOpen: Boolean = false): Dp {
    val dropdown = if (suggestionsOpen) pickerSuggestionsMax(windowHeight, imeBottom) else 0.dp
    val wanted = (windowHeight - imeBottom - PICKER_CHROME_HEIGHT - dropdown)
        .coerceIn(LIST_MIN_HEIGHT, LIST_HEIGHT)
    // …and never more than the card can hold with its fixed rows: the footer is
    // the block that falls off a short window last, so the body is what gives
    // way (v51, see [PICKER_FOOTER_HEIGHT]). The dropdown shares the same room,
    // since it is in flow directly above the footer.
    val room = (pickerBodyRoom(pickerCardMax(windowHeight, imeBottom)) - dropdown).coerceAtLeast(0.dp)
    return wanted.coerceAtMost(room)
}

/**
 * Room the browser body may take inside a card capped at [cardMax]: what is
 * left of the card once the rows that must never be clipped are paid for
 * ([PICKER_FOOTER_HEIGHT]) plus the resolved-path line, which always reads the
 * same text the path field does.
 *
 * Every cap is derived from this one subtraction, so
 * `PICKER_FOOTER_HEIGHT + PICKER_RESOLVED_LINE_HEIGHT + bodyMax ≤ cardMax` holds
 * by construction — the invariant the v51 dead end broke.
 */
internal fun pickerBodyRoom(cardMax: Dp): Dp =
    (cardMax - PICKER_FOOTER_HEIGHT - PICKER_RESOLVED_LINE_HEIGHT).coerceAtLeast(0.dp)

/**
 * Whether an **optional** row of [height] (the v1 lock hint) still fits below
 * the body without pushing the footer off the card. Optional rows give way
 * before the footer does; the body gives way before they do.
 */
internal fun pickerOptionalFits(cardMax: Dp, bodyMax: Dp, height: Dp): Boolean =
    bodyMax + height <= pickerBodyRoom(cardMax)

/**
 * The Windows drive root of [path] in the canonical `C:/` spelling
 * ([browseRoots]' uppercase), or `""` when [path] names no drive — `"/"`,
 * a UNC share, a relative path.
 */
internal fun pickerDriveRoot(path: String): String {
    val root = FsBrowse.root(path)
    val drive = root.length == 3 && root[0].isLetter() && root[1] == ':' && root[2] == '/'
    return if (drive) "${root[0].uppercaseChar()}:/" else ""
}

/**
 * Upstream `createDirectorySearch`'s `scoped()` result
 * (`_upstream/packages/app/src/workspaces/selection/domain.ts:350-361`)
 * collapsed to what a single-query `searchDirectories` can consume:
 * [directory] is the scope the input resolves to (root / home / base plus
 * every segment but the last — the segment walk of `:415-428` collapsed to a
 * literal join), [name] is the last segment as the name query, and [pathLike]
 * is upstream's `pathInput` discriminator (`:390`) that decides how results
 * are scoped. Trailing-`/` and bare-`~` inputs keep an empty [name] (children
 * listing upstream).
 */
internal data class ScopedSearch(
    val directory: String,
    val name: String,
    val pathLike: Boolean,
)

/** `scoped()` + segment walk for the picker's debounced suggestion search. */
internal fun scopedSearch(input: String, home: String?, base: String): ScopedSearch {
    val raw = FsBrowse.normalizeDrive(input.trim())
    val root = FsBrowse.root(raw)
    val homeDirectory = home?.takeIf { it.isNotBlank() }
    val scopeRoot: String
    val path: String
    when {
        root.isNotEmpty() -> {
            scopeRoot = FsBrowse.trimEnd(root)
            path = raw.substring(root.length)
        }
        raw == "~" || raw.startsWith("~/") -> {
            // `home || base` upstream: an unanswered home falls back to base.
            scopeRoot = FsBrowse.trimEnd(homeDirectory ?: base)
            path = if (raw == "~") "" else raw.substring(2)
        }
        else -> {
            scopeRoot = FsBrowse.trimEnd(base)
            path = raw
        }
    }
    val pathLike = root.isNotEmpty() || raw == "~" || raw.startsWith("~/") || raw.contains('/')
    val segments = path.split('/').filter { it.isNotEmpty() && it != "." }
    val tail = if (path.endsWith("/")) "" else segments.lastOrNull().orEmpty()
    val head = if (path.endsWith("/")) segments else segments.dropLast(1)
    val directory = FsBrowse.canonical(FsBrowse.join(scopeRoot, head.joinToString("/")))
    return ScopedSearch(directory, tail, pathLike)
}

/**
 * Upstream's result scoping (`treePathWithin` for a bare word, the segment
 * walk's direct-child matches for a path input): fuzzy hits outside the
 * scoped directory must not surface. Relative results (v1 `/find/file` answers
 * cwd-relative paths) are anchored on [root] — the transport's browse root —
 * before the comparison.
 *
 * Kept as the guard for any candidate that did not come from [scope.directory]
 * itself; the suggestion read now lists that directory directly, so every row
 * it yields is a child of the scope by construction.
 */
internal fun filterScoped(results: List<DirEntry>, scope: ScopedSearch, root: String): List<DirEntry> =
    results.filter { entry ->
        if (!entry.isDirectory || entry.ignored) {
            false
        } else {
            val absolute = FsBrowse.absolute(entry.absolute, root)
            if (scope.pathLike) {
                HomeProjects.parentOf(absolute)
                    ?.let { FsBrowse.trimEnd(it) == FsBrowse.trimEnd(scope.directory) } == true
            } else {
                FsBrowse.isWithin(scope.directory, absolute)
            }
        }
    }

/**
 * The directory a **complete** typed path resolves to, browsed without the IME's
 * Search key (v63), or `null` when the input does not name one directory yet.
 *
 * The answer comes from the listing the debounced suggestion read already made,
 * never from a guess: a path is browsable here only when [entries] — the
 * children of [ScopedSearch.directory] — hold exactly one directory whose name is
 * the typed last segment. A half-typed segment matches none (or several), the
 * dropdown offers them, and the listing is left alone; a path that does not exist
 * is never probed, so the error card stays the answer to a deliberate lookup
 * rather than to a keystroke.
 *
 * Only **rooted** input qualifies (`/…`, `~…`, a drive or a drive-less
 * `c/…`). A relative name typed while browsing resolves against the current
 * directory, so auto-browsing it would move the base out from under the rest of
 * the word being typed — the field's own Enter key and the suggestion rows stay
 * the way a relative segment is committed.
 *
 * [currentPath] is skipped: a settled path must not re-list on every keystroke.
 */
internal fun pickerAutoBrowseTarget(
    input: String,
    scope: ScopedSearch,
    entries: List<DirEntry>,
    currentPath: String,
): String? {
    val raw = input.trim()
    val rooted = raw.startsWith("/") ||
        raw.startsWith("~") ||
        FsBrowse.root(FsBrowse.normalizeDrive(raw)).isNotEmpty() ||
        DRIVE_INPUT.matches(raw)
    if (!rooted) return null
    val name = scope.name.trim()
    if (name.isEmpty()) return null
    val exact = entries.filter { it.name.equals(name, ignoreCase = true) }
    if (exact.size != 1) return null
    val target = exact.single().absolute
    return target.takeIf { it.isNotEmpty() && !pickerSamePath(it, currentPath) }
}

/**
 * The directory a **rooted, complete** typed path names, resolved on its own —
 * the second of the two auto-browse routes (v65, `research/v65_verify/5556/
 * 57_path_typed.png`), used when [pickerAutoBrowseTarget] cannot answer.
 *
 * The caller reads the returned path and browses it only if the server lists
 * it, so this stays a *naming* function: it decides what a settled absolute
 * path would mean, not whether it exists.
 *
 * - **rooted only** (`/…`, `~…`, a drive or a drive-less `c/…`): a relative
 *   name resolves against the current directory, and auto-browsing it would
 *   move the base out from under the rest of the word being typed.
 * - **a trailing `/` is not complete**: it asks for the scope's children, not
 *   for one directory.
 * - the path already on screen is skipped, so a finished path does not re-list
 *   on every keystroke.
 */
internal fun pickerDirectBrowseTarget(
    input: String,
    home: String?,
    base: String,
    currentPath: String,
): String? {
    val raw = input.trim()
    if (raw.isEmpty() || raw.endsWith("/") || raw.endsWith("\\")) return null
    val normalized = FsBrowse.normalizeDrive(raw)
    val rooted = normalized.startsWith("/") ||
        normalized.startsWith("~") ||
        FsBrowse.root(normalized).isNotEmpty() ||
        DRIVE_INPUT.matches(normalized)
    if (!rooted) return null
    val resolved = pickerResolveInput(raw, home, base)
    if (resolved.isEmpty() || pickerSamePath(resolved, currentPath)) return null
    return resolved
}

/**
 * Whether a failed read of the auto-browse target should be reported as a
 * **missing directory** (v67) instead of being swallowed.
 *
 * Three conditions, all of which a half-typed path fails:
 * - the server could not list the target ([listed] is false) — the only failure
 *   worth reporting; a readable path is browsed outright;
 * - the field has **settled** ([settled]: the debounce read the *same* input
 *   after [PICKER_MISSING_SETTLE_MS] of silence, so the user stopped typing);
 * - no child of [scope] fuzzy-matches the typed tail — while the user is still
 *   inside a segment the dropdown is offering candidates, and the exact-match
 *   route ([pickerAutoBrowseTarget]) is what commits one of them.
 */
internal fun pickerMissingBrowse(
    scope: ScopedSearch,
    entries: List<DirEntry>,
    listed: Boolean,
    settled: Boolean,
): Boolean {
    if (listed || !settled) return false
    val name = scope.name.trim()
    if (name.isEmpty()) return false
    return entries.none { it.isDirectory && !it.ignored && pickerNameMatches(it.name, name) }
}

/**
 * The settled effect's single verdict for a complete typed path (v68): which
 * path to browse, or `null` to stay where the browser is.
 *
 * A path the server can list is browsed outright — the answer the IME's Search
 * key gives, without requiring it. One it cannot is browsed only as a
 * **missing directory** ([pickerMissingBrowse]): settled, and no child of the
 * scope fuzzy-matches the typed tail, so a half-typed segment never flashes
 * the error card — and the listing then answers with the error card + Retry
 * instead of the old directory with no hint (bug 3 is covered by that answer).
 * Staying put covers the half path, the already-browsed path and the
 * fuzzy-matched tail alike.
 */
internal fun pickerMissingDecision(
    currentPath: String,
    direct: String?,
    scope: ScopedSearch,
    entries: List<DirEntry>,
    listed: Boolean,
): String? {
    if (direct.isNullOrEmpty() || pickerSamePath(direct, currentPath)) return null
    if (listed) return direct
    return direct.takeIf { pickerMissingBrowse(scope, entries, listed = false, settled = true) }
}

/**
 * The remembered last-used directory as a picker [initialPath]: blank memory
 * means "no memory" (null, so the probes decide), and a remembered Windows
 * system directory is refused exactly like a probed one ([isSystemStart] —
 * the server launch cwd is never a sane start, even when the user once
 * browsed through it). Anything else starts the browser verbatim; the
 * probe adoption ([resolvePickerStart]) leaves a non-empty start alone — a
 * system one excepted, which it re-roots — and the listing simply fails
 * visibly if the directory is gone.
 *
 * This is Home's half only: [pickerEntryStart] is the same refusal on the
 * picker's own entry, so a caller that hands a path over verbatim is covered
 * too.
 */
internal fun pickerRememberedStart(lastUsed: String?): String? {
    val start = lastUsed?.trim().orEmpty()
    if (start.isEmpty() || isSystemStart(start)) return null
    return start
}

/**
 * The picker's own entry guard: the starting state for [initialPath], whatever
 * the caller passed.
 *
 * [pickerRememberedStart] guards Home's remembered directory, but that is a
 * *caller* guard — `SessionActionsDialogs.MoveSurface` hands the session's own
 * server-confirmed directory straight through, and a session running in the
 * server's launch cwd (`C:\Windows\System32` is what a Windows host reports —
 * the same value v2 returns as the *location*) parked the browser there: the
 * listing showed System32's subdirectories and the footer path named System32,
 * with the user reporting a locked directory. The guard belongs on the entry,
 * not on one of its callers, so every entry gets it — including a future one.
 *
 * A system start re-roots to the drive root ([pickerDriveRoot], `C:/`), which is
 * browsable and is what the probe adoption ([resolvePickerStart]) does with a
 * probed system directory. Everything else starts verbatim, blank stays blank
 * (the probes then decide the start).
 *
 * A drive-less spelling (`/Windows/System32`) is not caught here — it needs the
 * probed root to be read as a Windows path at all, and the callback does catch
 * it there ([resolvePickerStart]).
 */
internal fun pickerEntryStart(initialPath: String?): String {
    val start = initialPath?.trim().orEmpty()
    if (start.isEmpty() || !isSystemStart(start)) return start
    return pickerDriveRoot(start)
}

/**
 * Where the 「~」 button goes: the server user's home derived from the path the
 * browser is **on** ([FsBrowse.homeOf] of `current`), then from the probed
 * location, and `null` when neither names a home — the button then stays
 * disabled instead of promising a directory nobody knows.
 *
 * The location alone is not enough (v33 BUG-1, `research/v33_verify/tA/06,07`):
 * on v2 it is the **server process cwd**, not `$HOME` — measured
 * `/mnt/c/sensorsdata/main/program/opencode-android-shell` (opencode serve
 * launched from a WSL mount). [FsBrowse.homeOf] names no home there, so the
 * target used to fall back to the location itself and `~` jumped away from the
 * browsed `/home/xiufeigo/v32projA` to that cwd. The path on screen is the
 * honest source: `/home/xiufeigo/v32projA` → `/home/xiufeigo`.
 *
 * The location remains a *second* source — a browser parked on a directory that
 * names no home can still use the project's own home — but it is never the
 * target itself. That raw-location fallback is gone: it is exactly what made
 * `~` a silent no-op when the picker already sat on the location
 * (v32 `tB/18_picker_home.png`).
 *
 * [remembered] is the last home the browser *did* name, kept for the session.
 * It is what makes `~` work one level **above** the home
 * (v57, `research/v57_verify/E2ECHK5560/13-16`): `/home` names no user segment,
 * so `/home/xiufeigo` is not derivable from the path on screen and the probed
 * location names no home either — the button went grey on the one directory
 * whose whole purpose is to be a step away from home. The remembered target
 * answers exactly that case and never invents one: with nothing ever named a
 * home the answer is still `null`.
 *
 * [fallback] is the last resort behind all three: the probed location itself
 * (the server's working directory), taken raw instead of derived. On a server
 * that names no home anywhere — a WSL/Windows cwd such as `/mnt/c/…`, where
 * every source above answers `null` and `~` used to sit grey and dispatch
 * nothing (`research/v68_verify/5560/75_tilde.png`) — `~` jumps back to the
 * base the session runs from instead of staying a dead zone. A derived home
 * always outranks it, and [pickerHomeEnabled] still greys the button while
 * already there, so the fallback can never be the v32 dead tap.
 */
internal fun pickerHomeTarget(
    current: String?,
    location: String?,
    remembered: String? = null,
    fallback: String? = null,
): String? =
    FsBrowse.homeOf(current) ?: FsBrowse.homeOf(location) ?: FsBrowse.homeOf(remembered)
        ?: fallback?.takeIf { it.isNotBlank() }

/**
 * Enablement of the 「~」 ghost button: a home [pickerHomeTarget] actually named
 * (v40 BUG-4) **and** not the directory the browser already sits on. Exposed as
 * its own function so the *disabled* half of the button's contract is pinned by
 * a test — the measured failure was a `~` that could not go anywhere at all yet
 * looked and behaved exactly like a live one. Already being home is the same
 * dead tap wearing a live button's colours (v57), so it greys out too.
 */
internal fun pickerHomeEnabled(homeTarget: String?, current: String? = null): Boolean =
    !homeTarget.isNullOrBlank() && !pickerSamePath(homeTarget, current)

/** Path identity for the picker's own decisions — canonicalised, trailing separator irrelevant. */
internal fun pickerSamePath(left: String?, right: String?): Boolean {
    val a = left?.trim().orEmpty()
    val b = right?.trim().orEmpty()
    if (a.isEmpty() || b.isEmpty()) return false
    return FsBrowse.canonical(a) == FsBrowse.canonical(b)
}

/**
 * Whether a directory [name] is a suggestion for the typed [query] — upstream's
 * `fuzzy` over the segment walk's candidates (`domain.ts:415-428`), collapsed
 * to a case-insensitive **subsequence** match so a half-typed segment still
 * offers its directory (`openc` → `opencode`).
 *
 * An empty query (a bare `~`, a root, a trailing `/`) matches everything: the
 * input then asks for the scope's children, not for one of them by name.
 */
internal fun pickerNameMatches(name: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val candidate = name.lowercase()
    var matched = 0
    for (char in candidate) {
        if (matched < q.length && char == q[matched]) matched++
    }
    return matched == q.length
}

/**
 * Whether a listing read launched for [requested] still describes the browser.
 *
 * [ChatViewModel.listFiles] answers from the view model's own scope, so a read
 * the user has already navigated away from can land late — v54 tD
 * (`research/v54_verify/tD/17_nodir_err.png`) measured `/home/xiufeigo`, which
 * exists, reporting 「不存在」 because the *previous* path's failure arrived
 * after the new read had started. A late answer for a path that is no longer on
 * screen is dropped rather than painted.
 */
internal fun pickerReadIsCurrent(requested: String, currentPath: String): Boolean =
    requested == currentPath

/**
 * Enablement of 「选择此目录」 (upstream `dialog.tsx`:
 * `disabled={!policy.result(...)}`).
 *
 * A drive-level browsing state selects nothing, an empty path names nothing,
 * and a path the server has not listed names something it cannot read: v40
 * measured the button still accent-cyan and tappable under 「目录不存在或无法读取」,
 * one tap away from adding a nonexistent directory as a project.
 *
 * v54 P1 (`research/v54_verify/tD/15_badpath.png` → `16_badrESULT.png`): the
 * old `failed` flag described the listing of `currentPath`, while the button
 * armed the **typed** path — so typing `/tmp/nope_zzz` and tapping Select armed
 * a directory nobody had ever listed, and the armed row submitted it after that
 * listing failed. The gate therefore takes the target and the last path the
 * server did list ([verifiedPath]); the two must be the same string. A verified
 * *empty* directory still selects (an empty listing is a directory, `null` is
 * not), and re-navigating drops the verification until the new read answers.
 */
internal fun pickerCanConfirm(target: String, atDrives: Boolean, verifiedPath: String?): Boolean =
    target.isNotEmpty() && !atDrives && verifiedPath != null && verifiedPath == target

/**
 * The footer confirm's label per two-tap stage (v49 tD_59→61): unarmed it
 * invites the first tap (arm); armed it names the submit, so the armed card
 * is recognisable — the two stages used to read byte-identical while the
 * confirm body tapped dead. Pure so the stage split stays unit-testable.
 */
@StringRes
internal fun pickerConfirmLabelRes(armed: Boolean): Int =
    if (armed) R.string.oc_home_projects_confirm_directory else R.string.oc_home_projects_select_directory

/**
 * The card's own ceiling: the window minus the covered bottom strip, less a
 * gutter — and never less than [PICKER_FOOTER_HEIGHT], the rows the card cannot
 * do without ([pickerBodyRoom] spends what is left of this).
 *
 * The covered strip is capped at `window − PICKER_MIN_VIEWPORT` first. An inset
 * that leaves the picker less than a usable viewport cannot be telling the truth
 * about a keyboard — it is the navigation bar plus a keyboard inset that has
 * not been released, or a window too short for the dialog at all. Either way,
 * believing it produced the v51 dead end: a card shorter than its own fixed
 * rows, an empty browser body and no footer. Above the cap nothing changes: a
 * 914 dp phone with a 300 dp keyboard (v40) keeps the same `606 dp` card and
 * the same `320 dp` body.
 */
internal fun pickerCardMax(windowHeight: Dp, imeBottom: Dp): Dp {
    val believable = (windowHeight - PICKER_MIN_VIEWPORT).coerceAtLeast(0.dp)
    val covered = imeBottom.coerceIn(0.dp, believable)
    return (windowHeight - covered - CARD_GUTTER).coerceAtLeast(PICKER_FOOTER_HEIGHT)
}

/**
 * The smallest window the picker is still worth opening in: the rows that must
 * never be clipped, the resolved-path line and a two-row browser
 * ([LIST_MIN_HEIGHT]). The covered strip is not believed past this point — see
 * [pickerCardMax].
 */
private val PICKER_MIN_VIEWPORT = PICKER_FOOTER_HEIGHT + PICKER_RESOLVED_LINE_HEIGHT + LIST_MIN_HEIGHT

/** Breathing room between the card and the window edge / keyboard top. */
private val CARD_GUTTER = 8.dp

/**
 * Starting-path adoption for the constraints callback: null keeps the current
 * path. Adopts only while the user has not navigated anywhere meaningful —
 * an empty path, or one still sitting on the previous root fallback (the
 * credential/probe window answers no home, so the first open parks on `/`
 * and must move once the home arrives).
 *
 * One thing outranks that rule: a **Windows system directory** is never left on
 * screen. The early return above (`currentPath.isNotEmpty() && currentPath !=
 * previousRoot`) is what the non-empty `initialPath` of the 移动到… surface hits
 * — `SessionActionsDialogs.MoveSurface` passes the session's own directory, so
 * the picker used to keep `C:\Windows\System32` (the server's launch cwd, which
 * v2 also reports as the location) for the whole session, System32 listed in
 * the body and named in the footer. The check therefore runs **before** the
 * early return, and it reads the path through [FsBrowse.absolute] so the
 * drive-less spelling (`/Windows/System32`, or the MSYS `/mnt/c/…` twin) is
 * judged against the probed root as the Windows path it is. A user who navigated
 * there on purpose is untouched ([userNavigated] returns first).
 */
internal fun resolvePickerStart(
    currentPath: String,
    previousRoot: String,
    userNavigated: Boolean,
    home: String?,
    root: String,
): String? {
    if (userNavigated) return null
    if (isSystemStart(FsBrowse.absolute(currentPath, root))) {
        // The drive root, then the probed root: browsable either way, never the
        // directory the user reported as locked.
        val safe = pickerDriveRoot(FsBrowse.absolute(currentPath, root)).ifEmpty { root }
        return safe.takeIf { it.isNotBlank() && it != currentPath }
    }
    if (currentPath.isNotEmpty() && currentPath != previousRoot) return null
    val start = home?.takeIf { it.isNotBlank() } ?: root
    if (start.isBlank() || start == currentPath) return null
    if (isSystemStart(start)) {
        // v2 has no home field: the location is the server launch cwd, which
        // on a Windows host is easily `C:\Windows\System32` — never a sane
        // start. Fall back to the drive root derived client-side (then the
        // probed root), so the picker opens browsable instead of parked.
        val safe = FsBrowse.root(start).ifEmpty { root }
        return safe.takeIf { it.isNotBlank() && it != currentPath }
    }
    return start
}

/**
 * The browsed path after the probe answered a **Windows** root, or `null` when
 * nothing changes.
 *
 * A drive-less spelling of a Windows directory is that directory with its volume
 * gone — `/sensorsdata/…/v36projB` for `C:\sensorsdata\…\v36projB`, the spelling
 * the v36 device run produced (`research/v36_verify/tB/v36tb_bug_addproject.png`)
 * — so once the picker knows the root it re-roots such a path on it instead of
 * displaying, browsing and finally **selecting** the drive-less string. The path
 * can arrive that way from the remembered MRU (a record an older build wrote),
 * and 「选择此目录」 hands `currentPath` to `HomeProjectRecords.open` verbatim.
 *
 * `null` on every POSIX server: there the root is `/`, [FsBrowse.absolute] keeps
 * a POSIX-rooted path verbatim, and `/x` really is `/x`. `null` too for a path
 * that already names its volume with a drive or a UNC head, so a chosen path is
 * never re-pointed; the one volume-bearing rewrite it does perform is the
 * MSYS/Cygwin fold (`/mnt/c/x` → `c:/x`), which is the *same* directory — the
 * twin `ProjectPaths.matchKey` already merges — in the spelling a Windows host
 * can actually browse.
 */
internal fun adoptPickerRoot(currentPath: String, root: String): String? {
    if (currentPath.isBlank() || root.isBlank()) return null
    return FsBrowse.absolute(currentPath, root).takeIf { it != currentPath }
}

/**
 * May the constraints probe still move the browser to a probed start path?
 *
 * Only while the user has not taken hold of the picker. Navigating is
 * [userNavigated]; the two states that used to be missed are **typing** and an
 **armed confirm** (v41 BUG-1, `research/v41_verify/tA/32_sel_tap1.png`): the
 * first 「选择此目录」 tap sets [pendingConfirm], and a probe still in flight then
 * rewrote `currentPath`/`pathInput` under it, so the card named three different
 * paths at once — the confirm body the armed one, the path field and the
 * resolved-path line the freshly adopted start — and the second tap submitted
 * one of them while the user was reading another. An armed confirm is a user
 * action, so it counts as one, exactly like a typed path (v41 BUG-2, where the
 * same rewrite moved the base a relative input resolves against).
 */
internal fun probeMayAdopt(
    userNavigated: Boolean,
    userEdited: Boolean,
    pendingConfirm: String?,
): Boolean = !userNavigated && !userEdited && pendingConfirm == null

/**
 * The directory a relative path typed into the field resolves against: the
 * browser's own path, then the `~` target while there is no browsed path (or the
 * virtual drive level is on, which is not a path), and only then the probed
 * root.
 *
 * The `~` target outranking the probed root is the v41 BUG-2 fix: with nothing
 * probed for a home, the base *was* the bare root `/`, so `app/src/main`
 * resolved to `/app/src/main` and the server reported a directory that does not
 * exist — while the home the picker derives from the path on screen sat unused.
 * A blank answer only when nothing at all is known; the input then stays as
 * typed instead of being guessed at.
 */
internal fun pickerInputBase(currentPath: String, browseRoot: String, homeTarget: String?): String {
    if (currentPath.isNotEmpty() && currentPath != DRIVES_LEVEL) return currentPath
    return homeTarget?.takeIf { it.isNotBlank() } ?: browseRoot
}

/**
 * The path the card names everywhere — the path field, the resolved-path line
 * and the confirm body — for the current state: the armed confirm wins over
 * both the browsed path and whatever the field holds, so the three lines cannot
 * disagree. Outside a confirm it is the field's own text (untyped: the browsed
 * path).
 *
 * [edited] is the one exception: a field the user has typed in and then emptied
 * stays empty. Refilling it with the browsed path made select-all + DEL a no-op
 * — the text was back the instant the field went blank, so the old path could
 * only be deleted one character at a time, and every retype appended to it
 * instead of replacing it (`research/v51_verify/tD/v51_23_typed2.png`: the same
 * input typed twice grew `/mnt` and then `/mnt/mnt` onto the end).
 */
internal fun pickerConfirmPath(
    pendingConfirm: String?,
    currentPath: String,
    pathInput: String,
    edited: Boolean = false,
): String = pendingConfirm?.takeIf { it.isNotBlank() }
    ?: if (edited) pathInput else pathInput.ifEmpty { currentPath }

/** Split offset of the last segment in a confirm path (`/` keeps offset 0). */internal fun confirmPathTail(path: String): Int {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return 0
    return trimmed.lastIndexOf('/') + 1
}

/**
 * v62: the selection the path field takes when it **gains focus** — the whole
 * value, so the first keystroke replaces the path the picker opened on instead
 * of extending it (device v62, 5560: a hand-typed path came out as
 * `/tmp/x/mnt/c/…`).
 *
 * Select-all-on-focus is what the browser combobox this field mirrors does with a
 * path it filled in itself, and it leaves every other gesture alone: a tap moves
 * the caret to the finger, so a mid-path edit still works, and a value the user
 * typed is not re-selected because the field never loses and regains focus.
 */
internal fun pickerFocusSelection(text: String): TextRange = TextRange(0, text.length)

/**
 * The field's value: [displayPath] (the path the card names — see
 * [pickerConfirmPath]) with [selection] clamped to it. The clamp matters because
 * the two are written from different places: a navigation sets the selection to
 * the end of the path it just resolved, while the value the field is handed can
 * still be the shorter one a confirm has since put on screen, and a
 * [TextRange] past the end of the text is an illegal value for the text field.
 */
internal fun pickerFieldValue(displayPath: String, selection: TextRange): TextFieldValue {
    val end = displayPath.length
    val start = selection.start.coerceIn(0, end)
    val stop = selection.end.coerceIn(start, end)
    return TextFieldValue(displayPath, TextRange(start, stop))
}

/**
 * The 「选择此目录」 two-tap state machine (P1): the first tap always arms the
 * confirm for the current path — including the initial `/` — and only the
 * second tap submits the armed path. No path value may submit on first tap.
 */
internal sealed interface DirectoryConfirm {
    data class Armed(val path: String) : DirectoryConfirm
    data class Submit(val path: String) : DirectoryConfirm
}

/**
 * The path the first 「选择此目录」 tap arms: the user-input-parsed target —
 * [FsBrowse.resolveInput] of the field through the same base [navigate] uses
 * ([pickerInputBase]) — falling back to the browsed [currentPath] for a blank
 * field or an unresolvable input.
 *
 * v47 (`research/v47_verify/tD/tD_08_pathB.png` → `tD_09_confirm2.png`): the
 * tap armed `currentPath`, so typing `/home/xiufeigo/v36projB` while browsing
 * `/home/xiufeigo` froze the **parent** on the confirm card and, on the second
 * tap, moved the session there — zero requests, zero errors. Confirm and
 * submit now uniformly name the resolved typed target instead.
 */
internal fun pickerArmTarget(
    pathInput: String,
    currentPath: String,
    browseRoot: String,
    homeTarget: String?,
): String {
    val typed = pathInput.trim()
    if (typed.isEmpty()) return currentPath
    val base = pickerInputBase(currentPath, browseRoot, homeTarget)
    return pickerResolveInput(typed, homeTarget, base).ifEmpty { currentPath }
}

/** A drive letter typed without its colon: `c/sensorsdata/…`, `C:/Users/u`. */
private val DRIVE_INPUT = Regex("""^[A-Za-z][\\/].*$""")

/** The MSYS / Cygwin mount prefix a path is already spelled with. */
private val DRIVE_MOUNT = Regex("""^(/mnt/|/cygdrive/)""", RegexOption.IGNORE_CASE)

/**
 * [FsBrowse.resolveInput] plus the one input shape it got wrong on a WSL
 * server: a **drive letter without its colon**.
 *
 * `c/sensorsdata/main/program/v36projC` names a drive root, not a directory
 * relative to whatever the browser happens to be on — [FsBrowse] reads it as
 * relative and joins it onto the browsed path, so from `/mnt` it landed on
 * `/mnt/c/…` by luck and from `/mnt/c/sensorsdata/main/program/opencode-android-shell`
 * on `<that>/c/sensorsdata/…`, a path the server cannot read
 * (`research/v51_verify/tD/v51_23_typed2.png`). It is resolved in the
 * **server's own spelling** instead: the MSYS/Cygwin mount the browsed path
 * already speaks (`/mnt/c/…`, `/cygdrive/c/…`), else the drive root when the
 * location is a Windows path.
 *
 * Repeating the same input therefore converges on the same directory instead
 * of compounding onto the previous attempt's result, which is what turned one
 * mistyped drive letter into `/mnt/mnt/c/…` on the second round.
 *
 * A base that names no drive and no mount (`/home/xiufeigo`, `/`) has no
 * evidence for either spelling, so it falls back to [FsBrowse.resolveInput] and
 * the relative reading wins — guessing a mount prefix would invent a directory
 * the user never named.
 */
internal fun pickerResolveInput(raw: String, home: String?, base: String): String {
    val value = FsBrowse.normalizeDrive(raw.trim())
    if (FsBrowse.root(value).isNotEmpty() || !DRIVE_INPUT.matches(value)) {
        return FsBrowse.resolveInput(raw, home, base)
    }
    val drive = value[0]
    val tail = value.substring(2).trim('/')
    val mount = DRIVE_MOUNT.find(FsBrowse.normalizeDrive(base))?.value
    val target = when {
        mount != null -> "$mount${drive.lowercaseChar()}/$tail"
        pickerDriveRoot(base).isNotEmpty() -> "${drive.uppercaseChar()}:/$tail"
        else -> return FsBrowse.resolveInput(raw, home, base)
    }
    return FsBrowse.canonical(target)
}

internal fun confirmStep(pending: String?, current: String): DirectoryConfirm =
    if (pending != null) DirectoryConfirm.Submit(pending) else DirectoryConfirm.Armed(current)

/**
 * Failure message for the browse body, split by what the user can actually do
 * about it.
 *
 * The picker used to render every [TransportFailure] as 「not connected to a
 * server」, which sends the user off to fix a connection that is measurably
 * fine: [TransportFailure.NotConnected] is the *probe's* "answered, but not a
 * 2.x server" verdict (socket and route both alive), and a directory the
 * server cannot read arrives as a live-transport [TransportFailure.RequestFailed]
 * (an empty-bodied `500` — measured on 4095 for a missing directory). The
 * categories are therefore:
 *
 * - [TransportFailure.ProbePending] — the probe has not landed yet;
 * - [TransportFailure.AuthRequired] — the server row needs credentials;
 * - [TransportFailure.Unreachable] — the socket/proxy never answered;
 * - [TransportFailure.NotConnected] — answered, but no usable directory API;
 * - [TransportFailure.RequestFailed] with a message — the server refused the
 *   read and said why (the message is shown under the hint);
 * - [TransportFailure.RequestFailed] with only a status — the target itself is
 *   gone or unreadable.
 *
 * Pure (no `stringResource`) so the split stays unit-testable.
 */
@StringRes
internal fun pickerFailureRes(failure: TransportFailure?): Int = when (failure) {
    is TransportFailure.ProbePending -> R.string.oc_summary_status_connecting
    is TransportFailure.AuthRequired -> R.string.oc_server_error_auth
    is TransportFailure.Unreachable -> R.string.oc_server_error_connect
    is TransportFailure.NotConnected -> R.string.oc_picker_error_unusable
    is TransportFailure.RequestFailed ->
        if (failure.message.isNullOrBlank() || STATUS_ONLY_ERROR.matches(failure.message)) {
            R.string.oc_picker_error_missing
        } else {
            R.string.oc_picker_error_request
        }
    else -> R.string.oc_home_projects_load_failed
}

/**
 * The status-only message `V2Api.errorText` builds when the server answered an
 * error **without** an explanation — measured on 4095 for a directory that is
 * not there (`GET /api/fs/list?location[directory]=/&path=<missing>` → `500`
 * with an empty body → `HTTP 500`). Such a read says nothing about why, so the
 * picker names the only honest candidate: the target itself.
 */
private val STATUS_ONLY_ERROR = Regex("""^HTTP \d{3}$""")

/**
 * The server's own explanation for a refused read, or `null` when there is
 * nothing to add under the friendly line.
 *
 * A **status-only** message (`HTTP 500`, what a bodiless refusal parses to) is
 * not an explanation: [pickerFailureRes] has already named the only honest
 * candidate, and v40 measured the raw status rendered a second time under it
 * (`DirectoryPickerDialog`'s `failed` body). Only a message that says more than
 * the status gets the second line.
 */
internal fun failureDetail(failure: TransportFailure?): String? {
    val message = (failure as? TransportFailure.RequestFailed)?.message
    if (message.isNullOrBlank() || STATUS_ONLY_ERROR.matches(message.trim())) return null
    return message
}

@Composable
internal fun failureText(failure: TransportFailure?): String =
    stringResource(pickerFailureRes(failure))

/**
 * Virtual level above the Windows drive roots: [currentPath] takes this
 * value while the body lists [driveEntries]. It is deliberately unrooted
 * ([FsBrowse.root] is empty) and unlistable — [navigate] and the suggestion
 * search resolve against the probed root while it is current.
 */
internal const val DRIVES_LEVEL = ":drives:"

/**
 * 「根目录」 target: the true filesystem root derived client-side from the
 * current path (upstream `pickerRoot(root)` — `C:/` on Windows, `/` on
 * POSIX), with the probed root only as the empty-path fallback. The probed
 * value is never a navigation ceiling: on v2 it derives from the location
 * itself, so targeting it parks the tree on the start directory.
 */
internal fun pickerTrueRoot(currentPath: String, probedRoot: String): String =
    FsBrowse.root(currentPath).ifEmpty { probedRoot }

/**
 * Client-side root set for the drive level: every distinct drive root
 * (`C:/`, uppercased for stable keys) plus UNC share roots seen across the
 * probed home, the sampled path, and the probed root; POSIX-only samples
 * yield `/`. A probed v1-style root that equals the start directory is
 * *not* a root and never survives here — that conflation is what locked the
 * picker onto the start directory.
 */
internal fun browseRoots(home: String?, current: String, probedRoot: String): List<String> {
    val roots = listOfNotNull(
        home?.takeIf { it.isNotBlank() },
        current.takeIf { it.isNotBlank() },
        probedRoot.takeIf { it.isNotBlank() },
    )
        .map { FsBrowse.root(it) }
        .mapNotNull { root ->
            when {
                root.length == 3 && root[0].isLetter() && root[1] == ':' && root[2] == '/' ->
                    "${root[0].uppercaseChar()}:/"
                root.startsWith("//") && root.length > 2 -> root
                else -> null
            }
        }
        .distinct()
        .sorted()
    return roots.ifEmpty { listOf("/") }
}

/** Display label for a derived root: drives read native (`C:/` → `C:\`). */
internal fun driveLabel(root: String): String =
    if (root.length == 3 && root[1] == ':') root.dropLast(1) + "\\" else root

/** Synthetic body rows for the drive level (drives are not listable). */
internal fun driveEntries(roots: List<String>): List<DirEntry> =
    roots.map { root -> DirEntry(name = driveLabel(root), absolute = root, isDirectory = true) }

/** Upward step from [currentPath]: a real parent, the drive level, or stuck. */
internal sealed interface PickerUp {
    data class Parent(val path: String) : PickerUp
    data object Drives : PickerUp
    data object None : PickerUp
}

/**
 * Upstream `pickerParent` without the ceiling: at a true root a Windows
 * drive root opens the drive level ([Drives]) while POSIX `/` and UNC share
 * roots stay put ([None], like upstream reloading the root itself).
 * [parentCrossable] is the v1 `isWithin` gate (`escapeAllowed || within`);
 * unlocked transports cross every parent.
 */
internal fun pickerUp(
    currentPath: String,
    escapeAllowed: Boolean,
    parentCrossable: (String) -> Boolean,
): PickerUp {
    if (currentPath.isBlank() || currentPath == DRIVES_LEVEL) return PickerUp.None
    val parent = HomeProjects.parentOf(currentPath)
    if (parent != null) return if (parentCrossable(parent)) PickerUp.Parent(parent) else PickerUp.None
    if (!escapeAllowed) return PickerUp.None
    val normalized = FsBrowse.normalizeDrive(currentPath)
    val driveRoot = normalized.length == 3 &&
        normalized[0].isLetter() && normalized[1] == ':' && normalized[2] == '/'
    return if (driveRoot) PickerUp.Drives else PickerUp.None
}

/**
 * The v1 cwd-lock hint shows only on a genuinely locked transport. In
 * particular an unlocked (v2) transport never shows it — even when the
 * probed root equals the start directory, which is a probe artifact, not a
 * ceiling.
 */
internal fun showBrowseLockHint(escapeAllowed: Boolean, parentAllowed: Boolean, failed: Boolean): Boolean =
    !escapeAllowed && (!parentAllowed || failed)

/**
 * A start candidate that must never park the picker: inside the Windows
 * system directory (`C:\Windows`, hence `System32`/`SysWOW64`). v2 reports
 * the server launch cwd as the location, so a host started from System32
 * would otherwise open (and look locked) there.
 */
internal fun isSystemStart(path: String?): Boolean {
    val value = FsBrowse.normalizeDrive(path?.trim().orEmpty())
    if (value.length < 4 || value[1] != ':') return false
    val rest = value.substring(3)
    return rest.equals("windows", ignoreCase = true) || rest.startsWith("windows/", ignoreCase = true)
}
