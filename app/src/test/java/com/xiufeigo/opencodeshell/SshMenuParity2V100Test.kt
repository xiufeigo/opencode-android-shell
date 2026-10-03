package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshRowMenuEntry
import com.xiufeigo.opencodeshell.ui.servers.SshStage
import com.xiufeigo.opencodeshell.ui.servers.sshRowMenuEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v100 — the **rest** of upstream `servers/ssh/menu.tsx`, the three elements the
 * v99 round explicitly left open (`research/v97_fix/SSH_ROW_MENU_FIX.md` §7.2:
 * the group label, the `Set as default` / `Remove default` pair, the
 * separator).
 *
 * What upstream actually has, in `Menu.Group` order (`menu.tsx:28-49`):
 *
 * ```
 * 28  <Menu.Group>
 * 29    <Menu.GroupLabel>{language.t("ssh.server.menu.label")}</Menu.GroupLabel>
 * 30    <Show when={item().stage !== "ready"}>
 * 31      <Menu.Item disabled={ssh.pending(props.id)} …>            Connect / Authenticate
 * 35    <Show when={defaults.available() && defaults.key() !== key()}> Set as default
 * 40    <Show when={defaults.available() && defaults.key() === key()}> Remove default
 * 45    <Menu.Separator />
 * 46    <Menu.Item onSelect={…remove(key())}>                       Delete
 * ```
 *
 * ## A level — done (label + separator)
 *
 * Both are structural and carry no semantics the shell has to own, and the
 * label reuses upstream's **own** i18n key: `ssh.server.menu.label` already
 * exists in all three locales as `ssh_server_menu_label` ("SSH server" /
 * "SSH 服务器" / "SSH 伺服器"). **No new user-visible string.**
 *
 * ## B level — deliberately not done, and this class is the pin for that
 *
 * The two default entries are gated on `props.domain.defaults`, and upstream's
 * `useDefaultServer` (`registry/controller.ts:19-48`) is:
 *
 * ```ts
 * available: createMemo(() => !!platform.getDefaultServer && !!platform.setDefaultServer)
 * key: () => defaultKey.latest
 * set: async (key) => { await platform.setDefaultServer?.(key); defaultKeyActions.mutate(key) }
 * ```
 *
 * So `available()` is a **platform capability** probe, not a per-server
 * condition, and `platform.setDefaultServer` is documented at
 * `runtime/platform/platform.tsx:85-86` as *"the default server URL to use **on
 * app startup**"*. Only `runtime/platform/web.ts:41-45` implements it, against
 * `localStorage["opencode.settings.dat:defaultServerUrl"]`.
 *
 * The shell's nearest thing is `SettingsServerBridge.kt:137`
 * (`isDefault = server.url == store?.activeUrl()`) — but that is a **different
 * fact**: (a) it is an HTTP-server identity check, and `ServerListItem.url` is
 * non-null while an SSH entry has no URL at all, so no SSH row could ever
 * satisfy it; (b) `setDefault(url)` (`:205`) calls
 * `store.replaceAll(list, url)`, i.e. it **switches the active server now**,
 * not a launch-time default. Reusing it under the words "Set as default" would
 * show a user one thing and do another; building the launch-time concept is a
 * new feature, not a parity fix.
 *
 * So the entries stay in [sshRowMenuEntries] — ordered and truth-tabled by
 * [theUpstreamDefaultTruthTableIsPinnedButUnwired] so the upstream slot is
 * recorded in executable form — and are never drawn, which
 * [theDefaultEntriesAreNeverDrawn] asserts.
 */
class SshMenuParity2V100Test {

    private fun source(relative: String): String =
        listOf(File("app/$relative"), File(relative))
            .firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing $relative")

    private fun host() =
        source("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt")

    private fun menu() = host().substringAfter("private fun SshTunnelRowMenu(")

    private fun res(qualifier: String) =
        source("src/main/res/$qualifier/strings_oc_i18n.xml")

    private val allStages = SshStage.entries

    // ------------------------------------------------- A: the group label

    @Test
    fun theGroupLabelLeadsTheMenuInEveryStage() {
        // `menu.tsx:28-29` — `Menu.Group`'s first child is a `Menu.GroupLabel`,
        // with no `Show` around it, so it is unconditional. Asserted per stage
        // so a gate wrapped around the label fails rather than only the order.
        for (stage in allStages) {
            val entries = sshRowMenuEntries(stage)
            assertEquals(
                "the label leads the popup in $stage (`menu.tsx:29` is unconditional)",
                SshRowMenuEntry.GroupLabel,
                entries.first(),
            )
            assertEquals(
                "the label appears exactly once in $stage",
                1,
                entries.count { it == SshRowMenuEntry.GroupLabel },
            )
        }
    }

    @Test
    fun theGroupLabelUsesTheUpstreamI18nKeyInAllThreeLocales() {
        // Upstream `menu.tsx:29` renders `ssh.server.menu.label`
        // (`i18n/en.ts:9` = "SSH server"). The shell carries that same key as
        // `ssh_server_menu_label`, so this adds no new copy — the point worth
        // pinning, because "a new group label" is exactly the kind of change
        // that quietly ships a fourth locale.
        assertTrue("the menu renders the existing label", menu().contains("R.string.ssh_server_menu_label"))
        assertTrue("the label is drawn by the GroupLabel branch", menu().contains("SshRowMenuEntry.GroupLabel ->"))
        for ((qualifier, expected) in listOf(
            "values" to "SSH server",
            "values-zh-rCN" to "SSH 服务器",
            "values-zh-rTW" to "SSH 伺服器",
        )) {
            val line = res(qualifier).lineSequence().firstOrNull { it.contains("name=\"ssh_server_menu_label\"") }
                ?: error("ssh_server_menu_label missing in $qualifier")
            assertTrue(
                "$qualifier carries the existing localized text, not a new one",
                line.contains(">$expected<"),
            )
        }
    }

    // --------------------------------------------------- A: the separator

    @Test
    fun theSeparatorSitsImmediatelyAboveDelete() {
        // `menu.tsx:45` — `<Menu.Separator />` directly above the Delete item,
        // and *outside* the Connect `Show` (which closes at `:34`). Asserted as
        // adjacency rather than mere presence: a separator that drifted to the
        // top of the group would satisfy "contains a separator" and still be
        // the wrong divider.
        for (stage in allStages) {
            val entries = sshRowMenuEntries(stage)
            val separator = entries.indexOf(SshRowMenuEntry.Separator)
            assertTrue("a separator is drawn in $stage", separator >= 0)
            assertEquals(
                "the separator is the last entry but one in $stage",
                SshRowMenuEntry.Delete,
                entries[separator + 1],
            )
            assertEquals(
                "the separator closes the menu in $stage",
                entries.lastIndex,
                separator + 1,
            )
        }
    }

    @Test
    fun theSeparatorSurvivesARowWithNothingToConnect() {
        // The interesting case: a `ready` row draws **no** Connect entry, so
        // the separator is not "trailing after two items" — it is still the
        // split between the (here empty) action group and the destructive one.
        assertEquals(
            "`menu.tsx:30` hides Connect on ready, leaving label / separator / delete",
            listOf(SshRowMenuEntry.GroupLabel, SshRowMenuEntry.Separator, SshRowMenuEntry.Delete),
            sshRowMenuEntries(SshStage.READY),
        )
        // The same holds mid-flight, where Connect is drawn but disabled — the
        // separator is about Delete, not about the Connect gate.
        assertEquals(
            "a connecting row is label / connect / separator / delete",
            listOf(
                SshRowMenuEntry.GroupLabel,
                SshRowMenuEntry.Connect,
                SshRowMenuEntry.Separator,
                SshRowMenuEntry.Delete,
            ),
            sshRowMenuEntries(SshStage.CONNECTING),
        )
    }

    @Test
    fun theSeparatorIsARealDividerNotPadding() {
        // A `Spacer` of the same height would pass a position assertion and
        // still read as nothing; the shell idiom inside a `DropdownMenu` is a
        // 0.5 dp `HorizontalDivider` (`NewSessionScreen.kt:1864`).
        assertTrue("the separator draws a real divider", menu().contains("HorizontalDivider(thickness = 0.5.dp"))
    }

    // ----------------------------------------- the whole order, every stage

    @Test
    fun theEntryOrderMatchesTheUpstreamGroupForEveryStage() {
        // `menu.tsx:28-49` read top to bottom. The expected list is written out
        // per stage rather than derived, so reordering two branches is a
        // failure instead of a silently different popup.
        for (stage in allStages) {
            val expected = buildList {
                add(SshRowMenuEntry.GroupLabel)
                if (stage != SshStage.READY) add(SshRowMenuEntry.Connect)
                add(SshRowMenuEntry.Separator)
                add(SshRowMenuEntry.Delete)
            }
            assertEquals("the popup order in $stage follows `menu.tsx`", expected, sshRowMenuEntries(stage))
        }
    }

    @Test
    fun theMenuHandlesEveryEntryOfTheEnumeration() {
        // The composable is a `when` over [SshRowMenuEntry]; an unhandled value
        // would silently drop a row, so all six are required to be named.
        for (entry in SshRowMenuEntry.entries) {
            assertTrue(
                "`${entry.name}` has no branch in the menu",
                menu().contains("$entry ->") || menu().contains("$entry,\n"),
            )
        }
    }

    // ------------------------------- B: the default entries, pinned unwired

    @Test
    fun theUpstreamDefaultTruthTableIsPinnedButUnwired() {
        // `menu.tsx:35` and `:40`, exactly: `available() && key() !== key()` vs
        // `available() && key() === key()` — mutually exclusive, and both
        // vanish when `available()` is false.
        val expected = mapOf(
            (false to false) to emptyList<SshRowMenuEntry>(),
            (false to true) to emptyList<SshRowMenuEntry>(),
            (true to false) to listOf(SshRowMenuEntry.SetDefault),
            (true to true) to listOf(SshRowMenuEntry.RemoveDefault),
        )
        for ((flags, slots) in expected) {
            val (available, isDefault) = flags
            val drawn = sshRowMenuEntries(
                SshStage.DISCONNECTED,
                defaultsAvailable = available,
                isDefault = isDefault,
            )
            val defaults = drawn.filter {
                it == SshRowMenuEntry.SetDefault || it == SshRowMenuEntry.RemoveDefault
            }
            assertEquals("available=$available isDefault=$isDefault", slots, defaults)
            assertEquals(
                "the pair is mutually exclusive at available=$available isDefault=$isDefault",
                if (slots.isEmpty()) 0 else 1,
                defaults.size,
            )
        }
    }

    @Test
    fun theDefaultEntriesAreNeverDrawn() {
        // The decision this round reached: no equivalent concept, so the two
        // entries are never drawn. Two halves —
        //
        // 1. the shell's call passes no `defaultsAvailable`, so the upstream-
        //    absent branch is the only one reachable; and
        // 2. the composable has **no draw branch** for them: it renders `Unit`
        //    with a comment saying why, rather than an `Item` that would be a
        //    lie. Both are checked, because either alone is easy to undo.
        for (stage in allStages) {
            val drawn = sshRowMenuEntries(stage)
            assertFalse(
                "no Set-as-default entry in $stage",
                drawn.contains(SshRowMenuEntry.SetDefault),
            )
            assertFalse(
                "no Remove-default entry in $stage",
                drawn.contains(SshRowMenuEntry.RemoveDefault),
            )
        }
        // Even if someone passes the flag, the popup must not invent the item.
        assertFalse(
            "the popup draws no default item even when asked",
            menu().contains("SshRowMenuEntry.SetDefault -> DropdownMenuItem") ||
                menu().contains("SshRowMenuEntry.RemoveDefault -> DropdownMenuItem"),
        )
        // And the shell's own default concept is not silently repurposed: it is
        // an HTTP-server identity check over a URL an SSH entry does not have.
        val bridge = source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/SettingsServerBridge.kt")
        assertTrue(
            "the shell's `isDefault` is an active-URL identity check, not a launch default",
            bridge.contains("isDefault = server.url == store?.activeUrl()"),
        )
    }

    // ------------------------------------------ nothing regressed on the way

    @Test
    fun theDangerEntryKeptItsLookAndTheConnectGateItsRule() {
        // v99's assertions, re-checked here so a later edit to the entry list
        // cannot quietly drop a styling or gating rule the earlier round
        // established.
        assertTrue("Delete stays the danger entry", menu().contains("c.stateDangerFg"))
        assertTrue("Delete only arms the row", menu().contains("onClick = onDelete"))
        assertTrue("Connect carries the pending gate", menu().contains("enabled = sshRowMenuConnectEnabled(stage)"))
        assertTrue("Delete keeps its own ungated rule", menu().contains("enabled = sshRowMenuDeleteEnabled(stage)"))
        assertTrue("the auth wall still says Authenticate", menu().contains("R.string.ssh_action_authenticate"))
    }
}
