package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.servers.SshRowMenuEntry
import com.xiufeigo.opencodeshell.ui.servers.sshRowMenuEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// ============================================================================
// v115 / Task B — anchor the row menu's *absence* of the two default entries
// to upstream, on the upstream's own terms.
//
// v99 (`SshRowMenuParityV99Test`) pinned the Connect / Delete half of
// `menu.tsx`. The remaining question was the B-grade item "Set as default" /
// "Remove default", which the shell does not render. The instinct is that a
// missing menu item is a gap. It is not, and the reason is one line upstream:
//
//   `_upstream/packages/app/src/servers/registry/controller.ts:45`
//     available: createMemo(() => !!platform.getDefaultServer && !!platform.setDefaultServer),
//
// `platform.tsx:83,86` declares both hooks **optional** (`getDefaultServer?()`,
// `setDefaultServer?()`), and exactly two platform adapters implement them:
// web and desktop. A platform that has neither — which is what the Android
// shell is — evaluates `available()` to `false`, and both `<Show>` gates in
// `menu.tsx:35` and `:40` are false, so upstream itself renders neither item.
// **Not rendering is the official behaviour**, not a mobile gap.
//
// This suite pins that *reasoning*, so that if upstream ever changes the gate
// or the semantics, the discrepancy surfaces here instead of being argued
// about in a report three rounds from now. It reads the real upstream tree.
// ============================================================================

class SshRowMenuUpstreamParityV115Test {

    // ------------------------------------------------------ upstream reads

    /**
     * Gradle runs these with either the repo root or the module dir as the
     * working directory (`SshRowMenuParityV99Test.source()` and
     * `ProjectParityV97Test.theHintNamesTheVariablesTheServerExports` both
     * already have to cope with both), so `_upstream` is looked up from each.
     */
    private fun upstream(relative: String): String =
        listOf(
            File("_upstream/$relative"),
            File("../_upstream/$relative"),
            File("app/../_upstream/$relative"),
        ).firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing _upstream/$relative (cwd=${File(".").absolutePath})")

    private val controller = upstream("packages/app/src/servers/registry/controller.ts")
    private val menu = upstream("packages/app/src/servers/ssh/menu.tsx")
    private val platform = upstream("packages/app/src/runtime/platform/platform.tsx")

    // ------------------------------------------------- ① the decisive line

    /**
     * `registry/controller.ts:45` — the single line the whole B-grade decision
     * rests on. Asserted on the **definition**, found by shape rather than by
     * a hard-coded line number, so an upstream edit that keeps the semantics
     * does not break the pin, and an edit that drops a hook does.
     */
    @Test
    fun theDefaultsAvailabilityGateRequiresBothPlatformHooks() {
        val definition = controller.lines().firstOrNull { it.contains("available:") }
            ?: error(
                "controller.ts must still expose an `available` accessor for the defaults " +
                    "domain (menu.tsx:35/:40 read it) — found none in:\n" +
                    controller.lines().take(60).joinToString("\n"),
            )
        assertTrue(
            "the gate must require the read hook: $definition",
            definition.contains("platform.getDefaultServer"),
        )
        assertTrue(
            "the gate must require the write hook: $definition",
            definition.contains("platform.setDefaultServer"),
        )
        // A conjunction, not a disjunction: `||` would make the two items
        // render on any platform that can only read or only write, which is a
        // different (and wrong) contract.
        assertTrue(
            "the gate must be a conjunction of both hooks: $definition",
            definition.contains("&&"),
        )
    }

    /**
     * `platform.tsx:83,86` — the hooks are **optional members of the platform
     * interface**. Without the `?` every platform would be forced to
     * implement them, `available()` could never be false, and the whole
     * argument would collapse.
     */
    @Test
    fun theTwoHooksAreOptionalOnThePlatformInterface() {
        val getter = platform.lines().firstOrNull { it.contains("getDefaultServer?(") }
        val setter = platform.lines().firstOrNull { it.contains("setDefaultServer?(") }
        assertTrue("platform.tsx must declare getDefaultServer as optional (`getDefaultServer?()`) — got: $getter", getter != null)
        assertTrue("platform.tsx must declare setDefaultServer as optional (`setDefaultServer?()`) — got: $setter", setter != null)
        assertTrue("they must be async-capable reads/writes, not a no-op stub", getter!!.contains("Promise<"))
        assertTrue(setter!!.contains("Promise<"))
    }

    /**
     * Only web and desktop implement the two hooks. There is no native/mobile
     * adapter, which is what makes the Android shell an `available() === false`
     * platform by construction rather than by omission someone may later fill in.
     */
    @Test
    fun onlyWebAndDesktopImplementTheDefaultServerHooks() {
        val packages = listOf(File("../_upstream/packages"), File("_upstream/packages"), File("app/../_upstream/packages"))
            .firstOrNull { it.isDirectory }
            ?: error("missing _upstream/packages")
        // A pruned breadth-first sweep, not `walkTopDown()`: the upstream copy
        // carries `node_modules` trees that turn an unpruned walk into minutes
        // of CPU. Depth is capped at the package/src/runtime/renderer layout.
        val heavy = setOf("node_modules", "dist", "build", "out", ".git", ".next", ".output", "target", "coverage")
        val queue = ArrayDeque<File>()
        val seen = mutableSetOf<String>()
        queue.addLast(packages)
        val adapters = linkedSetOf<String>()
        var depth = 0
        while (queue.isNotEmpty() && depth < 8) {
            val level = queue.size
            repeat(level) {
                val dir = queue.removeFirst()
                val children = dir.listFiles() ?: return@repeat
                for (child in children) {
                    val name = child.name
                    if (child.isDirectory) {
                        if (name in heavy) continue
                        val key = child.absolutePath
                        if (seen.add(key)) queue.addLast(child)
                        continue
                    }
                    if (!name.endsWith(".ts") && !name.endsWith(".tsx")) continue
                    // The adapters are `platform/web.ts` and `platform/index.ts`,
                    // so the marker is the *containing directory*. Compare the
                    // directory's own name: `File.path` of a directory carries no
                    // trailing separator, so a "/platform/" substring test
                    // silently never matches.
                    if (child.parentFile?.name != "platform") continue
                    if (!child.readText().contains("getDefaultServer:")) continue
                    adapters += child.path.replace('\\', '/')
                        .substringAfter("packages/").substringBeforeLast('/')
                }
            }
            depth++
        }
        assertEquals(
            "the set of platform adapters implementing getDefaultServer — upstream semantics " +
                "depend on it staying exactly these two, so that any new adapter is a decision " +
                "to re-open this parity question rather than an accident. (sweep depth=$depth, " +
                "scanned ${seen.size} dirs) found $adapters",
            setOf("app/src/runtime/platform", "desktop/src/renderer/platform"),
            adapters.toSet(),
        )
    }

    /**
     * `menu.tsx:35` and `:40` — both default entries are behind
     * `props.domain.defaults.available()`, and they are mutually exclusive by
     * `defaults.key()`. The shell models both slots but draws neither; this
     * pins the gate those two slots would have to be wired to.
     */
    @Test
    fun bothDefaultEntriesAreGatedOnAvailabilityAndAreMutuallyExclusive() {
        val setAsDefault = Regex("""<Show when=\{props\.domain\.defaults\.available\(\) && props\.domain\.defaults\.key\(\) !== key\(\)\}>""")
        val removeDefault = Regex("""<Show when=\{props\.domain\.defaults\.available\(\) && props\.domain\.defaults\.key\(\) === key\(\)\}>""")
        assertEquals("menu.tsx must keep exactly one Set-as-default gate", 1, setAsDefault.findAll(menu).count())
        assertEquals("menu.tsx must keep exactly one Remove-default gate", 1, removeDefault.findAll(menu).count())
        // `dialog.server.menu.default` / `defaultRemove` are the two labels; the
        // shell is allowed to have neither, but upstream must still have both.
        assertTrue("the Set-as-default label must exist upstream", menu.contains("dialog.server.menu.default"))
        assertTrue("the Remove-default label must exist upstream", menu.contains("dialog.server.menu.defaultRemove"))
    }

    /**
     * The group structure the shell *does* draw, anchored to the same file:
     * `menu.tsx:29` label, `:45` separator, `:46` delete, and the Connect
     * entry's pending gate at `:31`.
     */
    @Test
    fun theGroupStructureTheShellRendersIsUpstreamsOwn() {
        val label = menu.lines().indexOfFirst { it.contains("Menu.GroupLabel") } + 1
        val separator = menu.lines().indexOfFirst { it.trim() == "<Menu.Separator />" } + 1
        val connect = menu.lines().indexOfFirst { it.contains("disabled={ssh.pending(props.id)}") } + 1
        val remove = menu.lines().indexOfFirst { it.contains("connection.remove(key())") } + 1
        assertTrue("menu.tsx must keep a GroupLabel (:29)", label > 0)
        assertTrue("menu.tsx must keep a Separator (:45)", separator > 0)
        assertTrue("menu.tsx must keep Connect gated on pending (:31)", connect > 0)
        assertTrue("menu.tsx must keep the Delete entry (:46)", remove > 0)
        assertTrue("the label leads the group", label < separator)
        assertTrue("the separator splits the drive group from the destructive one", separator < remove)
    }

    // ------------------------------------------------------- the shell side

    /** The same two-candidate read the v99 suite uses for shell sources. */
    private fun host(): String =
        listOf(
            File("app/src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt"),
            File("src/main/java/com/xiufeigo/opencodeshell/ui/servers/SshTunnelHost.kt"),
        ).firstOrNull { it.isFile }
            ?.readText()?.replace("\r\n", "\n")
            ?: error("missing SshTunnelHost.kt (cwd=${File(".").absolutePath})")

    /**
     * The shell draws the label, the (stage-gated) Connect entry, the
     * separator and Delete — and **neither** default entry, because
     * `defaultsAvailable` is never passed as `true`.
     */
    @Test
    fun theShellRendersTheGroupWithoutEitherDefaultEntry() {
        for (stage in SshStageForPin.all) {
            val entries = sshRowMenuEntries(stage)
            assertTrue("the group label leads the popup in $stage", entries.first() == SshRowMenuEntry.GroupLabel)
            assertTrue("the separator is always present in $stage", entries.contains(SshRowMenuEntry.Separator))
            assertTrue("Delete is always present in $stage", entries.contains(SshRowMenuEntry.Delete))
            assertFalse(
                "Set-as-default must not render in $stage — the shell has no launch-time default, " +
                    "which is upstream's `available() === false` branch (controller.ts:45), not a gap",
                entries.contains(SshRowMenuEntry.SetDefault),
            )
            assertFalse(
                "Remove-default must not render in $stage — same upstream-absent branch",
                entries.contains(SshRowMenuEntry.RemoveDefault),
            )
        }
    }

    /**
     * The rule only stays upstream-faithful if the composable actually calls it
     * with the default (upstream-absent) arguments. A future edit that starts
     * passing `defaultsAvailable = true` would render both items on a platform
     * whose `available()` is false — the exact divergence this suite exists to
     * catch, on the shell's own side.
     */
    @Test
    fun theComposablesTakeTheUpstreamAbsentBranchByOmission() {
        val source = host()
        val callSites = Regex("""sshRowMenuEntries\(\s*stage\s*[,)]""").findAll(source).count()
        assertTrue("the row menu must derive from sshRowMenuEntries (found $callSites call sites)", callSites > 0)
        assertTrue(
            "no call site may pass defaultsAvailable — the shell has no equivalent of " +
                "platform.getDefaultServer/setDefaultServer, so available() is false by construction",
            !Regex("""defaultsAvailable\s*=\s*true""").containsMatchIn(source),
        )
        assertTrue(
            "the shell must still model the two slots so the upstream ordering stays pinned",
            source.contains("SshRowMenuEntry.SetDefault") && source.contains("SshRowMenuEntry.RemoveDefault"),
        )
        // And the composable genuinely draws a label and a divider — the two
        // structural pieces the B-grade question did not touch.
        val menu = source.substringAfter("private fun SshTunnelRowMenu(")
        assertTrue("the group label is drawn", menu.contains("SshRowMenuGroupLabel()"))
        assertTrue("the separator is drawn as a divider", menu.contains("HorizontalDivider("))
    }
}

/**
 * Every stage, reached through the same enum the v99 suite uses, so this suite
 * cannot drift onto a hand-listed subset.
 */
private object SshStageForPin {
    val all: List<com.xiufeigo.opencodeshell.ui.servers.SshStage> =
        com.xiufeigo.opencodeshell.ui.servers.SshStage.entries.toList()
}
