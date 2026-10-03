package com.xiufeigo.opencodeshell.ui.servers

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.settings.LocalOcSelectContainerBounds
import com.xiufeigo.opencodeshell.ui.settings.OcBodyText
import com.xiufeigo.opencodeshell.ui.settings.OcButton
import com.xiufeigo.opencodeshell.ui.settings.OcSelect
import com.xiufeigo.opencodeshell.ui.settings.OcTextField
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogBandLiftDp
import com.xiufeigo.opencodeshell.ui.settings.serverDialogLiftShield
import com.xiufeigo.opencodeshell.ui.settings.windowImeBottomDp
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The connection-config half of upstream's `servers/ssh/dialog.tsx:113-287`
 * (`DialogSsh`): the `ssh.target` + `dialog.server.add.name` fields and the
 * Cancel / `dialog.server.add.button` footer, laid out like the add/edit
 * server dialog (`ui/settings/ServersPanel.kt` `AddEditServerDialog`).
 *
 * On submit the dialog dials the tunnel itself (upstream's `ssh.connect` call
 * at `dialog.tsx:198-240`) through [SshConnection] — jsch, the project's SSH client library.
 * The dial hands the host both the [SshConnectionConfig] and the live
 * [SshConnection]: the handle is the 建连 + 状态回传 contract, its
 * [SshConnection.state] carries the connect → authenticating → trust prompt →
 * connected / failed lifecycle this dialog renders (progress line,
 * [SshAuthenticate] for `ssh.continue` / `ssh.trust`, error copy), and
 * `status.localUrl` (`http://127.0.0.1:<port>`) is the `ServerConfigStore` url
 * to activate once the forward is up. The dialog closes on `ready` (upstream
 * `dialog.tsx:210-215`); cancelling mid-attempt disposes the half-built
 * session (`controller.ts` `cancel`), a connected one stays for the host.
 *
 * **Android extension:** the auth-method picker (`password` / `private key`)
 * with its secret fields. Upstream authenticates through the system `ssh`
 * binary and prompts mid-connect; an in-app client needs the credential up
 * front, so the form collects it and [SshConnectionConfig] carries it.
 *
 * Target validation is upstream's form gate only (non-blank,
 * `dialog.tsx:101`); "Remote commands and unsupported SSH options aren't
 * allowed" is enforced at dial time by [parseSshTarget] and surfaces as
 * `SshItem.error == "input"` ([SshErrorCode.INPUT]).
 *
 * **v90 — the submit path's feedback (`research/v89_verify/5556/`).** The v89
 * round found the dialog's "Add server" tap a **silent** no-op on the device:
 * filled Host, tap, nothing (no save, no line, no close, clean logcat), while
 * the same footer's Cancel worked and the primary button reported
 * `clickable+enabled`. The break was the *gate* half of submit
 * ([submit]'s `validateSshForm` refusal) plus its only surface: this form's
 * error line is the **last** thing the scrollable column draws while the v88/v89
 * pinned band leaves that scroller showing the card's head and its first fields
 * — `ui_06_add_ready.xml` has the visible form ending on the auth select
 * (`Authentication` y=506, its `Password` value y=544) with the pinned footer at
 * y=575, so the password field the gate demands, and every line after it, drew
 * below the clip. The tester never filled the password (no dump shows the form
 * scrolled past the name field), the gate refused on
 * [SshFormError.PasswordRequired], its line landed off-screen, and
 * `SshTunnelHost.submit` was never reached — the panel carries no tunnel row in
 * `ui_03`…`ui_10` and the button kept its enabled accent fill, which is what
 * rules out the click, the scope and the host.
 *
 * The fix keeps every surface and every string where they are and makes them
 * reachable: the feedback lines are one group ([SshSubmitFeedback]) and each
 * submit attempt brings the group back into view through
 * [BringIntoViewRequester], which the form's own `verticalScroll` answers
 * (Compose 1.7's `ScrollableNode` delegates a `BringIntoViewResponderNode`, see
 * `androidx.compose.foundation.gestures.Scrollable.kt`). A refused tap therefore
 * scrolls the form to the refusal line — and to the field that caused it — and a
 * failed dial scrolls to the failure line, so neither half of the submit path can
 * be silent again. Nothing in the band/floor/footer arithmetic moves: the group
 * is composed only while it has something to say, and its 12 dp spacing is the
 * form column's own, so a form without feedback is byte-identical.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun SshDialog(
    onSubmit: (SshConnectionConfig, SshConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = OcTheme.colors
    val config = LocalConfiguration.current
    val width = minOf(config.screenWidthDp.dp - 32.dp, 480.dp)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // `dialog.tsx:70`: one id per dialog instance, stable across retries.
    val connectionId = remember { UUID.randomUUID().toString() }
    var target by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf(SshAuthMethod.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var privateKeyPath by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<SshFormError?>(null) }
    var connection by remember { mutableStateOf<SshConnection?>(null) }
    var status by remember { mutableStateOf(SshConnectionStatus()) }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    // v90: how many times the footer's primary action (or a field's IME action) was
    // fired. The counter — not the feedback value — is what re-runs the scroll below,
    // so a second tap on the *same* refusal brings the same line back on screen
    // instead of looking like another dead button.
    var submitAttempts by remember { mutableStateOf(0) }
    // The form's one feedback surface, as a handle the scroller above can answer.
    val feedbackRequester = remember { BringIntoViewRequester() }
    // v98 D3: the form's **required fields** need the same handle the feedback group
    // has, each on its own. The device evidence (`research/v97_verify/5554_v98d3/
    // REPORT_COVERAGE4.md` §2.1/§2.5, `REPORT_COVERAGE5.md` §4.3) is that the band
    // caps this card at ~136.5 dp of scroller (80.5 pinned footer + 56 form floor,
    // `SERVER_DIALOG_WANTED_BAND_DP`) while the form's content runs to the password
    // field at ~218 dp: the viewport is real and so is the scroll range, but the
    // password field sits past the clip and is **pruned from the a11y tree**, so no
    // a11y client (TalkBack, `uiautomator`) can reach or fill it — 11 scroll
    // positions across two rounds never surfaced it. A refusal from
    // [validateSshForm] was therefore unrepairable through the UI.
    //
    // The handle does not change what the gate accepts: it only makes every required
    // field **reachable** (scrollable into the viewport, focusable, clickable, and
    // present in the semantics tree). The refusal path still runs
    // [validateSshForm] and still refuses, and `Add server` keeps its
    // `!busy && target.isNotBlank()` gate — the fields are simply now reachable so
    // the user can fill them.
    val targetRequester = remember { BringIntoViewRequester() }
    val nameRequester = remember { BringIntoViewRequester() }
    val passwordRequester = remember { BringIntoViewRequester() }
    val privateKeyRequester = remember { BringIntoViewRequester() }
    val passphraseRequester = remember { BringIntoViewRequester() }
    val fieldRequesters: Map<SshFormField, BringIntoViewRequester> = mapOf(
        SshFormField.Target to targetRequester,
        SshFormField.Name to nameRequester,
        SshFormField.Password to passwordRequester,
        SshFormField.PrivateKeyPath to privateKeyRequester,
        SshFormField.Passphrase to passphraseRequester,
    )

    /** The one field a refusal points at — see [sshFormErrorField]. */
    suspend fun revealRefusedField(refusal: SshFormError) {
        // One frame between the composition and the request, the same reason the
        // feedback effect above waits: a requester whose node is not attached and
        // placed yet answers nothing.
        withFrameNanos { }
        runCatching { fieldRequesters[sshFormErrorField(refusal)]?.bringIntoView() }
    }

    LaunchedEffect(connection) {
        connection?.state?.collect { status = it }
    }
    LaunchedEffect(status.stage) {
        // `dialog.tsx:210-215`: close once the stage is ready.
        if (status.stage == SshStage.READY) onDismiss()
    }
    // v90: a submit attempt that left a line ([SshSubmitFeedback.onScreen]) scrolls the
    // form to it. Without this the refusal / dial-failure line draws *below* the band's
    // clip — the device evidence in this function's KDoc — and the tap reads as dead.
    // The scroll is the only thing that moves: the band, the floor and the pinned footer
    // are untouched (the geometry pins in `ServersFormImeBandV83/V85/V87Test`,
    // `ServersFormImeFloorV88Test` / `…DownV89Test`).
    val feedback = sshSubmitFeedback(error, status)
    LaunchedEffect(submitAttempts, feedback) {
        if (feedback.onScreen) {
            // One frame between the state write that composes the group and the request:
            // its node has to be attached *and placed* before `bringIntoView()` can read
            // the rect it asks the scroller for (`BringIntoViewRequesterNode` walks the
            // responder chain with the node's own bounds).
            withFrameNanos { }
            feedbackRequester.bringIntoView()
            // v98 D3: the same attempt also reveals the **field the refusal is
            // about**. The v90 request above brings the *line* into view, and on
            // the v98 device that line arrived while the field it asks the user to
            // fill stayed below the clip and out of the a11y tree — so the
            // instruction had nothing to point at and the form could not be
            // completed at all. Revealing the field walks the whole responder
            // chain (this form's `verticalScroll` *and* the panel's outer one),
            // which is what puts it inside the region that is both visible and
            // addressable. `submitAttempts` re-arms this on a repeat refusal, the
            // same way it re-arms the line.
            val refusal = error
            if (refusal != null) revealRefusedField(refusal)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // `dialog.tsx:264-266` / `controller.ts` `cancel`: an unfinished
            // attempt is cancelled; a ready tunnel is the host's to release.
            if (status.stage != SshStage.READY) {
                connectJob?.cancel()
                connection?.cancelPrompt()
                connection?.disconnect()
            }
        }
    }

    fun submit() {
        // v90: arm the feedback scroll before the gate runs — a refusal is exactly the
        // case that used to leave the dialog looking untouched (see the KDoc).
        submitAttempts++
        val invalid = validateSshForm(target, auth, password, privateKeyPath)
        if (invalid != null) {
            error = invalid
            return
        }
        error = null
        connectJob?.cancel()
        connection?.cancelPrompt()
        connection?.disconnect()
        val sshConfig = SshConnectionConfig(
            id = connectionId,
            target = target.trim(),
            name = name.trim(),
            auth = auth,
            password = password.takeIf { it.isNotEmpty() },
            privateKeyPath = privateKeyPath.trim().takeIf { it.isNotEmpty() },
            passphrase = passphrase.takeIf { it.isNotEmpty() },
        )
        val tunnel = SshConnection(
            sshConfig,
            knownHostsFile = File(context.filesDir, "ssh/known_hosts"),
        )
        connection = tunnel
        onSubmit(sshConfig, tunnel)
        connectJob = scope.launch { tunnel.connect() }
    }

    // v92: the prompt card's band inputs — the same window IME bottom and panel
    // slot box the form below feeds its band (see SshAuthenticate). Read here,
    // before the early return, so the prompt card is measured against the same
    // floor; the card measures its own top edge itself.
    val promptImeBottomDp = windowImeBottomDp()
    val promptPanel = LocalOcSelectContainerBounds.current
    val promptPanelTopDp = promptPanel?.topDp ?: -1f
    val promptPanelHeightDp = promptPanel?.let { (it.bottomDp - it.topDp).coerceAtLeast(0f) } ?: 0f
    val promptWindowHeightDp = config.screenHeightDp.toFloat()

    val prompt = status.prompt
    if (prompt != null) {
        SshAuthenticate(
            prompt = prompt,
            hostKey = status.hostKey,
            onTrust = { connection?.respondTrust(true) },
            onContinue = { connection?.respondPrompt(prompt.id, it) },
            // F13b: only the cancel — no `onDismiss()`. `awaitAnswer` comes back
            // `accepted=false` → jsch `Auth cancel` → `connect()` takes its `failure`
            // branch → `teardown` releases the socket, and the card leaves the
            // composition by itself once `prompt` goes null. `onDismiss()` here used
            // to tear down a *successful* attempt too: after Continue, `respondPrompt`
            // → `PromptResolved` → `prompt=null` dropped the card, the
            // `DisposableEffect(Unit)` above ran `cancel()+disconnect()` before READY
            // ever arrived (server logged `Accepted password`, app showed
            // `Could not establish`). A prompt that disappears on its own after
            // Continue never reaches this callback; `onDismiss` stays reserved for
            // READY and explicit close.
            onCancel = {
                connection?.cancelPrompt()
            },
            windowHeightDp = promptWindowHeightDp,
            imeBottomDp = promptImeBottomDp,
            panelTopDp = promptPanelTopDp,
            panelHeightDp = promptPanelHeightDp,
        )
        return
    }

    val busy = isSshConnecting(status.stage)
    // v83: the same band + pinned footer as `AddEditServerDialog` (see
    // [serverDialogBandDp]): the form scrolls, Cancel / Add server does not, so
    // the primary action stays inside the visible band above the keyboard instead
    // of sitting below the fold of a 244 dp viewport
    // (`research/v83_verify/5556/26_edit_imeup.png` … `29_edit_kbdown.png` is the
    // sibling card's evidence — same slot, same settings layer).
    //
    // v86: this dialog is the Servers panel's second appended card, so its top
    // edge sits below the header and the list and the space above it can be
    // smaller than the form's preferred band (measured: card top y=227, keyboard
    // top y=336 → 109 dp). [serverDialogBandDp] handed it that space, which pinned
    // the footer's bottom edge to the keyboard while the *form* got the remainder
    // — the same `space − footer` squeeze as the edit card, and the same empty
    // card in `research/v86_verify/5556/` (`ui_add_kbdup.xml`,
    // `ui_add_after_swipeup.xml`). The inset fed below is the window's keyboard
    // height, not an already-spent one — see [windowImeBottomDp].
    val density = LocalDensity.current
    val imeBottomDp = windowImeBottomDp()
    var topInWindowDp by remember { mutableStateOf(0f) }
    // The panel region this card is drawn in (`PanelSlot`'s visible box, the
    // measurement the panel's selects already flip against): the card may never be
    // taller than it, so the form's floor is paid for out of the panel on screen —
    // v87, and 0/null outside the Servers panel, where the keyboard alone bounds
    // the band. v88: that box's **bottom edge** is also where the card's own bottom
    // edge is drawn ([serverDialogFloorDp]), so the whole card — its rounded head
    // included — sits inside the region the user sees and its pinned footer ends on
    // the panel's own bottom line. v89: the same floor with the keyboard **down**,
    // which is what puts Cancel / Add server inside the panel there without
    // scrolling it: this card is the panel's last child, so before v89 the
    // keyboard-down card ran to the window's bottom — 132 dp past the panel's own
    // clip on the v88 device (`research/v88_verify/5556/05c_add_kbddown.png`, the
    // same border rows at x=450: 84, 132, 341 and no card bottom below them) and its
    // footer drew nowhere.
    val panel = LocalOcSelectContainerBounds.current
    val panelTopDp = panel?.topDp ?: -1f
    val panelHeightDp = panel?.let { (it.bottomDp - it.topDp).coerceAtLeast(0f) } ?: 0f
    val windowHeightDp = config.screenHeightDp.toFloat()
    val band = serverDialogBandDp(
        windowHeightDp = windowHeightDp,
        imeBottomDp = imeBottomDp.value,
        topInWindowDp = topInWindowDp,
        panelHeightDp = panelHeightDp,
        panelTopDp = panelTopDp,
    ).dp
    // The card's top edge gives way for the form's floor: with the keyboard up the
    // flow above this card leaves less than the footer plus one labelled field, so
    // the card moves up until its pinned footer sits on the floor (the panel's own
    // bottom edge, v88). With the keyboard down it is 0 wherever the panel's flow
    // leaves that floor at least the band, and otherwise the card is drawn from the
    // floor up with its footer inside the panel (v89).
    val lift = serverDialogBandLiftDp(
        bandDp = band.value,
        windowHeightDp = windowHeightDp,
        imeBottomDp = imeBottomDp.value,
        topInWindowDp = topInWindowDp,
        panelTopDp = panelTopDp,
        panelHeightDp = panelHeightDp,
    ).dp

    Column(
        Modifier
            .width(width)
            // The flow position, measured outside the lift (see AddEditServerDialog).
            .onGloballyPositioned { coords ->
                topInWindowDp = coords.positionInWindow().y / density.density
            }
            .offset(y = -lift)
            // The shield only while lifted: a lifted card is drawn over the panel's
            // header and list and takes the touches that land on none of its own
            // controls, so no covered row can fire under it.
            .then(if (lift > 0.dp) Modifier.serverDialogLiftShield() else Modifier)
            // v88: with the keyboard up and the panel measured the card's box is the
            // **fixed** band, so its bottom edge lands on the floor the band was drawn
            // against (the panel's bottom edge) whatever its content measures; with
            // the keyboard down the max-only cap is untouched, so a card whose content
            // fits above that same floor is drawn where the v85 wrap put it and one
            // that does not is clamped to the line the panel clips at (v89).
            .then(
                if (imeBottomDp > 0.dp && panelHeightDp > 0f) {
                    Modifier.height(band)
                } else {
                    Modifier.heightIn(max = band)
                },
            )
            .background(c.bgBase, RoundedCornerShape(8.dp))
            .border(0.5.dp, c.borderBase, RoundedCornerShape(8.dp)),
    ) {
        // The form gives way (`fill = false`: its own height while the band has
        // room, a scroller once it has not) — except with the keyboard up, where the
        // band *is* the card's box and the form takes all of it above the footer;
        // the footer below never gives way.
        Column(
            Modifier
                .then(
                    if (imeBottomDp > 0.dp && panelHeightDp > 0f) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.weight(1f, fill = false)
                    },
                )
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                // `ssh.add` (`dialog.tsx:175-177`); `ssh.connectTo` once the dial owns the dialog.
                text = if (connection == null) {
                    stringResource(R.string.oc_ssh_title)
                } else {
                    stringResource(R.string.ssh_connectTo, sshName(name, target))
                },
                color = c.textBase,
                fontSize = 15.sp,
                fontWeight = FontWeight(640),
            )
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))

            OcBodyText(stringResource(R.string.oc_ssh_target_label), muted = true)
            OcTextField(
                value = target,
                onValueChange = {
                    target = it
                    error = null
                },
                placeholder = stringResource(R.string.oc_ssh_target_placeholder),
                enabled = !busy,
                imeAction = ImeAction.Next,
                // v98 D3: the field carries its own reveal handle (see the
                // [SshFormField] map near the top of this function), so a refusal
                // naming the target scrolls it into the viewport instead of only
                // drawing a line about it.
                modifier = Modifier.bringIntoViewRequester(targetRequester),
            )
            OcBodyText(stringResource(R.string.dialog_server_add_name), muted = true)
            OcTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.dialog_server_add_namePlaceholder),
                enabled = !busy,
                imeAction = ImeAction.Next,
                modifier = Modifier.bringIntoViewRequester(nameRequester),
            )
            OcBodyText(stringResource(R.string.oc_ssh_auth_label), muted = true)
            OcSelect(
                value = stringResource(
                    if (auth == SshAuthMethod.PASSWORD) R.string.dialog_server_add_password
                    else R.string.oc_ssh_auth_key,
                ),
                options = listOf(
                    SshAuthMethod.PASSWORD.name to stringResource(R.string.dialog_server_add_password),
                    SshAuthMethod.KEY.name to stringResource(R.string.oc_ssh_auth_key),
                ),
                onSelect = { value ->
                    auth = SshAuthMethod.valueOf(value)
                    error = null
                },
            )
            when (auth) {
                SshAuthMethod.PASSWORD -> {
                    OcBodyText(stringResource(R.string.dialog_server_add_password), muted = true)
                    OcTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            error = null
                        },
                        password = true,
                        enabled = !busy,
                        keyboardType = KeyboardType.Password,
                        onImeAction = { submit() },
                        // v98 D3: **the field the v98 device run could never reach.**
                        // It is the deepest field of this branch and the one
                        // `validateSshForm` demands, so it carries a reveal handle
                        // of its own — the one a `PasswordRequired` refusal asks
                        // for. `REPORT_COVERAGE4.md` §2.5 / `REPORT_COVERAGE5.md`
                        // §4.3: 11 scroll positions, never in the a11y tree, the
                        // submit therefore unreachable through the UI.
                        modifier = Modifier.bringIntoViewRequester(passwordRequester),
                    )
                }

                SshAuthMethod.KEY -> {
                    OcBodyText(stringResource(R.string.oc_ssh_key_path_label), muted = true)
                    OcTextField(
                        value = privateKeyPath,
                        onValueChange = {
                            privateKeyPath = it
                            error = null
                        },
                        placeholder = stringResource(R.string.oc_ssh_key_path_placeholder),
                        enabled = !busy,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.bringIntoViewRequester(privateKeyRequester),
                    )
                    OcBodyText(stringResource(R.string.oc_ssh_passphrase_label), muted = true)
                    OcTextField(
                        value = passphrase,
                        onValueChange = {
                            passphrase = it
                            error = null
                        },
                        password = true,
                        enabled = !busy,
                        keyboardType = KeyboardType.Password,
                        onImeAction = { submit() },
                        modifier = Modifier.bringIntoViewRequester(passphraseRequester),
                    )
                }
            }
            // v90: the submit path's whole feedback surface, wrapped so one
            // [BringIntoViewRequester] can hand it to the scroller above (see the effect
            // near the top of this function). The wrapper draws nothing of its own and
            // its 12 dp gaps are the form column's own `spacedBy(12.dp)`, so a form
            // without feedback is byte-identical to the v83…v89 geometry — which is why
            // it is composed only while it has a line to show.
            if (error != null || status.error != null ||
                (connection != null && status.stage != SshStage.READY)
            ) {
                Column(
                    Modifier.fillMaxWidth().bringIntoViewRequester(feedbackRequester),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    error?.let { invalid ->
                        Text(
                            text = stringResource(sshFormErrorResId(invalid)),
                            color = c.stateDangerFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight(440),
                        )
                    }
                    status.error?.let { sshError ->
                        Text(
                            text = stringResource(sshErrorResId(sshError)),
                            color = c.stateDangerFg,
                            fontSize = 11.sp,
                            fontWeight = FontWeight(440),
                        )
                    }
                    if (connection != null && status.stage != SshStage.READY) {
                        Text(
                            text = stringResource(sshStageResId(status.stage)),
                            color = c.textMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight(440),
                        )
                    }
                }
            }
        }
        // Cancel / Add server is pinned to the card's bottom edge, which is inside
        // the band, so it is inside the panel the scroller clips — with the keyboard
        // up (v88) and, since v89, with it down, where the card's own bottom edge is
        // the panel's. Its 12 dp top padding is the gap the single column's
        // `spacedBy(12.dp)` used to leave above the row, so the footer's own box is
        // the same one it has always had.
        Column(
            Modifier.padding(PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                OcButton(
                    label = stringResource(R.string.common_cancel),
                    primary = false,
                    onClick = onDismiss,
                )
                OcButton(
                    // `dialog.tsx:232-241`: busy → `ssh.stage.connecting`, else the add/connect label.
                    label = stringResource(
                        if (busy) R.string.ssh_stage_connecting
                        else if (connection == null) R.string.dialog_server_add_button
                        else R.string.ssh_connect,
                    ),
                    enabled = !busy && target.isNotBlank(),
                    onClick = { submit() },
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.borderBase))
        }
    }
}
