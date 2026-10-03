package com.xiufeigo.opencodeshell.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.components.OcDotsIcon
import com.xiufeigo.opencodeshell.ui.components.OcPlusIcon
import com.xiufeigo.opencodeshell.ui.components.ocOverlayEscape
import com.xiufeigo.opencodeshell.ui.theme.OcTheme

/**
 * The `settings-v2` primitives from `research/02_ui_screens.md` §5.3 / §5.6 and
 * `research/05_palette_host_session.md` §5.2.
 *
 * Sizes are transcribed 1:1 from the vendored CSS: list = `layer-01`, radius 8,
 * `padding-inline: 20px`, `inset 0 0 0 0.5px border-muted`; row = `padding-block:
 * 20px` + `0.5px` bottom border (none on `:last-child`), title `13px/530`,
 * description `13px/440` in `text-muted`; nav trigger wrapper `h-28dp / r-4dp`.
 */

/**
 * `13px/530` row title / `13px/440` muted description.
 *
 * [description] is the already-resolved copy for a description that carries
 * arguments (the Follow-up behavior row's `{{keybind}}` placeholder): passing it
 * keeps the row's copy — and therefore the label column's width — with
 * [SettingsRowCopy], instead of a caller rendering the text inside the control.
 */
@Composable
fun SettingsRowCopy(
    titleResId: Int,
    descriptionResId: Int?,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val c = OcTheme.colors
    val copy = description ?: descriptionResId?.let { stringResource(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(titleResId),
            color = c.textBase,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
        if (copy != null) {
            Text(
                text = copy,
                color = c.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                lineHeight = 20.sp,
            )
        }
    }
}

/** `[data-component=settings-v2-list]`. */
@Composable
fun SettingsList(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = OcTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.layer01, RoundedCornerShape(8.dp))
            .border(0.5.dp, c.borderMuted, RoundedCornerShape(8.dp))
            .padding(PaddingValues(start = 20.dp, end = 20.dp)),
    ) {
        content()
    }
}

/**
 * One `[data-component=settings-v2-row]`: `copy (weight 1f) | 16 dp | control`
 * while the row is wide enough, and the copy above the control below
 * [SettingsRowStackedWidth].
 *
 * The stacked shape used to be a parameter no caller ever set — `stacked = true`
 * appears nowhere — so a narrow row always took the two-column branch and the
 * copy column starved instead of moving under the control. The row now measures
 * itself (`BoxWithConstraints`) and stacks on its **own** width, which is what
 * upstream's container query reads too (`settings.css` `@container
 * settings-panel (max-width: 520px)`), plus upstream's base wrap on a
 * `<640 px` viewport ([SettingsRowStackedViewportWidth]). [stacked] stays as a
 * caller's override.
 *
 * [onToggle] is the second, much larger target of a `SettingsControl.SWITCH`
 * row: when non-null the row's **copy** (title + description) becomes tappable
 * and runs the same write as the switch itself, so tapping the label flips the
 * setting instead of doing nothing. Upstream gets that for free — its kobalte
 * `Switch` wraps the label in the control — while a 40×24 dp knob is the only
 * target a bare Compose switch offers. Rows with any other control pass `null`
 * and keep the copy inert.
 *
 * [description] overrides the copy [SettingsRowCopy] renders (an already
 * formatted description for a row whose string carries arguments). The copy
 * always stays in this row's own column — never inside [control] — and every
 * control shape is handed [SettingsRowControlWidth], so no control can squeeze
 * the label into a vertical sliver.
 */
@Composable
fun SettingsRow(
    titleResId: Int,
    descriptionResId: Int?,
    last: Boolean,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
    onToggle: (() -> Unit)? = null,
    description: String? = null,
    control: @Composable () -> Unit,
) {
    val viewportWidthDp = LocalConfiguration.current.screenWidthDp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        SettingsRowBody(
            titleResId = titleResId,
            descriptionResId = descriptionResId,
            last = last,
            stacked = settingsRowStacked(maxWidth, stacked, viewportWidthDp),
            onToggle = onToggle,
            description = description,
            control = control,
        )
    }
}

/**
 * The row width below which a [SettingsRow] stacks its copy above its control —
 * upstream's `@container settings-panel (max-width: 520px)` wrap, expressed on
 * the width the row can measure itself.
 *
 * `settings.css` puts `container: settings-panel / inline-size` on the panel and
 * wraps `[data-component=settings-row]` inside it at 520 px. The row is not the
 * container: two paddings sit between them — `SettingsPanelBody`'s
 * `0 40px 40px` (40 dp a side) and `SettingsList`'s `padding-inline: 20px`
 * (another 20) — so the row is the container **minus 120 dp**, and 520 − 120 =
 * **400 dp** is the same rule in the units the row owns.
 *
 * This replaces a 320 dp breakpoint derived from the copy column's own
 * readability (`0.4 × row − 16 = 112 dp`, i.e. the width at which a 13 sp title
 * plus its description still reads in the two-column shape). That derivation was
 * the wrong quantity: it asked "when does the copy column stop being readable?"
 * and answered 320 dp, while upstream asks "when does the panel stop being a
 * wide panel?" and answers 520 px. The gap is exactly where the device sat — a
 * 1080 px / 320 dpi phone is a 540 dp window, so the dialog is
 * `min(540 − 32, 980)` = 508 dp, the panel insets take 80 and the list 40, and
 * the row is **388 dp**: above 320 (two columns, the descriptions wrapping to
 * two and three lines beside their controls — `research/v36_verify/tC/
 * tC_03e_narrow_general.png`, `tC_03f_narrow_appearance.png`) and below 400
 * (stacked). The old KDoc's "360 dp phone" arithmetic was right about the
 * geometry and wrong about where the rule comes from.
 *
 * Provenance note: the vendored build's CSS
 * (`app/src/main/assets/opencode-app/assets/index-D_rl_FNw.css`) has **no**
 * `@container settings-panel (max-width: 520px)` rule — its only row rules are
 * the base `[data-component=settings-v2-row]{flex-wrap:wrap}`, the
 * `@media(min-width:640px){flex-wrap:nowrap}` override and the ≤639 px
 * `flex-direction:column` one. This 400 dp breakpoint is the dev-HEAD container
 * rule, and it is kept on purpose: in the 640…792 dp band the vendored build
 * stays on one line, so its copy column (`flex:1; min-width:0`, the control
 * `width:auto; flex-shrink:0`) is squeezed to a sliver — the v36 defect this
 * file's history documents.
 */
internal val SettingsRowStackedWidth: Dp = 400.dp

/**
 * The viewport width below which upstream's row is wrapped **whatever the panel
 * measures**: `[data-component=settings-row]` is `flex-wrap: wrap` in the base
 * rules and only becomes `nowrap` under `@media (min-width: 640px)`
 * (`settings.css:446-463`). So a sub-640 px window stacks even when the panel is
 * wide, and `SettingsDialogContent` already reads the same 640 dp to choose its
 * nav shape (top bar below, 240 dp sidebar above).
 *
 * A row's own width cannot express this rule: the nav breakpoint moves the row
 * *down* 241 dp at exactly 640 (the sidebar appears), so row width is not
 * monotone in viewport width and the 552…639 dp band — a wide phone or a small
 * tablet in portrait, where the row is 400…487 dp and upstream still wraps —
 * would keep two columns on the width rule alone.
 *
 * The **vendored** build — the CSS actually bundled in this app
 * (`app/src/main/assets/opencode-app/assets/index-D_rl_FNw.css`, offset
 * ≈477,609, recorded in `research/05_palette_host_session.md` §5.3) — carries
 * the same breakpoint as a hard override rather than a wrap:
 * `[data-component=settings-v2-row]{flex-direction:column;align-items:stretch;
 * flex-wrap:wrap}`, plus `[data-slot=settings-v2-row-control]{width:100%;
 * justify-content:flex-start}` and `[data-slot=settings-v2-row-copy]{width:100%;
 * max-width:100%}` — the stacked shape unconditionally at ≤639 px, which is the
 * branch [SettingsRowBody] renders. Its companion is
 * `@media (min-width:640px){[data-component=settings-v2-row]{flex-wrap:nowrap}}`,
 * the two-column shape from 640 px up.
 *
 * **The window's dp width is `px × 160 / densityDpi`** (the same scale
 * `Configuration` uses; `ui/nav/ViewportWidth.kt` carries the conversion for
 * form-factor triage),
 * and `wm size` alone does not change the density: `wm size 1600x1200` is an
 * 800 dp window at 320 dpi but a **609 dp** one on a 420 dpi phone, and the v37
 * settings captures (`research/v37_verify/tB/02-05`) are all the latter kind —
 * 411 dp for the 1080 px ones (the dialog measures 993 px = 379 dp = `min(W −
 * 32, 980)`) and 609 dp for the 1600x1200 one (1517 px = 578 dp). All four are
 * below this rule and stack, and all four show the **top bar**, the branch that
 * needs the same `<640`. A two-column row needs a window of ≥793 dp (the row is
 * `viewport − 393` there, 400 dp at exactly 793), i.e. the 2560x1600 tablet at
 * 1280 dp or a window whose density matches its pixels.
 */
internal const val SettingsRowStackedViewportWidth: Int = 640

/**
 * [SettingsRow]'s shape decision, pure so a unit test can pin it: the panels
 * have no JVM-renderable surface in this project (see `SettingsPanelScrollTest`),
 * and the branch is a *width* rule, not a source fact. [forced] is the caller's
 * `stacked` argument; the measured [rowWidth] decides below
 * [SettingsRowStackedWidth] and the window's [viewportWidthDp] below
 * [SettingsRowStackedViewportWidth]. An unbounded row (`Dp.Infinity`, a
 * horizontally scrolling host) never compares as narrow and stays in the
 * two-column shape; [viewportWidthDp] defaults to `Int.MAX_VALUE` so a caller
 * with no window (previews, a bare unit test) is decided by the row alone.
 */
internal fun settingsRowStacked(
    rowWidth: Dp,
    forced: Boolean = false,
    viewportWidthDp: Int = Int.MAX_VALUE,
): Boolean = forced ||
    viewportWidthDp < SettingsRowStackedViewportWidth ||
    rowWidth < SettingsRowStackedWidth

/**
 * [SettingsRow]'s body, after the shape has been decided. Split out so the
 * decision above reads the measured width in one place and the row below can
 * take `stacked` as an ordinary parameter.
 */
@Composable
private fun SettingsRowBody(
    titleResId: Int,
    descriptionResId: Int?,
    last: Boolean,
    stacked: Boolean,
    onToggle: (() -> Unit)?,
    description: String?,
    control: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    val border = if (last) Color.Transparent else c.borderBase
    // The row's copy (title + description, and the free space beside them) is a
    // second target for a SWITCH row's write; every other control passes null
    // and leaves the copy inert.
    val toggle = if (onToggle == null) Modifier else Modifier.clickable(onClick = onToggle)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
    ) {
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsRowCopy(titleResId, descriptionResId, Modifier.fillMaxWidth().then(toggle), description)
                // Upstream's wrapped control is `width: 100%` with
                // `justify-content: flex-end` (`settings.css`
                // `[data-slot=settings-row-control]`): the control takes the
                // whole line and its own content sits at the trailing edge, so a
                // 40 dp switch lines up under the right margin instead of
                // floating at the start. A control that is `fillMaxWidth()`
                // (every text field) is unaffected by the alignment, and the
                // others keep their own band inside it.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { control() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).then(toggle)) {
                    SettingsRowCopy(titleResId, descriptionResId, description = description)
                }
                Spacer(Modifier.width(16.dp))
                control()
            }
        }
        if (!last) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(border))
        }
    }
}

/**
 * The width band a [SettingsRow]'s **control** column may occupy — the one
 * modifier every control shape is handed (SWITCH rows excepted: [OcSwitch] is a
 * fixed 40×24 dp knob that cannot grow).
 *
 * A row lays out as `copy (weight 1f) | 16 dp | control`, and Compose measures
 * the **unweighted** control first: a control allowed to ask for its natural
 * width takes that width out of the row, and the weighted copy column gets
 * whatever is left. `OcTextField` is `fillMaxWidth()` by design and an
 * `OcSelect` trigger hugs its value, so on device both starved the copy column:
 * the 外观 → Code Font row's title and its `Customise the font used in code
 * blocks` description collapsed to a ~27 dp sliver that wrapped one fragment per
 * line while the dropdown kept its full width
 * (`research/v33_verify/tB/v33tb_1c_after_swipe2.png`; the Terminal Font row was
 * the same defect on the TEXT shape in v32, `12b_settings_scroll.png`).
 *
 * `fillMaxWidth(0.6f)` is the part that makes the copy column structurally safe
 * wherever the row keeps its two columns: it reserves 40 % of the row whatever
 * the row's absolute width is (a fixed dp cap alone still starves the label on a
 * 320 dp window, where the whole row is ~200 dp). The 140–260 dp band keeps the
 * control usable on a wide window, and being shared by every shape is what lines
 * the controls up down the page. Below [SettingsRowStackedWidth] the row stops
 * laying out two columns at all, so the band is only ever read where it fits.
 */
internal val SettingsRowControlWidth: Modifier =
    Modifier.fillMaxWidth(0.6f).widthIn(min = 140.dp, max = 260.dp)

/** `settings-v2-section` (`gap:16px`) + `settings-v2-section-title`. */
@Composable
fun SettingsSectionBlock(titleResId: Int?, content: @Composable () -> Unit) {
    SettingsSectionBlockTitle(titleResId?.let { stringResource(it) }, content)
}

/**
 * [SettingsSectionBlock] with an already-resolved title — the Providers/Models
 * panels group by *dynamic* provider names that have no resource id.
 */
@Composable
fun SettingsSectionBlock(title: String, content: @Composable () -> Unit) {
    SettingsSectionBlockTitle(title, content)
}

@Composable
private fun SettingsSectionBlockTitle(title: String?, content: @Composable () -> Unit) {
    val c = OcTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (title != null) {
            Text(
                text = title,
                color = c.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight(640),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        content()
    }
}

/**
 * `SelectV2` stand-in: a `button-v2`-shaped trigger plus an anchored menu.
 * `appearance="large"` ⇒ 36dp tall, radius 6, `layer-01` surface.
 *
 * [fillWidth] hands the trigger the whole width the caller's [modifier] already
 * assigned instead of the component's own 140–260 dp band. A caller that gives
 * the select a `weight` (the Workspaces filter row) wants the *visible* box to
 * be that share — with the band it stays narrower than the sibling it shares the
 * row with, which is what makes the row's sub-items look uneven. Every other
 * call site keeps the band: there the trigger hugs its value so a `SettingsRow`
 * copy column keeps its 40 %.
 *
 * The open list is an **inline column** in the layout — never a `DropdownMenu`
 * / `Popup` window — so it can only ever paint inside the bounds of the scroll
 * body that hosts the select. A `DropdownMenu` is a separate platform window
 * which the dialog card cannot clip, so its list drew past the card's rounded
 * edge onto the tab strip and the drawer above (`research/v40_verify/tD`
 * `tD_23_uifont` / `tD_81_fontdd` / `tD_30_sound` / `tD_83_snddd`). The inline
 * list pushes the rows below it down while open, exactly like upstream's
 * wrapping select, so the dialog keeps its own geometry instead of painting
 * over its chrome.
 *
 * v43 (`research/v43_verify/tE/v43_05_lastrow_dropdown.png`) found the one case
 * the inline contract did not cover: on the **last row** of a section the host
 * scroller is already at its end, so a list that only ever opens downward and
 * grows to its full height is clipped by the dialog card — Terminal Font showed
 * half of its first option and nothing after it, and the list neither scrolled
 * nor flipped (`v43_06_dropdown_scroll.png`). The side is now decided per
 * open ([ocSelectShouldFlipUp], the list opens above when below cannot hold it
 * and above can) and the list body is height-capped and scrollable
 * ([ocSelectListMaxHeightDp]), so every option is reachable either way. Both
 * halves stay layout-only — no second window, so the card still clips them.
 *
 * v46 (`research/v46_verify/tE/tE_05_termfont_first.png` /
 * `tE_06_tf_settled.png` / `tE_07_tf_scrolled.png`) found what the v43 rule
 * still got wrong: its "room below" was measured against the **window** bottom,
 * but the list is clipped by the **dialog card** bottom — on a wide window the
 * card ends hundreds of dp above the screen edge, so below looked roomy, the
 * list opened down, and everything past the card edge was cut (first option
 * half-visible, the second fully gone, no self-heal; a swipe starting on the
 * cut part never reached a scroller and scrolled the page behind instead).
 * The same component near the *screen* bottom flips correctly
 * (`tE_26_modeldd.png` / `tE_30_dd2.png`) — the rule existed, its boundary was
 * wrong. The boundary is therefore the nearest constraining container: the
 * dialog content provides its bounds through [LocalOcSelectContainerBounds]
 * and the side ([ocSelectShouldFlipUp]) plus the cap ([ocSelectSpaceForSide] →
 * [ocSelectListMaxHeightDp]) are decided against them. Without a provided
 * container (the session-page consumers, or a host whose measurement has not
 * landed) the side is still the old window-based one, and the cap reads the
 * window room on the side that won ([ocSelectSpaceForSide]) — v47's backstop,
 * so a list that cannot fit is a bounded scroller there too instead of a box
 * clipped past the card with no scroll room left.
 *
 * v63 (`research/v63_verify/5554/55_scheme_list.png` / `31_fontlist.png`) is what
 * an inline list still got wrong on the 外观 page: the list sized itself from its
 * own option rows while the trigger above it hugged its own value, so the open
 * menu came out **wider than the control it belongs to** and spilled left over
 * the row's copy column and right past the trigger into the row below — it read
 * as an overlay painted on top of the page instead of a menu under its trigger.
 * The list now takes the trigger's measured width, so both edges line up by
 * construction whatever the value, the font scale or the family.
 */
@Composable
fun OcSelect(
    value: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    onSelect: (String) -> Unit,
) {
    val c = OcTheme.colors
    var expanded by remember { mutableStateOf(false) }
    // v43: the list used to always open *below* the trigger and to grow to its
    // full height. On the last row of a settings section the host scroller was
    // already at its end, so the part that grew past the dialog card's bottom
    // edge was simply clipped — the Terminal Font list showed half of its first
    // option and nothing at all after it, and the list had no way to scroll or
    // flip (`research/v43_verify/tE/v43_05_lastrow_dropdown.png` /
    // `v43_06_dropdown_scroll.png`). The trigger's position in the window now
    // decides the side ([ocSelectShouldFlipUp]) and the list is height-capped and
    // scrollable ([ocSelectListMaxHeightDp]), so every option stays reachable
    // whichever side wins.
    val density = LocalDensity.current
    val windowHeightDp = with(density) { LocalConfiguration.current.screenHeightDp.toFloat() }
    // v46: the flip/cap boundary is the constraining *container* (the dialog
    // content, provided above this select), not the window — see this
    // function's KDoc. Both edges of the trigger are tracked so the room above
    // reads from the trigger's top, not its bottom.
    val container = LocalOcSelectContainerBounds.current
    var triggerTopDp by remember { mutableStateOf(0f) }
    var triggerBottomDp by remember { mutableStateOf(0f) }
    // v63 (`research/v63_verify/5554/55_scheme_list.png` / `31_fontlist.png`):
    // the list measured itself — `widthIn(140…260)` around its own option rows —
    // while the trigger above it hugged its value plus a chevron. The two never
    // came out equal, so the open list was a **wider, offset** box: on 外观 →
    // 配色方案 it sprawled past its own trigger into the row's copy column and
    // over the description of the row below, and read as a floating overlay
    // painted on top of the page instead of a menu anchored under the control
    // it belongs to. The list now takes the trigger's measured width, so the two
    // edges line up by construction whatever the value, the font scale or the
    // font family are.
    var triggerWidthDp by remember { mutableStateOf(0f) }
    val desiredHeightDp = remember(options) { ocSelectListDesiredHeightDp(options.size) }
    // v44 (`research/v44_verify/tE/tE_27_fontdd.png` / `tE_28_fontscroll.png`):
    // the first frame of the list was still painted *below* the trigger even on
    // the last row, because the very first open can run before the trigger's
    // window position has been reported — a `0` bottom reads as "the whole
    // window is free below", so the list opened down, got clipped by the
    // dialog card and only settled after the host scroller moved (which is
    // what made a later open, or a reopen after scrolling, look right). The
    // list is therefore withheld until a real measurement exists, so the very
    // first frame is already the decided side: nothing is painted for one
    // composition instead of painting a clipped frame.
    val sideKnown = ocSelectHasTriggerBottom(triggerBottomDp)
    val flipUp = expanded && sideKnown && ocSelectShouldFlipUp(
        triggerBottomDp,
        desiredHeightDp,
        windowHeightDp,
        triggerTopDp = triggerTopDp,
        containerTopDp = container?.topDp,
        containerBottomDp = container?.bottomDp,
    )
    // v46: the cap is decided against the room on the side that won — the
    // container's visible stretch below/above the trigger when one is provided
    // (the window room on that side otherwise, v47's backstop — see
    // [ocSelectSpaceForSide]). A list that cannot outgrow its visible side
    // cannot be clipped by the card edge, and its own scroller then reaches
    // every option.
    val spaceBelowDp = ocSelectSpaceForSide(triggerTopDp, triggerBottomDp, windowHeightDp, container, flipUp = false)
    val spaceAboveDp = ocSelectSpaceForSide(triggerTopDp, triggerBottomDp, windowHeightDp, container, flipUp = true)
    Column(
        // v58 (`research/v57_verify/emu5556`): the open list answered nothing to
        // ESC — 点外面正常关，Esc 没反应。The list is an **inline** column
        // (no `Popup` window of its own, see this function's KDoc), so the only
        // focus owner that can see the key is one inside this subtree: while the
        // list is open the select takes focus itself and closes on ESC. The
        // modifiers are layout-neutral, so the trigger and the list keep their
        // geometry either way.
        modifier = if (expanded) Modifier.ocOverlayEscape(open = true) { expanded = false } else modifier,
    ) {
        if (flipUp) {
            Spacer(Modifier.height(4.dp))
            OcSelectList(
                options = options,
                value = value,
                enabled = enabled,
                fillWidth = fillWidth,
                triggerWidthDp = triggerWidthDp,
                maxHeightDp = ocSelectListMaxHeightDp(options.size, windowHeightDp, spaceAvailableDp = spaceAboveDp),
                onPick = {
                    expanded = false
                    onSelect(it)
                },
            )
            // v47 (`research/v47_verify/tE/tE_06_app_bottom.png`): the flipped
            // list used to sit flush on the trigger while the downward branch
            // keeps a 4 dp gap between trigger and list — the cap reserves that
            // chrome on both sides ([OC_SELECT_LIST_CHROME_DP]), so the upward
            // branch must actually draw it. Without the gap the list's own edge
            // reads as the cut line for its first/last row.
            Spacer(Modifier.height(4.dp))
        }
        Row(
            (if (fillWidth) Modifier.fillMaxWidth() else Modifier.widthIn(min = 140.dp, max = 260.dp))
                .onGloballyPositioned { coords ->
                    val top = with(density) { coords.positionInWindow().y.toFloat() / density.density }
                    val height = with(density) { coords.size.height.toFloat() / density.density }
                    val width = with(density) { coords.size.width.toFloat() / density.density }
                    triggerTopDp = top
                    triggerBottomDp = top + height
                    triggerWidthDp = width
                }
                .defaultMinSize(minHeight = 36.dp)
                .background(c.layer01, RoundedCornerShape(6.dp))
                .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
                .clickable(enabled = enabled) { expanded = !expanded }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = if (enabled) c.textBase else c.textFaint,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Chevron(c.iconMuted, expanded = expanded)
        }
        if (expanded && sideKnown && !flipUp) {
            Spacer(Modifier.height(4.dp))
            OcSelectList(
                options = options,
                value = value,
                enabled = enabled,
                fillWidth = fillWidth,
                triggerWidthDp = triggerWidthDp,
                maxHeightDp = ocSelectListMaxHeightDp(options.size, windowHeightDp, spaceAvailableDp = spaceBelowDp),
                onPick = {
                    expanded = false
                    onSelect(it)
                },
            )
        }
    }
}

/** One option row: 13 sp label on a 10 dp vertical pad, 4 dp above/below the list. */
private const val OC_SELECT_ROW_HEIGHT_DP = 37f

/** Smallest list box a flipped/short list is given, so one option is never a sliver. */
private const val OC_SELECT_LIST_MIN_HEIGHT_DP = 44f

/** The list never takes more than this share of the window, flipped or not. */
private const val OC_SELECT_LIST_MAX_WINDOW_SHARE = 0.4f

/**
 * Vertical chrome around the option rows the cap ([ocSelectListMaxHeightDp])
 * reserves room for on the winning side: the 4 dp gap between trigger and
 * list plus the list's own 4 dp vertical padding on each side. The downward
 * branch draws exactly this; the upward branch additionally keeps its
 * content-side gap (v47), which still fits inside the same reservation.
 */
internal const val OC_SELECT_LIST_CHROME_DP = 12f

/** Height the list *wants*: 4 dp of the card's vertical padding + one row per option. */
internal fun ocSelectListDesiredHeightDp(optionCount: Int): Float =
    8f + OC_SELECT_ROW_HEIGHT_DP * optionCount.coerceAtLeast(0)

/**
 * Whether the trigger's window position is known well enough to place the list.
 *
 * A `0` bottom is the pre-measurement sentinel: it would read as "everything
 * below is free" and open the list down under the dialog card, so the list waits
 * for a real measurement instead of painting a clipped first frame.
 */
internal fun ocSelectHasTriggerBottom(triggerBottomDp: Float): Boolean = triggerBottomDp > 0f

/** A trigger closer to the window's bottom edge than this counts as "last row". */
private const val OC_SELECT_FLIP_BAND = 0.25f

/**
 * The collision boundary an [OcSelect] decides its open side against: the
 * visible box of the nearest constraining container, in window dp.
 *
 * The dialog content provides these (its panel slot reports its own box), so a
 * select deep inside the settings dialog flips against the **card** edge that
 * actually clips its list — not the window edge hundreds of dp further down
 * (`research/v46_verify/tE/tE_05_termfont_first.png`). No provider (every
 * consumer outside the dialog) means the old window-based math, unchanged.
 */
data class OcSelectContainerBounds(val topDp: Float, val bottomDp: Float)

/** The bounds above, or null outside a constraining container. */
val LocalOcSelectContainerBounds: ProvidableCompositionLocal<OcSelectContainerBounds?> =
    compositionLocalOf { null }

/**
 * Whether the list opens **above** its trigger.
 *
 * Opening below is the default and stays the default whenever it fits: the
 * inline list pushes the rows below it down and the host scroller follows, so
 * a select in the middle of a section is not the defect. Flipping up covers the
 * copy column of the row it belongs to, so it is reserved for the case v43
 * actually reported — a trigger in the **last band** of the window, where
 * below cannot hold the list and above has room to give.
 *
 * v46: inside a constraining container ([containerTopDp]/[containerBottomDp],
 * the dialog content) the "room below" is the container's, not the window's —
 * the card edge is what clips. The shape of the rule is unchanged (fits below
 * ⇒ down; plenty below ⇒ down and the host follows; last band ⇒ the roomier
 * side wins), only the quantities it reads come from the container. A null or
 * degenerate container keeps the exact window math, so the session-page
 * consumers that flip correctly today (`research/v46_verify/tE/tE_26_modeldd`)
 * never see this branch.
 */
internal fun ocSelectShouldFlipUp(
    triggerBottomDp: Float,
    listHeightDp: Float,
    windowHeightDp: Float,
    triggerTopDp: Float = triggerBottomDp,
    containerTopDp: Float? = null,
    containerBottomDp: Float? = null,
): Boolean {
    if (windowHeightDp <= 0f) return false
    if (containerTopDp != null && containerBottomDp != null && containerBottomDp > containerTopDp) {
        val below = (containerBottomDp - triggerBottomDp).coerceAtLeast(0f)
        if (below >= listHeightDp) return false
        if (below > (containerBottomDp - containerTopDp) * OC_SELECT_FLIP_BAND) return false
        val above = (triggerTopDp - containerTopDp).coerceAtLeast(0f)
        return above > below
    }
    val spaceBelow = (windowHeightDp - triggerBottomDp).coerceAtLeast(0f)
    if (spaceBelow >= listHeightDp) return false
    if (spaceBelow > windowHeightDp * OC_SELECT_FLIP_BAND) return false
    return triggerBottomDp > spaceBelow
}

/**
 * The stretch the opened list may take on its side: the container's visible
 * room below/above the trigger when one is provided; the **window** room on
 * that side otherwise.
 *
 * v47 (`research/v47_verify/tE/tE_11_tf_clean.png` / `tE_08_termfont_open.png`):
 * the no-container fallback used to be the *whole window*, so wherever the
 * container bounds never arrived the flip read the window (roomy below on a
 * tall window ⇒ opens down) **and** the cap read the window too — a short
 * list rendered its full wanted height past the card edge with no scroll room
 * left inside it, and the third stop sat 8 px under the card, unreachable.
 * The fallback is therefore the window room on the side that won
 * (`window − triggerBottom` below, `triggerTop` above): a list that fits keeps
 * byte-identical behavior (room ≥ wanted ⇒ cap == wanted, see
 * [ocSelectListMaxHeightDp]), while one that does not becomes a capped
 * scroller instead of a clipped box. The flip itself is untouched — still the
 * window rule without a container, still an optimization on top of a list that
 * is always bounded.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun ocSelectSpaceForSide(
    triggerTopDp: Float,
    triggerBottomDp: Float,
    windowHeightDp: Float,
    container: OcSelectContainerBounds?,
    flipUp: Boolean,
): Float {
    if (container == null || container.bottomDp <= container.topDp) {
        return if (flipUp) {
            triggerTopDp.coerceAtLeast(0f)
        } else {
            (windowHeightDp - triggerBottomDp).coerceAtLeast(0f)
        }
    }
    return if (flipUp) {
        (triggerTopDp - container.topDp).coerceAtLeast(0f)
    } else {
        (container.bottomDp - triggerBottomDp).coerceAtLeast(0f)
    }
}

/**
 * Height cap for the list, so it is a scroller rather than something the dialog
 * card clips: the wanted height, clamped to the window's share and to what is
 * left below the trigger (or above it, when the list is flipped).
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun ocSelectListMaxHeightDp(
    optionCount: Int,
    windowHeightDp: Float,
    spaceAvailableDp: Float = windowHeightDp,
): Float {
    if (windowHeightDp <= 0f) return 0f
    val wanted = ocSelectListDesiredHeightDp(optionCount)
    val windowCap = windowHeightDp * OC_SELECT_LIST_MAX_WINDOW_SHARE
    val available = (spaceAvailableDp - OC_SELECT_LIST_CHROME_DP).coerceAtLeast(OC_SELECT_LIST_MIN_HEIGHT_DP)
    return minOf(wanted, windowCap, available).coerceAtLeast(OC_SELECT_LIST_MIN_HEIGHT_DP)
}

// ------------------------------------------------- the settings layer's card

/** Upstream's `min(100vh − 92, 600)` gutter — the air around the card, in dp. */
internal const val SETTINGS_CARD_GUTTER_DP = 92f

/** Upstream's `600px` ceiling for the settings card, in dp. */
internal const val SETTINGS_CARD_MAX_HEIGHT_DP = 600f

/** The floor the card never goes below on a short window, in dp. */
internal const val SETTINGS_CARD_MIN_HEIGHT_DP = 240f

/**
 * The settings dialog card's height, with the soft keyboard taken into account.
 *
 * Upstream's container is `min(100vh − 92, 600)`, and on this shell `100vh` is
 * `LocalConfiguration.screenHeightDp` — the **display** height, which does not
 * change while the keyboard is up (`OcActivity` is edge-to-edge and the IME is
 * drawn *over* the composition; the same read `paletteShell` documents). A card
 * sized from it kept its full 600 dp while the IME covered its lower half, so
 * the Shortcuts rows and the server form's Save sat behind the keyboard — and
 * because the card itself still fitted the window there was no inner scroller to
 * reach them with. Measured on 1366x768 @160dpi (`research/v82_verify/5556/`,
 * pixel probes of `40_set.png` and `20_sc.png`): the card's top edge is y=84 in
 * both shots — `(768 − 600) / 2`, i.e. the same centred 600 dp box — while the
 * keyboard's top edge is y=342 in the second, so only the top 258 px of the card
 * are on screen there.
 *
 * [imeBottomDp] is `WindowInsets.ime`'s bottom. The card gives way to it, so the
 * visible card is the space above the keyboard — no second 92 dp gutter is taken
 * out of that space, because the gutter is the *window's* breathing room, not a
 * share of a keyboard-sized band. With the keyboard down [imeBottomDp] is 0 and
 * the value is upstream's `min(window − 92, 600)`, unchanged.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun settingsDialogCardHeightDp(
    windowHeightDp: Float,
    imeBottomDp: Float = 0f,
): Float {
    val visible = windowHeightDp - imeBottomDp.coerceAtLeast(0f)
    return minOf(windowHeightDp - SETTINGS_CARD_GUTTER_DP, SETTINGS_CARD_MAX_HEIGHT_DP, visible)
        .coerceAtLeast(SETTINGS_CARD_MIN_HEIGHT_DP)
}

/**
 * The band v83 asked for on a second-level form, in dp:
 * [SERVER_DIALOG_PINNED_FOOTER_DP] plus one labelled field (80.5 + 79.5, v83's
 * arithmetic). It is no longer a floor of [serverDialogBandDp] — the space
 * above the keyboard, the footer and [SERVER_DIALOG_FORM_MIN_DP] are — but it
 * remains the number [SETTINGS_SHORT_IME_BAND_DP] derives the narrow chrome's
 * collapse from, and v87's [SERVER_DIALOG_WANTED_BAND_DP] (224 dp) only ever
 * asks for *more* than it: the v83 floor measured one labelled field from the
 * field's own label down and left the card's head (its 24 dp top padding, its
 * 15 sp title and the hairline below it) out of the account.
 */
internal const val SERVER_DIALOG_MIN_BAND_DP = 160f

/**
 * The pinned footer's own height, in dp: the 12 dp gap above the button row, the
 * 32 dp `OcButton`, the 12 dp gap below it, the 0.5 dp hairline and the card's
 * 24 dp bottom padding. It is the one floor [serverDialogBandDp] keeps even when
 * the keyboard leaves less than that — a shorter card cannot hold its own footer
 * (Compose would squeeze the fixed-height buttons into a shorter box).
 */
internal const val SERVER_DIALOG_PINNED_FOOTER_DP = 80.5f

/**
 * The form's **own** floor, in dp: the card's head plus its first labelled
 * field — the least a second-level server form can show and still be a form the
 * user can type into.
 *
 * v86 (`research/v86_verify/5556/`) shipped the band as *the space above the
 * keyboard* and left the form `space − footer`: on the 1366x768 @160 dpi device
 * (1 px = 1 dp) the keyboard's top edge is y=336 and the `Edit server` card's
 * own top edge is y≈231…250 there — 86…105 dp of space — so the form's scroller
 * came out **24.5 dp** (at 105) or **0 dp** (at 86, where the footer's 80.5 dp
 * took the whole band and the 12/0.5/24 dp of padding after the button row fell
 * behind the keyboard), and the a11y dump of the state (`ui_edit_pre_type.xml`,
 * `ui_edit_kbdup.xml`, `ui_now.xml`) has no field node at all while typing still
 * reached the address box: an invisible form.
 *
 * The floor is that card's head measured from the card's own top edge to the
 * **bottom of the first input**, at the card's own tokens:
 *
 * | the card's form column | dp |
 * |---|---|
 * | `padding(top = 24.dp)` | 24 |
 * | the 15 sp `Edit server` title (19 dp on device) | 19 |
 * | `spacedBy(12.dp)` + the 0.5 dp hairline + `spacedBy(12.dp)` | 24.5 |
 * | the 13 sp `Server address` label (16 dp on device) | 16 |
 * | `spacedBy(12.dp)` | 12 |
 * | the 48 dp `OcTextField` | 48 |
 * | **total** | **143.5** |
 *
 * 143.5 dp, and the device dump agrees: in the keyboard-down state
 * (`research/v86_verify/5556/ui_kbdown_scrolled.xml`, the card at y=193) the
 * first `EditText` ends at y=332 — 139 dp below the card's top edge, the
 * 4.5 dp difference being the a11y box's own inset around the input.
 *
 * v83's [SERVER_DIALOG_MIN_BAND_DP] arithmetic counted only the label and the
 * field below it (79.5 dp), which is why a 96 dp form body — inside the range
 * that arithmetic suggests — still shows the title and the label while the
 * input itself stays 0…10 px tall: the head above the label is 67.5 dp of the
 * 143.5.
 */
internal const val SERVER_DIALOG_FORM_MIN_DP = 143.5f

/**
 * The band a second-level server form **wants**, in dp: the pinned footer plus
 * the form's own floor ([SERVER_DIALOG_FORM_MIN_DP]) — 80.5 + 143.5 = **224**.
 * It is the floor [serverDialogBandDp] applies; the space above the keyboard can
 * only ever *add* to it, and the card's own top edge (never the form) is what
 * moves when the space cannot pay for it.
 */
internal const val SERVER_DIALOG_WANTED_BAND_DP =
    SERVER_DIALOG_PINNED_FOOTER_DP + SERVER_DIALOG_FORM_MIN_DP

/**
 * The band a **second-level** server form may take: the height the card is
 * drawn with, whose bottom edge lands on the keyboard's top edge.
 *
 * v83 (`research/v83_verify/5556/26_edit_imeup.png` … `29_edit_kbdown.png`)
 * introduced the band and the pinned footer: Settings → Servers → ⋮ → Edit
 * mounts its form as a card *inside* the settings layer, and the form itself was
 * one scrolling column, so with the keyboard up its Save button sat below the
 * fold of the visible band. The card caps itself to the band
 * (`.heightIn(max = band)`); the footer below its scroller then keeps
 * Cancel / Save inside the card.
 *
 * v85 (`research/v85_verify/5556/17…19z_edit_kbd_up.png`,
 * `24_edit_kbd_up_repro.png`, `27_addssh_kbd_up.png`, plus the `u4.xml`/`u5.xml`
 * dumps of that state; the same 1366x768 @160dpi device, so 1 px = 1 dp) found
 * the footer *still* behind the keyboard — Cancel/Save 6 px visible, Add server
 * 10 px — because the band asked for more than the space that exists:
 *
 *  - the v84 panel shape appends the form to the panel's **own scroll content**
 *    (header, list, then the card, `ServersPanel`), so the card's top edge is no
 *    longer the slot's top (the y=91 v83 measured): with the keyboard up it is
 *    y=231 (Edit) / y=227 (Add SSH);
 *  - the keyboard's top edge is y=336 (a 432 dp inset on that window), so the
 *    space left to the card is 105 dp (Edit) / 109 dp (Add SSH) — **below**
 *    [SERVER_DIALOG_MIN_BAND_DP];
 *  - the v83 floor produced a 160 dp card anyway, its bottom edge fell at y=391
 *    (55 dp *behind* the keyboard), and the last 55 dp of that card are exactly
 *    its pinned footer (`u4.xml`: the 48 dp touch targets at y[314,360] around a
 *    32 dp button row at y=322, labels clipped at the IME edge, y=336).
 *
 * v85 made the band the space, which put the footer back on screen but left the
 * form `space − footer`: 24.5 dp at the 105 dp measurement and 0 dp one scroll
 * position lower (`research/v86_verify/5556/`: `ui_edit_pre_type.xml` /
 * `ui_edit_kbdup.xml` have Cancel/Save and **no field node**, `04z_wide_kbd_up.png`
 * / `12z_panel_crop.png` / `14z_card_crop.png` show the empty card,
 * `ui_now.xml` the same at a 86 dp space — the v86 state this helper fixes).
 * The band is therefore
 *
 *  - **floored** at [SERVER_DIALOG_WANTED_BAND_DP] (the pinned footer plus the
 *    form's own floor): the form's scroller is never `space − footer` again;
 *  - capped at the room the card is actually drawn in — the keyboard's top edge
 *    and, while the keyboard is up and the caller measured it, the panel region's
 *    own height ([panelHeightDp], `PanelSlot`'s box through
 *    [LocalOcSelectContainerBounds]);
 *  - and the card's **top edge** is what gives way when the space above the
 *    card is smaller than the band: `serverDialogBandLiftDp` moves the card up
 *    by `band − space`, so its bottom edge lands on the floor
 *    ([serverDialogFloorDp]) — the keyboard's top edge, or the panel's own bottom
 *    edge while the keyboard is up and the panel is measured — with the footer
 *    entirely inside the panel, and never rising above the panel's own top edge.
 *
 * v88: the floor used to be the keyboard's top edge *alone*, so the band (224 dp,
 * the form's floor plus the footer) could be taller than the room between the
 * panel's own top edge and that floor and the card's head rose into the panel's
 * clip. On the v87 device (`research/v87_verify/5556/`, 1366x768 @160dpi,
 * 1 px = 1 dp) the panel's box is y=91…335.5 (245 dp) while the composition read of
 * `WindowInsets.ime` is an IME frame whose transparent top band (the Gboard strip,
 * y=293.5…341.5) is not where the keyboard is drawn: the floor was y=293.5, the room
 * above the panel's top edge only 202.5 dp, and the cards were drawn `[69.5, 293.5]`
 * — 21.5 dp of the head (its top border and its 24 dp top padding) above the panel's
 * top edge, clipped away by the panel's scroller, the visible form 122 dp instead of
 * the 143.5 dp floor, and the footer's bottom 48 px above the keyboard
 * (`02_edit_kbd.png` / `r1_edit_kbd.png`: card interior y=91…292, Cancel/Save's 48 dp
 * targets y[217,265], keyboard from y=342). Taking the panel's own bottom edge as the
 * floor draws the same state at `[111.5, 335.5]`: inside the panel, the whole
 * 143.5 dp form visible, the footer's bottom on the settings card's own bottom line,
 * 6 px above the first keyboard pixel.
 *
 * On the device that is `band = 224 dp` for both cards whatever their flow
 * position (112…336 drawn, the form 143.5 dp, Cancel/Save inside the card's last
 * 80.5 dp — y≈260…308 for their 48 dp touch targets), and
 * with the keyboard **down** `band = 427 dp` for every caller that has no panel
 * top (the v85/v86 value, because `space` = the window minus the card's top edge
 * there is above the floor and the lift is 0) — but `294.5 dp` for the two cards
 * that do pass the panel's top edge: v89's floor is that panel's own bottom edge
 * in both keyboard states, so the keyboard-down card ends on the line its own
 * scroller clips at instead of 132 dp past it ([serverDialogFloorDp]).
 *
 * [imeBottomDp] must be the keyboard's own height as the **window** reports it
 * ([windowImeBottomDp]), read **raw**: `WindowInsets.ime.getBottom(density)` in a
 * composable body *is* the platform's `WindowInsetsCompat.getInsets(IME).bottom`
 * (Compose 1.7.6 `AndroidWindowInsets.getBottom`). The exclusion an ancestor's
 * inset padding performs applies to *modifier* reads — `windowInsetsPadding` and
 * `consumeWindowInsets` consume for their descendants through
 * `ModifierLocalConsumedWindowInsets` — and not to this composition read, so a
 * card nested inside the settings layer still sees the keyboard's height here.
 * That is what the device above shows: the fed value was 432 dp (the 105 dp
 * space was measured against it), not a consumed 0, and the v83 note that "a
 * second keyboard padding adds nothing" is about the modifier, not this read.
 * With the keyboard down [imeBottomDp] is 0 and the floor is the panel's own
 * bottom edge, so the band is the space down to it (294.5 dp at the v88 device's
 * y=341 flow top; 442.5 dp scrolled to the panel's end, where the card's own
 * content is 442.5 dp and therefore the band does not bind — `ui_kbdown_scrolled.xml`
 * has that state at y[193,635.5] with the footer on the panel's bottom edge).
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun serverDialogFloorDp(
    windowHeightDp: Float,
    imeBottomDp: Float = 0f,
    panelTopDp: Float = -1f,
    panelHeightDp: Float = 0f,
): Float {
    val keyboardTopDp = (windowHeightDp - imeBottomDp.coerceAtLeast(0f)).coerceAtLeast(0f)
    return if (panelHeightDp > 0f && panelTopDp >= 0f) {
        panelTopDp + panelHeightDp
    } else {
        keyboardTopDp
    }
}

/**
 * The y a **second-level** server card's bottom edge lands on, in window dp: the
 * *floor* of the box the card is laid out in (v88, generalized to both keyboard
 * states by v89).
 *
 * Whenever the caller measured the panel's own box ([panelTopDp] + [panelHeightDp],
 * `PanelSlot`'s visible region through [LocalOcSelectContainerBounds]) the floor is
 * the panel's **bottom edge**: the card's pinned footer ends on the same line as
 * the panel's own, and no part of the card is left outside the region its scroller
 * clips. With the keyboard **up** (v88) that is what puts the whole card — head
 * included — inside the panel and its footer on the settings card's own bottom
 * line instead of 21.5 dp of head clipped away above the panel's top edge
 * (`research/v87_verify/5556/`, see [serverDialogBandDp] for the numbers).
 *
 * With the keyboard **down** (v89) the same floor is what makes the *other* half of
 * the card reachable. The v88 keyboard-down floor was the keyboard's top edge —
 * with the keyboard down, the **window's** bottom — 132 dp *below* the panel's own
 * clip, so the card laid itself out from its flow top to the window bottom and its
 * pinned footer drew past the panel's scroller: on the v88 device
 * (`research/v88_verify/5556/06_edit_kbddown.png`, 1366x768 @160dpi, 1 px = 1 dp)
 * the panel's box is y=133…635.5 (`ui_15_edit_kbddown.xml`'s `ScrollView`
 * `[434,133][1173,636]`) while the `Edit server` card was drawn y=341…768 — its
 * 80.5 dp footer at y=687.5…768, **entirely below the clip**, with the a11y footer
 * nodes present and no pixel of them on screen (the pixel probe's only
 * non-background row in y=620…700 is the settings card's own bottom border at
 * y=683). Save was reachable only by scrolling the panel 132 dp (the v87 tester:
 * "上滑面板外层 148px 后 footer 露出 y575..591 可点") or by tapping a field to raise
 * the keyboard. On the panel's own bottom edge the same state draws y=341…635.5,
 * its footer y=555…635.5 inside the panel — no scroll, the same reachability the
 * keyboard-up state has had since v88.
 *
 * The keyboard's own top edge (`windowHeight − ime`) is the floor only when the
 * caller cannot say where the panel's box begins ([panelTopDp] left at its
 * "unmeasured" default, or a zero height) — the v83…v88 arithmetic, kept to the
 * pixel for every caller that never had the panel's top edge (the V83/V85/V87
 * pins' call shape).
 *
 * v88 device (`research/v87_verify/5556/`, 1366x768 @160dpi, 1 px = 1 dp): the
 * panel's box is y=91…335.5 (245 dp) while the composition read of `WindowInsets.ime`
 * there is an IME frame whose transparent top band (the Gboard strip,
 * y=293.5…341.5) is *not* where the keyboard is drawn — the layer's own card ends
 * at y=335.5, 6 px above the first keyboard pixel (y=342). A card taking its floor
 * from that inset (y=293.5, the v87 behavior) drew `[69.5, 293.5]`: 21.5 dp of its
 * head was clipped away above the panel's 91 dp top edge (the visible form 122 dp
 * instead of the 143.5 dp floor) and its footer ended 48 px above the keyboard. On
 * the panel's own bottom edge the same state draws `[111.5, 335.5]`.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun serverDialogBandDp(
    windowHeightDp: Float,
    imeBottomDp: Float = 0f,
    topInWindowDp: Float = 0f,
    panelHeightDp: Float = 0f,
    panelTopDp: Float = -1f,
): Float {
    // The keyboard's top edge, in window dp: everything past it is drawn *behind*
    // the keyboard. An inset larger than the window is "the keyboard ate the
    // window" and leaves no room, not a negative band.
    val keyboardTopDp = (windowHeightDp - imeBottomDp.coerceAtLeast(0f)).coerceAtLeast(0f)
    // Where the card's bottom edge is drawn, and therefore what its flow has to pay
    // for: the space between the card's own top edge (the layout's, never the lifted
    // one) and that floor — the panel's own bottom edge whenever the caller measured
    // it (v88 with the keyboard up, v89 with it down), the keyboard's top edge
    // otherwise.
    val floorDp = serverDialogFloorDp(windowHeightDp, imeBottomDp, panelTopDp, panelHeightDp)
    val spaceDp = floorDp - topInWindowDp.coerceAtLeast(0f)
    // The room the card may occupy while staying on screen: never taller than the
    // panel region it is drawn in (0 = the caller has not measured its panel yet),
    // and never past the keyboard's top edge. The keyboard-up branch is the one that
    // needs the panel's own height as a cap — there the card's box *is* the band and
    // a band taller than the panel would draw its head above the panel's top edge,
    // the v87 defect. With the keyboard down the space to the panel's bottom edge
    // already *is* at most that panel's height for every flow position the panel's
    // scroller can reach (`space = panelBottom − top ≤ panelHeight` because the card
    // is the panel's last child and can never be placed above its own top edge), so
    // the keyboard's edge alone caps it and the room expression is untouched — the
    // v85/v86/v87 pins' shape. (v88: a band inside the panel's own height keeps the
    // drawn card inside the panel's two edges now that the floor is the panel's
    // bottom edge, so the card's head can no longer rise into the clip that cut it
    // in v87.)
    val keyboardUp = imeBottomDp > 0f
    val roomDp = if (keyboardUp && panelHeightDp > 0f) {
        minOf(keyboardTopDp, panelHeightDp)
    } else {
        keyboardTopDp
    }
    // The space can only ever *add* to the form's floor: [spaceDp] below
    // [SERVER_DIALOG_WANTED_BAND_DP] is what the card's top edge gives way for
    // (serverDialogBandLiftDp), not what the form's scroller loses. The pinned
    // footer's own height is the last floor that survives even a room that cannot
    // hold it — a card shorter than its footer could not draw its buttons at all.
    return maxOf(
        minOf(maxOf(spaceDp, SERVER_DIALOG_WANTED_BAND_DP), roomDp),
        SERVER_DIALOG_PINNED_FOOTER_DP,
    )
}

/**
 * How far a second-level form's card moves **up** from the position the panel's
 * flow gives it, in dp, so that the card's bottom edge — the pinned footer —
 * lands on the floor ([serverDialogFloorDp]): the panel's own bottom edge
 * whenever the caller measured the panel (v88 with the keyboard up, v89 with it
 * down), the keyboard's top edge otherwise.
 *
 * The card's top edge is the one quantity v86 let the form pay for
 * ([serverDialogBandDp]'s `space` operand): with the band floored at
 * [SERVER_DIALOG_WANTED_BAND_DP] the card needs `band − space` more than the
 * flow above it leaves, and that much is exactly what this lift takes. It is 0
 * whenever the space can pay for the band — every keyboard-down state whose flow
 * leaves the floor 224 dp of room below the card (`space` = 294.5 at the v88
 * device's y=341 flow top, the panel's bottom edge 635.5 minus that top) — and it
 * is `119 dp` on the v86 device's Edit card (band 224, space 105), which draws the
 * card at y=112…336 with its 80.5 dp footer entirely above the y=336 keyboard.
 * With the keyboard down it is non-zero only where the panel's flow leaves the
 * card less than the band — a long server list, or a panel shorter than 224 dp —
 * and there it is the same mechanism: the card is drawn from the floor up, so its
 * footer stays inside the panel the scroller clips ([serverDialogFloorDp]).
 *
 * The lift is the caller's own `Modifier.offset(y = -lift)` on the card: a real
 * placement, so the card's semantics/a11y bounds — and its hit area — follow the
 * pixels, unlike a draw-time translation.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun serverDialogBandLiftDp(
    bandDp: Float,
    windowHeightDp: Float,
    imeBottomDp: Float = 0f,
    topInWindowDp: Float = 0f,
    panelTopDp: Float = -1f,
    panelHeightDp: Float = 0f,
): Float {
    // The same floor the band is drawn against, so the drawn bottom edge *is* the
    // floor by construction: `top − (band − space) + band = floor`.
    val floorDp = serverDialogFloorDp(windowHeightDp, imeBottomDp, panelTopDp, panelHeightDp)
    val spaceDp = floorDp - topInWindowDp.coerceAtLeast(0f)
    return (bandDp - spaceDp).coerceAtLeast(0f)
}

/**
 * What a lifted card's own surface does with a pointer event that reaches **it**
 * and none of its controls: it consumes it.
 *
 * The lift ([serverDialogBandLiftDp]) draws the card over the panel's header and
 * list, and those rows keep their own hit areas underneath — a tap on the
 * card's padding (its 24 dp gutter, the form's gaps, the footer's 12/24 dp
 * paddings) would otherwise land on a **hidden** `⋮` / `Add SSH server` button
 * drawn under the card. Compose dispatches a pointer event to every node whose
 * bounds contain it, topmost first in the `Main` pass, and a `clickable` ignores
 * a down that was already consumed (`awaitFirstDown(requireUnconsumed = true)`):
 * consuming here, on the card's own node, is what keeps the covered rows
 * unreachable by touch. The card's children are dispatched **before** their
 * parent in that pass, so the form's fields, its scroller and the footer's
 * buttons keep every gesture they own — this only takes what nothing inside the
 * card wanted.
 *
 * Applied only while the card is actually lifted: every state whose space can pay
 * for the band — the keyboard-down flow top the cards are opened at included —
 * leaves the card exactly where it was, with the panel's own scroll receiving a
 * drag over it as before. The keyboard-down state is lifted only where the panel's
 * flow leaves the card less than the band ([serverDialogBandLiftDp]), and there the
 * card really is drawn over the rows above it, so the shield is what those rows
 * keep.
 */
internal fun Modifier.serverDialogLiftShield(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/**
 * The soft keyboard's height, in dp, read from the window rather than through a
 * modifier: `WindowInsets.ime.getBottom` is the platform's own
 * `WindowInsetsCompat.getInsets(IME).bottom` (Compose 1.7.6
 * `AndroidWindowInsets.getBottom`), read in composition so the caller re-runs
 * when the keyboard appears — the same read the settings layer's own IME-aware
 * height uses (`SettingsScreen.BoxWithCappedSize`).
 *
 * Consumption is deliberately not part of that value. An ancestor's inset
 * padding excludes what it spent for *modifier* reads only
 * (`ModifierLocalConsumedWindowInsets`), so a card nested inside a layer that
 * pads for the keyboard still gets the keyboard's full height here — the fact
 * the v85 band was computed from 432 dp, not from 0, is how the v83 "a nested
 * second layer would be 0" note is reconciled with the device.
 */
@Composable
internal fun windowImeBottomDp(): Dp = with(LocalDensity.current) {
    WindowInsets.ime.getBottom(this).toDp()
}

/**
 * The keyboard-up band below which the narrow (<639dp) top chrome yields to
 * the panel, in dp.
 *
 * Full narrow chrome is ~141dp (Close 48 + search 52 + tab strip 40 +
 * hairlines) while a second-level server form *prefers* 160
 * ([SERVER_DIALOG_MIN_BAND_DP], see [serverDialogBandDp] for the keyboard's
 * veto): below 141 + 160 the chrome would eat the form's preferred band, so the
 * search field and the tab strip collapse and the slot below keeps the band. 320
 * is that sum plus the hairlines' margin — the 720x1280@420dp window (band ~208
 * with the keyboard up) collapses, a desktop window (band ~418) does not.
 */
internal const val SETTINGS_SHORT_IME_BAND_DP = 320f

/**
 * Whether the narrow top chrome ([SettingsNavTopBar]'s search field + tab
 * strip) collapses so the panel slot keeps a usable band while the keyboard
 * is up (720x1280@420dp: the Close row + search + strip ate the whole ~170dp
 * card above the IME, leaving the Servers form and the Shell page a ~50px
 * sliver with no way to scroll to the Edit card or its footer).
 *
 * The strip is redundant mid-edit and the query survives in state, so both
 * return with the keyboard; the Close row (the layer's only exit) never
 * collapses. A focused search field vetoes the collapse — unmounting it would
 * drop the IME connection the user is typing on — as does a live query, whose
 * hits render in that same chrome.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun settingsTopChromeCollapsed(
    windowHeightDp: Float,
    imeBottomDp: Float,
    queryBlank: Boolean = true,
    searchFocused: Boolean = false,
): Boolean {
    if (imeBottomDp <= 0f || !queryBlank || searchFocused) return false
    return windowHeightDp - imeBottomDp.coerceAtLeast(0f) < SETTINGS_SHORT_IME_BAND_DP
}

/**
 * The vertical drag the [OcSelectList] pre-scroll connection consumes itself:
 * exactly the room the list can scroll, in the drag's own sign (finger up is
 * negative y and grows the scroll value toward [scrollMax]; finger down is
 * positive y and shrinks it toward 0). Anything beyond that — the list's
 * edges — returns 0 so the panel behind still scrolls through.
 *
 * v48 (`research/v48_verify/tE/v48_16/17/21`): a **short** list whose content
 * fits ([scrollMax] ≤ 0, e.g. Terminal Font's 2 options) used to return 0
 * here, so a swipe starting on the open popup scrolled the settings panel
 * behind it while the popup stood still (125 px of background travel). The
 * previous rule only ate "the part the popup itself can scroll", and a short
 * list has no such part — every vertical drag fell through. An open popup now
 * eats the **whole** vertical delta when it has no scroll room of its own, so
 * the gesture dies on the popup either way: a long list scrolls itself, a
 * short list swallows. Horizontal deltas are never touched (the caller only
 * forwards y), and taps are not scrolls, so both keep working.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun ocSelectNestedConsumeY(deltaY: Float, scrollValue: Float, scrollMax: Float): Float {
    if (deltaY == 0f) return 0f
    if (scrollMax <= 0f) return deltaY
    return if (deltaY < 0f) {
        deltaY.coerceAtLeast(scrollValue - scrollMax)
    } else {
        deltaY.coerceAtMost(scrollValue)
    }
}

/**
 * The fling velocity the [OcSelectList] connection eats itself: the whole
 * vertical velocity when the list has no scroll room of its own
 * ([scrollMax] ≤ 0) — the same v48 short-list case as
 * [ocSelectNestedConsumeY]. Drag deltas die in the pre-scroll above, but the
 * release velocity travels the fling chain on its own: without this the panel
 * behind would still fling on a fast swipe whose drags went nowhere. A list
 * that can scroll ([scrollMax] > 0) returns 0 and keeps the stock behavior —
 * its own scroller flings first, the panel takes the remainder — so long
 * lists (Theme / UI Font) are untouched.
 *
 * Pure dp math so the JVM pin can read it without a Compose runtime.
 */
internal fun ocSelectNestedConsumeVelocityY(velocityY: Float, scrollMax: Float): Float {
    if (velocityY == 0f || scrollMax > 0f) return 0f
    return velocityY
}

/**
 * Whether an option row draws as the current one.
 *
 * Every [OcSelect] call site hands the trigger (and therefore this list) the
 * option's **label** — `options.firstOrNull { it.first == current }?.second`
 * — never the stored id, so comparing the id against it marked nothing, ever:
 * the Model reasoning list showed Hidden/Compact/Full with no current stop
 * (`research/v46_verify/tD/tD_67_reasoning_open.png`), and every other select
 * was exactly as unmarked once opened. Matching the label as well as the id
 * marks the current stop with no call-site change (no two stops of one list
 * share a label, so the extra disjunct cannot mark a second row).
 *
 * Pure so the mapping is JVM-tested without a Compose runtime.
 */
internal fun ocSelectOptionSelected(optionValue: String, optionLabel: String, currentValue: String): Boolean =
    optionValue == currentValue || optionLabel == currentValue

/**
 * The expanded list body: an inline, height-capped, vertically scrollable
 * `Column` — the same card the old unbounded one drew, with the v43 reachability
 * rules. It stays a plain layout child (no `DropdownMenu` / `Popup` window, see
 * [OcSelect]'s KDoc), so the dialog card still clips everything it paints.
 *
 * v47 (`research/v47_verify/tE/tE_12_tf_swipe.png`): a swipe starting on the
 * open list scrolled the settings panel behind it while the list itself stood
 * still. A stock `verticalScroll` offers its drag deltas to the ancestors'
 * pre-scroll first, so the outer panel eats the gesture whenever it still has
 * room — even when the list is the one that needs it. The `nestedScroll`
 * connection below therefore sits *outside* the list's own scroller and takes
 * first crack at the drag, consuming exactly what the list can scroll
 * ([ocSelectNestedConsumeY]) and leaving the rest for the panel: the list eats
 * its own region's gestures, and the panel still scrolls through at the
 * list's edges.
 *
 * v48 (`research/v48_verify/tE/v48_16/17/21`): the v47 rule still leaked for
 * a **short** list (Terminal Font, 2 options): with no scroll room of its own
 * the consume math returned 0 for every drag, so the whole swipe fell through
 * to the panel (the popup stood still, the settings behind travelled 125 px),
 * and the release velocity flung the panel afterwards on a fast swipe. The
 * connection now eats the full vertical delta — and the fling velocity, via
 * [ocSelectNestedConsumeVelocityY] — whenever the list cannot scroll itself;
 * a list that can scroll keeps the exact v47 behavior (own room first, edges
 * fall through, stock fling chain untouched).
 *
 * v49 (`research/v49_verify/tE/v49_33/34/35`): the v48 "no room → swallow
 * everything" case never applied to the **long** lists after all, because
 * their rows drew taller than [OC_SELECT_ROW_HEIGHT_DP] estimates, so a popup
 * showing every option still measured scroll room and took the v47
 * fall-through path (UI Font: 8 options wanted 304 dp, drew ~360 dp → ~34 dp
 * of room → the swipe scrolled the popup to its end and the remainder moved
 * the settings panel behind it by the same ~34 px, once). The rows now carry
 * the constant's height, so the cap, the wanted height and the drawn height
 * are one number again and a fully visible popup is a `maxScroll == 0` popup.
 */
@Composable
private fun OcSelectList(
    options: List<Pair<String, String>>,
    value: String,
    enabled: Boolean,
    fillWidth: Boolean,
    maxHeightDp: Float,
    triggerWidthDp: Float = 0f,
    onPick: (String) -> Unit,
) {
    val c = OcTheme.colors
    val scrollState = rememberScrollState()
    // Reads the scroller live: a snapshot of its value taken here would go
    // stale the first time the list moves, and the math itself stays pure in
    // [ocSelectNestedConsumeY] so the JVM pin can read it without Compose.
    val nestedPriority = remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val consumeY = ocSelectNestedConsumeY(
                    available.y,
                    scrollState.value.toFloat(),
                    scrollState.maxValue.toFloat(),
                )
                return if (consumeY == 0f) Offset.Zero else Offset(0f, consumeY)
            }
            // v48: the short list (maxValue == 0) swallows the release fling
            // too, or a fast swipe flings the panel behind after its drags
            // died above. A list that can scroll leaves the stock chain
            // alone — its own scroller flings first, the panel takes the rest.
            override suspend fun onPreFling(available: Velocity): Velocity {
                val consumeY = ocSelectNestedConsumeVelocityY(
                    available.y,
                    scrollState.maxValue.toFloat(),
                )
                return if (consumeY == 0f) Velocity.Zero else Velocity(0f, consumeY)
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                val consumeY = ocSelectNestedConsumeVelocityY(
                    available.y,
                    scrollState.maxValue.toFloat(),
                )
                return if (consumeY == 0f) Velocity.Zero else Velocity(0f, consumeY)
            }
        }
    }
    Column(
        Modifier
            .then(
                when {
                    fillWidth -> Modifier.fillMaxWidth()
                    // v63: the trigger's measured width wins over the list's own
                    // band, so the menu is exactly as wide as the control it
                    // hangs from (see [OcSelect]'s KDoc). `0` is the
                    // pre-measurement frame and the first frame there still waits
                    // for it (`sideKnown`), so the fallback below only draws in a
                    // call site that never reports a position.
                    triggerWidthDp > 0f -> Modifier.width(triggerWidthDp.dp)
                    else -> Modifier.widthIn(min = 140.dp, max = 260.dp)
                },
            )
            .heightIn(max = maxHeightDp.dp)
            .background(c.layer01, RoundedCornerShape(6.dp))
            .border(0.5.dp, c.borderMuted, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp)
            .nestedScroll(nestedPriority)
            .verticalScroll(scrollState),
    ) {
        options.forEach { (optionValue, label) ->
            val selected = ocSelectOptionSelected(optionValue, label, value)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onPick(optionValue) }
                    // v49 (`research/v49_verify/tE/v49_33/34/35`): the row's real
                    // height was the 13 sp text's own line box plus 20 dp of
                    // padding — ~44 dp against the 37 dp
                    // [OC_SELECT_ROW_HEIGHT_DP] the cap reserves
                    // ([ocSelectListDesiredHeightDp]). A list therefore never
                    // matched its own wanted height: UI Font's 8 options wanted
                    // 304 dp and drew ~360 dp, so the box the cap gave it (and
                    // therefore the *scroll room* it measured) was ~34 dp short
                    // of its content. That silently moved a fully visible popup
                    // onto the scrollable-list path, where the leftover room at
                    // an edge falls through to the panel behind (the v47 rule) —
                    // a swipe on the open popup scrolled the settings list ~34 px
                    // instead of dying on it, while the `maxScroll == 0` swallow
                    // (v48) never got a chance to apply. The row now *is* the
                    // constant, so the wanted height is the drawn height and the
                    // two paths agree. The label is `maxLines = 1` +
                    // ellipsized, so an extreme font scale shortens the text
                    // rather than clipping the row.
                    .height(OC_SELECT_ROW_HEIGHT_DP.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = if (selected) c.textAccent else c.textBase,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight(530) else FontWeight(440),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * `appearance="large"` TextInputV2 (`h-36`, radius 6, `layer-02@60%`).
 *
 * [autoCorrect] defaults **off**: every settings call site holds a value the
 * app keeps verbatim — a URL, an API key, a port, a shell path, a credential
 * label, a search query — so the IME must not rewrite it (Gboard turned the
 * rename labels' `:` into `：` plus autocorrect substitutions before they
 * reached the store). A free-prose field that genuinely wants correction opts
 * back in with an explicit `autoCorrect = true` and a why-comment.
 */
@Composable
fun OcTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    keyboardType: KeyboardType = KeyboardType.Text,
    autoCorrect: Boolean = false,
    onImeAction: (() -> Unit)? = null,
    style: TextStyle? = null,
) {
    val c = OcTheme.colors
    val base = style ?: TextStyle(fontSize = 13.sp, fontWeight = FontWeight(440))
    Box(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(c.layer02.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .border(0.5.dp, c.borderBase, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder != null) {
            Text(placeholder, color = c.textFaint, fontSize = base.fontSize, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = base.copy(color = c.textBase),
            cursorBrush = SolidColor(c.textAccent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = autoCorrect,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            // F13a: without `onNext` an `imeAction = ImeAction.Next` field (the SSH
            // dialog's host / name / key path) fired no callback at all, so the IME
            // moved focus on its own and the text the tester had just typed landed in
            // the *next* field — "the host field got eaten by name".
            keyboardActions = KeyboardActions(
                onNext = { onImeAction?.invoke() },
                onDone = { onImeAction?.invoke() },
                onGo = { onImeAction?.invoke() },
                onSend = { onImeAction?.invoke() },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * `Switch` stand-in (M3 primitives only ship layout; colour comes from tokens).
 *
 * A **controlled** toggle, like upstream's kobalte `Switch`
 * (`packages/ui/src/forms/switch/switch.tsx`: `checked` + `onChange`):
 * `checked` is the value the caller read out of the store and
 * `onCheckedChange` is the only way it changes, so the knob can never drift
 * from what is persisted.
 *
 * It is a real `toggleable` with `Role.Switch` rather than a bare `clickable`
 * Box: the setting is then reachable by TalkBack, by `uiautomator` taps on the
 * reported node, and by keyboard/Enter — not only by a pixel-accurate tap on
 * the 40×24 dp knob. The drawn track keeps upstream's compact geometry
 * (`switch.css` `[data-slot=switch-control]`), and the row's copy is a second
 * target through `SettingsRow(onToggle = …)`.
 *
 * Disabled (`enabled = false`, e.g. a `McpChooserDialog` row whose write is in
 * flight) dims the whole knob to half opacity — the same `0.5f` factor the
 * dock submit buttons and the terminal icons use — and drops the track/knob to
 * the desaturated tokens the other disabled controls use (`OcButton`'s primary
 * falls back from `accent` to `layer03`, `OcSelect`'s value from `textBase` to
 * `textFaint`). Without both halves a pending/busy row read as tappable while
 * its write was disabled. `enabled = true` renders exactly as before.
 */
@Composable
fun OcSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    val c = OcTheme.colors
    val track = if (checked) c.accent else c.layer03
    val knob = if (checked) c.textInverse else c.textMuted
    // Disabled (`enabled = false`, e.g. a `McpChooserDialog` row whose write is
    // in flight) falls back to the desaturated tokens the other disabled
    // controls use: `OcButton`'s primary drops from `accent` to `layer03`,
    // `OcSelect`'s value from `textBase` to `textFaint`.
    val dimmedTrack = if (enabled) track else c.layer03
    val dimmedKnob = if (enabled) knob else c.textFaint
    Box(
        Modifier
            .size(width = 40.dp, height = 24.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .background(dimmedTrack, RoundedCornerShape(12.dp))
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(dimmedKnob, RoundedCornerShape(10.dp)))
    }
}

/**
 * `settings-v2-keybind-button`: `11px/530`, faint, `layer-02` on hover/press.
 *
 * W5-S (D4): it is a **button** now — tapping starts/stops key capture
 * (`keybinds.tsx:448-463` sets `settings-keybind-button--active` while capturing),
 * and the accent tint marks the row that currently owns the keyboard.
 */
@Composable
fun KeybindChip(
    text: String,
    modifier: Modifier = Modifier,
    recording: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = OcTheme.colors
    Box(
        modifier
            .background(if (recording) c.stateInfoBg else c.layer02, RoundedCornerShape(2.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = if (recording) c.stateInfoFg else c.textFaint,
            fontSize = 11.sp,
            fontWeight = FontWeight(530),
        )
    }
}

/** Small square icon button used by the servers tab (`plus` / `dots`). */
@Composable
fun OcIconButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = OcTheme.colors
    Box(
        modifier
            .size(28.dp)
            .background(Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun AddServerButton(onClick: () -> Unit) {
    val c = OcTheme.colors
    OcIconButton(onClick = onClick) { OcPlusIcon(c.iconMuted) }
}

@Composable
fun ServerRowMenu(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = OcTheme.colors
    // The `⋮` trigger is otherwise an unlabelled clickable Box — invisible to
    // TalkBack and to a QA pass reading the accessibility tree. `oc_servers_more`
    // is the same "Server actions" label the web row menu carries.
    val label = stringResource(R.string.oc_servers_more)
    OcIconButton(
        modifier.semantics { contentDescription = label },
        onClick,
    ) { OcDotsIcon(c.iconMuted) }
}

/** Chevron used by [OcSelect]; 10dp, `icon-muted`. Points down closed, up open. */
@Composable
private fun Chevron(tint: Color, expanded: Boolean = false) {
    Canvas(Modifier.size(10.dp)) {
        val s = size.minDimension
        val up = if (expanded) 1f else -1f
        val cy = s * 0.5f
        drawLine(tint, Offset(s * 0.15f, cy - up * s * 0.15f), Offset(s * 0.5f, cy + up * s * 0.18f), s * 0.13f, StrokeCap.Round)
        drawLine(tint, Offset(s * 0.5f, cy + up * s * 0.18f), Offset(s * 0.85f, cy - up * s * 0.15f), s * 0.13f, StrokeCap.Round)
    }
}

/** Body text helper so screens never hardcode a colour. */
@Composable
fun OcBodyText(text: String, modifier: Modifier = Modifier, muted: Boolean = false, size: TextUnit = 13.sp) {
    Text(
        text = text,
        color = if (muted) OcTheme.colors.textMuted else OcTheme.colors.textBase,
        fontSize = size,
        fontWeight = FontWeight(440),
        modifier = modifier,
    )
}
