package com.xiufeigo.opencodeshell

import com.xiufeigo.opencodeshell.ui.session.composer.ModelPickerItems
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-D: every picker provider group without a stored credential must render
 * the "unconnected" badge — on the v2 backend that is *all* groups
 * (`GET /api/integration` reports `connections: []` everywhere).
 *
 * The data half (`Group.connected`) is pinned against the real predicate
 * input shape; the render half is a source pin on the group-header block (repo
 * `FindFilesRoutingTest` convention): the `!group.connected` branch, its
 * string, and the full-width header [Row] that keeps the badge on the header's
 * right side instead of squeezed out of a wrap-content row.
 */
class ModelPickerBadgeTest {

    private fun source(relative: String): String =
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("missing $relative")

    private fun window(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing window start: $from", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("missing window end: $to", end > start)
        return src.substring(start, end)
    }

    private fun catalog(connected: List<String>) = OpenCodeClient.Providers(
        all = listOf(
            OpenCodeClient.Providers.Provider(
                id = "opencode-go",
                name = "OpenCode Go",
                models = listOf(
                    OpenCodeClient.Providers.Model(id = "m1", name = "M1", capabilities = null),
                ),
            ),
            OpenCodeClient.Providers.Provider(
                id = "xiaomi",
                name = "Xiaomi",
                models = listOf(
                    OpenCodeClient.Providers.Model(id = "m2", name = "M2", capabilities = null),
                ),
            ),
        ),
        connected = connected,
        default = emptyMap(),
    )

    @Test
    fun emptyConnectedMarksEveryGroupForBadge() {
        val groups = ModelPickerItems.groups(catalog(connected = emptyList()), includeUnconnected = true)
        assertFalse(groups.isEmpty())
        assertTrue(groups.none { it.connected })
    }

    @Test
    fun mixedConnectedMarksOnlyUnconnectedGroups() {
        val groups = ModelPickerItems.groups(catalog(connected = listOf("xiaomi")), includeUnconnected = true)
        val byID = groups.associateBy { it.providerID }
        assertTrue(byID.getValue("xiaomi").connected)
        assertFalse(byID.getValue("opencode-go").connected)
    }

    @Test
    fun groupHeaderRendersUnconnectedBadge() {
        val picker = source(
            "src/main/java/com/xiufeigo/opencodeshell/ui/session/composer/ModelPicker.kt",
        )
        val header = window(
            picker,
            "for (group in visibleGroups)",
            "private fun ModelPickerRow",
        )
        assertTrue(header.contains("if (!group.connected)"))
        assertTrue(header.contains("oc_model_picker_unconnected"))
        // Full-width header row: the name takes the free space and the badge
        // lands on the group's right side; without it the badge sits
        // left-adjacent in a wrap-content row and reads as missing.
        assertTrue(header.contains("fillMaxWidth"))
    }
}
