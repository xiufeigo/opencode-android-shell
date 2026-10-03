package com.xiufeigo.opencodeshell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v11: Material3's `DropdownMenu` defaults to `MenuDefaults.containerColor`
 * (`surfaceContainer`), a **light** translucent tint that the shell's own
 * `OcTheme` never re-themes — so in the dark theme the rows' `textBase` /
 * `textFaint` landed on a near-white panel. The in-scope menus are adapted to
 * the theme's opaque surface token (`layer01`) with zero tonal elevation, the
 * same pairing `NewSessionScreen` and `SettingsComponents` already use.
 *
 * Source-pinned (the house style for Compose structure), because the palette
 * and the menu's own colours are not observable from JVM tests.
 *
 * Swept and asserted here (every shell menu that carries the full four-parameter
 * pairing): `ui/session/context/SessionContextPanel.kt`,
 * `ui/home/HomeProjectsList.kt` (desktop column + mobile dropdown),
 * `ui/home/HomeSessionsList.kt`, `ui/home/NewSessionScreen.kt`,
 * `ui/session/drawer/SessionDrawer.kt`,
 * `ui/session/review/ArtifactView.kt`,
 * `ui/session/review/MobileSessionTabs.kt`, `ui/settings/ServersPanel.kt` and
 * `ui/terminal/TerminalPanel.kt`. `ui/session/composer` has no `DropdownMenu` at
 * all (the `@`/`/` menus are `MentionPopover`/`ComposerSuggestions`, which are
 * not M3 menus).
 *
 * The gap this sweep used to track is closed: `ui/home/HomeSessionsList.kt`
 * (the session-row context menu) was the last 0-parameter call and now carries
 * the pairing as well, so the residue scan below asserts **zero** menus left on
 * the M3 default instead of pinning a known remainder.
 * `ui/settings/SettingsComponents.kt:213` carries only the first half of the
 * pairing (`containerColor` + `tonalElevation`, no `shape`/`border`) and is the
 * pre-v11 reference the other menus copied, so it is deliberately left out of
 * the four-parameter assertion set rather than retro-fitted here — the surface
 * half the dark theme actually depends on is still covered, by the residue scan.
 *
 * The surface half is only one of the two halves of this root cause. The other
 * is the row itself: an uncoloured `Text` inside a `DropdownMenuItem` inherits
 * M3's `onSurface`, so a themed panel with near-black rows is still unreadable.
 * `everyMenuItemTextCarriesAnExplicitColor` sweeps that half from the tree —
 * which is what closed the last 15 rows (the two long-press menus on
 * `HomeProjectsList` / `HomeSessionsList`, the tab-strip and mobile-drawer
 * rename rows, the `ServersPanel` edit/default rows and the three `TerminalPanel`
 * rows), all of which a real device showed as blank in the dark theme.
 */
class DropdownMenuDarkSurfaceTest {

    private fun source(relative: String): String {
        val file = File(relative).takeIf { it.isFile } ?: File("app/$relative")
        return file.readText()
    }

    private fun mainSources(): List<File> {
        val root = listOf(
            File("src/main/java/com/xiufeigo/opencodeshell"),
            File("app/src/main/java/com/xiufeigo/opencodeshell"),
        ).firstOrNull { it.isDirectory } ?: error("missing main source root")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** The files the sweep asserts the full pairing on, one menu each. */
    private val swept = listOf(
        "ui/session/context/SessionContextPanel.kt",
        "ui/home/HomeProjectsList.kt",
        "ui/home/HomeSessionsList.kt",
        "ui/home/NewSessionScreen.kt",
        "ui/session/drawer/SessionDrawer.kt",
        "ui/session/review/ArtifactView.kt",
        "ui/session/review/MobileSessionTabs.kt",
        "ui/settings/ServersPanel.kt",
        "ui/terminal/TerminalPanel.kt",
    )

    /**
     * The `DropdownMenu(…) {` call's argument list, not the `DropdownMenuItem(` rows.
     *
     * Comments come out first (v61), then the argument list is cut at the
     * **matching** `)` instead of the first `") {"`: a trailing-lambda argument
     * (`ocOverlayEscape(open = menuOpen) { … }`) puts a `) {` inside the list, and
     * the naive cut sliced the call in half there. Comment lines are dropped
     * whole; a trailing `//` is only dropped on lines without a string literal, so
     * a `http://` in an argument can never be cut.
     */
    private fun menuCall(body: String): String = menuCalls(body).first()

    /** Every `DropdownMenu(…) {` call's argument list in one file. */
    private fun menuCalls(body: String): List<String> {
        val text = withoutComments(body)
        val calls = mutableListOf<String>()
        var index = text.indexOf(OPEN)
        while (index >= 0) {
            val argsStart = index + OPEN.length
            var depth = 1
            var cursor = argsStart
            while (cursor < text.length && depth > 0) {
                when (text[cursor]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                cursor++
            }
            if (depth == 0) calls.add(text.substring(argsStart, cursor - 1))
            index = text.indexOf(OPEN, cursor)
        }
        return calls
    }

    private fun withoutComments(body: String): String =
        body.split("\n")
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0 || line.contains('"')) line else line.substring(0, marker)
            }

    private companion object {
        const val OPEN = "DropdownMenu("
    }

    /**
     * The dark-surface guarantee itself: the opaque theme token, no tonal tint.
     * The receiver is the file's own palette handle — `HomeSessionsList` reads
     * `OcTheme.colors` where the others bind a local `c` — so both spellings of
     * the same token count.
     */
    private fun hasThemeSurface(menu: String): Boolean =
        (menu.contains("containerColor = c.layer01") ||
            menu.contains("containerColor = OcTheme.colors.layer01")) &&
            menu.contains("tonalElevation = 0.dp")

    /** The full four-parameter pairing the v11 sweep copied into every menu. */
    private fun assertThemed(menu: String) {
        assertTrue(menu, hasThemeSurface(menu))
        assertTrue(menu, menu.contains("shape = RoundedCornerShape(6.dp)"))
        assertTrue(
            menu,
            menu.contains("border = BorderStroke(0.5.dp, c.borderMuted)") ||
                menu.contains("border = BorderStroke(0.5.dp, OcTheme.colors.borderMuted)"),
        )
    }

    @Test
    fun theContextPanelExportMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/session/context/SessionContextPanel.kt"),
            ),
        )
    }

    @Test
    fun theHomeProjectRowMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeProjectsList.kt")),
        )
    }

    @Test
    fun theHomeSessionsRowMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/home/HomeSessionsList.kt"),
            ),
        )
    }

    @Test
    fun theFileViewerMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/ArtifactView.kt"),
            ),
        )
    }

    @Test
    fun theMobileSessionTabMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/session/review/MobileSessionTabs.kt"),
            ),
        )
    }

    @Test
    fun theSessionDrawerRowMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/session/drawer/SessionDrawer.kt"),
            ),
        )
    }

    @Test
    fun theServersPanelMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/settings/ServersPanel.kt"),
            ),
        )
    }

    @Test
    fun theTerminalPanelMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/terminal/TerminalPanel.kt"),
            ),
        )
    }

    @Test
    fun theNewSessionMenuUsesTheThemeSurface() {
        assertThemed(
            menuCall(
                source("src/main/java/com/xiufeigo/opencodeshell/ui/home/NewSessionScreen.kt"),
            ),
        )
    }

    @Test
    fun noShellMenuIsStillOnTheM3DefaultSurface() {
        // The sweep's old gap ("the one remaining 0-parameter call") is closed,
        // so the negative assertion flips to zero residue: every `DropdownMenu`
        // in the shell carries the dark-surface pairing. Scanned from the tree
        // rather than a fixed list, so a *new* unadapted menu fails here instead
        // of silently rendering `textBase` rows on M3's light `surfaceContainer`.
        val scanned = mainSources().flatMap { file ->
            menuCalls(file.readText()).map { menu -> file.name to menu }
        }
        assertTrue("no DropdownMenu calls found in the shell", scanned.isNotEmpty())
        val names = scanned.map { it.first }.toSet()
        val missed = swept.map { it.substringAfterLast('/') }.filterNot { it in names }
        assertTrue("swept menus not scanned: $missed", missed.isEmpty())
        val residue = scanned.filterNot { hasThemeSurface(it.second) }.map { it.first }
        assertTrue("menus still on the M3 default surface: $residue", residue.isEmpty())
    }

    /** The row-colour sweep's own `swept` list — the menus a real device showed. */
    private val colorSwept = listOf(
        "HomeProjectsList.kt",
        "HomeSessionsList.kt",
        "DesktopTabStrip.kt",
        "MobileTabsDrawer.kt",
        "ServersPanel.kt",
        "TerminalPanel.kt",
    )

    /**
     * Index of the delimiter closing the one at [open], or -1 when unbalanced.
     * Delimiters inside string literals are counted too, which is harmless here:
     * no row label in the shell carries a bracket.
     */
    private fun matchingDelimiter(body: String, open: Int, left: Char, right: Char): Int {
        var depth = 0
        for (i in open until body.length) {
            when (body[i]) {
                left -> depth++
                right -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** The `text = …` argument of a menu row: a braced lambda or a bare call. */
    private fun textArgument(block: String): String? {
        val at = block.indexOf("text =")
        if (at < 0) return null
        var i = at + "text =".length
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length) return null
        if (block[i] == '{') {
            val close = matchingDelimiter(block, i, '{', '}')
            return if (close < 0) null else block.substring(i + 1, close)
        }
        val open = block.indexOf('(', i)
        if (open < 0) return null
        val close = matchingDelimiter(block, open, '(', ')')
        return if (close < 0) null else block.substring(open + 1, close)
    }

    /** True when any `Text(…)` in the row's `text` argument names no `color`. */
    private fun hasUncolouredText(textArgument: String): Boolean {
        var at = textArgument.indexOf("Text(")
        while (at >= 0) {
            val before = textArgument.getOrNull(at - 1)
            val isCall = before == null || !(before.isLetterOrDigit() || before == '_')
            if (isCall) {
                val open = at + "Text(".length - 1
                val close = matchingDelimiter(textArgument, open, '(', ')')
                if (close < 0) return true
                if ("color" !in textArgument.substring(open + 1, close)) return true
            }
            at = textArgument.indexOf("Text(", at + 1)
        }
        return false
    }

    /**
     * The row-level half of the same root cause: a `DropdownMenuItem` whose
     * `text` lambda names no `color` falls back to
     * `MenuDefaults.itemColors().textColor` = `MaterialTheme.colorScheme.onSurface`
     * — near-black on the light palettes, which is exactly what the dark theme
     * painted onto the now-opaque `layer01` panel (`OcTheme` deliberately never
     * re-themes `MaterialTheme.colorScheme`, see the class KDoc). The row must
     * name its own token: `c.textBase` / `OcTheme.colors.textBase`, the muted
     * token a secondary entry already carried, or the danger token a destructive
     * entry already carried.
     *
     * Scanned from the tree rather than a fixed list, so a *new* uncoloured row
     * fails here. Each `Text(…)` is checked on its own, so a `Row { Icon(...);
     * Text(...) }` whose `color` only lands on the icon still fails.
     */
    @Test
    fun everyMenuItemTextCarriesAnExplicitColor() {
        val missing = mutableListOf<String>()
        val files = mutableSetOf<String>()
        var scanned = 0
        mainSources().forEach { file ->
            val body = file.readText()
            var at = body.indexOf("DropdownMenuItem(")
            while (at >= 0) {
                val open = at + "DropdownMenuItem(".length - 1
                val close = matchingDelimiter(body, open, '(', ')')
                if (close < 0) break
                scanned++
                files += file.name
                val line = body.take(at).count { it == '\n' } + 1
                val text = textArgument(body.substring(open + 1, close))
                if (text == null || hasUncolouredText(text)) {
                    missing += "${file.name}:$line"
                }
                at = body.indexOf("DropdownMenuItem(", close)
            }
        }
        assertTrue("no DropdownMenuItem rows found in the shell", scanned > 0)
        val notScanned = colorSwept.filterNot { it in files }
        assertTrue("menus not scanned for row colour: $notScanned", notScanned.isEmpty())
        assertTrue("menu rows inheriting M3's onSurface: $missing", missing.isEmpty())
    }
}
