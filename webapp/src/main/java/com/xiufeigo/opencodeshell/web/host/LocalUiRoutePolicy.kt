package com.xiufeigo.opencodeshell.web.host

/**
 * 本机 UI origin 的纯路由/路径策略。
 *
 * 该对象不碰 socket、assets 或线程；它只回答三个问题：
 * 请求路径如何归一、是否属于绝不能由本机端点应答的后端路由、以及官方
 * vite `/_assets/` URL 映射到 APK 内哪个磁盘目录。边界 effect 仍归
 * [LocalUiOriginServer]。
 */
internal object LocalUiRoutePolicy {
    /**
     * 官方产物里 JS chunk 目录叫 `/_assets/`（vite 默认）。
     * AGP 的资源合并会丢掉下划线开头的目录（实测 890 个 chunk 全被过滤，
     * 同级的 `assets/`、`icons/` 正常进包），所以磁盘上把同一批文件放在
     * [BUNDLE_DIR] 下；这里只映射 URL，不改写官方产物内容。
     */
    const val URL_BUNDLE_PREFIX = "/_assets/"
    const val BUNDLE_DIR = "oa"

    /**
     * `/api/` 与 SSE `/event` 永远不由本机端点应答。
     *
     * 静态资源目录里本来就没有这些文件，所以这实际是双保险：即使将来有人误把
     * 后端产物塞进 `assets/www/ui/api/`，也不会变成回环代理。
     */
    fun refuseApiPath(path: String): Boolean {
        val p = normalize(path)
        return p == "/api" || p.startsWith("/api/") || p == "/event" || p.startsWith("/event")
    }

    /** 归一化：绝对路径、去 `..`、去重复斜杠；`..` 不许穿越出根。 */
    fun normalize(path: String): String {
        var p = path.ifEmpty { "/" }
        if (!p.startsWith("/")) p = "/$p"
        val parts = ArrayList<String>()
        for (seg in p.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /** 请求路径 → 磁盘路径：只把 vite 的 `/_assets/` 映射到 [BUNDLE_DIR]。 */
    fun toDiskPath(normalized: String): String =
        if (normalized.startsWith(URL_BUNDLE_PREFIX)) {
            "/$BUNDLE_DIR/" + normalized.removePrefix(URL_BUNDLE_PREFIX)
        } else {
            normalized
        }
}
