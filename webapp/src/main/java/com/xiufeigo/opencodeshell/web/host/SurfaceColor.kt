package com.xiufeigo.opencodeshell.web.host

/**
 * 页面报上来的 CSS 颜色 → ARGB int（[WebHostBridge.reportSurfaceColor] 消费）。
 *
 * `getComputedStyle().backgroundColor` 只吐 `rgb(...)` / `rgba(...)` 两种形态，
 * `#hex` 顺带支持。透明（alpha=0）返回 null：没有可跟随的表面色，留在
 * `oc_host_bg`（DayNight）兜底，而不是把透明黑写进条带。
 *
 * 不用 `android.graphics.Color`：那是框架桩，纯 JVM 单测里调它就抛
 * "not mocked" —— 位运算自己拼 ARGB 才是真正可测的纯函数。
 */
object SurfaceColor {

    private val FUNC = Regex("^rgba?\\((.+)\\)$")
    private val HEX = Regex("^#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$")

    fun parse(css: String?): Int? {
        val s = css?.trim()?.lowercase() ?: return null
        if (s.isEmpty() || s == "transparent") return null

        HEX.matchEntire(s)?.let { return parseHex(it.groupValues[1]) }

        val parts = FUNC.matchEntire(s)?.groupValues?.get(1)?.split(",")?.map { it.trim() }
            ?: return null
        if (parts.size != 3 && parts.size != 4) return null
        val r = channel(parts[0]) ?: return null
        val g = channel(parts[1]) ?: return null
        val b = channel(parts[2]) ?: return null
        val alpha = if (parts.size == 4) parts[3].toFloatOrNull() ?: return null else 1f
        if (alpha <= 0f) return null
        return argb(0xFF, r, g, b)
    }

    /** `#rgb`/`#rgba`/`#rrggbb`/`#rrggbbaa`；alpha 通道为 0 时返回 null（透明）。 */
    private fun parseHex(body: String): Int? {
        val full = when (body.length) {
            3, 4 -> body.map { "$it$it" }.joinToString("")
            else -> body
        }
        val value = full.toLongOrNull(16) ?: return null
        return when (full.length) {
            6 -> (0xFF000000 or value).toInt()
            8 -> if (value and 0xFF == 0L) null else value.toInt()
            else -> null
        }
    }

    private fun channel(raw: String): Int? = raw.toIntOrNull()?.coerceIn(0, 255)

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
