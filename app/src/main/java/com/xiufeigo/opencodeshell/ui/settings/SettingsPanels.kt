package com.xiufeigo.opencodeshell.ui.settings

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.BuildConfig
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.SettingsStore
import com.xiufeigo.opencodeshell.ui.components.OcDotsIcon
import com.xiufeigo.opencodeshell.ui.nav.TabLayout
import com.xiufeigo.opencodeshell.ui.overlay.DialogScrim
import com.xiufeigo.opencodeshell.ui.overlay.dialogCardBounds
import com.xiufeigo.opencodeshell.ui.overlay.rememberDialogScrimState
import com.xiufeigo.opencodeshell.ui.session.drawer.MobileForm
import com.xiufeigo.opencodeshell.ui.session.drawer.SessionTimeLabel
import com.xiufeigo.opencodeshell.ui.theme.OcSystemFonts
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The `settings-v2` panels, driven by the pure tables in [SettingsModel].
 *
 * Sizes and structure are transcribed 1:1 from the vendored CSS
 * (`research/02` §5.2/§5.3): list = `layer-01`, radius 8, `padding-inline: 20px`;
 * row = `padding-block: 20px` + `0.5px` bottom border (none on `:last-child`).
 */

// ------------------------------------------------------------------- general

/**
 * `SettingsGeneralV2` (`settings/general/general.tsx:465-498`). Render order is
 * fixed by [GENERAL_PANEL]: General (untitled) → Timeline → Advanced.
 *
 * Updates / Display (desktop-only) are intentionally dropped on Android; the
 * Android-specific rows fold into the untitled first block.
 */
@Composable
fun GeneralPanel(
    settings: SettingsStore.Settings,
    store: SettingsStore,
    jumpAction: String? = null,
    onJumpHandled: () -> Unit = {},
) {
    // B-16: the General page is the one panel with a form-dependent row — the
    // mobile-titlebar switch exists only on the mobile form (`general.tsx:369-381`),
    // and the same 768 dp breakpoint decides which strip `ui/App.kt` mounts, so the
    // row can never be shown where its consumer is a no-op.
    val mobile = MobileForm.isMobile(LocalConfiguration.current.screenWidthDp)
    SettingsPanelRows(
        panel = renderedPanel(SettingsTab.GENERAL, mobile = mobile),
        settings = settings,
        store = store,
        jumpAction = jumpAction,
        onJumpHandled = onJumpHandled,
    )
}

/**
 * `SettingsAppearanceV2` (`settings/appearance/appearance.tsx:64-131`): color
 * scheme, theme picker, UI/code/terminal fonts, under upstream's title +
 * description header — the full upstream row order, where every row has a
 * native consumer (see [APPEARANCE_PANEL]).
 */
@Composable
fun AppearancePanel(
    settings: SettingsStore.Settings,
    store: SettingsStore,
    jumpAction: String? = null,
    onJumpHandled: () -> Unit = {},
) {
    SettingsPanelRows(
        panel = APPEARANCE_PANEL,
        settings = settings,
        store = store,
        headerTitleResId = SettingsTab.APPEARANCE.headerTitleResId(),
        descriptionResId = SettingsTab.APPEARANCE.pageDescriptionResId(),
        jumpAction = jumpAction,
        onJumpHandled = onJumpHandled,
    )
}

/**
 * `SettingsNotificationsV2` (`settings/notifications/notifications.tsx:56-127`):
 * the three switches W5-E5's in-app feed consumes, then upstream's `Sound
 * effects` section ([SOUNDS_PANEL]) — two `settings-section`s, exactly like
 * `notifications.tsx:72-126`.
 */
@Composable
fun NotificationsPanel(
    settings: SettingsStore.Settings,
    store: SettingsStore,
    jumpAction: String? = null,
    onJumpHandled: () -> Unit = {},
) {
    SettingsPanelRows(
        panel = NOTIFICATIONS_PANEL + SOUNDS_PANEL,
        settings = settings,
        store = store,
        headerTitleResId = SettingsTab.NOTIFICATIONS.headerTitleResId(),
        descriptionResId = SettingsTab.NOTIFICATIONS.pageDescriptionResId(),
        jumpAction = jumpAction,
        onJumpHandled = onJumpHandled,
    )
}

/**
 * The row panels' body — one scroll for the header (when the page has one) and
 * the rows, plus the search jump.
 *
 * [jumpAction] is a `data-action` the settings search asked to land on (v48:
 * a result only switched tabs before, so a row hit on the page you were already
 * on did nothing at all). While it is set the panel scrolls that row into view
 * and marks it; [onJumpHandled] clears it so the mark fades on its own.
 */
@Composable
private fun SettingsPanelRows(
    panel: List<SettingsSection>,
    settings: SettingsStore.Settings,
    store: SettingsStore,
    headerTitleResId: Int? = null,
    descriptionResId: Int? = null,
    jumpAction: String? = null,
    onJumpHandled: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    // Window-space y per `data-action`, plus the scroller's own: their
    // difference is the row's offset inside the scrolled content, which is what
    // `animateScrollTo` needs. Recomposed on every position pass (rows re-place
    // as the list re-measures), so a second jump reads fresh numbers.
    val rowTops = remember { mutableStateMapOf<String, Int>() }
    var contentTop by remember { mutableIntStateOf(0) }
    val scrollerModifier = Modifier
        .fillMaxSize()
        .verticalScroll(scrollState)
        // v54 (`research/v54_verify/tB/tB_87_appearance_scrolled.png`,
        // `tB_10_about.png`): this scroller had **no** trailing room, unlike
        // `SettingsPanelBody`'s `padding(0 40 40)` — so the last row of an
        // Appearance / Notifications / General page sat flush against the
        // panel's clip edge and read as sliced off, with nothing below it to
        // show there was more. The padding is applied **inside** the scroll
        // (after `verticalScroll`) on purpose: that is what makes it part of
        // the scrolled content, so scrolling to the end brings the row clear
        // of the edge instead of clipping it. Same 40 dp the body panels use,
        // so the two shapes stay consistent.
        .padding(bottom = 40.dp)
        .onGloballyPositioned { contentTop = it.positionInWindow().y.roundToInt() }
    LaunchedEffect(jumpAction) {
        val action = jumpAction ?: return@LaunchedEffect
        // The row reports its position on the layout pass it is placed on, so
        // the target only exists after the first frame this panel draws.
        var target: Int? = null
        var frames = 0
        while (target == null && frames < MAX_JUMP_FRAMES) {
            withFrameNanos { }
            target = rowTops[action]
            frames++
        }
        if (target != null) {
            val offset = (target!! - contentTop - JUMP_LEADING_PX).coerceIn(0, scrollState.maxValue)
            scrollState.animateScrollTo(offset)
            delay(SETTINGS_JUMP_HIGHLIGHT_MS)
        }
        onJumpHandled()
    }
    // BUG-2 (v32 tB): the header is part of the **same** scroll as the rows,
    // exactly like [PairingPanel]. The old split (fixed header + inner
    // `verticalScroll` body) left the page's top band — the title and its
    // description, the first thing under the nav — outside every scroll
    // container, so a swipe that started there was dropped with no feedback at
    // all, and it made the panel's scroll position invisible to an
    // accessibility dump (the fixed header never moves, so "did it scroll?"
    // read as "no"). Upstream scrolls the whole tab body
    // (`settings-v2-tab-body` is `overflow:auto`, header included), and the
    // Pairing page already proved the single-scroll shape on device
    // (`SettingsPanelBody(scroll = false)`). The untitled General page takes
    // the same shape: it has no header to keep out of the scroller, and one
    // scroller is what lets a search hit scroll to a row on it.
    Column(scrollerModifier) {
        if (headerTitleResId != null) {
            SettingsPanelHeader(
                titleResId = headerTitleResId,
                below = descriptionResId?.let { { OcBodyText(stringResource(it), muted = true) } },
            )
        }
        SettingsPanelBody(scroll = false) {
            SettingsPanelSections(panel, settings, store, jumpAction, rowTops)
        }
    }
}

/** How long the row a search hit landed on stays marked. */
private const val SETTINGS_JUMP_HIGHLIGHT_MS = 3_000L

/** Rows report their position on the first frame they are placed on; stop after this many. */
private const val MAX_JUMP_FRAMES = 60

/** Room left above the target row so the row below it stays readable. */
private const val JUMP_LEADING_PX = 24

@Composable
private fun SettingsPanelSections(
    panel: List<SettingsSection>,
    settings: SettingsStore.Settings,
    store: SettingsStore,
    highlightAction: String? = null,
    rowTops: MutableMap<String, Int> = mutableMapOf(),
) {
    val c = OcTheme.colors
    for (section in panel) {
        SettingsSectionBlock(section.titleResId) {
            SettingsList {
                section.rows.forEachIndexed { index, row ->
                    // A SWITCH row's copy is a second target for the same
                    // write: tapping the title flips the setting exactly like
                    // tapping the knob, through the same `store.setSwitch`.
                    val toggle: (() -> Unit)? = if (row.control == SettingsControl.SWITCH) {
                        { store.setSwitch(row.action, !switchValue(settings, row.action)) }
                    } else {
                        null
                    }
                    // The Follow-up behavior description carries upstream's
                    // `{{keybind}}` placeholder (`general.tsx:157-184`), which
                    // `stringResource(id)` would render as a literal `%1$s`:
                    // resolve the formatted copy here and hand it to the row, so
                    // the description stays in the **copy** column like every
                    // other row's (a wide control cannot starve the label).
                    val description = if (row.action == "settings-follow-up-behavior") {
                        row.descriptionResId?.let { stringResource(it, FOLLOW_UP_BEHAVIOR_KEYBIND) }
                    } else {
                        null
                    }
                    val action = row.action
                    SettingsRow(
                        titleResId = row.titleResId,
                        descriptionResId = row.descriptionResId,
                        last = index == section.rows.lastIndex,
                        onToggle = toggle,
                        description = description,
                        modifier = Modifier
                            // The mark a search hit leaves on the row it landed
                            // on — without it the jump reads as "nothing
                            // happened" again (`research/v48_verify/tE/
                            // v48_10_reasoning_searchjump.png`).
                            .background(
                                if (action == highlightAction) c.stateInfoBg else Color.Transparent,
                                RoundedCornerShape(6.dp),
                            )
                            .onGloballyPositioned {
                                rowTops[action] = it.positionInWindow().y.roundToInt()
                            }
                            .testTag("settings-row:$action"),
                    ) {
                        SettingsRowControl(row, settings, store)
                    }
                }
            }
        }
    }
}

/** One row's control, driven purely by [SettingsControl]. */
@Composable
private fun SettingsRowControl(
    row: SettingsRow,
    settings: SettingsStore.Settings,
    store: SettingsStore,
) {
    if (row.action == "settings-timeline-detail") {
        TimelineDetailControl(settings, store)
        return
    }
    when (row.control) {
        SettingsControl.SWITCH -> OcSwitch(
            checked = switchValue(settings, row.action),
            onCheckedChange = { store.setSwitch(row.action, it) },
        )

        SettingsControl.SELECT -> {
            // v46 (`research/v46_verify/tD/tD_63_gen3.png`): the Model
            // reasoning row stores "" until the user picks a stop (unset →
            // follow the preset), which resolved to an empty trigger label and
            // an unmarked list. It reads as [REASONING_MODE_DEFAULT] instead
            // ([reasoningModeOrDefault]), so the row always shows a label and
            // — through [ocSelectOptionSelected]'s label match — always marks
            // its stop.
            val current = stringValue(settings, row.action).let { stored ->
                if (row.action == "settings-reasoning-mode") reasoningModeOrDefault(stored) else stored
            }
            // The two font rows extend their declared stops with the named
            // families this device really has: a probe cannot live in the static
            // table (`UI_FONT_OPTIONS` is a top-level `val`). Every other SELECT
            // renders exactly the options its row declares.
            val declared = when (row.action) {
                "settings-ui-font" -> remember { uiFontOptions(OcSystemFonts.availableSans()) }
                "settings-code-font" -> remember { codeFontOptions(OcSystemFonts.availableMono()) }
                "settings-terminal-font" -> remember { terminalFontOptions(OcSystemFonts.availableMono()) }
                else -> row.options
            }
            val options = declared.map { it.value to (it.label ?: stringResource(it.labelResId)) }
            // BUG (v33 tB): the trigger hugs its value, so on the 外观 page the
            // Code Font row measured its select at ~217 dp inside a ~260 dp row
            // and left the copy column ~27 dp — the title and the
            // `Customise the font used in code blocks` description wrapped one
            // fragment per line beside a full-width dropdown
            // (`research/v33_verify/tB/v33tb_1c_after_swipe2.png`). The same band
            // the TEXT row uses reserves 40 % of the row for the copy at every
            // window width, and ellipsizes the value instead (the menu still
            // lists it in full).
            OcSelect(
                value = options.firstOrNull { it.first == current }?.second ?: current,
                options = options,
                modifier = SettingsRowControlWidth,
                onSelect = { store.update(row.action, it) },
            )
        }

        SettingsControl.TEXT -> OcTextField(
            value = stringValue(settings, row.action),
            onValueChange = { store.update(row.action, it) },
            placeholder = stringResource(R.string.oc_setting_font_default),
            // BUG-2 (v32 tB): `OcTextField` is `fillMaxWidth()` by design
            // (the search field and every dialog input need the whole line), and
            // a row measures its control *before* the weighted copy column. Left
            // unbounded, the field took the entire row, the label column
            // collapsed to 0 dp and its text wrapped to one glyph per line — a
            // ~290 dp tall, label-less box with a full-width `BasicTextField` in
            // the middle (`research/v32_verify/tB/12b_settings_scroll.png`), which
            // is also what made the scrolled page read as a broken empty panel.
            //
            // [SettingsRowControlWidth] is the shared band: the 60 % fraction is
            // what makes the label column structurally safe (a fixed dp cap can
            // still starve it on a 320 dp window), and the 140–260 dp part is what
            // the SELECT controls use too, so this row's control column lines up
            // with its siblings' on a normal phone.
            modifier = SettingsRowControlWidth,
        )
    }
}

/**
 * The `{{keybind}}` the Follow-up behavior description substitutes
 * (`FollowUpBehaviorSetting`, `general.tsx:157-184`): the delivery toggle's
 * alternate key, which the composer binds to Ctrl+Enter
 * (`ComposerDelivery.alternate`).
 */
internal const val FOLLOW_UP_BEHAVIOR_KEYBIND = "Ctrl+Enter"

/**
 * `TimelineDetailControl` (`settings/timeline-detail.tsx:21-68`) reduced to the
 * three booleans the native timeline consumes: the preset dropdown sets the
 * keys it owns, the current value is *detected* against those same keys
 * ([TimelineDetailPreset.of], upstream `timelinePreset(props.value)`), and the
 * three switches below stay the advanced/granular control (upstream's
 * collapsible "Advanced" block) for everything a preset does not own — so a
 * `Custom` chip means shell and edit disagree, i.e. the two switches were
 * flipped independently, and nothing else.
 */
@Composable
private fun TimelineDetailControl(settings: SettingsStore.Settings, store: SettingsStore) {
    val current = TimelineDetailPreset.of(
        shellExpanded = settings.shellToolPartsExpanded,
        editExpanded = settings.editToolPartsExpanded,
        reasoningShown = settings.reasoningSummaries,
    )
    val options = TimelineDetailPreset.Options.map { it.id to stringResource(it.labelResId) }
    // A SELECT row like any other: the preset label ("Detailed", "Minimal") is
    // short, but the band keeps this control column the same width as its
    // siblings' and leaves the copy column its 40 % at every window width.
    OcSelect(
        value = current?.let { stringResource(it.labelResId) }
            ?: stringResource(R.string.oc_settings_timeline_custom),
        options = options,
        modifier = SettingsRowControlWidth,
        // v63 (`research/v63_verify/5554/33-42`): this used to hand the preset id
        // to `SettingsStore.update("settings-timeline-detail", …)`, whose table
        // wrote all three booleans — so picking Compact turned a reasoning
        // summaries switch the user had just switched **off** back on, and the
        // chip then re-derived from a value the user never chose. That store
        // entry is deleted; the write goes through the presets' own key set
        // ([TimelineDetailPreset.writes]) and the per-switch actions, which
        // write exactly one key each, so a preset can only touch what it owns.
        onSelect = { id ->
            val preset = TimelineDetailPreset.Options.firstOrNull { it.id == id } ?: return@OcSelect
            for ((key, value) in preset.writes()) store.setSwitch(key.action, value)
        },
    )
}

/**
 * Controlled read-back of a [SettingsControl.SWITCH] row: the boolean the
 * switch draws, and the value the row's tap flips. Keyed by the row's
 * `data-action` through [stringValue], so it can never disagree with the
 * persisted write [SettingsStore.setSwitch] performs for the same action
 * (both are pinned by `SettingsAutoAcceptSwitchTest`).
 */
fun switchValue(settings: SettingsStore.Settings, action: String): Boolean =
    stringValue(settings, action) == "true"

/**
 * Reads the current value of a `data-action` as a string — the single bridge
 * between the pure [SettingsRow] table and [SettingsStore.Settings].
 */
fun stringValue(settings: SettingsStore.Settings, action: String): String = when (action) {
    "settings-language" -> settings.language
    "settings-auto-accept-permissions" -> settings.autoAcceptPermissions.toString()
    "settings-shell" -> settings.shell
    "settings-terminal-placement" -> settings.terminalPlacement
    "settings-feed-reasoning-summaries" -> settings.reasoningSummaries.toString()
    "settings-feed-shell-tool-parts-expanded" -> settings.shellToolPartsExpanded.toString()
    "settings-feed-edit-tool-parts-expanded" -> settings.editToolPartsExpanded.toString()
    "settings-timeline-detail" -> TimelineDetailPreset.of(
        shellExpanded = settings.shellToolPartsExpanded,
        editExpanded = settings.editToolPartsExpanded,
        reasoningShown = settings.reasoningSummaries,
    )?.id ?: "custom"

    "settings-color-scheme" -> settings.colorScheme
    "settings-theme" -> settings.themeId
    "settings-ui-font" -> settings.uiFont
    "settings-code-font" -> settings.codeFont
    "settings-terminal-font" -> settings.terminalFont
    "settings-notifications-agent" -> settings.notifyAgent.toString()
    "settings-notifications-permissions" -> settings.notifyPermissions.toString()
    "settings-notifications-errors" -> settings.notifyErrors.toString()
    "settings-sounds-agent" -> settings.soundAgent
    "settings-sounds-permissions" -> settings.soundPermissions
    "settings-sounds-errors" -> settings.soundErrors
    "settings-show-file-tree" -> settings.showFileTree.toString()
    "settings-show-search" -> settings.showSearch.toString()
    "settings-show-status" -> settings.showStatus.toString()
    "settings-show-custom-agents" -> settings.showCustomAgents.toString()
    "settings-tab-layout" -> settings.tabLayout
    // B-16: the same pref seen as a boolean — the General page's「底部导航」switch
    // is checked while the stored layout puts the strip at the bottom.
    "settings-mobile-titlebar-bottom" -> TabLayout.bottomStrip(settings.tabLayout).toString()
    "settings-follow-up-behavior" -> settings.followUpBehavior
    "settings-reasoning-mode" -> settings.reasoningMode
    "settings-workspace-destination" -> settings.workspaceDefaultDestination
    "settings-mobile-diff-wrap" -> settings.mobileDiffWrap.toString()
    else -> ""
}

// ------------------------------------------------------------------ shortcuts

/**
 * `SettingsKeybinds` with the **record → conflict-check → persist** flow
 * (`settings/keybinds/keybinds.tsx:248-338`):
 *
 * * tap a binding → the chip reads "Press keys" and the panel takes the keyboard;
 * * `Backspace` / `Delete` (no modifier) writes `"none"` = cleared;
 * * `Escape` cancels; a bare modifier keeps waiting;
 * * a chord already used by another row is **rejected** and the conflict is named
 *   inline (upstream shows the same as a toast);
 * * "Reset to defaults" clears the whole override table (disabled while there is
 *   nothing to reset).
 *
 * The table itself is `ui/App.kt`'s binding list, so what a row shows is what the
 * shell already implements. The recorded override is persisted in
 * [SettingsStore] and displayed from there; wiring it into `globalChord` is a
 * Lead-level change (`research/wave5_settings.md` §D4).
 */
@Composable
fun ShortcutsPanel(settings: SettingsStore.Settings, store: SettingsStore) {
    var recording by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<ShortcutConflict?>(null) }
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val rows = remember(settings.keybinds) { applyKeybindOverrides(settings.keybinds) }
    // Titles are resolved once here (they are resource ids) so the filter stays a
    // plain string match on title + binding, like upstream's fuzzysort target
    // keys `["title", "keybind"]` (`keybinds.tsx:183-186`).
    val titleById = rows.associate { it.id to stringResource(it.titleResId) }
    val grouped = remember(rows, query, titleById) {
        val lowered = query.trim().lowercase()
        val filtered = if (lowered.isEmpty()) rows else rows.filter {
            it.keybind.lowercase().contains(lowered) ||
                titleById[it.id]?.lowercase()?.contains(lowered) == true
        }
        shortcutsByGroup(filtered)
    }
    val hasOverrides = settings.keybinds.isNotEmpty()

    LaunchedEffect(recording) {
        if (recording != null) runCatching { focusRequester.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val id = recording ?: return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (event.key == Key.Escape) {
                    recording = null
                    conflict = null
                    return@onPreviewKeyEvent true
                }
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed
                val shift = event.isShiftPressed
                val meta = event.isMetaPressed
                if ((event.key == Key.Backspace || event.key == Key.Delete) && !ctrl && !alt && !shift && !meta) {
                    store.clearKeybind(id)
                    recording = null
                    conflict = null
                    return@onPreviewKeyEvent true
                }
                // v63 F-3: the **platform** event names the key, not Compose's
                // `Key` enum. `event.key.keyCode` answers 0 for a press the
                // platform dispatched without a key code (and `adb shell input
                // keyevent` is one such path), which `keyCodeToString` then
                // spells `KEYCODE_UNKNOWN` — so every recording came out
                // `UNKNOWN` and overwrote the row's real default
                // (`research/v63_verify/5560/60_key_set.png`). The native event
                // is the same value Compose's `Key` is built from and the same
                // one `ui/App.kt` matches a chord back against; the enum name is
                // the second source, and a press that names nothing is refused
                // rather than written down (`recordedKeyToken`).
                val native = event.nativeKeyEvent
                val name = recordedKeyToken(
                    nativeKeyName = AndroidKeyEvent.keyCodeToString(native.keyCode),
                    unicodeChar = native.unicodeChar.takeIf { it > 0 }?.let { runCatching { it.toChar() }.getOrNull() },
                ) ?: return@onPreviewKeyEvent true
                val candidate = formatKeybindChord(ctrl, alt, shift, meta, name)
                val clashes = keybindConflicts(candidate, rows, id)
                if (clashes.isEmpty()) {
                    store.setKeybind(id, candidate)
                    recording = null
                    conflict = null
                } else {
                    // Upstream stays in capture mode and reports the clash
                    // (`keybinds.tsx:295-304`); nothing is written.
                    conflict = ShortcutConflict(candidate, clashes)
                }
                true
            },
    ) {
        SettingsPanelHeader(
            titleResId = SettingsTab.SHORTCUTS.headerTitleResId(),
            trailing = {
                OcButton(
                    label = stringResource(R.string.oc_settings_shortcuts_reset),
                    enabled = hasOverrides,
                    primary = false,
                    onClick = {
                        recording = null
                        conflict = null
                        store.resetKeybinds()
                    },
                )
            },
            below = {
                OcTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.oc_settings_shortcuts_search),
                    imeAction = ImeAction.Search,
                )
            },
        )
        SettingsPanelBody {
            conflict?.let { notice ->
                val titles = notice.rows.map { stringResource(it.titleResId) }.joinToString()
                OcBodyText(
                    stringResource(R.string.oc_settings_shortcuts_conflict, notice.candidate, titles),
                    muted = false,
                )
            }
            if (grouped.isEmpty()) {
                OcBodyText(stringResource(R.string.oc_settings_shortcuts_empty), muted = true)
            }
            for ((group, groupRows) in grouped) {
                SettingsSectionBlock(group.titleResId()) {
                    SettingsList {
                        groupRows.forEachIndexed { index, row ->
                            SettingsRow(
                                titleResId = row.titleResId,
                                descriptionResId = null,
                                last = index == groupRows.lastIndex,
                            ) {
                                KeybindChip(
                                    text = when {
                                        recording == row.id -> stringResource(R.string.oc_shortcut_press_keys)
                                        isKeybindUnassigned(row.keybind) ->
                                            stringResource(R.string.oc_shortcut_unassigned)

                                        else -> row.keybind
                                    },
                                    recording = recording == row.id,
                                    onClick = {
                                        conflict = null
                                        recording = if (recording == row.id) null else row.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A rejected recording: the candidate chord and the rows that already use it. */
private data class ShortcutConflict(val candidate: String, val rows: List<ShortcutRow>)

// ---------------------------------------------------------------------- about

/**
 * `SettingsAbout` (`settings/about/about.tsx:30-93`) — the native subset, in
 * upstream's order: the version/license intro, the credits
 * (`writtenBy`/`illustratedBy`), the publication lines
 * (`firstPublished`/`firstIllustrated`), the website, the details
 * (`description`/`trademark`/`typeset`), the tagline and the copyright.
 *
 * Copy comes from the generated `settings.about.*` resources (the same keys
 * upstream reads), and the credited names are upstream's own lists
 * (`about.tsx:9-31`) linked through its `profile()` mapping. Three pieces stay
 * undrawn because they are desktop assets, not copy: the animated wordmark
 * (`AnimatedWordmark`) and the two decorative SVGs (`legal.svg`,
 * `anomaly-brush.svg`).
 *
 * One deviation: upstream's `writtenBy` line ends with a **live** GitHub
 * contributor count (`contributors.ts` `loadOtherContributorCount`); this shell
 * makes no third-party request from the About page, so the tail carries
 * upstream's own `FALLBACK_OTHER_CONTRIBUTORS` (935) — the number upstream
 * shows whenever that fetch fails.
 */
@Composable
fun AboutPanel() {
    val c = OcTheme.colors
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(titleResId = SettingsTab.ABOUT.headerTitleResId())
        SettingsPanelBody {
            SettingsList {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.oc_settings_app_name),
                        color = c.textBase,
                        fontSize = 15.sp,
                        fontWeight = FontWeight(640),
                    )
                    OcBodyText(stringResource(R.string.oc_about_version, BuildConfig.VERSION_NAME), muted = true)
                    OcBodyText(stringResource(R.string.oc_about_license), muted = true)
                }
                RowSeparator()
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CreditLine(
                        labelResId = R.string.settings_about_writtenBy,
                        names = ABOUT_WRITERS,
                        tailText = stringResource(
                            R.string.settings_about_otherContributor_other,
                            OTHER_CONTRIBUTORS,
                        ),
                        tailUrl = CONTRIBUTORS_URL,
                    )
                    CreditLine(
                        labelResId = R.string.settings_about_illustratedBy,
                        names = ABOUT_ILLUSTRATORS,
                    )
                }
                RowSeparator()
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OcBodyText(stringResource(R.string.settings_about_firstPublished), muted = true)
                    OcBodyText(stringResource(R.string.settings_about_firstIllustrated), muted = true)
                }
                RowSeparator()
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OcBodyText(stringResource(R.string.oc_about_description), muted = true)
                    OcButton(
                        label = stringResource(R.string.oc_about_website),
                        primary = false,
                        onClick = { uriHandler.openUri(OPENCODE_WEBSITE) },
                    )
                    OcBodyText(stringResource(R.string.settings_about_trademark), muted = true)
                    OcBodyText(stringResource(R.string.settings_about_typeset), muted = true)
                    OcBodyText(stringResource(R.string.settings_about_tagline), muted = true)
                    OcBodyText(stringResource(R.string.oc_about_copyright), muted = true)
                }
            }
        }
    }
}

/**
 * One `CreditLine` (`about.tsx:96-121`): the label, then the names joined with
 * `", "` — the last one with `", <and> "` unless a [tailText] follows, in which
 * case the conjunction introduces the tail. Every name links to its profile
 * ([contributorProfile]); the tail links to [tailUrl].
 */
@Composable
private fun CreditLine(
    labelResId: Int,
    names: List<String>,
    tailText: String? = null,
    tailUrl: String? = null,
) {
    val c = OcTheme.colors
    val label = stringResource(labelResId)
    val and = stringResource(R.string.settings_about_and)
    val text = buildAnnotatedString {
        append(label)
        append(" ")
        names.forEachIndexed { index, name ->
            if (index > 0) {
                append(if (index == names.lastIndex && tailText == null) ", $and " else ", ")
            }
            withLink(LinkAnnotation.Url(contributorProfile(name))) {
                withStyle(SpanStyle(color = c.textAccent)) { append(name) }
            }
        }
        if (tailText != null) {
            append(", $and ")
            if (tailUrl == null) {
                append(tailText)
            } else {
                withLink(LinkAnnotation.Url(tailUrl)) {
                    withStyle(SpanStyle(color = c.textAccent)) { append(tailText) }
                }
            }
        }
    }
    Text(
        text = text,
        color = c.textMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight(440),
        lineHeight = 20.sp,
    )
}

/** `about.tsx:9-25` — the credited writers, in upstream order. */
private val ABOUT_WRITERS = listOf(
    "thdxr",
    "adamdotdev",
    "rekram1-node",
    "kitlangton",
    "iamdavidhill",
    "jayair",
    "fwang",
    "brendonovich",
    "nexxeln",
    "hona",
    "kommander",
    "jlongster",
    "vimtor",
    "r44vcorp",
    "simonklee",
    "arvsrn",
)

/** `about.tsx:26` — the credited illustrators. */
private val ABOUT_ILLUSTRATORS = listOf("usrnk1", "ludvigrask_", "arvsrn", "iamdavidhill")

/**
 * `contributors.ts:5` `FALLBACK_OTHER_CONTRIBUTORS` — upstream's count for the
 * "other contributors" tail whenever its GitHub read is unavailable (this shell
 * never makes that read).
 */
private const val OTHER_CONTRIBUTORS = 935

/** The tail's target (`about.tsx:48`). */
private const val CONTRIBUTORS_URL = "https://github.com/anomalyco/opencode/graphs/contributors"

/** `about.tsx:108-112` `profile(name)`: GitHub, with upstream's two exceptions. */
private fun contributorProfile(name: String): String = when (name) {
    "r44vcorp" -> "https://github.com/R44VC0RP"
    "ludvigrask_" -> "https://x.com/ludvigrask_"
    else -> "https://github.com/$name"
}

/** `settings.about.website` points at the public site (`about.tsx:74-77`). */
private const val OPENCODE_WEBSITE = "https://opencode.ai"

// ---------------------------------------------------------------- shell

/**
 * The Shell tab: the server's `config.shell`
 * (`settings/general/controllers.ts:26-69` `createServerShellController`) —
 * `SettingsServerApi.serverShell()` =
 * `GET /api/config` + `GET /api/config/shell` through
 * [SettingsV2Delegate.configJson]/[SettingsV2Delegate.shells]. Upstream renders
 * this as a General-panel Select that writes `PATCH /api/experimental/config`;
 * this tab keeps the current value and the option list and adds the write half
 * below them ([ShellEditControls] → [SettingsServerData.setServerShell], blank
 * clears).
 *
 * `serverShell()` answers `null` on a v1 server (no v2 delegate) — the page
 * says so instead of drawing an empty picker.
 */
@Composable
fun ShellPanel(source: SettingsServerData?) {
    var reload by remember { mutableStateOf(0) }
    var state by remember(source, reload) {
        mutableStateOf<SettingsPageLoad<ServerShellInfo>?>(null)
    }
    LaunchedEffect(source, reload) {
        state = when {
            source == null -> SettingsPageLoad.NoServer
            else -> try {
                val shell = source.serverShell()
                if (shell == null) SettingsPageLoad.Failed(SHELL_UNSUPPORTED) else SettingsPageLoad.Loaded(shell)
            } catch (e: Exception) {
                SettingsPageLoad.Failed(e.message ?: "error")
            }
        }
    }
    // One outer scroll for the header and the page below it (the v32
    // single-scroll shape): on a short-window + keyboard viewport the fixed
    // header above an inner body left the Shell page a sliver with no way to
    // reach its rows. With the keyboard down everything fits and the geometry
    // is unchanged.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsPanelHeader(
            titleResId = SettingsTab.SHELL.headerTitleResId(),
            trailing = {
                OcButton(
                    label = stringResource(R.string.oc_settings_page_reload),
                    primary = false,
                    onClick = { reload++ },
                )
            },
            below = SettingsTab.SHELL.pageDescriptionResId()
                ?.let { { OcBodyText(stringResource(it), muted = true) } },
        )
        SettingsPanelBody(scroll = false) {
            when (val page = state) {
                null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
                SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
                is SettingsPageLoad.Failed ->
                    if (page.reason == SHELL_UNSUPPORTED) {
                        ServerPageMessage(stringResource(R.string.oc_settings_shell_unsupported))
                    } else {
                        ServerPageMessage(stringResource(R.string.oc_settings_page_failed, page.reason))
                    }

                is SettingsPageLoad.Loaded -> {
                    val shell = page.rows
                    SettingsList {
                        val rows = shellPageRows(
                            shell = shell,
                            summaryTitle = stringResource(R.string.oc_settings_shell_current),
                            autoLabel = stringResource(R.string.oc_setting_shell_auto),
                        )
                        if (shell.options.isEmpty()) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                                OcBodyText(stringResource(R.string.oc_settings_shell_unsupported), muted = true)
                            }
                        } else {
                            rows.forEachIndexed { index, row ->
                                when (row) {
                                    is ShellPageRow.Summary -> ShellValueRow(
                                        title = row.title,
                                        value = row.value,
                                        tag = stringResource(R.string.oc_settings_models_current),
                                    )
                                    is ShellPageRow.Option -> ShellValueRow(
                                        title = row.shell,
                                        value = "",
                                        tag = if (row.current) {
                                            stringResource(R.string.oc_settings_models_current)
                                        } else {
                                            null
                                        },
                                    )
                                }
                                if (index != rows.lastIndex) RowSeparator()
                            }
                        }
                    }
                    ShellEditControls(
                        current = shell.current,
                        source = source,
                        onWritten = { reload++ },
                    )
                }
            }
        }
    }
}

/** Diagnostic token: the active connection is not v2, so `serverShell()` is null. */
private const val SHELL_UNSUPPORTED = "no-shell"

@Composable
private fun ShellValueRow(title: String, value: String, tag: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = OcTheme.colors.textBase, fontSize = 13.sp, fontWeight = FontWeight(530))
            if (value.isNotEmpty()) {
                Text(
                    text = value,
                    color = OcTheme.colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tag != null) {
            Spacer(Modifier.width(8.dp))
            OcTag(tag)
        }
    }
}

/**
 * The Shell tab's write half: an unbound `config.shell` string with Save and
 * Clear. Blank — typed, or sent by Clear — is the API's "back to auto"
 * (`setServerShell("")` → `PATCH /api/experimental/config {shell:null}`).
 * `ProviderWriteResult.Unsupported` (v1 server, or the delegate write gate
 * still closed) reports "unsupported" instead of failing the page.
 */
@Composable
private fun ShellEditControls(
    current: String?,
    source: SettingsServerData?,
    onWritten: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val savedText = stringResource(R.string.oc_settings_shell_saved)
    val unsupportedText = stringResource(R.string.oc_settings_shell_write_unsupported)
    val failedFormat = stringResource(R.string.oc_settings_shell_write_failed, "")

    fun write(value: String) {
        if (source == null || busy) return
        busy = true
        status = null
        scope.launch {
            when (val result = source.setServerShell(value)) {
                ProviderWriteResult.Done -> {
                    draft = value
                    onWritten()
                    status = savedText
                }
                ProviderWriteResult.Unsupported -> status = unsupportedText
                is ProviderWriteResult.Failed ->
                    status = failedFormat.replace("%1\$s", result.reason)
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OcBodyText(stringResource(R.string.oc_settings_shell_edit), muted = true)
        OcTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = stringResource(R.string.oc_settings_shell_edit_hint),
            onImeAction = { write(draft) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.oc_common_save),
                enabled = source != null && !busy,
                onClick = { write(draft) },
            )
            OcButton(
                label = stringResource(R.string.oc_settings_shell_clear),
                primary = false,
                enabled = source != null && !busy,
                onClick = { write("") },
            )
        }
        status?.let { OcBodyText(it, muted = true) }
    }
}

// ----------------------------------------------------------- experimental tab

/**
 * `SettingsExperimental` (`settings/experimental/experimental.tsx:16-86`): the
 * upstream fields, honestly projected — see [ExperimentalRow] for the gate
 * table.
 *
 * **B-11:** the Tabs row is live — upstream's `horizontal`/`vertical` select
 * (`experimental.tsx:26-44`) writing `appearance.tabLayout` through the same
 * action table every other select uses, with `AppearancePrefs` → `OcApp` →
 * `BottomTabStrip` as its consumer. The remaining visible row (Show project
 * names) is **disabled** with `oc_settings_experimental_unavailable`: its
 * upstream consumer is the vertical tab nav's project line
 * (`shell/titlebar/tab-nav.tsx:303`) and this shell's `SessionTabRef` carries no
 * project to render. The browser-pane and prod-gated project-icon rows follow
 * upstream's own `Show when={…}` gates and stay hidden. Below them the optional
 * v1 history migration status (`GET /api/experimental/migration/v1`) renders
 * read-only when the connection speaks v2.
 */
@Composable
fun ExperimentalPanel(
    source: SettingsServerData? = null,
    settings: SettingsStore.Settings,
    store: SettingsStore,
) {
    var migration by remember(source) { mutableStateOf<MigrationStatusRow?>(null) }
    LaunchedEffect(source) {
        migration = source?.migrationStatusJson()?.let(::migrationStatusRow)
    }
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = SettingsTab.EXPERIMENTAL.headerTitleResId(),
            below = SettingsTab.EXPERIMENTAL.pageDescriptionResId()
                ?.let { { OcBodyText(stringResource(it), muted = true) } },
        )
        SettingsPanelBody {
            SettingsList {
                SettingsRow(
                    titleResId = R.string.oc_settings_experimental_tabs,
                    descriptionResId = R.string.oc_settings_experimental_tabs_description,
                    last = false,
                ) {
                    val options = TAB_LAYOUT_OPTIONS.map { it.value to stringResource(it.labelResId) }
                    val current = stringValue(settings, "settings-tab-layout")
                    // In a `SettingsRow` like the Appearance selects, so it takes
                    // the same band rather than hugging the layout's label.
                    OcSelect(
                        value = options.firstOrNull { it.first == current }?.second ?: current,
                        options = options,
                        modifier = SettingsRowControlWidth,
                        onSelect = { store.update("settings-tab-layout", it) },
                    )
                }
                // v46 tD_100: no explicit divider call here — the Tabs row
                // above draws its own bottom border (`last = false`), and a
                // second divider read as two adjacent lines under the card. The
                // disabled row below is `last = true`, so the list keeps exactly
                // one divider between the two rows and none after the last one.
                ExperimentalDisabledRow(
                    titleResId = R.string.oc_settings_experimental_project_name,
                    descriptionResId = R.string.oc_settings_experimental_project_name_description,
                )
            }
            OcBodyText(stringResource(R.string.oc_settings_experimental_unavailable), muted = true)
            migration?.let { status ->
                SettingsSectionBlock(R.string.oc_settings_migration_section) {
                    MigrationStatusRowUi(status)
                }
            }
        }
    }
}

@Composable
private fun MigrationStatusRowUi(status: MigrationStatusRow) {
    val detail = when {
        status.status == "completed" -> stringResource(R.string.oc_settings_migration_completed)
        status.status == "required" -> stringResource(R.string.oc_settings_migration_required)
        status.status == "running" -> stringResource(
            R.string.oc_settings_migration_running,
            status.label.orEmpty().ifEmpty { status.status },
        )
        status.status == "error" -> stringResource(
            R.string.oc_settings_migration_error,
            status.error.orEmpty().ifEmpty { status.status },
        )
        else -> status.status
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.oc_settings_migration_section),
                color = OcTheme.colors.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            Text(
                text = detail,
                color = OcTheme.colors.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight(440),
                lineHeight = 20.sp,
            )
        }
    }
}

/**
 * The disabled Show-project-names row, drawn as a [SettingsRow] exactly like its
 * Tabs sibling above it.
 *
 * v46 tD_99: this used to be a hand-built `Row` — `Column(weight 1f) | 8 dp |
 * OcTag` — and Compose measures the **unweighted** chip first, so the 28-char
 * "Not available in this app yet" chip took two thirds of the row and the copy
 * column collapsed to a third, wrapping the title and description one fragment
 * per line beside a floating chip (the same measure-order trap
 * [SettingsRowControlWidth] documents). As a `SettingsRow` the copy owns the
 * row's own column and the chip is the control inside the shared band: on a
 * phone-width window the row stacks the chip under the copy (upstream's wrapped
 * control), and on a wide window the band reserves the copy its 40 % — the chip
 * and the text can no longer squeeze each other at any width.
 */
@Composable
private fun ExperimentalDisabledRow(titleResId: Int, descriptionResId: Int) {
    SettingsRow(
        titleResId = titleResId,
        descriptionResId = descriptionResId,
        last = true,
    ) {
        Box(
            modifier = SettingsRowControlWidth,
            contentAlignment = Alignment.CenterEnd,
        ) {
            OcTag(stringResource(R.string.oc_settings_experimental_unavailable))
        }
    }
}

// ----------------------------------------------- read-only server pages (D1)

/**
 * Loads one read-only page. `null` while the request is in flight; a source-less
 * dialog (previews, tests) resolves to [SettingsPageLoad.NoServer] so the page
 * says why it is empty instead of silently showing nothing. A throwing [load]
 * lands on [SettingsPageLoad.Failed] — the `null` "loading" value must always
 * be replaced, whatever the transport does.
 */
@Composable
private fun <T> rememberServerPage(
    source: SettingsServerData?,
    reloadKey: Int,
    /**
     * A-5: a second key for pages that also ride a live event burst (the MCP
     * page keys on the settled `mcp.*` revision). Defaulted to 0 so every other
     * page keeps its single-key identity.
     */
    burstKey: Int = 0,
    load: suspend (SettingsServerData) -> SettingsPageLoad<T>,
): SettingsPageLoad<T>? {
    var value by remember(source, reloadKey, burstKey) { mutableStateOf<SettingsPageLoad<T>?>(null) }
    LaunchedEffect(source, reloadKey, burstKey) {
        value = if (source == null) {
            SettingsPageLoad.NoServer
        } else {
            try {
                load(source)
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                SettingsPageLoad.Failed(e.message ?: "error")
            }
        }
    }
    return value
}

/**
 * `SettingsProjects` (`settings/workspaces/projects.tsx`) as a **read-only** list:
 * name + worktree + vcs, straight from `GET /project`.
 *
 * No create/delete/rename action is drawn: upstream's project settings edit the
 * server's project store, and this wave adds no write endpoints
 * (`OpenCodeClient` is owned by another wave).
 */
@Composable
fun ProjectsPanel(source: SettingsServerData?) {
    var reload by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val state = rememberServerPage(source, reload) { it.projects() }
    ServerPageScaffold(
        titleResId = SettingsTab.PROJECTS.headerTitleResId(),
        descriptionResId = SettingsTab.PROJECTS.pageDescriptionResId(),
        state = state,
        onReload = { reload++ },
    ) { rows ->
        if (rows.isEmpty()) {
            OcBodyText(stringResource(R.string.oc_settings_projects_empty), muted = true)
        } else {
            if (showsProjectSearch(rows.size)) {
                OcTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.settings_projects_search_placeholder),
                    imeAction = ImeAction.Search,
                )
                Spacer(Modifier.height(12.dp))
            }
            val filtered = remember(rows, query) { filterProjectRows(rows, query) }
            if (filtered.isEmpty()) {
                OcBodyText(
                    stringResource(R.string.settings_search_empty_query, query),
                    muted = true,
                )
            } else {
                SettingsSectionBlock(R.string.oc_settings_projects_section) {
                    SettingsList {
                        filtered.forEachIndexed { index, row ->
                            ServerListRow(
                                title = row.name,
                                subtitle = row.worktree,
                                tag = row.vcs,
                            )
                            if (index != filtered.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

/**
 * `SettingsWorkspaces` (`settings/workspaces/workspaces.tsx`): the worktree
 * inventory (`GET /experimental/worktree`, or the flattened
 * `GET /api/worktree?projectID=` rows) with its project filter, the per-row
 * linked-session line and both bulk deletes.
 *
 * B-10 closed the three parity gaps the audit found against upstream:
 * - the **project filter** (`:87-98`, `:375-390`) over the owner the v2 read
 *   stamps on each row ([workspaceProjectOptions] offers no stops until more
 *   than one project owns a worktree, upstream's own `length > 1` gate);
 * - **Delete all worktrees** / **Delete worktrees without sessions**
 *   (`:304-323`, `:387-392`) through the same `DELETE /api/worktree` write the
 *   single-row action uses, one directory at a time
 *   (`removeWorkspacesSequentially`), with upstream's confirm + warning copy;
 * - the row's **linked-session count** and **last-active relative time**
 *   (`:129-173`, `:437`), read per directory through
 *   [SettingsServerData.workspaceSessions].
 *
 * WAVE 2.3a (A4 #17) kept: each row's Delete is guarded by [worktreeDeleteGuard]
 * — an active location or linked sessions block with an error, a dirty tree
 * needs the force confirm. The preflight signals the native client cannot
 * compute (vcs status) are assumed clean; the guards that CAN be evaluated still
 * apply, and the confirm dialog always shows the upstream warning copy. The
 * write itself is `DELETE /api/worktree` via
 * [SettingsServerData.deleteWorktree].
 */
@Composable
fun WorktreesPanel(source: SettingsServerData?) {
    var reload by remember { mutableStateOf(0) }
    var project by remember { mutableStateOf(WORKSPACE_FILTER_ALL) }
    var pendingDelete by remember { mutableStateOf<WorkspaceRow?>(null) }
    var bulk by remember { mutableStateOf<WorkspaceBulk?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    /** Rows the last bulk run kept because they still have linked sessions. */
    var kept by remember { mutableStateOf(0) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A-3 (the workspaces half of the relative-time freeze): the clock the rows'
    // last-active labels are measured against. The label used to read
    // `System.currentTimeMillis()` inside a `remember` — a read Compose cannot
    // see — so a row whose only moving input is time was skipped and a label
    // composed while the newest session was「just now」kept reading「just now」
    // for as long as the page stayed open (the same freeze the palette's meta
    // cell, the drawer's `RelativeTimeText` and Home's session rows each had to
    // fix). The tick lives in this panel, which `PanelSlot`'s `when (tab)`
    // composes *only* while the Workspaces tab is the selected one, so the
    // effect's lifetime is the panel's visibility gate: switching tabs or
    // closing the dialog leaves composition and cancels the loop.
    var workspacesNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            workspacesNow = System.currentTimeMillis()
            delay(WORKSPACES_CLOCK_TICK_MS)
        }
    }
    val state = rememberServerPage(source, reload) { it.workspaceInventory() }
    // B-10: the linked-session inventory, one read per worktree directory
    // (`workspaces.tsx:106-113`). Keyed on `state` — its identity only changes
    // when the page reloads, so this cannot re-fire on every recomposition.
    var sessionsByDirectory by remember {
        mutableStateOf<Map<String, List<OpenCodeClient.Session>>>(emptyMap())
    }
    LaunchedEffect(source, state) {
        val directories = (state as? SettingsPageLoad.Loaded)?.rows?.map { it.directory }.orEmpty()
        if (source == null || directories.isEmpty()) {
            sessionsByDirectory = emptyMap()
            return@LaunchedEffect
        }
        val loaded = mutableMapOf<String, List<OpenCodeClient.Session>>()
        coroutineScope {
            directories.map { directory ->
                async { directory to source.workspaceSessions(directory) }
            }.awaitAll().forEach { (directory, result) ->
                // A directory whose read failed keeps NO entry: the count then
                // renders "unavailable" and the bulk deletes leave that row
                // alone instead of reading a failure as "no sessions".
                if (result is SettingsPageLoad.Loaded) loaded[directory] = result.rows
            }
        }
        sessionsByDirectory = loaded
    }

    val rows = (state as? SettingsPageLoad.Loaded)?.rows.orEmpty()
    val projectOptions = remember(rows) { workspaceProjectOptions(rows) }
    val filtered = remember(rows, project) { filterWorkspaceRows(rows, project) }
    val withoutSessions = remember(filtered, sessionsByDirectory) {
        workspacesWithoutSessions(filtered, sessionsByDirectory)
    }

    ServerPageScaffold(
        titleResId = SettingsTab.WORKSPACES.headerTitleResId(),
        descriptionResId = SettingsTab.WORKSPACES.pageDescriptionResId(),
        state = state,
        onReload = { reload++ },
        toolbar = { loadedRows ->
            WorkspacesToolbar(
                rowCount = filtered.size,
                totalRows = loadedRows.size,
                projectOptions = projectOptions,
                project = project,
                onProject = { project = it },
                canDelete = source != null && !deleting,
                hasWithoutSessions = withoutSessions.isNotEmpty(),
                onDeleteAll = { bulk = WorkspaceBulk.ALL },
                onDeleteWithoutSessions = { bulk = WorkspaceBulk.WITHOUT_SESSIONS },
            )
        },
    ) { _ ->
        if (rows.isEmpty()) {
            OcBodyText(stringResource(R.string.oc_settings_worktrees_empty), muted = true)
        } else {
            failure?.let { ServerPageMessage(it) }
            if (kept > 0) OcBodyText(stringResource(R.string.settings_workspaces_deleteAll_warning), muted = true)
            if (filtered.isEmpty()) {
                // The filter hid everything: say so instead of painting an
                // empty list with a live toolbar above it.
                OcBodyText(stringResource(R.string.oc_settings_worktrees_empty), muted = true)
            } else {
                SettingsSectionBlock(R.string.oc_settings_worktrees_section) {
                    SettingsList {
                        filtered.forEachIndexed { index, row ->
                            WorkspaceRowUi(
                                row = row,
                                sessions = sessionsByDirectory[row.directory],
                                projectFiltered = project != WORKSPACE_FILTER_ALL,
                                canDelete = source != null && !deleting,
                                onDelete = { pendingDelete = row },
                                // A-3: the ticking clock the row's label measures
                                // against (see `workspacesNow`).
                                now = workspacesNow,
                            )
                            if (index != filtered.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { row ->
        SettingsModalOverlay(onDismiss = { if (!deleting) pendingDelete = null }) {
            WorktreeDeleteDialog(
                row = row,
                deleting = deleting,
                onDismiss = { if (!deleting) pendingDelete = null },
                onConfirm = { force ->
                    if (source == null) return@WorktreeDeleteDialog
                    deleting = true
                    failure = null
                    kept = 0
                    scope.launch {
                        val result = source.deleteWorktree(row.directory, force)
                        deleting = false
                        when (result) {
                            ProviderWriteResult.Done -> {
                                pendingDelete = null
                                reload++
                            }
                            ProviderWriteResult.Unsupported -> {
                                pendingDelete = null
                                failure = "unsupported"
                            }
                            is ProviderWriteResult.Failed -> {
                                pendingDelete = null
                                failure = result.reason
                            }
                        }
                    }
                },
            )
        }
    }
    bulk?.let { action ->
        val targets = when (action) {
            WorkspaceBulk.ALL -> filtered
            WorkspaceBulk.WITHOUT_SESSIONS -> withoutSessions
        }
        SettingsModalOverlay(onDismiss = { if (!deleting) bulk = null }) {
            BulkWorktreeDeleteDialog(
                action = action,
                count = targets.size,
                deleting = deleting,
                onDismiss = { if (!deleting) bulk = null },
                onConfirm = {
                    if (source == null) return@BulkWorktreeDeleteDialog
                    deleting = true
                    failure = null
                    kept = 0
                    scope.launch {
                        var blocked = 0
                        var failed = false
                        // `removeWorkspacesSequentially`: one directory at a time,
                        // and a row the guard refuses is *kept* (upstream's warning
                        // copy says exactly that) instead of aborting the run.
                        for (row in targets) {
                            val linked = sessionsForWorkspace(
                                sessionsByDirectory[row.directory].orEmpty(),
                                row.directory,
                            ).size
                            val guard = worktreeDeleteGuard(
                                dirty = false,
                                linkedSessions = linked,
                                isActiveLocation = false,
                                force = false,
                            )
                            if (guard != WorktreeDeleteGuard.OK) {
                                blocked++
                                continue
                            }
                            when (source.deleteWorktree(row.directory, false)) {
                                ProviderWriteResult.Done -> Unit
                                ProviderWriteResult.Unsupported -> failed = true
                                is ProviderWriteResult.Failed -> failed = true
                            }
                        }
                        deleting = false
                        bulk = null
                        kept = blocked
                        if (failed) failure = "write-failed"
                        reload++
                    }
                },
            )
        }
    }
}

/** The two bulk actions of the page's ⋮ menu (`workspaces.tsx:387-392`). */
enum class WorkspaceBulk { ALL, WITHOUT_SESSIONS }

/**
 * The page toolbar (`workspaces.tsx:375-397`): the count of the **filtered**
 * rows, the project picker (only when more than one project owns a worktree)
 * and the ⋮ menu with the two bulk deletes.
 *
 * The row is three **equal** children — `count (weight 1f) | 8 dp | select
 * (weight 1f) | 8 dp | ⋮ (weight 1f)` — so the three sub-items hold the same
 * width band at every window width. Compose measures an **unweighted** child
 * before a weighted one, so a select left to its own 140–260 dp band took that
 * band out of the row and left the count 0 dp wide on a phone-width window; a
 * weight on every child bounds all three instead, and the select *fills* the
 * share it is handed ([OcSelect]'s `fillWidth`) so its band cannot make its
 * visible box narrower than the count's cell again. [SettingsRowControlWidth] is
 * not the bound here: its 60 % would starve the count, which is why each child
 * carries a weight of its own. Upstream keeps the same rule with
 * `justify-content: space-between` + a `max-w-48` trigger + a truncating label
 * (`settings.css` `.settings-workspaces-toolbar`): no child may take a
 * sibling's room, and a control that cannot fit its own truncates instead (the
 * select's label, here).
 *
 * The ⋮ is upstream's `IconButton` + `outline-dots` trigger
 * (`aria-label={common.moreOptions}`) at the end of its third — the row's right
 * edge, where the header's Reload button ends too. It is an icon and not a text
 * label because a third of a narrow row is 90–94 dp (the 640 dp window is the
 * narrowest: its 608 dp dialog loses the 241 dp nav column, and the panel's
 * 40 dp inset a side leaves a 287 dp row), where "More options" is ~107 dp wide
 * and would ellipsize into an unreadable trigger; the icon is also the shape
 * every other ⋮ in the shell uses (`ServerRowMenu`, `HomeProjectsList`,
 * `SessionHeader`). Its label rides `oc_more_options` as a `contentDescription`
 * instead.
 *
 * The count is capped at two lines with an ellipsis: at that same third an
 * unbounded `Text` would break the word and read as a vertical sliver.
 */
@Composable
private fun WorkspacesToolbar(
    rowCount: Int,
    totalRows: Int,
    projectOptions: List<Pair<String, String>>,
    project: String,
    onProject: (String) -> Unit,
    canDelete: Boolean,
    hasWithoutSessions: Boolean,
    onDeleteAll: () -> Unit,
    onDeleteWithoutSessions: () -> Unit,
) {
    val c = OcTheme.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(
                if (rowCount == 1) R.string.settings_workspaces_count_one else R.string.settings_workspaces_count_other,
                rowCount.toString(),
            ),
            color = c.textMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight(440),
            // The count's third is 90–94 dp at worst; two lines and an ellipsis
            // keep it a *count* there instead of a one-character column (the
            // select truncates its own value inside its third, so neither child
            // is the one that disappears).
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (projectOptions.isNotEmpty()) {
            val labels = projectOptions.map { (id, label) ->
                id to if (id == WORKSPACE_FILTER_ALL) stringResource(R.string.settings_workspaces_filter_all) else label
            }
            OcSelect(
                value = labels.firstOrNull { it.first == project }?.second
                    ?: stringResource(R.string.settings_workspaces_filter_all),
                options = labels,
                // The weight is the select's third; `fillWidth` is what makes its
                // visible box *be* that third (see this function's KDoc).
                modifier = Modifier.weight(1f),
                fillWidth = true,
                onSelect = onProject,
            )
        }
        val menuEnabled = canDelete && totalRows > 0
        val menuLabel = stringResource(R.string.oc_more_options)
        Box(
            Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            // The ⋮ keeps a Box of its own inside the third: `DropdownMenu`
            // anchors to its parent's bounds, so a cell-wide anchor would open
            // the menu a third of the row to the left of the trigger.
            Box {
                OcIconButton(
                    modifier = Modifier.semantics { contentDescription = menuLabel },
                    enabled = menuEnabled,
                    onClick = { menu = true },
                ) { OcDotsIcon(if (menuEnabled) c.iconMuted else c.iconFaint) }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(6.dp),
                    containerColor = c.layer01,
                    tonalElevation = 0.dp,
                    border = BorderStroke(0.5.dp, c.borderMuted),
                ) {
                    if (hasWithoutSessions) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.settings_workspaces_deleteWithoutSessions),
                                    color = c.textBase,
                                    fontSize = 13.sp,
                                )
                            },
                            onClick = {
                                menu = false
                                onDeleteWithoutSessions()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.settings_workspaces_deleteAll),
                                color = c.textBase,
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            menu = false
                            onDeleteAll()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One worktree row (`workspaces.tsx:400-445`): name + directory, the linked
 * session line (`:129-173`) and, on the right, the newest session's relative
 * time plus the Delete action.
 */
@Composable
private fun WorkspaceRowUi(
    row: WorkspaceRow,
    sessions: List<OpenCodeClient.Session>?,
    projectFiltered: Boolean,
    canDelete: Boolean,
    onDelete: () -> Unit,
    /** A-3: the panel's clock, so the last-active label follows the wall clock. */
    now: Long,
) {
    val c = OcTheme.colors
    val linked = remember(sessions, row.directory) {
        sessionsForWorkspace(sessions.orEmpty(), row.directory)
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, color = c.textBase, fontSize = 13.sp, fontWeight = FontWeight(530))
            Text(
                text = row.directory,
                color = c.textMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = workspaceSessionLabel(
                    sessions = sessions,
                    linked = linked.size,
                    projectName = row.projectName,
                    projectFiltered = projectFiltered,
                ),
                color = c.textFaint,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        linked.firstOrNull()?.updatedAt?.let { updatedAt ->
            Spacer(Modifier.width(8.dp))
            WorkspaceRelativeTime(updatedAt = updatedAt, now = now)
        }
        if (row.branch != null) {
            Spacer(Modifier.width(8.dp))
            OcTag(row.branch)
        }
        Spacer(Modifier.width(8.dp))
        OcButton(
            label = stringResource(R.string.settings_workspaces_delete_button),
            primary = false,
            enabled = canDelete,
            onClick = onDelete,
        )
    }
}

/**
 * The row's meta line (`workspaces.tsx:129-173` `sessionCount`): the linked
 * session count with its project, the filter's own variant
 * (`settings.workspaces.sessions.filtered`) when a project is selected, and the
 * two non-answers — still loading, or the read failed — named instead of
 * rendered as "0 sessions".
 */
@Composable
private fun workspaceSessionLabel(
    sessions: List<OpenCodeClient.Session>?,
    linked: Int,
    projectName: String?,
    projectFiltered: Boolean,
): String = when {
    sessions == null -> stringResource(R.string.session_messages_loading)
    projectFiltered -> stringResource(
        if (linked == 1) R.string.settings_workspaces_sessions_filtered_one
        else R.string.settings_workspaces_sessions_filtered_other,
        linked.toString(),
    )
    else -> stringResource(
        if (linked == 1) R.string.settings_workspaces_sessions_one
        else R.string.settings_workspaces_sessions_other,
        linked.toString(),
        projectName ?: stringResource(R.string.settings_workspaces_filter_all),
    )
}

/**
 * The newest linked session's relative time (`workspaces.tsx:167-173`
 * `lastActive`, upstream's `settings.workspaces.lastActiveSession` tooltip).
 * The bucketing is the drawer's ([SessionTimeLabel]) so both surfaces round the
 * same way; this shell has no tooltip component, so the value is what shows.
 *
 * [now] is a parameter and never a `System.currentTimeMillis()` read inside this
 * composable: such a read is invisible to Compose, so the row — whose only
 * moving input is time — is skipped once composed and the label freezes at the
 * bucket it was composed with. That is the freeze the palette's meta cell, the
 * drawer's rows and Home's session rows each had to fix; [WorktreesPanel]'s
 * `workspacesNow` carries the panel's ticking value here (see its note).
 */
@Composable
private fun WorkspaceRelativeTime(updatedAt: Long, now: Long) {
    val relative = SessionTimeLabel.relative(updatedAt, now) ?: return
    Text(
        text = when (relative.unit) {
            SessionTimeLabel.Unit.JUST_NOW -> stringResource(R.string.oc_time_just_now)
            SessionTimeLabel.Unit.MINUTES ->
                pluralStringResource(R.plurals.oc_time_minutes_ago, relative.count, relative.count)

            SessionTimeLabel.Unit.HOURS ->
                pluralStringResource(R.plurals.oc_time_hours_ago, relative.count, relative.count)

            SessionTimeLabel.Unit.DAYS ->
                pluralStringResource(R.plurals.oc_time_days_ago, relative.count, relative.count)
        },
        color = OcTheme.colors.textMuted,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

/**
 * How often the workspaces panel's clock re-measures, i.e. how stale a
 * last-active label can get while the panel stays open. The same 30 s cadence as
 * the palette's (`PALETTE_CLOCK_TICK_MS`), the drawer's
 * (`DRAWER_CLOCK_TICK_MS`) and Home's (`HOME_CLOCK_TICK_MS`) — the four
 * surfaces share one bucketing, so they should also re-read it at one rate.
 */
private const val WORKSPACES_CLOCK_TICK_MS = 30_000L

/**
 * The bulk confirm (`workspaces.tsx:304-323` `DialogDeleteWorkspaces`): title,
 * the plural confirmation and the warning, with the count the caller resolved
 * from the rows the action will actually touch.
 *
 * This is the dialog's **body**: [WorktreesPanel] mounts it inside
 * `SettingsModalOverlay` (the MCP/credential modals' layer). Rendered as a bare
 * sibling of the page it was only a stack of lines painted over the panel — no
 * surface, no scrim, three body lines on top of each other — which is exactly
 * what a confirm dialog must not be.
 */
@Composable
private fun BulkWorktreeDeleteDialog(
    action: WorkspaceBulk,
    count: Int,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val titleRes = when (action) {
        WorkspaceBulk.ALL -> R.string.settings_workspaces_deleteAll
        WorkspaceBulk.WITHOUT_SESSIONS -> R.string.settings_workspaces_deleteWithoutSessions
    }
    val confirmRes = when (action) {
        WorkspaceBulk.ALL ->
            if (count == 1) R.string.settings_workspaces_deleteAll_confirm_one
            else R.string.settings_workspaces_deleteAll_confirm_other

        WorkspaceBulk.WITHOUT_SESSIONS ->
            if (count == 1) R.string.settings_workspaces_deleteWithoutSessions_confirm_one
            else R.string.settings_workspaces_deleteWithoutSessions_confirm_other
    }
    val warningRes = when (action) {
        WorkspaceBulk.ALL -> R.string.settings_workspaces_deleteAll_warning
        WorkspaceBulk.WITHOUT_SESSIONS -> R.string.settings_workspaces_deleteWithoutSessions_warning
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(titleRes), muted = false)
        OcBodyText(stringResource(confirmRes, count.toString()), muted = true)
        OcBodyText(stringResource(warningRes), muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.common_cancel),
                primary = false,
                enabled = !deleting,
                onClick = onDismiss,
            )
            OcButton(
                label = stringResource(R.string.common_delete),
                enabled = !deleting && count > 0,
                onClick = onConfirm,
            )
        }
    }
}

/**
 * One worktree's delete confirm (`workspaces.tsx:180-238` `DialogDeleteWorkspace`)
 * — the row's own force-delete body, mounted by [WorktreesPanel] in
 * `SettingsModalOverlay` like the bulk one above.
 */
@Composable
private fun WorktreeDeleteDialog(
    row: WorkspaceRow,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var force by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OcBodyText(stringResource(R.string.workspace_delete_confirm, row.name), muted = false)
        OcBodyText(stringResource(R.string.settings_workspaces_delete_warning), muted = true)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcSwitch(checked = force, enabled = !deleting, onCheckedChange = { force = it })
            OcBodyText(stringResource(R.string.workspace_status_dirty), muted = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OcButton(
                label = stringResource(R.string.common_cancel),
                primary = false,
                enabled = !deleting,
                onClick = onDismiss,
            )
            OcButton(
                label = stringResource(R.string.common_delete),
                enabled = !deleting,
                onClick = { onConfirm(force) },
            )
        }
    }
}

/**
 * A-5: the **settled** MCP revision, published once by `OcApp`
 * (`ChatViewModel.mcpRevision` → [rememberSettledRevision]).
 *
 * It is a CompositionLocal rather than a parameter because both consumers are
 * dialog layers — this panel (through `SettingsDialogContent`) and
 * `ui/overlay/McpChooserDialog` — and neither the settings screen nor the
 * chooser's host owns the `ChatViewModel` that produces it. 0 (the default:
 * previews, tests, an app without a view model) means "no event yet", so the
 * consumers fetch exactly as they did before.
 */
val LocalMcpRevision = staticCompositionLocalOf { 0 }

/**
 * Upstream `settleMs` (`_upstream/packages/client/src/solid/data.ts:79`): the
 * window a burst of same-key events has to go quiet for before the single
 * refetch fires.
 */
private const val MCP_SETTLE_MS = 150L

/**
 * A-5: trailing debounce of [revision] — the Compose form of upstream's
 * `settle(key, load)` (`client/src/solid/data.ts:218-230`).
 *
 * Booting a location with nine MCP servers emits nine `mcp.status.changed`
 * events as each one settles (`data.ts:1284-1286`), and each one bumps
 * `ChatViewModel.mcpRevision`; a collector that fetched per event would issue
 * nine `GET /api/mcp`. The returned value only moves after [settleMillis] of
 * quiet, so N events cost one refetch.
 *
 * Starts at the incoming [revision]: mounting into a live burst must not look
 * like a change, so the caller's first fetch keeps its own trigger.
 */
@Composable
fun rememberSettledRevision(revision: Int, settleMillis: Long = MCP_SETTLE_MS): Int {
    var settled by remember { mutableStateOf(revision) }
    // Keying the effect on `revision` is the debounce: a newer value cancels the
    // pending one and restarts the delay, so only the last event of a burst
    // ever lands.
    LaunchedEffect(revision) {
        if (revision == settled) return@LaunchedEffect
        delay(settleMillis)
        settled = revision
    }
    return settled
}

/**
 * A-5: a `reload`-style counter that bumps **once per settled MCP burst** —
 * 0 while nothing has happened, so a page that keys on it starts out with the
 * identity it always had.
 */
@Composable
fun rememberMcpBurstKey(revision: Int): Int {
    val settled = rememberSettledRevision(revision)
    var bursts by remember { mutableStateOf(0) }
    var seen by remember { mutableStateOf(revision) }
    LaunchedEffect(settled) {
        if (settled == seen) return@LaunchedEffect
        seen = settled
        bursts += 1
    }
    return bursts
}

/**
 * `SettingsExtensions` (`settings/providers/extensions.tsx:24-169`) as three
 * catalogs: the MCP servers (`GET /mcp`), the skills (`GET /skill`) and the
 * plugins (`GET /api/plugin`, v2 only).
 *
 * WAVE 2.3a (A4 #18): the MCP **toggle** is wired — `useMcpToggle` posts
 * `POST /api/experimental/mcp/{server}/{connect,disconnect}` and the row
 * Switch calls [SettingsServerData.setMcpEnabled]. On a v1 server (or while
 * the write is in flight) the Switch disables itself instead of lying.
 * The plugins subtab renders the v2 plugin list; on v1 it shows the documented
 * empty state. Subtabs are `mcps`/`plugins`/`skills` pill tabs
 * (`settings.extensions.tab.*`, plugins title reuses
 * `status.popover.tab.plugins`).
 *
 * A-5: the MCP list is also refreshed off the **live** `mcp.*` events, through
 * [LocalMcpRevision] — the settled revision `OcApp` publishes — so a server
 * that reports `pending → connected/failed` (or another client toggling a
 * server) updates this page without a manual Reload.
 */
@Composable
fun ExtensionsPanel(source: SettingsServerData?) {
    var reload by remember { mutableStateOf(0) }
    var subtab by remember { mutableStateOf(ExtensionSubtab.MCPS) }
    // A-5: one bump per settled burst, keyed onto the MCP page only — upstream
    // refreshes the mcp server list on `mcp.status.changed` and leaves
    // skills/plugins alone (`client/src/solid/data.ts:1287-1290`).
    val mcpBurst = rememberMcpBurstKey(LocalMcpRevision.current)
    val mcp = rememberServerPage(source, reload, mcpBurst) { it.mcpServers() }
    val skills = rememberServerPage(source, reload) { it.skills() }
    val plugins = rememberServerPage(source, reload) { it.plugins() }
    // The add/resources dialogs used to render at the end of the scroll body, so
    // tapping their buttons only added content screens below the fold — no
    // popup — and both buttons looked dead. Their state lives here so they mount
    // in the modal layer above the panel instead.
    var mcpAddOpen by remember { mutableStateOf(false) }
    var mcpAddBusy by remember { mutableStateOf(false) }
    var mcpAddFailure by remember { mutableStateOf<String?>(null) }
    var mcpResourcesOpen by remember { mutableStateOf(false) }
    var mcpResources by remember { mutableStateOf<McpResourceListRow?>(null) }
    var mcpResourcesLoading by remember { mutableStateOf(false) }
    val panelScope = rememberCoroutineScope()
    // A-5: while the resource dialog is up it rides the same settled burst
    // (upstream refreshes the resource catalog on `mcp.resources.changed`).
    LaunchedEffect(mcpResourcesOpen, source, mcpBurst) {
        if (!mcpResourcesOpen) return@LaunchedEffect
        mcpResourcesLoading = true
        mcpResources = source?.mcpResourcesJson()?.let(::mcpResourceListRow)
        mcpResourcesLoading = false
    }
    val mcpPageFailedFormat = stringResource(R.string.oc_settings_page_failed, "")
    val mcpUnsupportedText = stringResource(R.string.oc_settings_mcp_unsupported)
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = SettingsTab.EXTENSIONS.headerTitleResId(),
            trailing = { ReloadButton(onClick = { reload++ }) },
            below = {
                SettingsTab.EXTENSIONS.pageDescriptionResId()?.let {
                    OcBodyText(stringResource(it), muted = true)
                }
            },
        )
        ExtensionSubtabBar(current = subtab, onSelect = { subtab = it })
        SettingsPanelBody {
            when (subtab) {
                ExtensionSubtab.MCPS -> ExtensionMcpList(
                    state = mcp,
                    source = source,
                    onReload = { reload++ },
                    onAddClick = {
                        mcpAddFailure = null
                        mcpAddOpen = true
                    },
                    onResourcesClick = {
                        mcpResources = null
                        mcpResourcesOpen = true
                    },
                )
                ExtensionSubtab.PLUGINS -> ExtensionPluginList(
                    state = plugins,
                    source = source,
                    onReload = { reload++ },
                )
                ExtensionSubtab.SKILLS -> ExtensionSkillList(state = skills)
            }
        }
    }
    if (mcpAddOpen) {
        SettingsModalOverlay(onDismiss = { if (!mcpAddBusy) mcpAddOpen = false }) {
            mcpAddFailure?.let { OcBodyText(it, muted = false) }
            McpAddDialog(
                busy = mcpAddBusy,
                onDismiss = { if (!mcpAddBusy) mcpAddOpen = false },
                onSubmit = { spec ->
                    if (source == null || mcpAddBusy) return@McpAddDialog
                    mcpAddBusy = true
                    mcpAddFailure = null
                    panelScope.launch {
                        val result = when (spec) {
                            is McpAddSpec.Local -> source.mcpAddLocal(spec.name, spec.command)
                            is McpAddSpec.Remote -> source.mcpAddRemote(spec.name, spec.url)
                        }
                        mcpAddBusy = false
                        when (result) {
                            ProviderWriteResult.Done -> {
                                mcpAddOpen = false
                                reload++
                            }
                            ProviderWriteResult.Unsupported -> mcpAddFailure = mcpUnsupportedText
                            is ProviderWriteResult.Failed ->
                                mcpAddFailure = mcpPageFailedFormat.replace("%1\$s", result.reason)
                        }
                    }
                },
            )
        }
    }
    if (mcpResourcesOpen) {
        SettingsModalOverlay(onDismiss = { mcpResourcesOpen = false }) {
            McpResourcesDialog(
                rows = mcpResources,
                loading = mcpResourcesLoading,
                onDismiss = { mcpResourcesOpen = false },
            )
        }
    }
    }
}

/** Pill subtabs of the Extensions page (`mcps`/`plugins`/`skills`). */
private enum class ExtensionSubtab { MCPS, PLUGINS, SKILLS }

@Composable
private fun ExtensionSubtabBar(current: ExtensionSubtab, onSelect: (ExtensionSubtab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExtensionSubtabChip(
            label = stringResource(R.string.settings_extensions_tab_mcps),
            selected = current == ExtensionSubtab.MCPS,
            onClick = { onSelect(ExtensionSubtab.MCPS) },
        )
        ExtensionSubtabChip(
            label = stringResource(R.string.status_popover_tab_plugins),
            selected = current == ExtensionSubtab.PLUGINS,
            onClick = { onSelect(ExtensionSubtab.PLUGINS) },
        )
        ExtensionSubtabChip(
            label = stringResource(R.string.settings_extensions_tab_skills),
            selected = current == ExtensionSubtab.SKILLS,
            onClick = { onSelect(ExtensionSubtab.SKILLS) },
        )
    }
}

@Composable
private fun ExtensionSubtabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = OcTheme.colors
    Box(
        Modifier
            .background(if (selected) c.layer02 else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight(530),
        )
    }
}

@Composable
private fun ExtensionMcpList(
    state: SettingsPageLoad<List<McpPageRow>>?,
    source: SettingsServerData?,
    onReload: () -> Unit,
    onAddClick: () -> Unit,
    onResourcesClick: () -> Unit,
) {
    var pending by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    when (state) {
        null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
        is SettingsPageLoad.Failed ->
            ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

        is SettingsPageLoad.Loaded -> {
            val rows = state.rows
            failure?.let { ServerPageMessage(it) }
            val unsupportedText = stringResource(R.string.oc_settings_mcp_unsupported)
            val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "")
            fun record(result: ProviderWriteResult, done: () -> Unit) {
                pending = null
                when (result) {
                    ProviderWriteResult.Done -> done()
                    ProviderWriteResult.Unsupported -> failure = unsupportedText
                    is ProviderWriteResult.Failed ->
                        failure = pageFailedFormat.replace("%1\$s", result.reason)
                }
            }
            SettingsSectionBlock(R.string.oc_settings_mcp_section) {
                OcBodyText(stringResource(R.string.oc_settings_mcp_runtime_hint), muted = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcButton(
                        label = stringResource(R.string.oc_settings_mcp_add),
                        enabled = source != null && pending == null,
                        onClick = onAddClick,
                    )
                    OcButton(
                        label = stringResource(R.string.oc_settings_mcp_resources),
                        primary = false,
                        enabled = source != null && pending == null,
                        onClick = onResourcesClick,
                    )
                }
                Spacer(Modifier.height(8.dp))
                SettingsList {
                    if (rows.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                            OcBodyText(stringResource(R.string.oc_settings_mcp_empty), muted = true)
                        }
                    } else {
                        rows.forEachIndexed { index, row ->
                            McpToggleRow(
                                row = row,
                                enabled = source != null && pending == null,
                                checked = row.connected,
                                busy = pending == row.name,
                                onCheckedChange = { checked ->
                                    if (pending != null || source == null) return@McpToggleRow
                                    pending = row.name
                                    failure = null
                                    scope.launch {
                                        record(source.setMcpEnabled(row.name, checked)) { onReload() }
                                    }
                                },
                                onDelete = {
                                    if (pending != null || source == null) return@McpToggleRow
                                    pending = row.name
                                    failure = null
                                    scope.launch {
                                        record(source.mcpRemoveServer(row.name)) { onReload() }
                                    }
                                },
                            )
                            if (index != rows.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun McpToggleRow(
    row: McpPageRow,
    enabled: Boolean,
    checked: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.name,
                color = OcTheme.colors.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            row.error?.let {
                Text(
                    text = it,
                    color = OcTheme.colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (busy) {
                OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
            }
        }
        OcButton(
            label = stringResource(R.string.common_delete),
            primary = false,
            enabled = enabled && !busy,
            onClick = onDelete,
        )
        Spacer(Modifier.width(8.dp))
        OcSwitch(checked = checked, enabled = enabled && !busy, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ExtensionPluginList(
    state: SettingsPageLoad<List<PluginPageRow>>?,
    source: SettingsServerData?,
    onReload: () -> Unit,
) {
    var checked by remember { mutableStateOf<List<PluginPageRow>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "")
    val upToDateText = stringResource(R.string.oc_settings_plugins_up_to_date)

    when (state) {
        null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
        is SettingsPageLoad.Failed ->
            ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

        is SettingsPageLoad.Loaded -> {
            val rows = checked ?: state.rows
            val targets = remember(rows) { pluginUpdateTargets(rows) }
            SettingsSectionBlock(R.string.status_popover_tab_plugins) {
                failure?.let { ServerPageMessage(it) }
                notice?.let { ServerPageMessage(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcButton(
                        label = stringResource(R.string.oc_settings_plugins_check),
                        primary = false,
                        enabled = source != null && !busy,
                        onClick = {
                            if (source == null || busy) return@OcButton
                            busy = true
                            failure = null
                            notice = null
                            scope.launch {
                                val body = source.pluginCheckJson()
                                busy = false
                                if (body == null) {
                                    failure = pageFailedFormat.replace("%1\$s", "unsupported")
                                } else {
                                    val rows2 = pluginCheckRows(body)
                                    checked = rows2
                                    notice = if (pluginUpdateTargets(rows2).isEmpty()) upToDateText else null
                                }
                            }
                        },
                    )
                    OcButton(
                        label = stringResource(R.string.oc_settings_plugins_update),
                        enabled = source != null && !busy && targets.isNotEmpty(),
                        onClick = {
                            if (source == null || busy || targets.isEmpty()) return@OcButton
                            busy = true
                            failure = null
                            notice = null
                            scope.launch {
                                when (val result = source.pluginUpdate(targets)) {
                                    ProviderWriteResult.Done -> {
                                        checked = null
                                        onReload()
                                    }
                                    ProviderWriteResult.Unsupported ->
                                        failure = pageFailedFormat.replace("%1\$s", "unsupported")

                                    is ProviderWriteResult.Failed ->
                                        failure = pageFailedFormat.replace("%1\$s", result.reason)
                                }
                                busy = false
                            }
                        },
                    )
                    if (targets.isNotEmpty()) {
                        OcTag(stringResource(R.string.oc_settings_plugins_outdated), accent = true)
                    }
                }
                Spacer(Modifier.height(8.dp))
                SettingsList {
                    if (rows.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                            OcBodyText(stringResource(R.string.oc_settings_plugins_empty), muted = true)
                        }
                    } else {
                        rows.forEachIndexed { index, row ->
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = row.name,
                                        color = OcTheme.colors.textBase,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight(530),
                                    )
                                    if (row.outdated) {
                                        Spacer(Modifier.width(8.dp))
                                        OcTag(stringResource(R.string.oc_settings_plugins_outdated), accent = true)
                                    }
                                }
                                row.packageVersion?.let {
                                    Text(
                                        text = it,
                                        color = OcTheme.colors.textMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (index != rows.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionSkillList(state: SettingsPageLoad<List<SkillPageRow>>?) {
    when (state) {
        null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
        is SettingsPageLoad.Failed ->
            ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

        is SettingsPageLoad.Loaded -> {
            val rows = state.rows
            SettingsSectionBlock(R.string.oc_settings_skills_section) {
                SettingsList {
                    if (rows.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                            OcBodyText(stringResource(R.string.oc_settings_skills_empty), muted = true)
                        }
                    } else {
                        rows.forEachIndexed { index, row ->
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = row.name,
                                    color = OcTheme.colors.textBase,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight(530),
                                )
                                row.description?.let {
                                    Text(
                                        text = it,
                                        color = OcTheme.colors.textMuted,
                                        fontSize = 11.sp,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (index != rows.lastIndex) RowSeparator()
                        }
                    }
                }
            }
        }
    }
}

/** Shared chrome for the single-list read-only pages. */
@Composable
private fun <T> ServerPageScaffold(
    titleResId: Int,
    descriptionResId: Int?,
    state: SettingsPageLoad<T>?,
    onReload: () -> Unit,
    /**
     * B-10: the page's own toolbar, rendered above the list once the rows are
     * known (the Workspaces page's count + project filter + bulk menu). Null for
     * every page that has none.
     */
    toolbar: (@Composable (T) -> Unit)? = null,
    list: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = titleResId,
            trailing = { ReloadButton(onClick = onReload) },
            below = descriptionResId?.let { { OcBodyText(stringResource(it), muted = true) } },
        )
        SettingsPanelBody {
            when (state) {
                null -> OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
                SettingsPageLoad.NoServer -> ServerPageMessage(stringResource(R.string.oc_settings_page_no_server))
                is SettingsPageLoad.Failed ->
                    ServerPageMessage(stringResource(R.string.oc_settings_page_failed, state.reason))

                is SettingsPageLoad.Loaded -> {
                    toolbar?.invoke(state.rows)
                    list(state.rows)
                }
            }
        }
    }
}

/** One name + path (or branch) row of a read-only page. */
@Composable
private fun ServerListRow(title: String, subtitle: String, tag: String?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = OcTheme.colors.textBase, fontSize = 13.sp, fontWeight = FontWeight(530))
            Text(
                text = subtitle,
                color = OcTheme.colors.textMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (tag != null) {
            Spacer(Modifier.width(8.dp))
            OcTag(tag)
        }
    }
}

@Composable
private fun ServerPageMessage(text: String) {
    OcBodyText(text, muted = true)
}

/** Reload is a real action: it re-runs the page's fetch, not a decorative button. */
@Composable
private fun ReloadButton(onClick: () -> Unit) {
    OcButton(
        label = stringResource(R.string.oc_settings_page_reload),
        primary = false,
        onClick = onClick,
    )
}

// ------------------------------------------------------------ providers/models

/**
 * `SettingsProvidersV2` — the real `GET /provider` catalog
 * (`providers/catalog/providers.ts:14-86`).
 *
 * WAVE 2.3a (A4 #1): Connected + Popular sections (`providers.tsx:134-213`)
 * with source badges, provider notes, the recommended badge and the view-all
 * connect entry. Connect opens [IntegrationConnectSheet], which reads
 * `integrationDetail`'s `Method` union and drives the matching route (key /
 * OAuth / command); the stored-credential list (rename + activate + disconnect)
 * and the wellknown import row sit beside them. Disconnect is upstream's
 * credential removal (`providers.tsx:100-121`, `DELETE /api/credential/{id}`
 * per credential of the integration) through [SettingsServerData.disconnectProvider].
 *
 * Body states come from [providersCatalogState]: `Loading` only while the
 * catalog flow is silent within [PROVIDERS_SETTLE_TIMEOUT_MS]; past that (or
 * on a failed/empty catalog) the page lands on a terminal state with retry —
 * the flow's owner can fail without ever emitting, and the tab must not spin.
 */
@Composable
fun ProvidersPanel(extra: SettingsPanelExtras, store: SettingsStore? = null) {
    val providers = extra.providers?.collectAsState()?.value
    val baseRows = remember(providers) { providerRows(providers) }
    // The `GET /provider` catalog flow (`ChatViewModel.availableProviders`) does
    // not re-emit on connect/disconnect, so the panel keeps the ids it changed
    // itself: disconnect marks false, connect marks true. Reset on every fresh
    // catalog emission so the server stays the source of truth.
    var connectionDelta by remember(providers) { mutableStateOf(emptyMap<String, Boolean>()) }
    val adjustedBase = remember(baseRows, connectionDelta) {
        applyProviderConnectionDelta(baseRows, connectionDelta)
    }
    var integrations by remember { mutableStateOf<List<ProviderIntegrationRow>>(emptyList()) }
    var viewAll by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var connectId by remember { mutableStateOf<String?>(null) }
    var renameRow by remember { mutableStateOf<CredentialRow?>(null) }
    var credentialsReload by remember { mutableStateOf(0) }
    var wellknownBusy by remember { mutableStateOf(false) }
    var catalogRetry by remember { mutableStateOf(0) }
    var catalogSettled by remember(extra.providers) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val serverData = extra.serverBridge as? SettingsServerData

    LaunchedEffect(extra.providers, catalogRetry) {
        catalogSettled = false
        if (extra.providers != null) {
            delay(PROVIDERS_SETTLE_TIMEOUT_MS)
            catalogSettled = true
        }
    }

    LaunchedEffect(serverData, providers) {
        integrations = try {
            serverData?.integrations() ?: emptyList()
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    val byIntegration = remember(integrations) { integrations.associateBy { it.id } }
    val enriched = remember(adjustedBase, byIntegration) {
        adjustedBase.map { row ->
            val integration = byIntegration[row.id]
            val types = integration?.connectionTypes ?: emptyList()
            val has = integration?.hasIntegration ?: false
            row.copy(
                source = ProviderConnectCatalog.resolveSource(types, has, catalogSource = null),
                disconnectable = row.connected && ProviderConnectCatalog.canDisconnect(types, has, null),
            )
        }
    }
    val rows = remember(enriched) { ProviderConnectCatalog.providerSourceRows(enriched) }
    val connectedRows = remember(rows) { rows.filter { it.connected } }
    val popularRows = remember(rows) { rows.filter { !it.connected } }
    val connected = connectedRows.size
    val total = providers?.all?.size ?: 0
    val catalogState = providersCatalogState(
        hasCatalogFlow = extra.providers != null,
        providers = providers,
        settled = catalogSettled,
    )
    // "view all" lists every catalog row with its Connect entry — the panel's
    // always-reachable connect door, even when the popular slice is empty.
    val shownConnect = remember(rows, popularRows, viewAll) {
        if (viewAll) rows else popularRows.take(POPULAR_VISIBLE_COUNT)
    }
    val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "%1\$s")
    val disconnectedText = stringResource(R.string.oc_settings_disconnect_done)

    fun refreshIntegrations() {
        scope.launch {
            integrations = serverData?.integrations() ?: emptyList()
            credentialsReload++
        }
    }

    fun refreshAfterWrite() {
        refreshIntegrations()
        extra.onProvidersChanged?.invoke()
    }

    fun recordWrite(result: ProviderWriteResult, successText: String? = null, onDone: () -> Unit) {
        busyId = null
        val reason = writeFailureReason(result)
        if (reason == null) {
            feedback = successText
            onDone()
        } else {
            failure = pageFailedFormat.replace("%1\$s", reason)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = SettingsTab.PROVIDERS.headerTitleResId(),
            below = if (providers == null) {
                null
            } else {
                {
                    OcBodyText(
                        stringResource(R.string.oc_settings_providers_connected, connected),
                        muted = true,
                    )
                    Spacer(Modifier.height(2.dp))
                    OcBodyText(stringResource(R.string.oc_settings_providers_total, total), muted = true)
                    failure?.let {
                        Spacer(Modifier.height(2.dp))
                        OcBodyText(it, muted = false)
                    }
                    feedback?.let {
                        Spacer(Modifier.height(2.dp))
                        OcBodyText(it, muted = true)
                    }
                }
            },
        )
        SettingsPanelBody {
            when (catalogState) {
                ProvidersCatalogState.Missing ->
                    OcBodyText(stringResource(R.string.oc_settings_providers_unavailable), muted = true)

                ProvidersCatalogState.Loading ->
                    OcBodyText(stringResource(R.string.oc_common_loading), muted = true)

                ProvidersCatalogState.TimedOut -> {
                    OcBodyText(pageFailedFormat.replace("%1\$s", "timeout"), muted = false)
                    OcButton(
                        label = stringResource(R.string.oc_settings_page_reload),
                        primary = false,
                        onClick = { catalogRetry++ },
                    )
                }

                ProvidersCatalogState.Empty ->
                    OcBodyText(stringResource(R.string.oc_providers_empty), muted = true)

                is ProvidersCatalogState.Content -> {
                    SettingsSectionBlock(R.string.settings_providers_section_connected) {
                        SettingsList {
                            if (connectedRows.isEmpty()) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                                    OcBodyText(
                                        stringResource(R.string.settings_providers_connected_empty),
                                        muted = true,
                                    )
                                }
                            } else {
                                connectedRows.forEachIndexed { index, row ->
                                    ProviderConnectedRow(
                                        row = row,
                                        busy = busyId == row.id,
                                        canAct = serverData != null && busyId == null,
                                        onDisconnect = {
                                            if (serverData == null) return@ProviderConnectedRow
                                            busyId = row.id
                                            failure = null
                                            feedback = null
                                            scope.launch {
                                                recordWrite(
                                                    serverData.disconnectProvider(row.id),
                                                    successText = disconnectedText,
                                                ) {
                                                    connectionDelta = connectionDelta + (row.id to false)
                                                    refreshAfterWrite()
                                                }
                                            }
                                        },
                                    )
                                    if (index != connectedRows.lastIndex) RowSeparator()
                                }
                            }
                        }
                    }
                    SettingsSectionBlock(R.string.oc_settings_credentials_section) {
                        CredentialsSection(
                            source = serverData,
                            reloadKey = credentialsReload,
                            onChanged = { refreshAfterWrite() },
                            onRename = { renameRow = it },
                        )
                    }
                    SettingsSectionBlock(R.string.settings_providers_section_popular) {
                        SettingsList {
                            if (shownConnect.isEmpty()) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                                    OcBodyText(
                                        stringResource(R.string.oc_settings_providers_popular_empty),
                                        muted = true,
                                    )
                                }
                            } else {
                                shownConnect.forEachIndexed { index, row ->
                                    ProviderPopularRow(
                                        row = row,
                                        canAct = serverData != null && busyId == null,
                                        onConnect = { connectId = row.id },
                                    )
                                    if (index != shownConnect.lastIndex) RowSeparator()
                                }
                            }
                        }
                        if (!viewAll) {
                            OcButton(
                                label = stringResource(R.string.dialog_provider_viewAll),
                                primary = false,
                                onClick = { viewAll = true },
                            )
                        }
                    }
                    SettingsSectionBlock(R.string.oc_settings_wellknown_section) {
                        WellknownImportRow(
                            busy = wellknownBusy,
                            onSubmit = { url ->
                                if (serverData == null || wellknownBusy) return@WellknownImportRow
                                wellknownBusy = true
                                failure = null
                                scope.launch {
                                    recordWrite(serverData.wellknownAdd(url)) { refreshAfterWrite() }
                                    wellknownBusy = false
                                }
                            },
                        )
                    }
                }
            }
        }
        }
        connectId?.let { id ->
            ProviderConnectOverlay(
                providerID = id,
                source = serverData,
                onDismiss = { connectId = null },
                onComplete = {
                    connectId = null
                    connectionDelta = connectionDelta + (id to true)
                    refreshAfterWrite()
                },
            )
        }
        // The rename form used to be appended at the end of the scroll body, so
        // tapping Rename only added content screens below the fold — no popup —
        // and the button looked dead. It mounts in the modal layer instead.
        renameRow?.let { row ->
            SettingsModalOverlay(onDismiss = { if (busyId == null) renameRow = null }) {
                CredentialRenameDialog(
                    row = row,
                    busy = busyId == row.id,
                    onDismiss = { if (busyId == null) renameRow = null },
                    onSubmit = { label ->
                        if (serverData == null || busyId != null) return@CredentialRenameDialog
                        busyId = row.id
                        failure = null
                        scope.launch {
                            recordWrite(serverData.renameCredential(row.id, label)) {
                                renameRow = null
                                refreshAfterWrite()
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Mounts [IntegrationConnectSheet] as a modal overlay above the panel. The
 * sheet used to be a bare column appended at the end of the scrollable panel
 * body, so tapping Connect only added content screens below the fold — no
 * popup, no toast, no log — and the button looked completely dead. Scrim tap
 * or Close dismisses.
 */
@Composable
private fun ProviderConnectOverlay(
    providerID: String,
    source: SettingsServerData?,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
) {
    val c = OcTheme.colors
    val scrim = rememberDialogScrimState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        DialogScrim(scrim, onDismiss)
        Column(
            Modifier.fillMaxWidth()
                .padding(24.dp)
                .heightIn(max = 480.dp)
                .background(c.layer01, RoundedCornerShape(8.dp))
                .border(0.5.dp, c.borderBase, RoundedCornerShape(8.dp))
                .dialogCardBounds(scrim)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                OcButton(
                    label = stringResource(R.string.oc_settings_close),
                    primary = false,
                    onClick = onDismiss,
                )
            }
            IntegrationConnectSheet(
                providerID = providerID,
                source = source,
                onDismiss = onDismiss,
                onComplete = onComplete,
            )
        }
    }
}

/** Visible popular rows before "view all" expands the list. */
private const val POPULAR_VISIBLE_COUNT = 8

@Composable
private fun ProviderConnectedRow(row: ProviderRow, busy: Boolean, canAct: Boolean, onDisconnect: () -> Unit) {
    val c = OcTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight530,
                    // v46 tD_92: two lines, not one — a single end-ellipsized line
                    // cut exactly the tail that tells one provider from the next
                    // (`Default / Ope…`). Two lines fit every cataloged name, so
                    // the distinguishing tail stays on screen.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                OcTag(
                    stringResource(
                        when (row.source) {
                            ProviderConnectCatalog.ProviderSource.ENV -> R.string.settings_providers_tag_environment
                            ProviderConnectCatalog.ProviderSource.API -> R.string.provider_connect_method_apiKey
                            ProviderConnectCatalog.ProviderSource.CONFIG -> R.string.settings_providers_tag_config
                            ProviderConnectCatalog.ProviderSource.CUSTOM -> R.string.settings_providers_tag_custom
                            else -> R.string.settings_providers_tag_other
                        },
                    ),
                    accent = true,
                )
            }
            Text(row.id, color = c.textMuted, fontSize = 13.sp, fontWeight = FontWeight440)
            Text(
                text = stringResource(R.string.oc_settings_provider_models, row.modelCount),
                color = c.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight440,
            )
            if (busy) OcBodyText(stringResource(R.string.oc_common_loading), muted = true)
        }
        if (row.disconnectable) {
            Spacer(Modifier.width(8.dp))
            OcButton(
                label = stringResource(R.string.common_disconnect),
                primary = false,
                enabled = canAct && !busy,
                onClick = onDisconnect,
            )
        } else {
            Spacer(Modifier.width(8.dp))
            OcBodyText(
                stringResource(R.string.settings_providers_connected_environmentDescription),
                muted = true,
            )
        }
    }
}

@Composable
private fun ProviderPopularRow(row: ProviderRow, canAct: Boolean, onConnect: () -> Unit) {
    val c = OcTheme.colors
    val noteKey = remember(row.id) { ProviderConnectCatalog.providerNoteKey(row.id) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight530,
                    // v46 tD_92: two lines, same as the connected row — the tail
                    // is where one provider's name differs from the next.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ProviderConnectCatalog.isRecommended(row.id)) {
                    Spacer(Modifier.width(8.dp))
                    OcTag(stringResource(R.string.dialog_provider_tag_recommended), accent = true)
                }
            }
            if (noteKey != null) {
                val note = when (noteKey) {
                    "dialog.provider.opencode.note" -> stringResource(R.string.dialog_provider_opencode_note)
                    "dialog.provider.opencodeGo.tagline" -> stringResource(R.string.dialog_provider_opencodeGo_tagline)
                    "dialog.provider.anthropic.note" -> stringResource(R.string.dialog_provider_anthropic_note)
                    "dialog.provider.copilot.note" -> stringResource(R.string.dialog_provider_copilot_note)
                    "dialog.provider.openai.note" -> stringResource(R.string.dialog_provider_openai_note)
                    "dialog.provider.google.note" -> stringResource(R.string.dialog_provider_google_note)
                    "dialog.provider.openrouter.note" -> stringResource(R.string.dialog_provider_openrouter_note)
                    "dialog.provider.vercel.note" -> stringResource(R.string.dialog_provider_vercel_note)
                    else -> null
                }
                note?.let {
                    Text(color = c.textMuted, fontSize = 11.sp, fontWeight = FontWeight440, text = it)
                }
            } else if (row.id == "_custom") {
                Text(
                    text = stringResource(R.string.settings_providers_custom_description),
                    color = c.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight440,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        OcButton(
            label = stringResource(R.string.common_connect),
            primary = false,
            enabled = canAct,
            onClick = onConnect,
        )
    }
}

/**
 * `SettingsModelsV2` — every cataloged model, grouped by provider, with the
 * effective model tagged.
 *
 * Intentional v2 divergence (aligned with the composer's ModelPicker decision,
 * `ModelPickerItems.groups(includeUnconnected = true)`): upstream lists every
 * model of every **connected** provider (`providers/models/models.tsx:24-31`),
 * but on the v2 backend `GET /api/integration` reports `connections: []` for
 * every provider, so the connected-only filter emptied the tab. Unconnected
 * rows stay listed with an "Unconnected" badge and keep their visibility
 * Switch. The catalog is the only source of the context window the ring
 * needs (`limit.context`), so the row meta shows it.
 *
 * WAVE 2.3a (A4 #2): each row carries the visibility Switch — the tab's reason
 * to exist (`models.tsx:186-199`, `models.visible/setVisibility`). Groups sort
 * popular-first and collapse per provider (`ModelProvidersSchema.collapsed`);
 * both persist in [SettingsStore] and drive the composer ModelPicker filter
 * (consumer wiring note in `wave2.3a-notes.md` §6).
 */
@Composable
fun ModelsPanel(extra: SettingsPanelExtras, store: SettingsStore? = null) {
    val providers = extra.providers?.collectAsState()?.value
    val selected = extra.selectedModel?.collectAsState()?.value
    val visibility = store?.state?.collectAsState()?.value?.let {
        runCatching { store.modelVisibility() }.getOrDefault(emptyMap())
    } ?: emptyMap()
    val collapsed = store?.state?.collectAsState()?.value?.let {
        runCatching { store.modelsCollapsed() }.getOrDefault(emptyMap())
    } ?: emptyMap()
    val allRows = remember(providers, selected, visibility) { modelRows(providers, selected, visibility) }
    var query by remember { mutableStateOf("") }
    // The catalog flow can stay silent (probe window, failed fetch): without a
    // settle timeout the tab spins on `Loading` forever, exactly the trap the
    // Providers tab's `catalogSettled` fixed. Retry also asks the host to
    // re-pull (`onProvidersChanged` → `ChatViewModel.requestProvidersRefresh`)
    // so a dead catalog has a recovery door, not just a re-wait.
    var catalogRetry by remember { mutableStateOf(0) }
    var catalogSettled by remember(extra.providers) { mutableStateOf(false) }
    LaunchedEffect(extra.providers, catalogRetry) {
        catalogSettled = false
        if (extra.providers != null) {
            delay(PROVIDERS_SETTLE_TIMEOUT_MS)
            catalogSettled = true
        }
    }
    val pageFailedFormat = stringResource(R.string.oc_settings_page_failed, "%1\$s")
    val rows = remember(allRows, query) { filterModelRows(allRows, query) }
    val groups = remember(rows, collapsed, query) {
        sortedModelGroups(rows, collapsed, searching = query.isNotBlank())
    }
    val current = allRows.firstOrNull { it.current }

    Column(Modifier.fillMaxSize()) {
        SettingsPanelHeader(
            titleResId = SettingsTab.MODELS.headerTitleResId(),
            below = if (providers == null) {
                null
            } else {
                {
                    OcBodyText(
                        stringResource(R.string.oc_settings_models_count, allRows.size),
                        muted = true,
                    )
                    Spacer(Modifier.height(2.dp))
                    OcBodyText(
                        text = current?.name
                            ?: stringResource(R.string.oc_settings_models_default),
                        muted = true,
                    )
                }
            },
        )
        SettingsPanelBody {
            when {
                extra.providers == null ->
                    OcBodyText(stringResource(R.string.oc_settings_providers_unavailable), muted = true)

                providers == null && !catalogSettled ->
                    OcBodyText(stringResource(R.string.oc_common_loading), muted = true)

                providers == null -> {
                    OcBodyText(
                        pageFailedFormat.replace("%1\$s", "timeout"),
                        muted = false,
                    )
                    OcButton(
                        label = stringResource(R.string.oc_settings_page_reload),
                        primary = false,
                        onClick = {
                            catalogRetry++
                            extra.onProvidersChanged?.invoke()
                        },
                    )
                }

                allRows.isEmpty() -> {
                    OcBodyText(stringResource(R.string.oc_models_empty), muted = true)
                    // An empty catalog with a live flow is worth one re-pull:
                    // the shared flow re-emits on every provider fetch, so a
                    // transient empty emission heals through the host hook.
                    if (extra.onProvidersChanged != null) {
                        OcButton(
                            label = stringResource(R.string.oc_settings_page_reload),
                            primary = false,
                            onClick = { extra.onProvidersChanged?.invoke() },
                        )
                    }
                }
                else -> {
                    OcTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.oc_settings_models_search),
                        imeAction = ImeAction.Search,
                    )
                    if (rows.isEmpty()) {
                        OcBodyText(stringResource(R.string.oc_settings_search_empty), muted = true)
                    }
                    for (group in groups) {
                        ModelGroupBlock(
                            group = group,
                            canCollapse = query.isBlank() && store != null,
                            onToggleCollapse = {
                                store?.setModelsCollapsed(group.providerID, !group.collapsed)
                            },
                        ) {
                            SettingsList {
                                group.rows.forEachIndexed { index, row ->
                                    ModelVisibilityRow(
                                        row = row,
                                        canToggle = store != null,
                                        onToggle = { visible ->
                                            // Entry-level keys: one Switch per catalog
                                            // entry, so hiding `gpt-5.6-fast` leaves the
                                            // base and `-pro` entries alone. Flipping the
                                            // *base* entry additionally pins its siblings
                                            // to their current state — the base's key is
                                            // also the legacy `providerID:modelID` key,
                                            // which they would otherwise inherit
                                            // (`ModelVisibility.toggleWrites`).
                                            val target = store
                                            if (target != null) {
                                                for (write in ModelVisibility.toggleWrites(allRows, row, visible)) {
                                                    target.setModelVisible(
                                                        write.providerID,
                                                        write.entryID,
                                                        write.visible,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    if (index != group.rows.lastIndex) RowSeparator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelGroupBlock(
    group: ModelGroup,
    canCollapse: Boolean,
    onToggleCollapse: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = canCollapse, onClick = onToggleCollapse),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = group.providerName,
                color = OcTheme.colors.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight(640),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (group.collapsed) "+" else "−",
                color = OcTheme.colors.textMuted,
                fontSize = 13.sp,
            )
        }
        if (!group.collapsed) content()
    }
}

/**
 * Middle-truncates [text] to [maxLength] chars, keeping the head **and** the
 * tail (`head…tail`): the entries this serves (`provider/model` refs) tell
 * apart exactly by their tails, which an end ellipsis would cut. Short enough
 * to fit passes through untouched, so wide windows keep showing full refs.
 *
 * Pure (no `stringResource`) so the split stays unit-testable. This Compose
 * version has no `TextOverflow.MiddleEllipsis`, hence code instead of layout.
 */
internal fun middleEllipsize(text: String, maxLength: Int): String {
    if (maxLength < 2 || text.length <= maxLength) return text
    val tail = (maxLength - 1) / 2
    val head = maxLength - 1 - tail
    return text.take(head) + "…" + text.takeLast(tail)
}

/** Char budget [middleEllipsize] applies to a model row's `provider/model` ref. */
internal const val MODEL_REF_MAX_CHARS = 30

@Composable
private fun ModelVisibilityRow(row: ModelRow, canToggle: Boolean, onToggle: (Boolean) -> Unit) {
    val c = OcTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    color = c.textBase,
                    fontSize = 13.sp,
                    fontWeight = FontWeight530,
                    // v46 tD_89: two lines, not one — a single end-ellipsized
                    // line rendered two different entries as the identical
                    // `DeepSeek V4 Fl…`, the difference living in the cut tail.
                    // Two lines fit every cataloged name, so entries that share
                    // a head stay distinguishable by their tails.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.current) {
                    Spacer(Modifier.width(8.dp))
                    OcTag(stringResource(R.string.oc_settings_models_current), accent = true)
                }
                if (!row.connected) {
                    Spacer(Modifier.width(8.dp))
                    OcTag(stringResource(R.string.oc_settings_models_unconnected))
                }
            }
            // v46 tD_89: one middle-truncated line. The ref is a single unbroken
            // `provider/model` token, so wrapping broke it mid-word
            // (`…flash-vis|ion-exp`); one line can never break a word, and
            // [middleEllipsize] keeps the distinguishing tail — the part the
            // name above may still share — on screen. The trailing end ellipsis
            // is only the last resort for a token even that cannot fit.
            Text(
                middleEllipsize(row.ref, MODEL_REF_MAX_CHARS),
                color = c.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight440,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // v20: the meta prints the entry's **effective window**
        // `min(limit.input, limit.context)` (`ModelRow.window`), the same number
        // the ring / the context tab divide by — not the raw `limit.context`,
        // which no consumer uses (the server compacts on the input half).
        //
        // v27: the meta is the row's **fixed** element. It is laid out
        // single-line (`softWrap = false`) so it can neither wrap nor be
        // ellipsized, and the name column above is the row's only flexible one
        // (`weight(1f)`), so the limit copy stays complete on a 2560 px window
        // instead of being squeezed against the trailing switch.
        formatContextLimit(row.window)?.let { limit ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.oc_settings_model_context, limit),
                color = c.textFaint,
                fontSize = 11.sp,
                fontWeight = FontWeight530,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.width(8.dp))
        }
        OcSwitch(checked = row.visible, enabled = canToggle, onCheckedChange = onToggle)
    }
}

/** The `0.5px` `border-bottom: border-base` between two settings rows. */
@Composable
internal fun RowSeparator() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(OcTheme.colors.borderBase))
}

private val FontWeight530 = androidx.compose.ui.text.font.FontWeight(530)
private val FontWeight440 = androidx.compose.ui.text.font.FontWeight(440)
